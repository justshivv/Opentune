# OpenTune

An Android YouTube Music client. Search and play tracks resolved through
YouTube's internal ("Innertube") API — no official API key, no server of
your own required.

## Status

Early MVP:

- Search (song results) via Innertube
- Playback through a Media3 `MediaSessionService` / ExoPlayer, with stream
  URLs resolved and deciphered via NewPipeExtractor
- A play queue: tapping a song starts it followed by its YouTube Music radio,
  and more radio is appended as playback nears the end. Search results can be
  queued with "Play next" / "Add to queue"; the now-playing screen lists what's
  up next, and tracks can be skipped, jumped to, or removed
- Mini player + a full now-playing screen with seek and previous/next

Not yet built: shuffle/repeat, downloads, lyrics, library/login, Discord Rich
Presence, scrobbling, on-device automix, and the "Listen Together" party
backend — see the architecture sketch this project started from for the
fuller shape.

## Building

Standard Gradle/Android Studio project. Open the repo root in Android
Studio, or from the CLI:

```
./gradlew assembleDebug
```

Requires an Android SDK (compileSdk 36) on `ANDROID_HOME` or in
`local.properties`; there's no signing config, so this produces an unsigned
debug APK. Unit tests run with `./gradlew testDebugUnitTest`.

## Attribution

The Innertube client, response parser, and YouTube stream resolver
(`app/src/main/java/com/opentune/data/`) are adapted from
[BitChord](https://github.com/kushagrasinghx/BitChord), used here under its
GPLv3 license. This project is licensed under the GNU General Public License
v3.0 — see [LICENSE](LICENSE).
