# Building OpenTune

Back to the [README](../README.md).

## Build

Open the repository in Android Studio, or build from the command line:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-<abi>-release.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # lint
```

Each build makes one APK per processor type (`arm64-v8a` for most phones,
`armeabi-v7a`, `x86_64`) and a `universal` one that runs anywhere. Release
builds go through R8, which only removes unused code: nothing is renamed,
so crash logs stay readable (see `app/proguard-rules.pro`).

You need JDK 17 or newer (CI uses 21) and an Android SDK with platform 37
(InnerTubeX is compiled against it; the app targets 36), set through
`ANDROID_HOME` or `local.properties`.

## CI and releases

GitHub Actions builds, tests and lints every push (`.github/workflows/ci.yml`)
and keeps the release APKs as an artifact. Pushing a tag such as `v0.2.0`
builds signed APKs and publishes them as a GitHub release
(`.github/workflows/release.yml`). Release signing uses the repository
secrets `OPENTUNE_KEYSTORE_BASE64`, `OPENTUNE_KEYSTORE_PASSWORD`,
`OPENTUNE_KEY_ALIAS` and `OPENTUNE_KEY_PASSWORD` when they're set, and the
debug key otherwise. Set the secrets before the first release: the in-app
updater can only install a release signed with the same key as the copy
already on the phone, and a CI runner's debug key changes from run to run.

## Project layout

```
app/src/main/java/com/opentune/
  data/innertube/   Innertube client and parser, StreamResolver (engine switch),
                    InnerTubeXResolver, PoToken WebView
  data/             account, downloads, library, history, lyrics, loudness, settings
  playback/         PlaybackService (ExoPlayer, cache, loudness, upgrade, sleep timer),
                    Crossfade, DSP
  ui/               Compose screens: home, explore, search, library, player, settings
ios/
  OpenTuneCore/     Swift package: YouTube Music client and parsers, LRCLIB lyrics,
                    likes and history (Foundation only; `swift test` runs on Linux too)
  OpenTune/         SwiftUI app: screens, AVPlayer playback, lock screen, AirPlay
  project.yml       XcodeGen project (`xcodegen generate`, then open in Xcode)
docs/               README banner, screenshots and the 40-second video
```

The README's screenshots are drawn from the app's own screens by
`ReadmeScreenshotTest`, which runs when `OPENTUNE_SHOT_ART` points at a
folder of cover images (they aren't kept in the repository).
