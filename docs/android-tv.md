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

## Using your phone as a remote

A phone with OpenTune can steer the TV app over the home Wi-Fi: a trackpad that moves a
cursor on the TV's screen, or a pad of buttons, switched with one tap.

**Pairing, once.** On the TV, open Settings, then Pair a phone, and a code comes up. On the
phone, open Settings, then Link a device, then TV, then Control the TV, and scan it. The code
lasts two minutes and works for one phone. After that the phone finds the TV by itself, and
Settings on the TV lists the paired phones, each with Remove.

**Trackpad**

- Slide one finger to move the cursor; faster slides go farther. A tap clicks.
- Hold a finger still, then slide, to drag (the seek bar, a long list).
- Slide two fingers to scroll under the cursor. Tap with two fingers for Back, three for Home.
- Settings (the sliders icon) has cursor speed, natural scrolling, tap to click and whether
  the phone's volume buttons change the TV's volume.

**Buttons.** Arrows (hold to repeat), OK, previous, rewind, fast-forward and next. Back, Home,
play/pause, volume, mute and a keyboard that sends a search to the TV are always at the bottom.
The phone shows what is playing and how far along it is.

**What it can't do.** The cursor and clicks work inside OpenTune on the TV, not across the whole
TV; the TV's own home screen and other apps need the TV's remote. It works while OpenTune is
open on the TV: if the TV has gone to another app, open OpenTune there first. Volume changes
OpenTune's stream, which a TV that fixes its volume ignores.

**How it is kept private.** Pairing gives each phone a key of its own, made on the TV and sent
only inside the code's one-time exchange. Every message afterwards is encrypted and tied to its
connection and order, so a recorded message can't be played back, and the TV answers only
phones that hold a key. Everything stays on your network; nothing goes to a server. Removing a
phone on the TV cuts it off at once.

## Verification

Automated tests cover the pages (Library, Liked songs, Settings, the player's empty
state, synced lyrics and the seek bar), focus moving along the top bar with the
arrows and OK opening a tab, going back through pages, and the small rules behind
them (time formatting, the format line, how a search is sorted into rows). For the
phone remote they cover pairing and control over real sockets, the refusals (a code used
twice, a removed phone, a wrong key, a replayed or oversized message), the trackpad's
gestures on the phone and the cursor's clicks, scrolling and dragging on the TV.

Before a TV store release, also test on a physical Android TV and Google TV: first
launch, the on-screen keyboard in Search, signing in from a phone, Back from every
page, media keys with the app in the background, and playback resuming. For the phone remote:
pairing, the cursor on the real screen and how fast it feels, and a network that blocks
discovery (the phone then falls back to the address it last saw the TV at). Firmware,
the keyboard and HDMI volume differ from one TV to the next.
