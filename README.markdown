Installation
============
    forge install-plugin osgi

Commands
============
    osgi setup

Adds and configures the Bundle Plugin to the Maven configuration and allows creation of an Activator class.

_WARNING!_ The Bundle Plugin needs the packaging type to be bundle to work correctly. This is currently not yet possible in Forge so you have to do this by hand.

    <packaging>bundle</packaging>

    osgi add-service-component demo.Myclass.java

Adds a Service-Component header to the Bundle Plugin configuration.
