# Starfront TD

A sci-fi tower defense game for Android where the field grows west: a new sector opens every 5 waves.

## Building locally

Open the project in Android Studio and use Run, or from Git Bash:

```sh
scripts/run.sh            # build, install, and launch (prefers a connected phone over the emulator)
scripts/run.sh -s SERIAL  # pick a specific device, e.g. -s emulator-5554
```

The script starts the `Medium_Phone_API_37.0` emulator if nothing is connected (override with `AVD=...`).
Gradle needs JDK 17–21. Android Studio's bundled JDK 25 is too new for Gradle 8.14,
so the script uses the JDK 21 Studio set up for the project (`~/.jdks/jbr-21.0.11`) unless `JAVA_HOME` is set.

Unit tests: `./gradlew :core:jvmTest`

## CI

Every push runs the unit tests and builds a debug APK on GitHub Actions, then publishes it as a
`build-N` release so it can be downloaded straight to the phone.

Debug builds are signed with the checked-in `starfront/debug.keystore`, so CI and local builds
install over each other and keep the saved game.

A second job builds the iOS app on a macOS runner and keeps an unsigned `.ipa` as a workflow
artifact.

## Code layout

- `core/`: the game itself, shared by both platforms. Rules and simulation (`game/`), sound
  synthesis (`audio/`), and the renderer, HUD and touch handling (`GameScreen`, `WorldRenderer`,
  `Sprites`, `UiKit`). It draws through `gfx/`, a small copy of the android.graphics API.
- `starfront/`: the Android app. `GameView` hosts `GameScreen`, `AndroidCanvas` maps `gfx` onto
  android.graphics, and `SoundFx` plays the sounds.
- `ios/`: the iOS side in Kotlin, built as the `StarfrontKit` framework. A Compose host draws
  `GameScreen` through Skia (`SkiaCanvas`), plus sound, haptics and saves.
- `iosApp/`: the Xcode project, a thin Swift shell around `StarfrontKit`.

## iOS

Building for iOS needs a Mac. To put the game on an iPhone:

1. Install Xcode from the App Store and a JDK 17–21 (for Gradle), e.g. `brew install --cask temurin@21`.
2. Clone the repo and open `iosApp/iosApp.xcodeproj` in Xcode.
3. Xcode → Settings → Accounts: add your Apple ID. A free one works.
4. Select the **iosApp** target → **Signing & Capabilities**. Pick your team ("Your Name (Personal
   Team)"), and change **Bundle Identifier** to something unique, such as `com.yourname.starfront`.
5. Connect the iPhone with a cable and tap **Trust** on it. Turn on Developer Mode
   (Settings → Privacy & Security → Developer Mode); the phone restarts.
6. Choose the iPhone as the run destination at the top of the Xcode window and press Run (⌘R).
   The first build takes several minutes while Gradle compiles the Kotlin side.
7. If iOS won't open the app the first time, go to Settings → General → VPN & Device Management
   and trust your developer certificate, then launch it again.

With a free Apple ID, the app stops launching after 7 days. Connect the phone and press Run again
to renew it; the saved game is kept. A paid Apple Developer account ($99/year) makes installs
last a year and enables TestFlight.

To try it without a phone, choose an iPhone simulator as the destination instead.
