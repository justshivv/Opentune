# OpenTune Connect: playback across your own devices

## Goal

Like Spotify Connect, between the Android app and the web player (`/player/`), for one person's own devices:

- **See** what plays on your other devices: the phone shows that the PC plays, and the other way round.
- **Control** it remotely: play/pause, next, previous, seek and volume, play a song, add to the queue.
- **Move** playback with its queue and position to another device ("play here" / "play there").

Listen Together (rooms with friends) stays as it is. Connect is a separate, private group of your own devices.

## Non-goals (first version)

- **Waking a closed app.** The phone is reachable while its process lives: open, in the background, playing or not. It isn't reachable once the app has been closed.
- **TV, Wear and desktop.** The protocol allows them, but they aren't built now.
- **Syncing anything other than live playback.** That covers history, likes, settings and saved queues.
- **YouTube's own cross-device queue.** That is `/next` with `WATCH_NEXT_TYPE_GET_QUEUE`, see the appendix.

## Approach

Connect uses the same transport as Listen Together, with a simpler layer on top.

- **Reused from Listen Together:**
  - the Nostr `RelayPool` (Kotlin `data/together/Nostr.kt`, TS `web/app/src/together/nostr.ts`);
  - signing (`Schnorr`, `signedEvent`);
  - AES-GCM sealing (`gcmSeal` / `gcmOpen`).
- **Events** are ephemeral: kind 21421, so relays don't store them. They are tagged `["t", groupTag]`.
- **No host, no lobby.** Every paired device is a peer. The device that plays publishes its state, and the others send commands addressed to it.

Two alternatives were rejected:

- **Listen Together as is:** its host/guest roles and admission flow don't fit a person's own devices. It would also mix friends' rooms with devices in the UI.
- **Direct WebRTC:** NAT traversal, ICE, STUN and TURN are a lot of work in the browser and on Android, for little gain. Commands need hundreds of milliseconds, not tens.

## Device group

- **Group secret:** 32 random bytes, stored on each device.
  - Android: app preferences. Web: `localStorage` `opentune:connect`.
  - From it, HKDF-SHA256 gives:
    - `tag` = `HKDF(secret, info "opentune/connect/tag")`, hex, 32 chars;
    - `key` = `HKDF(secret, info "opentune/connect/key")`, AES-256.
  - Messages are sealed with `key`, with `tag` as additional data. This is the same format as `RoomKey.seal`.
- **Device id:** random, 12 chars, kept per install.
- **Device name:** editable. The default is "Phone model" on Android and "Browser on OS" on the web.
- **Nostr key:** each device has a session Schnorr key for signing. Identity inside the group comes from the sealed content (device id), not from the Nostr pubkey.

### Pairing

1. **QR code or link.** Settings → Devices → Add a device shows a QR code, the link and a short text form of the secret.
   - Links: `opentune://connect/#<base64url secret>` and `https://justshivv.github.io/Opentune/player/#/settings/devices?join=<…>`. The secret is only in the fragment, so it never reaches a server.
   - Opening the link on a device that already has a different group asks before replacing it.
2. **Through the YouTube account** (opt-in switch "Sync through my YouTube account"):
   - **Turning it on:** OpenTune looks in the account's playlists for a private playlist titled `OpenTune Connect`. If one exists, it reads the secret from its description (`opentune-connect:v1:<base64url>`) and joins that group. If none exists, it creates the playlist (privacy `PRIVATE`, no songs) with the current secret.
   - **On start**, with the switch on and signed in, OpenTune reads that playlist again. A different secret there wins, which is how "Disconnect all" spreads.
   - **On the web**, reads and writes go through the extension's signed InnerTube calls (`playlist/create`, `browse/edit_playlist` `ACTION_SET_PLAYLIST_DESCRIPTION`, library playlists). Android uses its signed client.
   - **Cost:** the playlist shows in the library. Settings says so.
3. **Disconnect all** makes a new secret. With account sync on, it also writes the new secret to the playlist. Devices that haven't got it any more fall out.

## Messages

Every message is JSON sealed with the group key, as the content of a kind 21421 event tagged with the group tag. Each message carries `from` (the sender's device id), `seq` (rising per sender) and `at` (the sender's ms clock).

| type | sent by | content |
|---|---|---|
| `presence` | every device, on joining, every 30 s and on change | `name`, `form` (`phone`/`web`), `canPlay` (true), `active` (playing or paused with a track) |
| `state` | the active device, on every change and every 5 s while playing | `track`, `playing`, `pos` (ms at `at`), `queue` (up to 100 tracks after the current one), `index`, `volume` (0..1), `shuffle`, `repeat` |
| `command` | any device | `to`, `id`, `action`: `play`/`pause`/`next`/`previous`/`seek` (`pos`)/`volume` (`value`)/`playTrack` (`track`)/`addToQueue` (`tracks`) |
| `ack` | the target of a `command` or `transfer` | `id`, `ok`, `reason?` |
| `transfer` | the device playing now, or a controller asking it | `to`, `id`, `track`, `queue`, `index`, `pos`, `playing` |
| `leave` | a device turning Connect off | none |
| `ping` / `pong` | anyone / its target | clock offset, as `Msg.Ping` / `Msg.Pong` in Together |

- **Tracks** use the same shape as Together's `Track` / `RoomTrack`: `id`, `title`, `artist`, `thumb`, `length`, `album`, `station?`.
- **Tracks a device can't share are dropped** from `queue` and `transfer`: local files and private-server songs, as in `Track.of`.
- **A `state` whose track can't be shared** is sent with `track: null, private: true`, so others show "Playing something local".
- **Out-of-order messages:** a receiver keeps the highest `seq` per sender and drops anything older. It drops messages from its own `from`.
- **Large messages:** a `state` or `transfer` over 48 KB sealed (the relay message limit) cuts `queue` from the end until it fits.

## Behaviour

### Who is active

- The active device is the newest `state` with `track != null`, from a device that is still present. Presence is the last `presence` or `state` within 75 s.
- **On this device:** while another device is active and this one isn't playing, the player shows the remote one ("controlling"). Its buttons send `command`s instead of acting locally.
- **Starting something locally** (a tap on a song) plays locally. If another device was playing, it gets a `command` `pause`, so two devices don't play at once.

### Controlling

- A `command` has an `id`. The target applies it and answers `ack`.
- The UI updates optimistically: pause shows paused at once. It rolls back if no `ack` arrives within 5 s, with the notice "{name} isn't answering".
- Volume is the target's player volume: the web player's gain, and on Android the app's player volume, not the system volume.

### Moving playback

1. A controller sends `transfer` with `to` = the target, or the active device sends it itself. The track, queue and position come from the active device's last `state`.
2. The target loads the track at `pos`, adjusted by the clock offset and the time in flight. It plays only if `playing`. Then it answers `ack ok`.
3. On the `ack`, the source pauses, keeps its queue, and stops being active.
4. **Failure:** if the target can't play the track, it skips forward through `queue` to the first track it can play. If none plays, it answers `ack ok:false reason:"unplayable"`. The source keeps playing, and the controller shows "Can't play this on {name}".
5. **No `ack` within 8 s:** the source keeps playing and the notice "{name} isn't answering" shows.

The target picks its own source for each track (lossless add-ons, YouTube) by its own settings. Quality isn't carried across.

### Lifecycle

- **Android:**
  - A `Connect` object in `core` holds the `RelayPool`, started on app start when Connect is on and a group exists. It stops when the process ends.
  - It attaches to the player through the same `PlayerConnection` hook as `Together.attach`.
  - It doesn't run a foreground service. If Doze cuts the network, the pool reconnects as it does for Together.
- **Web:** connects while the tab is open. It sends `leave` on `pagehide` (best effort).
- **Listen Together:** while a room is joined, Connect still shows other devices. Moving playback away is disabled with "Leave the room first", so two features don't fight over the player.

## UI

- **Devices button** in the full player and on the web player bar, next to the cast and output button. It opens a sheet:
  - "This device", then the other present devices, each with a form icon and name, and the active one's song;
  - tapping a device moves playback there;
  - a footer link: "Add a device".
- **While controlling another device:**
  - the mini player shows the song with "Playing on {name}" in place of the artist line, and the play/pause and next buttons control that device;
  - the full player shows a bar "Controlling {name}";
  - the seek bar follows `state` with local interpolation.
- **Settings → Devices (Connect):**
  - an on/off switch;
  - "This device's name";
  - Add a device (QR, link, copy);
  - "Sync through my YouTube account";
  - the paired devices seen recently (name, last seen);
  - Disconnect all.
- **Web:** a Settings page `/settings/devices`, and opening `#/settings/devices?join=` joins.
- **Translations:** every new string goes into the 26 translated languages.

## Code layout

- **Kotlin, `core/src/main/java/com/opentune/data/connect/`:**
  - `ConnectGroup.kt`: secret, HKDF, seal/open, links;
  - `ConnectMessages.kt`: `@Serializable` sealed `ConnectMsg`;
  - `ConnectSession.kt`: presence, the active-device tracker, command/ack, transfer state machine; pure apart from an injected transport and clock;
  - `Connect.kt`: the app-wide object;
  - `ConnectAccountSync.kt`: the playlist secret.
- **Android UI:**
  - `app/.../ui/connect/DevicesSheet.kt`;
  - `app/.../ui/connect/ConnectSettings.kt`;
  - the "Playing on" state in the mini and full player;
  - deep link `opentune://connect`.
- **TypeScript, `web/app/src/connect/`:** `group.ts`, `messages.ts`, `session.ts`, `accountSync.ts`, `useConnect.ts` (zustand store), components `DevicesSheet.tsx`, `ConnectSettingsPage.tsx`.
- **The shared Nostr transport** stays in `together/`. Connect imports it, with no copy.

## Testing

- **Kotlin unit tests:**
  - group derivation and seal/open round trip;
  - parsing a link;
  - message (de)serialisation;
  - the active-device tracker (presence timeout, `seq` ordering);
  - the transfer state machine (`ack`, timeout, unplayable);
  - the command rollback;
  - trimming a large queue.
- **TS unit tests (vitest):** the same cases.
- **Shared test vectors:** a fixed secret, the expected tag, and a sealed message both sides must open. These are in `core/src/test/resources/connect/vectors.json` and copied into the web tests. They prove the phone and the web can read each other.
- **Manual:** phone (OpenTune Dev) ↔ web in Chrome:
  - pair by QR;
  - see the web's song on the phone;
  - pause/seek/next from the phone;
  - move to the phone and back;
  - kill the web tab (the device disappears within 75 s);
  - account sync on both.

## Appendix: YouTube's own cross-device queue

YouTube Music's "From your browser" mini player comes from a server-side queue tied to the account. The web client:

- restores it with `POST /youtubei/v1/next` `{watchNextType: "WATCH_NEXT_TYPE_GET_QUEUE", queueContextParams}`;
- reads `musicQueueRenderer.musicQueueConfig.musicQueueRestorationInfo.lastWatchTimestamp` and `onQueueRestoredCommand`;
- edits the queue with `/music/get_queue` and `/next` carrying `queueContextParams`;
- restores only for subscribers (`IS_SUBSCRIBER`).

It isn't live and can't be controlled, so Connect doesn't build on it. Showing YouTube's last queue on start could be a later, separate addition.
