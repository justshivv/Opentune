# OpenTune

An Android YouTube Music client. Search and play tracks resolved through
YouTube's internal ("Innertube") API — no official API key, no server of
your own required.

## Status

What works:

- **Browse:** Home ("Listen Now" with Recents from your history), Explore
  (moods and genres), Search with suggestions, recent searches and filters,
  album, playlist and artist pages, and a Library with Replay stats and
  music saved on the device
- **Playback:** a Media3 `MediaSessionService` / ExoPlayer, with YouTube
  streams resolved and deciphered via NewPipeExtractor. A queue with
  autoplay radio, shuffle and repeat, and swipe-to-queue on any song
- **Audio:** per-network quality (defaults to the best stream YouTube
  offers), a song cache for instant replays and seeks, preloading of the
  next song for near-instant skips, a 7-band equalizer with tone and
  balance, loudness levelling, stereo widening, skip silence, USB DAC
  preference, optional 32-bit float output, and a Remix sheet for speed,
  pitch, reverb and bass (Slowed + reverb, Nightcore and more). The EQ,
  levelling and bass run in the app's own DSP, so they work on any phone
- **Player:** artwork-tinted colors, mesh / gradient / blurred / plain
  backgrounds, optional full-screen cover art, volume bar, "Playing from",
  stats for nerds, swipe to skip, drag down to close
- **Lyrics:** synced from [LRCLIB](https://lrclib.net), highlighted word by
  word and scrolling with the song; YouTube Music's lyrics as a fallback
- **Look:** black-and-glass design with a floating nav bar; light, dark or
  system theme, Material You, accent colors, palette styles, pure black,
  color from artwork, reduced motion and reduced blur options
- **Data:** listening history on the device, Replay, and export/import of
  settings and history as JSON

YouTube's audio is lossy (Opus around 160 kbps; AAC 256 with Premium), so
there is no lossless or Dolby Atmos option.

Not yet built: account login, downloads, crossfade/automix, Listen
Together, Last.fm, lyric translation, app language.

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
