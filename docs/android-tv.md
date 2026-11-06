# OpenTune for Android TV and Google TV

OpenTune for TV is an app of its own (`tv/` in the repository), built for a remote
and a screen across the room. It is not the phone app: the phone APK no longer
shows up in a TV's apps row. Both run on the same shared code (`core/`), so
YouTube Music, lyrics, the library, history and the player service behave the same.

Install `OpenTune-TV-<version>.apk` from the
[releases](https://github.com/justshivv/Opentune/releases) page, then open
OpenTune from the TV's apps row. The package is `com.opentune.tv`, so it installs
beside the phone app on the same device without replacing it.

## What's in it

- **Home**: the songs you played last, then YouTube Music's rows.
- **Explore**: artists, new releases, charts, moods and genres.
- **Library**: Liked songs, playlists made on the TV, your YouTube Music playlists
  once signed in, and what you played recently.
- **Search**: type with the TV's own keyboard; results come in as you go.
- **Album, playlist and artist pages** with Play and Shuffle.
- **Now Playing**: the cover large, synced lyrics that follow the song, the queue, a
  seek bar, and the format line (Dolby Atmos, sample rate, bit depth, bitrate).
- **Settings**: sign in, streaming quality, lossless, even loudness, autoplay, Automix.

## Using the remote

- The arrows move the white focus ring. OK opens or plays.
- The bar at the top has Home, Explore, Library, Now Playing, Search and Settings.
  Down from it enters the page; Up from the first row comes back.
- On Now Playing, focus starts on Play/Pause. Up reaches the seek bar, where
  Left and Right move ten seconds. The row of buttons holds shuffle, previous, play,
  next, repeat, like, lyrics and queue.
- Play/Pause, Next, Previous, Fast-forward and Rewind keys work on the player, and in
  the background through the media session.
- Back goes up one page, then to Home, and from Home leaves the app.

## Signing in

Typing a password with a remote is no fun, so the TV is signed in from a phone:

1. On the TV, open Settings, then Account, and choose Sign in. A code comes up.
2. On a phone with OpenTune, signed in to YouTube Music, open Settings, then Link a
   device, then TV, and scan the code. Both must be on the same Wi-Fi.
3. Approve on the phone. The session goes straight from the phone to the TV over the
   local network, encrypted, and the code stops working after two minutes.

The phone and the TV each keep their own local library.

## Verification

Automated tests cover the pages (Library, Liked songs, Settings, the player's empty
state, synced lyrics and the seek bar), focus moving along the top bar with the
arrows and OK opening a tab, going back through pages, and the small rules behind
them (time formatting, the format line, how a search is sorted into rows).

Before a TV store release, also test on a physical Android TV and Google TV: first
launch, the on-screen keyboard in Search, signing in from a phone, Back from every
page, media keys with the app in the background, and playback resuming. Firmware,
the keyboard and HDMI volume differ from one TV to the next.
