package org.jboss.forge.osgi;

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
import org.jboss.forge.project.facets.WebResourceFacet;
import org.jboss.forge.project.packaging.PackagingType;
import org.jboss.forge.resources.java.JavaResource;
import org.jboss.forge.shell.PromptType;
import org.jboss.forge.shell.Shell;
import org.jboss.forge.shell.plugins.*;
import org.jboss.forge.spec.javaee.PersistenceFacet;

import javax.inject.Inject;
import java.io.FileNotFoundException;
import java.util.List;

@Alias("osgi")
@RequiresFacet({DependencyFacet.class, MavenCoreFacet.class, MavenPluginFacet.class})
public class OsgiPlugin implements Plugin {
    @Inject Shell shell;
    @Inject Project project;

    @Command("setup")
    public void setup() {
        MavenPluginFacet pluginFacet = project.getFacet(MavenPluginFacet.class);

        DependencyBuilder bundlePluginDependency = createBundlePluginDependency();

        MavenPluginBuilder bundlePlugin = MavenPluginBuilder.create().setDependency(bundlePluginDependency);
        ConfigurationElementBuilder instructions = ConfigurationElementBuilder.create().setName("instructions");

        if (project.hasFacet(WebResourceFacet.class)) {
            installWebInstructions(bundlePlugin, instructions);
        } else if (project.hasFacet(PersistenceFacet.class)) {

            installJpaInstructions(bundlePlugin, instructions);
        }

        boolean shouldCreateActivator = shell.promptBoolean("Do you want to create an Activator class?", false);
        if (shouldCreateActivator) {
            createActivator();
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
                        .addGoal("manifest"));

        pluginFacet.addPlugin(bundlePlugin);
    }

    private DependencyBuilder createBundlePluginDependency() {
        return DependencyBuilder.create()
                .setGroupId("org.apache.felix")
                .setArtifactId("maven-bundle-plugin");
    }

    @Command("add-service-component")
    public void addServiceComponent(@Option(name = "class") JavaResource clazz) {
        try {
            String qualifiedName = clazz.getJavaSource().getQualifiedName();
            MavenPluginFacet pluginFacet = project.getFacet(MavenPluginFacet.class);
            MavenPlugin plugin = pluginFacet.getPlugin(createBundlePluginDependency());
            ConfigurationElementBuilder builder = ConfigurationElementBuilder.create();
            builder.setName("Service-Component").setText(qualifiedName);

            if(plugin.getConfig().hasConfigurationElement("instructions")) {
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

    private void createActivator() {
        JavaSourceFacet javaSourceFacet = project.getFacet(JavaSourceFacet.class);

        String packageName = shell.promptCommon("What package do you want to use for the Activator class?", PromptType.JAVA_PACKAGE, javaSourceFacet.getBasePackage());
        String className = shell.prompt("How do you want to name the Activator class?", String.class, "Activator");

        JavaClass javaClass = JavaParser.create(JavaClass.class)
                .setName(className)
                .setPackage(packageName);

        javaClass.addImport("org.osgi.framework.BundleActivator");
        javaClass.addImport("org.osgi.framework.BundleContext");

        javaClass.addInterface("BundleActivator");

        javaClass.addField("private static BundleContext context");

        javaClass.addMethod("public void start(BundleContext bundleContext) throws Exception { Activator.context = bundleContext; }").addAnnotation(Override.class);
        javaClass.addMethod("public void stop(BundleContext bundleContext) throws Exception { Activator.context = null; }").addAnnotation(Override.class);

        try {
            javaSourceFacet.saveJavaSource(javaClass);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    private void installCoreLibraries() {
        DependencyFacet dependencyFacet = project.getFacet(DependencyFacet.class);
        DependencyBuilder osgiDependency = DependencyBuilder.create().setGroupId("org.osgi").setArtifactId("org.osgi.core");
        List<Dependency> versions = dependencyFacet.resolveAvailableVersions(osgiDependency);
        if (versions.size() > 0) {
            Dependency version = shell.promptChoiceTyped("Which version of org.osgi.core do you want to install?", versions, versions.get(versions.size() - 1));
            osgiDependency.setVersion(version.getVersion());
        }

        osgiDependency.setScopeType(ScopeType.PROVIDED);
        dependencyFacet.addDependency(osgiDependency);
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
