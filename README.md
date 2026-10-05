# OpenTune

An Android YouTube Music client. Search and play tracks resolved through
YouTube's internal ("Innertube") API — no official API key, no server of
your own required.

## Status

What works:

- **Browse:** Home ("Listen Now" with Recents from your history), Explore
  (moods and genres), Search with suggestions, recent searches and filters,
  album, playlist and artist pages, and a Library with listening cards,
  Replay stats, downloads, local music, liked songs and playlists
- **Playback:** a Media3 `MediaSessionService` / ExoPlayer, with YouTube
  streams resolved by two engines that switch automatically: OpenTune's own
  client walk (with NewPipeExtractor as the last resort) and InnerTubeX.
  Each track starts with whichever served the last one. A queue with
  autoplay radio, shuffle and repeat, and swipe-to-queue on any song
- **Audio:** per-network quality (defaults to the best stream YouTube
  offers), a song cache for instant replays and seeks, preloading of the
  next song for near-instant skips, a 7-band equalizer with tone and
  balance, loudness normalization from YouTube's own measurement, stereo widening, skip silence, USB DAC
  preference, optional 32-bit float output, and a Remix sheet for speed,
  pitch, reverb and bass (Slowed + reverb, Nightcore and more). The EQ,
  and bass run in the app's own DSP, so they work on any phone
- **Player:** artwork-tinted colors, mesh / gradient / blurred / plain
  backgrounds, optional full-screen cover art, a queue you can reorder by
  dragging, the current lyric line under the title, time remaining, a sleep
  timer, the output device's name, volume bar, "Playing from", stats for
  nerds, swipe to skip, drag down to close
- **Account and downloads:** sign in to YouTube Music on Google's own page
  (likes sync to the account, your playlists show in Library); download
  songs for offline play, with a quality setting and Wi-Fi only by default.
  The login cookie stays in app storage and is left out of backups
- **Lyrics:** synced from [LRCLIB](https://lrclib.net), highlighted word by
  word and scrolling with the song; YouTube Music's lyrics as a fallback
- **Look:** black-and-glass design with a floating nav bar, Liquid Glass
  (lens refraction on Android 13+, via Kyant's backdrop library); light, dark or
  system theme, Material You, accent colors, palette styles, pure black,
  color from artwork, reduced motion and reduced blur options
- **Data:** listening history on the device, Replay, and export/import of
  settings and history as JSON

YouTube's audio is lossy (Opus around 160 kbps; AAC 256 with Premium), so
there is no lossless or Dolby Atmos option.

Not yet built: crossfade/automix, Listen
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
GPLv3 license. The InnerTubeX glue and the PoToken WebView
(`data/innertube/InnerTubeXResolver.kt`, `data/innertube/potoken/`,
`assets/po_token.html`) also come from BitChord; the PoToken code follows
NewPipe's design. [InnerTubeX](https://github.com/MetrolistGroup/innertubex)
by MetrolistGroup is used as a library under GPLv3. This project is licensed under the GNU General Public License
v3.0 — see [LICENSE](LICENSE).
