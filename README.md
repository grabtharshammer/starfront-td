# Starfront TD

A sci-fi tower defense game for Android where the field grows west: a new sector opens every 5 waves.

## Building locally

Open the project in Android Studio and use Run, or from Git Bash:

```sh
scripts/run.sh            # build, install, and launch on the connected phone/emulator
scripts/run.sh -s SERIAL  # pick a device when several are connected
```

The script starts the `Medium_Phone_API_37.0` emulator if nothing is connected (override with `AVD=...`).
Gradle needs JDK 17–21. Android Studio's bundled JDK 25 is too new for Gradle 8.14,
so the script uses the JDK 21 Studio set up for the project (`~/.jdks/jbr-21.0.11`) unless `JAVA_HOME` is set.

Unit tests: `./gradlew :starfront:testDebugUnitTest`

## CI

Every push runs the unit tests and builds a debug APK on GitHub Actions, then publishes it as a
`build-N` release so it can be downloaded straight to the phone.

Debug builds are signed with the checked-in `starfront/debug.keystore`, so CI and local builds
install over each other and keep the saved game.
