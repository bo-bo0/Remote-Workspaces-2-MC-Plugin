## Local development

Use an MCreator source checkout with Java 25 / JBR and JGit 7. Run MCreator's
`initialSetup` task first if its bundled JBR has not been downloaded yet.

Create a local `gradle.properties` (ignored by Git), or copy
`gradle.properties.example`, and adjust these paths:

```properties
mcreator_path=...
org.gradle.java.installations.paths=...
```

The second path must point to MCreator's JBR for your platform. On Linux use
`jdk/jbr25_linux_64`; on macOS use
`jdk/jbr25_mac_aarch64/Contents/Home` or `jdk/jbr25_mac_x64/Contents/Home`.
Use Java 25 to run the Gradle wrapper. The wrapper uses Gradle 9.6.0.

Build the plugin with the jar gradle task:

The ZIP is written to `build/libs/mcreator-vcs-<plugin version>.zip`, including
compiled classes, resources, bundled dependencies, licenses and merged service
providers. MCreator's own classes and dependencies are used for compilation
without being bundled into the plugin.

Launch MCreator with the freshly built plugin using the runM gradle task.

Development mode sets `MCREATOR_PLUGINS_DEV=true`, allowing local testing even
when the checkout's version is absent from `plugin.json`'s `supportedversions`.
This does not change the versions declared as supported by the distributed ZIP.
MCreator build outputs for this project are kept in `build/mcreator`.

The previous `runMCreatorWithPlugin` task remains available as an alias for
`runM`.
