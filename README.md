# OpenTune

An Android YouTube Music client. Search and play tracks resolved through
YouTube's internal ("Innertube") API — no official API key, no server of
your own required.

## Status

What works:

- Home feed, Explore (moods and genres), and search with suggestions,
  recent searches and filters (songs, videos, albums, artists, playlists)
- Album, playlist and artist pages with Play and Shuffle
- Playback through a Media3 `MediaSessionService` / ExoPlayer, with stream
  URLs resolved and deciphered via NewPipeExtractor
- A play queue with autoplay radio, shuffle and repeat (off / all / one).
  "Play next" and "Add to queue" on any track
- A full-screen player with artwork-tinted colors, swipe to skip, drag down
  to close, and a floating mini player with progress
- Synced lyrics from [LRCLIB](https://lrclib.net), highlighted word by word,
  scrolling with the song; tap a line to jump there
- Remix: speed, pitch, reverb and bass boost, with presets such as Slowed +
  reverb and Nightcore. Reverb and bass use Android's audio effects, so
  support depends on the phone
- Themes: light, dark or system; Material You; accent colors; palette
  styles; pure black; color from artwork; three player backgrounds

Not yet built: downloads, library/login, Discord Rich Presence,
scrobbling, on-device automix, and the "Listen Together" party backend.

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
