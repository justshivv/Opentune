# OpenTune

An Android YouTube Music client. Search and play tracks resolved through
YouTube's internal ("Innertube") API — no official API key, no server of
your own required.

## Status

Early MVP:

- Search (song results) via Innertube
- Playback through a Media3 `MediaSessionService` / ExoPlayer, with stream
  URLs resolved and deciphered via NewPipeExtractor
- Mini player + a full now-playing screen with seek

Not yet built: queueing/autoplay, downloads, lyrics, library/login,
Discord Rich Presence, scrobbling, on-device automix, and the "Listen
Together" party backend — see the architecture sketch this project started
from for the fuller shape.

## Building

Standard Gradle/Android Studio project. Open the repo root in Android
Studio, or from the CLI:

```
./gradlew assembleDebug
```

Requires an Android SDK (compileSdk 36) on `ANDROID_HOME`; there's no signing
config, so this produces an unsigned debug APK.

## Attribution

The Innertube client, response parser, and YouTube stream resolver
(`app/src/main/java/com/opentune/data/`) are adapted from
[BitChord](https://github.com/kushagrasinghx/BitChord), used here under its
GPLv3 license. This project is licensed under the GNU General Public License
v3.0 — see [LICENSE](LICENSE).
