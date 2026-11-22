<p align="center">
  <img src="docs/banner.jpg" alt="OpenTune: a free, open-source music player for Android, built on YouTube Music" width="100%">
</p>

<p align="center">
  <a href="https://github.com/justshivv/Opentune/releases/latest"><b>Download</b></a>
  &nbsp;·&nbsp;
  <a href="https://justshivv.github.io/Opentune/">Website</a>
  &nbsp;·&nbsp;
  <a href="https://justshivv.github.io/Opentune/player/">Web player</a>
  &nbsp;·&nbsp;
  <a href="docs/features.md">Features</a>
  &nbsp;·&nbsp;
  <a href="https://discord.gg/6udrCVh5n">Discord</a>
</p>

<p align="center">
  <a href="https://github.com/justshivv/Opentune/actions/workflows/ci.yml"><img src="https://github.com/justshivv/Opentune/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0"></a>
  <a href="https://justshivv.github.io/Opentune/usage/"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fopentune-usage.create-a-new-site-with-sites.workers.dev%2Fv1%2Fbadge%3Fmetric%3Dinstallations%26label%3Dinstalls&cacheSeconds=300" alt="Installations reported"></a>
  <a href="https://justshivv.github.io/Opentune/usage/"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fopentune-usage.create-a-new-site-with-sites.workers.dev%2Fv1%2Fbadge%3Fmetric%3Ddaily%26label%3Dlistening%2520today&cacheSeconds=300" alt="Installations active today"></a>
  <a href="https://justshivv.github.io/Opentune/usage/"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fopentune-usage.create-a-new-site-with-sites.workers.dev%2Fv1%2Fbadge%3Flabel%3Dactive%2520this%2520month&cacheSeconds=300" alt="Active installations this month"></a>
</p>

<p align="center">
  <a href="https://trendshift.io/repositories/311235?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-311235" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/trendshift/repositories/311235/daily?language=Kotlin" alt="justshivv%2FOpentune | Trendshift" width="250" height="55"/></a>
  <a href="https://trendshift.io/repositories/311235?utm_source=repository-badge&amp;utm_medium=badge&amp;utm_campaign=badge-repository-311235" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/repositories/311235" alt="justshivv%2FOpentune | Trendshift" width="250" height="55"/></a>
</p>

<p align="center">
  <a href="https://rookieenough.github.io/Orion-Data/redirect.html?id=opentune&icon=https%3A%2F%2Fjustshivv.github.io%2FOpentune%2Ficon-512.png"><img src="https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png" alt="Get it on Orion Store" height="52"></a>
</p>

# OpenTune

A free music player for the YouTube Music catalogue, with no ads, no account
and no subscription. It runs on Android phones and TVs, Windows, macOS, Linux
and in the browser. Sign in only if you want your likes and playlists.

<p align="center">
  <img src="site/img/app/fullcover.webp" alt="The player, its cover filling the top of the screen" width="19%">
  <img src="site/img/app/lyrics.webp" alt="Synced lyrics, the line being sung in white" width="19%">
  <img src="site/img/app/halo.webp" alt="The halo player: a round cover ringed with light" width="19%">
  <img src="site/img/app/together.webp" alt="A Listen together room with its code and listeners" width="19%">
  <img src="site/img/app/radio.webp" alt="Live radio stations near you" width="19%">
</p>

## Get it

| Where | How |
|---|---|
| **Android phone or tablet** | Download the APK from the [latest release](https://github.com/justshivv/Opentune/releases/latest), open it and allow the install. Android 8.0 or newer. |
| **Android TV, Google TV** | Install `OpenTune-TV-<version>.apk` from the [releases](https://github.com/justshivv/Opentune/releases) and sign in by scanning a code with your phone. [More](docs/install.md#android-tv) |
| **Windows, macOS, Linux** | Desktop builds are attached to the [latest release](https://github.com/justshivv/Opentune/releases/latest). |
| **Browser** | Open the [web player](https://justshivv.github.io/Opentune/player/). It needs a small companion extension. [More](docs/install.md#web-player-preview) |
| **iPhone, iPad** (early build) | Through SideStore or AltStore, with your own Apple ID. [Steps](docs/install.md#iphone-and-ipad) |

On Android, `arm64-v8a` fits almost every recent phone and `universal` runs on
anything. Each release lists the SHA-256 checksums of its files, and the app
checks for updates once a day and can update itself.

## What it does

**Listening**
- Plays the best stream it can find, and upgrades mid-song without a gap.
- Crossfade and Automix, loudness levelling, a 15-band equalizer and AutoEq
  corrections for more than 8,000 headphones.
- Bit-perfect output to a USB DAC on Android 14 and newer.
- Lossless audio from add-ons you choose to connect. [How add-ons work](docs/audio-addons.md)
- Offline downloads, and the music files already on your phone.

**Lyrics**
- Synced lyrics lit word by word, from several sources, that also work offline.
- Tap a line to jump there, or share the lines you love as a picture or a video.

**Beyond music**
- Live radio from more than 50,000 stations, nearest first.
- Podcasts and audiobooks that pick up where you stopped.
- Listen together: share a room code and friends hear the same song in step.

**Your library**
- Likes, playlists, smart playlists, daily mixes and Replay stories.
- Import from Spotify and YouTube playlists; scrobble to Last.fm and ListenBrainz.
- Your own music server (Subsonic).

**Look and feel**
- A player that takes its colours from the cover, in seven layouts, from a
  full-screen cover to vinyl, cassette and a glowing halo.
- Light, dark and pure black themes, accent colours, Liquid Glass, and
  36 languages.

**Around the phone**
- Cast to Chromecasts, TVs and network speakers; Android Auto; a home-screen widget.
- Name that song: hold the phone up and it tells you what's playing.
- Your phone as the remote for the TV app.

The full list, with how each part works, is in [docs/features.md](docs/features.md).

## Documentation

| | |
|---|---|
| [Features](docs/features.md) | Everything the app does, in detail |
| [Installing](docs/install.md) | iPhone and iPad, Android TV and the web player, step by step |
| [Audio add-ons](docs/audio-addons.md) | Connecting your own lossless sources |
| [Android TV](docs/android-tv.md) | The TV app and the phone remote |
| [Appearance options](docs/appearance-options.md) | Themes, layouts and glass |
| [Languages](docs/languages.md) | Translations and how to add one |
| [Service integrations](docs/service-integrations.md) | Last.fm, Discord and build-owner setup |
| [Usage counts](docs/usage-counts.md) | What the app reports, and what it never does |
| [Building](docs/building.md) | Building from source, CI, releases and the project layout |

## Build it yourself

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
```

You need JDK 17 or newer and an Android SDK with platform 37. Details, release
signing and the project layout are in [docs/building.md](docs/building.md).

## Privacy

OpenTune has no ads and needs no account. Usage counting sends a random
installation ID, the app version and daily play totals; never song names,
accounts or listening history. You can turn it off under Settings › About.
[What is counted](docs/usage-counts.md) · [Privacy policy](https://justshivv.github.io/Opentune/privacy/)

## Community

Questions, ideas and bug reports are welcome on
[Discord](https://discord.gg/6udrCVh5n) and in the
[issues](https://github.com/justshivv/Opentune/issues).

## Disclaimer

The screenshots show real albums, artists and songs to show the app as it's
used. Cover art, names and recordings belong to their owners. OpenTune isn't
affiliated with or endorsed by YouTube, Google, Spotify or any artist or label
shown.

## License

OpenTune is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE). Code, libraries, fonts and data from other projects are
listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), as their licences
require.
