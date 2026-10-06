<p align="center">
  <img src="docs/banner.jpg" alt="OpenTune: a free, open-source music player for Android, built on YouTube Music" width="100%">
</p>

<p align="center">
  <a href="https://github.com/justshivv/Opentune/releases/latest"><b>Download the latest APK</b></a>
  &nbsp;·&nbsp;
  <a href="#features">Features</a>
  &nbsp;·&nbsp;
  <a href="#building">Build it yourself</a>
</p>

<p align="center">
  <a href="https://github.com/justshivv/Opentune/actions/workflows/ci.yml"><img src="https://github.com/justshivv/Opentune/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0"></a>
</p>

# OpenTune

An Android music player for YouTube Music. It talks to YouTube's internal
("Innertube") API directly, so there's no API key, no account required and
no server of your own. Sign in if you want your likes and playlists.

Built with Kotlin, Jetpack Compose and Media3. Android 8.0 and newer.

## See it

<p align="center">
  <a href="docs/media/opentune-reel.mp4"><img src="docs/media/preview.gif" alt="OpenTune in 30 seconds" width="300"></a>
  <br>
  <sub><a href="docs/media/opentune-reel.mp4">Watch the 30-second video</a> (MP4, with sound)</sub>
</p>

<table>
  <tr>
    <td><img src="docs/screenshots/01-player.jpg" alt="Player, coloured from the cover"></td>
    <td><img src="docs/screenshots/02-lyrics.jpg" alt="Synced lyrics"></td>
    <td><img src="docs/screenshots/03-explore.jpg" alt="Explore as a mood board"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/04-radio.jpg" alt="Live radio near you"></td>
    <td><img src="docs/screenshots/05-together.jpg" alt="Listen together room"></td>
    <td><img src="docs/screenshots/06-podcasts.jpg" alt="Podcasts"></td>
  </tr>
</table>

## Install

1. Open the [latest release](https://github.com/justshivv/Opentune/releases/latest)
   and download the APK for your phone. `arm64-v8a` fits almost every phone
   from the last several years; `universal` runs on anything.
2. Open the file and allow your browser or file manager to install apps
   when Android asks.
3. That's it. OpenTune checks this repository for new releases once a day
   and can update itself from Settings.

Each release lists SHA-256 checksums of its APKs in `SHA256SUMS.txt`.

## Highlights

- **No ads in the app, no account, no server.** Signing in is optional and
  only brings your likes and playlists along.
- **Best audio by default**, with crossfade, loudness levelling, a 15-band
  equalizer and AutoEq correction for 8,000+ headphones.
- **Synced lyrics**, lit word by word, that also work offline with downloads.
- **Listen together**: share a room code and friends hear the same song,
  in step, on their own phones.
- **Live radio** from 50,000+ stations, nearest first.
- **Podcasts**, downloads, Android Auto, a home-screen widget and imports
  from Spotify and YouTube playlists.

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
- **Sound tools.** A 15-band equalizer (ISO 25 Hz to 16 kHz) with presets,
  tone and balance, bass boost,
  stereo widening, skip silence, USB DAC preference, optional 32-bit float
  output, and a Remix sheet for speed, pitch and reverb (Slowed + reverb,
  Nightcore and more).
- **Headphone correction.** Pick your headphones from AutoEq's 8,000+
  measurements and their parametric correction runs ahead of your own EQ,
  so you start from a neutral sound.
- **Sleep timer.** Stop after 15 to 90 minutes, or at the end of the song.
- **SponsorBlock.** Music videos skip the parts viewers have marked as
  non-music (talking, skits), sponsor reads, self-promotion and like or
  subscribe reminders, using [SponsorBlock](https://sponsor.ajay.app)'s
  segments. Choose the categories in Settings. Only a 4-character hash of
  the video id is sent.
- **Headphones and volume** (optional, off by default). Playback can carry
  on when headphones are plugged in or a Bluetooth device connects, and
  pause when the volume is turned all the way down, playing again when it
  comes back up.
- **Podcasts.** A tab of its own: search shows or episodes, browse
  topics, popular episodes and shows to try from YouTube Music, and show
  pages with every episode. Subscribe to shows (kept on the phone, no
  account needed) to see their newest episodes up top. Episodes pick up
  where you stopped, with "Continue listening" for the ones in progress
  and a played mark for the ones finished.
- **Internet radio.** 50,000+ live stations from
  [Radio Browser](https://www.radio-browser.info), the free community
  directory: stations near you, your country's most played, worldwide
  favourites, genres and search. "Near you" uses approximate location,
  only when you ask, sends Radio Browser a position rounded to about a
  kilometre and keeps nothing; it lists stations nearest first with how far
  away they are, then stations listed for your city or state that have no
  map location (often the public broadcaster's FM channels), with the FM
  frequency when the name carries one. Stations the directory lacks can be
  added by stream address, or suggested to Radio Browser. The song on air shows in the player and notification when
  the station sends it. Favourite stations are in Android Auto too.
- **Listen together.** Start a room and share its code; friends who join
  hear what you play, in step, each phone streaming the music itself. Guests
  can add songs to the room's queue and chat, and the host can let everyone
  play, pause, seek and skip. Pausing without control pauses just for you;
  "Catch up" puts you back in step. Rooms run over public Nostr relays with
  no account or server: messages are encrypted with a key from the room
  code and sent as ephemeral events that relays don't keep. Invites open
  with an `opentune://together/<code>` link or by typing the code.
- **Picks up where you left off.** The queue, song and position come back
  at launch, paused, without fetching anything until you press play.
- **Home-screen widget.** Cover, title and artist, with previous,
  play/pause, next and like. Play on the widget, or on a Bluetooth headset,
  starts the saved queue even after the app was closed.
- **Like and shuffle in the notification.** Both sit in the media
  notification's extra buttons and in Android Auto, and stay in step with the
  app.
- **Android Auto.** Browse Home, Recent and Library (Liked, Downloads,
  playlists, radio stations) on the car screen, search by typing or voice ("play … on
  OpenTune"), and tap a song to play its list from there. Sideloaded
  builds show up in Android Auto once "Unknown sources" is turned on in
  Android Auto's developer settings.
- **Opens YouTube links.** music.youtube.com, youtube.com and youtu.be
  links, or links shared to the app, open in OpenTune.

### Library and account

- **Sign in to YouTube Music** on Google's own page inside the app. Likes
  sync to the account, your playlists show in Library, and Home turns
  personal. The login cookie stays in app-private storage and is left out
  of backups.
- **Downloads** for offline listening, with a quality setting and Wi-Fi
  only by default. They keep going after the app is closed.
- **Library:** a card for your year so far (minutes, plays, top song and
  artist), shortcuts to Liked, Downloads, On this phone and Replay, then
  your playlists from the app and from YouTube in one list.
- **Replay:** top songs, artists and albums from this device's history.
- **Your own music server** (optional): Navidrome, Gonic, Airsonic,
  Nextcloud Music or any Subsonic-compatible server. Browse recently added
  and most played albums, artists and playlists, search, and play songs
  as they're stored, so FLAC and hi-res files stay lossless. Also in
  Android Auto. Only a sign-in token is kept, never the password, and it's
  left out of backups.
- **ListenBrainz** (optional): send what you play to your open
  ListenBrainz history with your user token, and get its recommendations
  as a shelf on Home.
- **Album covers from MusicBrainz**: for music videos and local files
  without art, the player shows the album's cover from the Cover Art
  Archive instead of a video frame, when MusicBrainz has a confident match.
- **Last.fm scrobbling** (optional), with your own free Last.fm API key.
  The password is only used once to get a session and isn't stored.
- **Import playlists.** Settings › Import a playlist takes:
  - a **YouTube Music or YouTube playlist link**, read in full however long
    it is, and saved as a playlist on the phone (or played straight away);
  - a **Spotify playlist, album or song link**, pasted or shared to
    OpenTune from the Spotify app, with no sign-in. Each song is looked up
    on YouTube Music and the closest catalogue match kept, with misses
    listed. A song link plays straight away. Without signing in, Spotify
    only shows a playlist's first 100 songs;
  - **Export from Spotify**, for every song of any playlist or your Liked
    Songs: [Exportify](https://exportify.app) opens inside OpenTune, you
    sign in with Spotify there and tap Export (or Export All), and the
    playlist comes straight back ready to import, with no file to find.
    A CSV from Exportify, TuneMyMusic, Soundiiz and others can also be
    picked from the phone; columns are found by their headers.

  Nothing streams from Spotify.
- **New-release alerts.** Follow an artist from their page (no account
  needed) and a notification tells you when they put out an album or
  single. Checked twice a day.
- **Hide explicit content** (optional): songs and albums YouTube Music
  marks explicit are left out of Home, search, album and artist pages,
  autoplay and radio. Tracks the catalogue doesn't label still show.
- **Set as ringtone.** A downloaded song or a file on the phone can be the
  ringtone, notification sound or alarm. A downloaded song is copied to
  Ringtones/OpenTune first, since the system can't read app storage.
- **Export and import** of settings, history, likes and playlists as JSON.
- **Updates in the app.** OpenTune checks this repository's GitHub releases
  once a day (or when you ask), downloads the APK for your phone's
  processor, checks it against the release's SHA256SUMS and hands it to
  Android's installer. Android only installs an update signed with the same
  key as the installed app.

### Look and feel

- **Liquid Glass.** The floating bars and player buttons frost and bend
  what's behind them, like Apple's material (Android 13 and newer; frosted
  glass below that, solid with "Reduce dynamic blur").
- **A dock that gets out of the way.** Home, Explore, Podcasts and Library
  sit in a floating glass pill, each an icon over its name; a soft lens of
  the accent colour slides to the open one and its icon fills in. Search
  has its own round button beside the pill. The now-playing card above
  takes its tint from the cover and has the play button inside a progress
  ring. Scroll down and the dock tucks away while the card shrinks into a
  round cover bubble in the corner, still ringed by progress; scroll up and
  both come back. Pick how the dock goes: Fold, Glide (off the bottom and
  back on a spring), Retract (into the corner and unrolling from it),
  Cascade (tabs drop one by one and return in a wave) or Dissolve (blurs
  away and sharpens back, Android 12+).
- **Its own typeface.** Every screen is set in [Outfit](https://github.com/Outfitio/Outfit-Fonts).
- **Home greets you** by the time of day and your name, with your avatar
  leading to settings. Below YouTube Music's own shelves it adds a radio
  of your last song, albums, singles and similar artists for the artists
  you play most, new releases, the charts, Explore, and playlists for a
  couple of moods that change daily.
- **Explore as a board.** Pills for artists to jump to (the ones you play
  most first, then the charts' top artists), chips to narrow it to moods,
  genres, new releases or charts, and a two-column board of mood cards,
  new albums and chart playlists at different heights, Pinterest-style.
- **An Apple Music-style player.** Cover, lyrics and queue modes, heart and
  "…" buttons, the current lyric line under the title, the artist name
  opening their page, time remaining,
  large back/play/forward controls, a shuffle/repeat/autoplay pill, and the
  name of the output device. Drag it down and it shrinks into a card
  before it closes.
- **Synced lyrics** from [LRCLIB](https://lrclib.net), lit word by word,
  and YouTube Music's lyrics, in an order you set, with eight animation
  styles (Fluid, Karaoke, Slide, Focus zoom, Minimal, and three where the
  words themselves move: Bounce, Pop and Reveal), a per-song timing
  offset, adjustable text size, and lyrics saved with downloads so they
  work offline.
- **One song menu everywhere:** round buttons up top for like, download,
  add to playlist and share, then play next, add to queue, start radio,
  view artist, open album, convert to the music video, and dislike (kept
  out of autoplay); plus upgrade quality,
  signal path, sleep timer, lyrics offset and copy log in the player.
- **Player layouts:** Classic; Vinyl, where the cover turns as a record
  while the song plays; Lyrics first, with a small cover on top and the
  lyrics taking the rest; Minimal, with just the cover, title, seek bar
  and controls; Cassette, a tape whose reels turn and wind from one side to
  the other as the song goes on; Halo, a round cover in a ring of moving
  bars; and Polaroid, the cover as an instant photo that sways gently.
- **Wavy seek bar** (optional) that ripples while music plays.
- **Quick screens.** Home opens on the last copy saved while the fresh one
  loads; pages you've visited and searches you've made come back at once
  and refresh in the background.
- **iOS-style bounce** at the ends of every list.
- **Smooth tab changes.** The dock's highlight stretches toward the tab
  you pick and settles into it, without overshooting.
- **Haptic feedback you can tune**, from off to strong. Taps use the phone's
  crisp haptic primitives where it has them, and follow the system's touch
  feedback switch.
- **Themes:** light, dark or system, Material You, accent colors, palette
  styles, pure black, color from artwork, and reduced motion and blur.

YouTube's audio is lossy, so there's no lossless or Dolby Atmos stream
option from YouTube; lossless comes from your own music server. OpenTune
doesn't unlock JioSaavn streams with a hard-coded key, doesn't fetch Tidal,
Qobuz, Deezer or DAB audio through relay servers or decrypt Deezer
streams, doesn't use scraped Apple Music, Tidal or Spotify tokens for
animated "Canvas" covers, and doesn't take lyrics from Apple Music, Spotify
or Musixmatch through proxies or scraped secrets. Those depend on getting
around a service's protection or terms, so they stay out.

Not built yet: lyric translation and an in-app language setting.

## Building

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

### CI and releases

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
docs/               README banner, screenshots and the 30-second video
```

The README's screenshots are drawn from the app's own screens by
`ReadmeScreenshotTest`, which runs when `OPENTUNE_SHOT_ART` points at a
folder of cover images (they aren't kept in the repository).

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
frosted glass uses [Haze](https://github.com/chrisbanes/haze). The
[Outfit](https://github.com/Outfitio/Outfit-Fonts) font is used under the
SIL Open Font License 1.1 (`third_party/outfit/OFL.txt`). Album covers
come from the [Cover Art Archive](https://coverartarchive.org) through
[MusicBrainz](https://musicbrainz.org), and recommendations from
[ListenBrainz](https://listenbrainz.org), all run by the MetaBrainz
Foundation. Headphone corrections come from
[AutoEq](https://github.com/jaakkopasanen/AutoEq) by Jaakko Pasanen (MIT).
Skip segments come from [SponsorBlock](https://sponsor.ajay.app) (data
under CC BY-NC-SA 4.0), and radio stations from the
[Radio Browser](https://www.radio-browser.info) community directory.

The screenshots and video show real albums, artists and songs to show the
app as it's used. Cover art, names and recordings belong to their owners.
OpenTune isn't affiliated with or endorsed by YouTube, Google, Spotify or
any artist or label shown.

OpenTune is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE).
