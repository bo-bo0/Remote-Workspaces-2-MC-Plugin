## Local development

Use an MCreator source checkout with Java 25 / JBR and JGit 7. Run MCreator's
`initialSetup` task first if its bundled JBR has not been downloaded yet.

Create a local `gradle.properties` (ignored by Git), or copy
`gradle.properties.example`, and adjust these paths:

```properties
mcreator_path=D:/MCreator/MCREATOR_SOURCE/MCreator
org.gradle.java.installations.paths=D:/MCreator/MCREATOR_SOURCE/MCreator/jdk/jbr25_win_64
```

The second path must point to MCreator's JBR for your platform. On Linux use
`jdk/jbr25_linux_64`; on macOS use
`jdk/jbr25_mac_aarch64/Contents/Home` or `jdk/jbr25_mac_x64/Contents/Home`.
Use Java 25 to run the Gradle wrapper. The wrapper uses Gradle 9.6.0.

Build the installable plugin archive:

```powershell
.\gradlew.bat jar
```

The ZIP is written to `build/libs/mcreator-vcs-<plugin version>.zip`, including
compiled classes, resources, bundled dependencies, licenses and merged service
providers. MCreator's own classes and dependencies are used for compilation
without being bundled into the plugin.

Launch MCreator with the freshly built plugin:

```powershell
.\gradlew.bat runM
```

`runM` builds the ZIP, stages it in `build/run/plugins`, and starts MCreator using
its JBR, runtime dependencies and JVM arguments. It uses the MCreator checkout as
the working directory, so built-in plugins and other installation assets are
available. A separate development profile in `build/run/mcreator-home` enables
Java plugins automatically and keeps its preferences and logs inside this project.

Development mode sets `MCREATOR_PLUGINS_DEV=true`, allowing local testing even
when the checkout's version is absent from `plugin.json`'s `supportedversions`.
This does not change the versions declared as supported by the distributed ZIP.
MCreator build outputs for this project are kept in `build/mcreator`.

The previous `runMCreatorWithPlugin` task remains available as an alias for
`runM`. On Linux and macOS use `./gradlew` in place of `.\gradlew.bat`.
