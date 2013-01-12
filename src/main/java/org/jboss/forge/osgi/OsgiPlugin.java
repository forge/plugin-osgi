package org.jboss.forge.osgi;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.jboss.forge.maven.MavenCoreFacet;
import org.jboss.forge.maven.MavenPluginFacet;
import org.jboss.forge.maven.plugins.*;
import org.jboss.forge.parser.JavaParser;
import org.jboss.forge.parser.java.JavaClass;
import org.jboss.forge.project.Project;
import org.jboss.forge.project.dependencies.Dependency;
import org.jboss.forge.project.dependencies.DependencyBuilder;
import org.jboss.forge.project.dependencies.ScopeType;
import org.jboss.forge.project.facets.DependencyFacet;
import org.jboss.forge.project.facets.JavaSourceFacet;
import org.jboss.forge.project.facets.PackagingFacet;
import org.jboss.forge.project.facets.WebResourceFacet;
import org.jboss.forge.project.packaging.PackagingType;
import org.jboss.forge.resources.java.JavaResource;
import org.jboss.forge.shell.PromptType;
import org.jboss.forge.shell.Shell;
import org.jboss.forge.shell.plugins.*;
import org.jboss.forge.spec.javaee.PersistenceFacet;

import javax.inject.Inject;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Alias("osgi")
@RequiresFacet({DependencyFacet.class, MavenCoreFacet.class, MavenPluginFacet.class})
public class OsgiPlugin implements Plugin {
    @Inject
    Shell shell;
    @Inject
    Project project;

    static {
        Properties properties = new Properties();
        properties.setProperty("resource.loader", "class");
        properties.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");

        Velocity.init(properties);
    }

    @Command("setup")
    public void setup() {
        MavenPluginFacet pluginFacet = project.getFacet(MavenPluginFacet.class);

        PackagingFacet packagingFacet = project.getFacet(PackagingFacet.class);

        packagingFacet.setPackagingType(PackagingType.BUNDLE);

        DependencyBuilder bundlePluginDependency = createBundlePluginDependency();

        MavenPluginBuilder bundlePlugin = MavenPluginBuilder.create().setDependency(bundlePluginDependency);
        ConfigurationElementBuilder instructions = ConfigurationElementBuilder.create().setName("instructions");

        if (project.hasFacet(WebResourceFacet.class)) {
            installWebInstructions(bundlePlugin, instructions);
        } else if (project.hasFacet(PersistenceFacet.class)) {

            installJpaInstructions(bundlePlugin, instructions);
        }

        if (shouldCreateActivator()) {
            createActivator(bundlePlugin, shouldUseDmActivator());
            installCoreLibraries();
        } else {
            boolean shouldInstallCoreLibraries = shell.promptBoolean("Do you want to add the OSGI core libraries?", false);
            if (shouldInstallCoreLibraries) {
                installCoreLibraries();
            }
        }

        bundlePlugin.addExecution(
                ExecutionBuilder.create()
                        .setId("bundle-manifest")
                        .setPhase("process-classes")
                        .addGoal("manifest"))
                .setExtensions(true);

        pluginFacet.addPlugin(bundlePlugin);
        shell.println("Packaging type changed to bundle");
    }

    private boolean shouldCreateActivator() {
        return shell.promptBoolean("Do you want to create an Activator class?", false);
    }

    private DependencyBuilder createBundlePluginDependency() {
        return DependencyBuilder.create()
                .setGroupId("org.apache.felix")
                .setArtifactId("maven-bundle-plugin");
    }

    @Command("add-service-component")
    public void addServiceComponent(@Option(name = "class", required = true) JavaResource clazz) {
        try {
            String qualifiedName = clazz.getJavaSource().getQualifiedName();
            MavenPluginFacet pluginFacet = project.getFacet(MavenPluginFacet.class);
            MavenPlugin plugin = getBundlePlugin(pluginFacet);
            ConfigurationElementBuilder builder = ConfigurationElementBuilder.create();
            builder.setName("Service-Component").setText(qualifiedName);

            if (plugin.getConfig().hasConfigurationElement("instructions")) {
                plugin.getConfig().getConfigurationElement("instructions").getChildren().add(builder);
            } else {
                ConfigurationElementBuilder instruction = ConfigurationElementBuilder.create().setName("instruction").addChild(builder);
                plugin.getConfig().addConfigurationElement(instruction);
            }

            pluginFacet.removePlugin(createBundlePluginDependency());
            pluginFacet.addPlugin(plugin);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private MavenPlugin getBundlePlugin(MavenPluginFacet pluginFacet) {
        return pluginFacet.getPlugin(createBundlePluginDependency());
    }

    @Command("install-felix-dm")
    public void installFelixDm() {
        installFelixDmDependency();
        if (shouldCreateActivator()) {
            MavenPluginFacet pluginFacet = project.getFacet(MavenPluginFacet.class);
            MavenPlugin plugin = getBundlePlugin(pluginFacet);

            createActivator(MavenPluginBuilder.create(plugin), true);
        }
    }

    private void createActivator(MavenPluginBuilder bundlePlugin, boolean useDM) {
        JavaSourceFacet javaSourceFacet = project.getFacet(JavaSourceFacet.class);

        String packageName = shell.promptCommon("What package do you want to use for the Activator class? [" + javaSourceFacet.getBasePackage() + ".osgi]", PromptType.JAVA_PACKAGE, javaSourceFacet.getBasePackage() + ".osgi");
        String className = shell.prompt("How do you want to name the Activator class? [Activator]", String.class, "Activator");

        VelocityContext context = new VelocityContext();
        context.put("package", packageName);
        context.put("className", className);

        StringWriter writer = new StringWriter();

        if (useDM) {
            boolean useLogService = shell.promptBoolean("Do you want to use the LogService?", true);

            Map<String, String> dmComponent = createDmComponent(useLogService);
            context.put("dmComponentName", dmComponent.get("className"));
            context.put("dmComponentPackage", dmComponent.get("package"));
            context.put("useLogService", useLogService);
            Velocity.mergeTemplate("ActivatorDependencyManagerTemplate.vtl", "UTF-8", context, writer);
            installFelixDmDependency();
        } else {
            Velocity.mergeTemplate("ActivatorTemplate.vtl", "UTF-8", context, writer);
        }

        JavaClass activatorClass = JavaParser.parse(JavaClass.class, writer.toString());

        try {
            javaSourceFacet.saveJavaSource(activatorClass);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        ConfigurationElementBuilder builder = ConfigurationElementBuilder.create();
        builder.setName("Bundle-Activator").setText(packageName + "." + className);

        if (bundlePlugin.getConfig().hasConfigurationElement("instructions")) {
            bundlePlugin.getConfig().getConfigurationElement("instructions").getChildren().add(builder);
        } else {
            ConfigurationElementBuilder instruction = ConfigurationElementBuilder.create().setName("instructions").addChild(builder);
            bundlePlugin.getConfig().addConfigurationElement(instruction);
        }
    }

    private Map<String, String> createDmComponent(boolean useLogService) {
        JavaSourceFacet javaSourceFacet = project.getFacet(JavaSourceFacet.class);
        String packageName = shell.promptCommon("What package do you want to use for the DM component? [" + javaSourceFacet.getBasePackage() + "]", PromptType.JAVA_PACKAGE, javaSourceFacet.getBasePackage());
        String className = shell.prompt("How do you want to name the DM component class? [DmComponent]", String.class, "DmComponent");

        StringWriter writer = new StringWriter();
        VelocityContext context = new VelocityContext();
        context.put("package", packageName);
        context.put("className", className);
        context.put("useLogService", useLogService);

        Velocity.mergeTemplate("DmComponentTemplate.vtl", "UTF-8", context, writer);

        JavaClass dmComponentClass = JavaParser.parse(JavaClass.class, writer.toString());

        try {
            javaSourceFacet.saveJavaSource(dmComponentClass);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        HashMap<String, String> dmComponent = new HashMap<String, String>();
        dmComponent.put("className", className);
        dmComponent.put("package", packageName);
        return dmComponent;
    }

    private boolean shouldUseDmActivator() {
        return shell.promptBoolean("Do you want to use Felix Dependency Manager?", false);
    }

    private void installCoreLibraries() {
        installDependency("org.osgi.core", "org.osgi", "org.osgi.core");
        installDependency("org.osgi.core.compendium", "org.osgi", "org.osgi.compendium");
    }

    private void installFelixDmDependency() {
        installDependency("DependencyManager", "org.apache.felix", "org.apache.felix.dependencymanager");
    }

    private void installDependency(String name, String groupId, String artifactId) {
        DependencyFacet dependencyFacet = project.getFacet(DependencyFacet.class);
        DependencyBuilder dependency = DependencyBuilder.create().setGroupId(groupId).setArtifactId(artifactId);
        if (dependencyFacet.hasDirectDependency(dependency)) {
            shell.printlnVerbose(name + " was alread installed");
            return;
        }

        List<Dependency> versions = dependencyFacet.resolveAvailableVersions(dependency);
        if (versions.size() > 0) {
            Dependency version = shell.promptChoiceTyped("Which version of " + name + " do you want to install?", versions, versions.get(versions.size() - 1));
            dependency.setVersion(version.getVersion());
        }

        dependency.setScopeType(ScopeType.PROVIDED);
        dependencyFacet.hasDirectDependency(dependency);
        shell.println(name + " dependency added to the POM file");
    }


    private void installJpaInstructions(MavenPluginBuilder bundlePlugin, ConfigurationElementBuilder instructions) {
        bundlePlugin.createConfiguration().addConfigurationElement(
                instructions.addChild("Meta-Persistence").setText("META-INF/persistence.xml"));
    }

    private void installWebInstructions(MavenPluginBuilder bundlePlugin, ConfigurationElementBuilder instructions) {
        DependencyFacet dependencyFacet = project.getFacet(DependencyFacet.class);
        String contextPath = shell.prompt("What context path do you want to use?", String.class, "/");

        ConfigurationBuilder configuration = bundlePlugin.createConfiguration();
        configuration.addConfigurationElement(
                instructions
                        .addChild("Web-ContextPath").setText(contextPath).getParentElement()
                        .addChild("Bundle-ClassPath").setText("WEB-INF/classes").getParentElement()
        );

        StringBuilder sb = new StringBuilder();

        for (Dependency dependency : dependencyFacet.getDependencies()) {
            if (dependency.getScopeTypeEnum() == null || dependency.getScopeTypeEnum().equals(ScopeType.COMPILE) || dependency.getScopeTypeEnum().equals(ScopeType.RUNTIME)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }

                if (dependency.getPackagingTypeEnum().equals(PackagingType.JAR)) {
                    sb.append("WEB-INF/lib/").append(dependency.getArtifactId()).append("-").append(dependency.getVersion()).append(".jar");
                }
            }
        }

        if (sb.length() > 0) {
            ConfigurationElementBuilder bundleClassPath = (ConfigurationElementBuilder) configuration.getConfigurationElement("instructions").getChildByName("Bundle-ClassPath");
            bundleClassPath.setText(bundleClassPath.getText() + ", " + sb.toString());
        }

        configuration.addConfigurationElement(
                ConfigurationElementBuilder.create().setName("supportedProjectTypes").addChild("supportedProjectType").setText("war").getParentElement());


        boolean addImports = shell.promptBoolean("Do you want to add javax.servlet and javax.servlet.http", false);
        if (addImports) {
            instructions.addChild("Import-Package").setText("javax.servlet,javax.servlet.http");
        }
    }
}
