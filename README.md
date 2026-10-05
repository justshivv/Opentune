# OpenTune

An Android music player for YouTube Music. It talks to YouTube's internal
("Innertube") API directly, so there's no API key, no account required and
no server of your own. Sign in if you want your likes and playlists.

Built with Kotlin, Jetpack Compose and Media3. Android 8.0 and newer.

## Features

### Listening

- **Two stream engines that switch on their own.**
  [InnerTubeX](https://github.com/MetrolistGroup/innertubex) leads and
  OpenTune's own client walk backs it up; after a failure the walk leads
  for ten minutes, then InnerTubeX gets its turn back. NewPipeExtractor is
  the last resort. Every stream URL is
  test-fetched before playback, so a dead link fails fast instead of
  spinning. Stats for nerds shows which engine served the track.
- **Best audio by default.** Streams are ranked by how they sound, not by
  raw bitrate, so Opus wins over AAC at a similar rate, on Wi-Fi and mobile
  data alike, with a lower ceiling available per network.
- **Quality upgrade.** A few seconds into a song, OpenTune looks for a
  clearly better copy (your Premium account's 256 kbps streams, or Opus
  when the song started on AAC) and switches to it without stopping. The
  player's "…" menu has "Upgrade quality" to ask on demand.
- **Crossfade** from 1 to 12 seconds, with equal-power curves so the blend
  stays level. Off by default.
- **One steady volume.** Loudness normalization uses YouTube's own
  measurement of each song. Figures are kept on the device, so songs played
  from the cache or from downloads get the same level as fresh ones.
- **Your phone's own sound effects.** The audio session is opened to the
  system, so Dolby Atmos, Samsung SoundAlive, the system equalizer and
  similar effects apply to OpenTune the way they do to the stock players.
- **A clean signal path.** With the equalizer and effects off, decoded
  audio goes to the output untouched. When they're on, the app's own DSP
  adds headroom so boosts don't clip. The player's Signal path dialog
  shows every stage: source, engine, DSP, tempo, loudness, device effects,
  the Android mixer's rate, volume and output.
- **Bit-perfect USB output** (Android 14+, optional). A USB DAC gets the
  song at its own rate with no mixing, resampling or effects, through
  Android's bit-perfect mixer. The app applies the volume, and at full
  volume the signal is bit-exact.
- **Clarity** (optional). A tone curve after LastWave's Studio Master
  Clarity: rumble cut, firmer bass, less mud, more presence and air, with
  headroom and a soft limiter so peaks stay clean.
- **Fast starts and skips.** Playback begins after half a second of audio.
  The songs either side of the current one are found before you get to
  them, and the next one is buffered while this one plays. Played songs
  stay in a song cache (512 MB by default) for instant replays and seeks.
- **Queue and radio.** Autoplay keeps similar songs coming. Shuffle,
  repeat, play next, add to queue, swipe-to-queue on any row, and
  drag-to-reorder in the player's queue.
- **Sound tools.** A 7-band equalizer with tone and balance, bass boost,
  stereo widening, skip silence, USB DAC preference, optional 32-bit float
  output, and a Remix sheet for speed, pitch and reverb (Slowed + reverb,
  Nightcore and more).
- **Sleep timer.** Stop after 15 to 90 minutes, or at the end of the song.
- **Picks up where you left off.** The queue, song and position come back
  at launch, paused, without fetching anything until you press play.
- **Opens YouTube links.** music.youtube.com, youtube.com and youtu.be
  links, or links shared to the app, open in OpenTune.

### Library and account

- **Sign in to YouTube Music** on Google's own page inside the app. Likes
  sync to the account, your playlists show in Library, and Home turns
  personal. The login cookie stays in app-private storage and is left out
  of backups.
- **Downloads** for offline listening, with a quality setting and Wi-Fi
  only by default. They keep going after the app is closed.
- **Library:** listening cards (minutes this year, top song, top artist),
  Downloads, music on the device, Liked Music, playlists made in the app,
  and your YouTube playlists.
- **Replay:** top songs, artists and albums from this device's history.
- **Last.fm scrobbling** (optional), with your own free Last.fm API key.
  The password is only used once to get a session and isn't stored.
- **Export and import** of settings, history, likes and playlists as JSON.
- **Check for updates** against this repository's GitHub releases.

### Look and feel

- **Liquid Glass.** The floating bars and player buttons frost and bend
  what's behind them, like Apple's material (Android 13 and newer; frosted
  glass below that, solid with "Reduce dynamic blur").
- **A bottom bar that gets out of the way.** Scroll down and the tab bar
  folds into one round button while the mini player slides into the same
  row; scroll up and it unfolds. Each part springs into place.
- **An Apple Music-style player.** Cover, lyrics and queue modes, heart and
  "…" buttons, the current lyric line under the title, time remaining,
  large back/play/forward controls, a shuffle/repeat/autoplay pill, and the
  name of the output device. Drag it down and it shrinks into a card
  before it closes.
- **Synced lyrics** from [LRCLIB](https://lrclib.net), lit word by word,
  and YouTube Music's lyrics, in an order you set, with a per-song timing
  offset, adjustable text size, and lyrics saved with downloads so they
  work offline.
- **One song menu everywhere:** like, dislike (kept out of autoplay),
  add to playlist, download, convert to the music video, start radio, play
  next, add to queue, open album or artist, share; plus upgrade quality,
  signal path, sleep timer, lyrics offset and copy log in the player.
- **Wavy seek bar** (optional) that ripples while music plays.
- **Quick screens.** Home opens on the last copy saved while the fresh one
  loads; pages you've visited and searches you've made come back at once
  and refresh in the background.
- **iOS-style bounce** at the ends of every list.
- **Themes:** light, dark or system, Material You, accent colors, palette
  styles, pure black, color from artwork, and reduced motion and blur.

YouTube's audio is lossy, so there's no lossless or Dolby Atmos stream
option. OpenTune only plays YouTube's own streams: it doesn't pull from
JioSaavn, from lossless "addon" servers, or from Apple Music, Spotify or
Musixmatch through unofficial proxies.

Not built yet: Listen Together, lyric translation and an in-app language
setting.

## Building

Open the repository in Android Studio, or build from the command line:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

You need JDK 17 and an Android SDK with platform 37 (InnerTubeX is compiled
against it; the app targets 36), set through `ANDROID_HOME` or
`local.properties`. There's no release signing config; debug builds are
signed with the local debug key.

## Project layout

```
app/src/main/java/com/opentune/
  data/innertube/   Innertube client and parser, StreamResolver (engine switch),
                    InnerTubeXResolver, PoToken WebView
  data/             account, downloads, library, history, lyrics, loudness, settings
  playback/         PlaybackService (ExoPlayer, cache, loudness, upgrade, sleep timer),
                    Crossfade, DSP
  ui/               Compose screens: home, explore, search, library, player, settings
```

## Attribution

The Innertube client, response parser and YouTube stream resolver under
`data/innertube/` are adapted from
[BitChord](https://github.com/kushagrasinghx/BitChord), used under its
GPLv3 license. The InnerTubeX glue and the PoToken WebView
(`data/innertube/InnerTubeXResolver.kt`, `data/innertube/potoken/`,
`assets/po_token.html`) also come from BitChord; the PoToken code follows
NewPipe's design. [InnerTubeX](https://github.com/MetrolistGroup/innertubex)
by MetrolistGroup and [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
are used as libraries under GPLv3. Liquid Glass uses Kyant's
[backdrop](https://github.com/Kyant0/AndroidLiquidGlass) library, and
frosted glass uses [Haze](https://github.com/chrisbanes/haze).

OpenTune is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE).
