# Installing OpenTune

How to get OpenTune onto each kind of device. Back to the [README](../README.md).

## Android phones and tablets

1. Open the [latest release](https://github.com/justshivv/Opentune/releases/latest)
   and download the APK for your phone. `arm64-v8a` fits almost every phone
   from the last several years; `universal` runs on anything.
2. Open the file and allow your browser or file manager to install apps
   when Android asks.
3. That's it. OpenTune checks this repository for new releases once a day
   and can update itself from Settings.

Each release lists SHA-256 checksums of its APKs in `SHA256SUMS.txt`.

## iPhone and iPad

**Requires iOS/iPadOS 16 or newer. An Android APK cannot be installed on an iPhone.**
OpenTune's native iOS build is an `.ipa`. It is not currently on the App Store or TestFlight.

**Recommended: SideStore, or classic AltStore (not AltStore PAL)**

1. On a Windows PC or Mac, follow the official [SideStore setup guide](https://sidestore.io)
   or [classic AltStore installation guide](https://altstore.io). Complete that setup first,
   including its required pairing, background refresh or companion software. Classic
   AltStore needs AltServer available for refresh; SideStore has its own pairing/VPN setup.
2. On your iPhone/iPad, open the installed SideStore or classic AltStore app. Go to
   **Sources → Add source (+)** and paste this exact URL:

   ```text
   https://justshivv.github.io/Opentune/ios/source.json
   ```

3. Open the new OpenTune source, choose **OpenTune**, and tap **Install**. Let the
   installer sign the app with your Apple ID. Enter Apple credentials only into the
   installer you chose, never into an OpenTune issue, chat or website form.
4. If iOS asks, enable **Settings → Privacy & Security → Developer Mode**, restart,
   and confirm. If it reports an untrusted developer, open **Settings → General →
   VPN & Device Management**, select your developer profile and choose **Trust**.
   These prompts depend on the installer and iOS version.
5. Open OpenTune from the Home Screen. Keep the same installer/source for updates.
   With a free Apple ID the app normally needs re-signing every **7 days**:
   enable the installer's refresh setup and check its expiry date. Background refresh
   is not guaranteed, so refresh manually in the installer before expiry if needed.

**Alternative: install the IPA with a computer**

1. Download [OpenTune-iOS.ipa](https://github.com/justshivv/Opentune/releases/download/ios/OpenTune-iOS.ipa)
   and install [Sideloadly](https://sideloadly.io) on your PC or Mac.
2. Connect and unlock your iPhone/iPad, tap **Trust this computer**, choose that device
   in Sideloadly, select the IPA, then follow its signing/install instructions.
3. Complete the Developer Mode/Trust prompts above if shown. Use the same Apple ID
   and installation method when updating; follow Sideloadly's refresh instructions.

**Common problems**

- **“Lacks marketplaceID”**: you are using AltStore PAL. Use SideStore or classic
  AltStore installed through AltServer; PAL is a different distribution system.
- **The file downloads but nothing installs**: Safari cannot install an IPA directly.
  Import it into your configured installer, or use the source URL above.
- **The app stops opening after a week**: refresh/re-sign it through the same installer.
  Avoid deleting it first, because deleting the app also deletes its local data.
- **Signing/device limit error**: free Apple accounts have signing and active-app limits.
  Follow your installer's guidance to deactivate another app or wait for a signing slot.

Without sideloading, you can also open the [web player](https://justshivv.github.io/Opentune/player/)
from Safari and choose **Share → Add to Home Screen**. This installs the web app,
not the native IPA. Safari on iOS cannot run our Chrome/Firefox session extension,
so extension-dependent playback and account features are unavailable there.

The native iOS build currently includes Home, search with recent searches,
artist/album/playlist browsing, a player with synced lyrics and queue editing,
shuffle, repeat (off, all, one), playback speed with or without a change of pitch,
a sleep timer that fades out, background audio, lock-screen controls, AirPlay,
likes, history, your own playlists, albums and playlists saved with the heart, and
downloads for listening offline (one song, or Download all on an album or playlist).
It still has less than Android: no equalizer, no sign-in, no add-on sources and
lyrics from LRCLIB only.

## Android TV

OpenTune for TV is a separate app, made for the remote and a screen across
the room. Install `OpenTune-TV-<version>.apk` from the
[releases](https://github.com/justshivv/Opentune/releases) page; it shows
up in the TV's row of apps. The phone APK doesn't run as a TV app.

Typing a password with a remote is no fun, so you sign the TV in from
your phone instead:

1. On the TV, open Settings → Account and choose Sign in.
2. Scan the QR code with a phone that has OpenTune (Settings → Link a
   device → TV), on the same Wi-Fi, and approve sharing your YouTube
   Music session. The code expires after two minutes.

Your phone can also be the remote: a trackpad that moves a cursor on the
TV, with taps, drags, two-finger scrolling and Back and Home gestures, or a
pad of buttons. Pair it once from the TV's Settings and the phone's Link a
device page.

More in [docs/android-tv.md](android-tv.md).

The phone and the TV each keep their own local library.

## Web player (preview)

There's a player you open in a browser on a computer, written in React.
You can browse, search and listen there. It plays YouTube Music through a
companion browser extension, so you install that as well; it works in
Chrome and Firefox. The extension isn't in the browser stores yet, so you
load a preview package in developer mode. Your library stays in that
browser.

Open it at
[justshivv.github.io/Opentune/player](https://justshivv.github.io/Opentune/player/).

It's a preview, so not everything from the Android app is there. Audio
add-ons work for direct FLAC over CORS-enabled media, and the audio stays
on your own connection with no hosted proxy.
