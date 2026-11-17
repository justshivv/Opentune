# OpenTune Connect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One person's Android app and web player see each other's playback, control it, and move it between devices, over the encrypted Nostr relays Listen Together uses.

**Architecture:**

- **Platforms:** a pure protocol core (group key, messages, session state machine) exists twice, in Kotlin (`core/.../data/connect/`) and in TypeScript (`web/app/src/connect/`). Shared test vectors keep the two compatible.
- **Players:** each platform wires the session to its player the way Together does. On Android that is `PlayerConnection` through `PlayerViewModel`. On the web it is `usePlayer` plus the player's hooks.
- **UI:** a devices sheet in the players, a "Playing on" state in the mini and full players, and a Devices settings page with QR pairing and optional sync of the group secret through a private YouTube playlist.

**Tech Stack:**

- Android: Kotlin, kotlinx.serialization, OkHttp WebSocket (the existing `RelayPool`), Jetpack Compose, javax.crypto (HMAC-SHA256 for HKDF, AES-GCM).
- Web: TypeScript, React, zustand, WebCrypto (HKDF, AES-GCM), vitest.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-11-17-opentune-connect-design.md`.
- **Events:** Nostr kind **21421** (ephemeral), tagged `["t", groupTag]`. The relays are the same `Together.RELAYS` / `RELAYS`.
- **Key derivation:** `tag = hex(HKDF-SHA256(ikm=secret, salt=empty, info="opentune/connect/tag", L=16))` (32 hex chars). `key = HKDF-SHA256(ikm=secret, salt=empty, info="opentune/connect/key", L=32)`.
- **Sealing:** AES-GCM, `base64(iv12 || ciphertext+tag16)`, the group tag as associated data. This is the same as the existing `gcmSeal` / `gcmOpen`.
- **Secret encoding:** 32 bytes, base64url without padding.
- **Links:** `opentune://connect/#<secret>` and `https://justshivv.github.io/Opentune/player/#/settings/devices?join=<secret>`.
- **Timings:**
  - `presence` every 30 s; a device is gone after 75 s;
  - `state` every 5 s while playing, and on every change;
  - a `command` ack times out after 5 s, a `transfer` ack after 8 s;
  - `queue` holds at most 100 tracks;
  - a sealed message is at most 48 KB.
- **Wording:** user-facing English, translated into the 26 languages with `Tr.t` / `Tr.f` on Android and `t()` on the web, as the existing UI does.
- **Commits:** end every message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## File map

| File | Responsibility |
|---|---|
| `core/.../data/connect/ConnectGroup.kt` | Secret, HKDF tag/key, seal/open, links in and out |
| `core/.../data/connect/ConnectMessages.kt` | `@Serializable sealed interface ConnectMsg`, `CTrack` |
| `core/.../data/connect/ConnectSession.kt` | Presence, the active-device tracker, commands with acks, transfers; transport and clock injected |
| `core/.../data/connect/Connect.kt` | App-wide object: prefs, starting/stopping the session, attaching the player, StateFlows for the UI |
| `core/.../data/connect/ConnectAccountSync.kt` | Reads and writes the secret in the private "OpenTune Connect" playlist |
| `core/.../playback/PlayerConnection.kt` | Adds `volume` StateFlow and `setVolume(Float)` |
| `app/.../ui/PlayerViewModel.kt` | `Connect.attach`; controls go through `Connect.intercept` before `Together.intercept` |
| `app/.../ui/connect/DevicesSheet.kt` | The device list sheet |
| `app/.../ui/connect/ConnectSettingsScreen.kt` | Settings → Devices |
| `app/.../ui/connect/RemoteNowPlaying.kt` | The "Playing on …" mini-player state |
| `web/app/src/connect/group.ts`, `messages.ts`, `session.ts`, `connect.ts`, `accountSync.ts` | The same as Kotlin, plus the zustand store |
| `web/app/src/connect/DevicesSheet.tsx`, `pages/ConnectSettingsPage.tsx` | Web UI |
| `core/src/test/resources/connect/vectors.json` | Shared crypto vectors |

---

### Task 1: Kotlin group key, sealing and links

**Files:**
- Create: `core/src/main/java/com/opentune/data/connect/ConnectGroup.kt`
- Test: `core/src/test/java/com/opentune/data/connect/ConnectGroupTest.kt`
- Create: `core/src/test/resources/connect/vectors.json`

**Interfaces:**
- Produces:
  - `class ConnectGroup(val secret: ByteArray)` with `tag: String`, `seal(plain: String): String`, `open(sealed: String): String?`, `appLink: String`, `webLink: String`, `encoded: String`;
  - `companion { fun generate(): ConnectGroup; fun parse(text: String?): ConnectGroup?; fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray }`.

- [ ] **Step 1: Write the failing tests.**
  - The tag of the all-`0x01` secret equals the vector.
  - `seal`/`open` round trip.
  - `open` of the vector's sealed text gives `"hello connect"`.
  - `parse` accepts the app link, the web link and the bare base64url secret, and rejects wrong lengths or other hosts.
  - The RFC 5869 test case 3 for HKDF (IKM 22×0x0b, salt empty, info empty, L=42 → `8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8`).
- [ ] **Step 2: Run** `gradlew :core:testDebugUnitTest --tests '*ConnectGroupTest*'`. Expect it to fail to compile.
- [ ] **Step 3: Implement.**

```kotlin
package com.opentune.data.connect

import com.opentune.data.together.gcmOpen
import com.opentune.data.together.gcmSeal
import com.opentune.data.together.toHex
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A person's own devices: one secret, held by each of them, names the group on the relays and encrypts what it says. */
class ConnectGroup(val secret: ByteArray) {
    init { require(secret.size == 32) }
    val tag: String by lazy { hkdf(secret, "opentune/connect/tag", 16).toHex() }
    private val key by lazy { SecretKeySpec(hkdf(secret, "opentune/connect/key", 32), "AES") }
    fun seal(plain: String): String = gcmSeal(key, tag, plain)
    fun open(sealed: String): String? = gcmOpen(key, tag, sealed)
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    val appLink: String get() = "opentune://connect/#$encoded"
    val webLink: String get() = "https://justshivv.github.io/Opentune/player/#/settings/devices?join=$encoded"
    override fun equals(other: Any?) = other is ConnectGroup && other.secret.contentEquals(secret)
    override fun hashCode() = secret.contentHashCode()

    companion object {
        private val random = SecureRandom()
        fun generate() = ConnectGroup(ByteArray(32).also(random::nextBytes))

        fun parse(text: String?): ConnectGroup? {
            val value = text?.trim() ?: return null
            val raw = when {
                value.startsWith("opentune://connect/#") -> value.removePrefix("opentune://connect/#")
                value.startsWith("https://justshivv.github.io/Opentune/player/") -> value.substringAfter("#/settings/devices?join=", "").substringBefore('&')
                "://" in value -> return null
                else -> value
            }
            if (!raw.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
            return runCatching { ConnectGroup(Base64.getUrlDecoder().decode(raw)) }.getOrNull()
        }

        /** HKDF-SHA256 with an empty salt (RFC 5869). */
        fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = java.io.ByteArrayOutputStream()
            var block = ByteArray(0)
            var i = 1
            while (out.size() < length) {
                mac.update(block); mac.update(info.toByteArray()); mac.update(i.toByte())
                block = mac.doFinal(); out.write(block); i++
            }
            return out.toByteArray().copyOf(length)
        }
    }
}
```

- [ ] **Step 4: Generate `vectors.json`.** Run a one-off test that prints the tag and a sealed `"hello connect"` for secret `01×32`. Paste the output as `{ "secret": "AQEB…", "tag": "…", "sealed": "…", "plain": "hello connect" }`, then assert against it. Run the tests and expect them to pass.
- [ ] **Step 5: Commit** "Connect: the group key, sealing and links".

### Task 2: Kotlin messages

**Files:**
- Create: `core/src/main/java/com/opentune/data/connect/ConnectMessages.kt`
- Test: `core/src/test/java/com/opentune/data/connect/ConnectMessagesTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class CTrack(id, title, artist, thumb?, length?, album?)` with `toSong()` and `CTrack.of(song): CTrack?` (YouTube ids only);
  - `@Serializable sealed interface ConnectMsg { val from: String; val seq: Long; val at: Long }` with subclasses `Presence(name, form, active)`, `State(track?, private, playing, pos, queue, index, volume, shuffle, repeat)`, `Command(to, id, action, pos, value, track?, tracks)`, `Ack(id, ok, reason?)`, `Transfer(to, id, track, queue, index, pos, playing)`, `Leave`, `Ping(c)`, `Pong(c, h, to)`;
  - `ConnectMsg.encode(): String` and `ConnectMsg.decode(text): ConnectMsg?` (Json with `classDiscriminator = "type"` and `ignoreUnknownKeys`);
  - `fun fit(msg, group, limit = 48_000): String`, which seals the message, cutting `queue` from the end until it fits.
- **Wire names** (`@SerialName`): `presence`, `state`, `command`, `ack`, `transfer`, `leave`, `ping`, `pong`.
- **Actions:** `play`, `pause`, `next`, `previous`, `seek`, `volume`, `playTrack`, `addToQueue`.

- [ ] **Step 1: Failing tests.**
  - An encode/decode round trip for each type.
  - A fixed JSON literal decodes, so the web's wire format is pinned. Example: `{"type":"command","from":"a","seq":1,"at":2,"to":"b","id":"c","action":"seek","pos":5000}`.
  - An unknown `type` decodes to null.
  - `fit` with 1000 long tracks leaves at most 48 000 sealed chars and keeps the head of the queue.
- [ ] **Step 2: Run** the tests and expect them to fail.
- [ ] **Step 3: Implement** with defaults on every optional field, so short messages decode.
- [ ] **Step 4: Run** the tests and expect them to pass.
- [ ] **Step 5: Commit** "Connect: messages".

### Task 3: Kotlin session state machine

**Files:**
- Create: `core/src/main/java/com/opentune/data/connect/ConnectSession.kt`
- Test: `core/src/test/java/com/opentune/data/connect/ConnectSessionTest.kt`

**Interfaces:**
- Consumes: `RoomTransport` (internal, `data/together/RoomSession.kt`), `NostrEvent.signed`, `Schnorr`.
- Produces:
  - **The player interface:**
    ```kotlin
    internal interface ConnectPlayer {
        fun snapshot(): Snapshot?            // null when nothing is loaded
        fun play(); fun pause(); fun next(); fun previous()
        fun seekTo(ms: Long); fun setVolume(v: Float)
        fun playTrack(t: CTrack); fun addToQueue(ts: List<CTrack>)
        /** Loads [queue] from [index] at [pos]; false if none of it can play here. */
        fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean
    }
    data class Snapshot(val track: CTrack?, val private: Boolean, val playing: Boolean, val pos: Long, val queue: List<CTrack>, val index: Int, val volume: Float, val shuffle: Boolean, val repeat: String)
    ```
  - **The view the UI renders:**
    ```kotlin
    data class Device(val id: String, val name: String, val form: String, val lastSeen: Long, val state: ConnectMsg.State?)
    data class ConnectView(val me: String, val relays: Int, val devices: List<Device>, val activeId: String?, val pending: String?)
    ```
  - **The session:**
    ```kotlin
    class ConnectSession(group, myId, myName, form, player: ConnectPlayer, transport: RoomTransport, view: MutableStateFlow<ConnectView>, notice: (String) -> Unit, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis)
    ```
    Its members:
    - `start()` and `stop()`;
    - `localChanged()`: the player changed, so publish the state; if another device is active and playing, send it `pause`;
    - `intercept(action: String, pos: Long = 0, value: Float = 0f): Boolean`: true when a remote device is active and this one isn't playing; then it sends a `command`;
    - `transferTo(deviceId: String)`;
    - `controlling: String?`: the id of the remote device being controlled.

**Behaviour to test** (with a fake transport that loops events between two sessions, and a manual clock):

1. Two sessions see each other through `presence`. Each one's `devices` has the other. The other disappears 75 s after its last message.
2. A plays. B's `activeId` is A, and its `devices[A].state.track` is A's track.
3. B, not playing, calls `intercept("pause")`. A's player gets `pause()`, B gets the `ack`, and `pending` clears.
4. A command to a silent device: after 5 s the notice says "{name} isn't answering", and `pending` clears.
5. `transferTo(B)` from A. B's `load(...)` is called with A's queue and position, B acks, and A's `pause()` is called.
6. A transfer where B's `load` returns false: B acks `ok=false`, A keeps playing, and the notice says "Can't play this on {name}".
7. A message with an older `seq` from the same sender is ignored. Own messages are ignored.
8. B starts playing locally while A plays: `localChanged()` on B sends A `pause`.

- [ ] **Step 1:** Write tests 1–8 against the fake transport, reusing the pattern in `RoomSessionTest.kt`.
- [ ] **Step 2:** Run them and expect them to fail.
- [ ] **Step 3: Implement.**
  - Subscribe to `transport.events`, decode each event with `group.open` and then `ConnectMsg.decode`.
  - Keep a `HashMap<String, Long>` of the highest `seq` per sender.
  - Heartbeats: `presence` every 30 s; `state` every 5 s while `snapshot()?.playing`.
  - Pending commands: `HashMap<id, Job>`, with a timeout job that clears them.
  - Clock offset per device via `ping`/`pong`, as `RoomSession` does. Use it to adjust `pos` on `load`.
- [ ] **Step 4:** Run the tests and expect them to pass.
- [ ] **Step 5: Commit** "Connect: the session".

### Task 4: Android wiring (Connect object, player, volume)

**Files:**
- Create: `core/src/main/java/com/opentune/data/connect/Connect.kt`
- Modify:
  - `core/src/main/java/com/opentune/playback/PlayerConnection.kt` (add `volume` + `setVolume`);
  - `core/src/main/java/com/opentune/data/Core.kt` (`Connect.init(app)` next to `Together.init`);
  - `app/src/main/java/com/opentune/ui/PlayerViewModel.kt` (attach; route controls).

**Interfaces:**
- Produces, on `object Connect`:
  - `init(context)`, `attach(connection: PlayerConnection)`, `detach()`;
  - `val enabled: StateFlow<Boolean>`, `setEnabled(Boolean)`;
  - `val group: StateFlow<ConnectGroup?>`, `join(group)`, `newGroup()`, `disconnectAll()`;
  - `var deviceName`;
  - `val view: StateFlow<ConnectView?>`, `val notices: SharedFlow<String>`;
  - `intercept(action, pos, value): Boolean`, `transferTo(id)`;
  - `val controlling: StateFlow<Device?>`.
- **Preferences** `"connect"`: `secret`, `device_id`, `name`, `enabled`, `account_sync`.

- [ ] **Step 1: Volume.**
  - In `PlayerConnection`, add `private val _volume = MutableStateFlow(1f); val volume = _volume.asStateFlow()`.
  - Add `fun setVolume(v: Float) { controller?.volume = v.coerceIn(0f, 1f); _volume.value = controller?.volume ?: v }`.
  - Update `_volume` in the existing `Player.Listener` with `onVolumeChanged`.
- [ ] **Step 2: `ConnectionConnectPlayer(PlayerConnection)`**, mapping `ConnectPlayer` onto `PlayerConnection`:
  - `load` → `playAll(songs, index, shuffle=false, source=null, startPositionMs=pos, fromList=false)`, then `pause()` if not playing; it returns false when the list is empty;
  - `playTrack` → `playNext` + `skipNext`;
  - `snapshot` from `currentSong` / `queue` / `currentIndex` / `isPlaying` / `currentPositionMs()` / `volume` / `shuffleEnabled` / `repeatMode`.
- [ ] **Step 3: `Connect` object.**
  - Start the session when enabled and a group exists, with `RelayPool(Together.RELAYS, filter{kinds:[21421], #t:[tag], since: now-10})`. Restart on a group change.
  - `localChanged()` is collected from the player flows (`currentSong`, `isPlaying`, `seeks`, `queue`), debounced by 300 ms.
  - The default name is `Build.MANUFACTURER + " " + Build.MODEL`, title-cased.
- [ ] **Step 4: `PlayerViewModel`.**
  - Call `Connect.attach(player)` next to `Together.attach`.
  - In `togglePlayPause`, `skipNext`, `skipPrevious` and `seekTo`, check `Connect.intercept(...)` first. Toggle sends `pause` or `play` by the remote state.
  - Call `Connect.detach()` in `onCleared`.
- [ ] **Step 5: Run** `:core:testDebugUnitTest` and `:app:compileDebugKotlin`, then **commit** "Connect: run on Android and follow the player".

### Task 5: Android UI

**Files:**
- Create:
  - `app/src/main/java/com/opentune/ui/connect/DevicesSheet.kt`;
  - `app/src/main/java/com/opentune/ui/connect/ConnectSettingsScreen.kt`;
  - `app/src/main/java/com/opentune/ui/connect/RemoteNowPlaying.kt`.
- Modify:
  - the full player's top or action row (find the cast/output button in `app/.../ui/player/PlayerScreen.kt` / `PlayerParts.kt`) to add a devices button;
  - the mini player (`app/.../ui/BottomChrome.kt` or `components/PlayerSheet.kt`, wherever the mini player's artist line is drawn) to show `RemoteNowPlaying` while `Connect.controlling != null`;
  - `SettingsLayout.kt` / `SettingsScreen.kt` to add "Devices" under the account and integrations settings;
  - the deep link `opentune://connect` in `AndroidManifest.xml` and `Links.kt`, which ask before replacing a different group.

- [ ] **Step 1: `DevicesSheet`.** "This device" first, then the others (form icon `Icons.Rounded.Smartphone` / `Computer`, name, the active one's track). A tap calls `Connect.transferTo(id)`. The footer reads "Add a device". The empty state reads "Open OpenTune on your computer and add it to see it here".
- [ ] **Step 2: `ConnectSettingsScreen`.**
  - An on/off switch.
  - A name field.
  - An "Add a device" QR code, using the existing QR helper from `ui/together/InviteSheet.kt`, with a copy-link button.
  - The switch "Sync through my YouTube account" (Task 7).
  - The device list with last seen.
  - "Disconnect all", with a confirmation dialog.
- [ ] **Step 3: `RemoteNowPlaying`.** The mini player keeps the cover and title. The artist line becomes "Playing on {name}", and play/pause and next act through `Connect.intercept`. The full player gets a bar "Controlling {name}" above the controls. Position is the remote `pos + (now - at)` while playing.
- [ ] **Step 4:** Compile, install OpenTune Dev and check the sheet and the settings by hand. **Commit** "Connect: devices sheet, settings and remote now playing".

### Task 6: Web port (protocol, store, player hooks, UI)

**Files:**
- Create:
  - `web/app/src/connect/group.ts`, `messages.ts`, `session.ts`, `connect.ts`, `connect.test.ts`;
  - `web/app/src/connect/DevicesSheet.tsx`;
  - `web/app/src/pages/ConnectSettingsPage.tsx`.
- Modify:
  - `web/app/src/together/crypto.ts`: export `gcmSeal`, `gcmOpen`, `toBase64`, `fromBase64`, `aesKey`;
  - `web/app/src/state/player.ts`: let a second hook set intercept controls. Add `setConnectHooks({ intercept, onIntent })`, called before the party hooks in `next`, `previous`, `togglePlay` and `seek`, and add `onIntent`;
  - `web/app/src/pageRegistry.tsx`: `/settings/devices`;
  - the player bar (`shell/PlayerBar.tsx`) and the full player (`shell/FullScreenPlayer.tsx`): a devices button, and a "Playing on" state.
  - the app start (`shell/AppShell.tsx`): handle `#/settings/devices?join=` in the location hash.

- [ ] **Step 1: Failing vitest tests.**
  - The shared vectors: the tag and opening `sealed` from `core/src/test/resources/connect/vectors.json`, copied to `web/app/src/connect/vectors.json`.
  - HKDF against RFC 5869 case 3.
  - Decoding the same pinned JSON as Task 2.
  - Session scenarios 1–8 from Task 3 with a bus transport.
- [ ] **Step 2: Implement**
  - `group.ts`: WebCrypto `HKDF` with `deriveBits`, `salt: new Uint8Array()`;
  - `messages.ts`: types plus `encode`/`decode`, with the same field names as Kotlin;
  - `session.ts`: a port of Task 3;
  - `connect.ts`: a zustand store `useConnect` with `view`, `controlling`, `notice`, `enabled` and `name`. It keeps `localStorage` `opentune:connect` (secret, id, name, enabled, accountSync). Its `webPlayer` adapter is built the way `together/room.ts` builds one.
- [ ] **Step 3: UI.** The devices sheet and the settings page mirror Task 5. QR uses the same QR library the web Together invite uses. Check with `grep -rn qrcode web/app/src`.
- [ ] **Step 4: Run** `pnpm test`, `pnpm typecheck` and `pnpm --filter @opentune/app build` in `web/`. **Commit** "Connect: the web player".

### Task 7: Group secret through the YouTube account

**Files:**
- Create:
  - `core/src/main/java/com/opentune/data/connect/ConnectAccountSync.kt`;
  - `web/app/src/connect/accountSync.ts`.
- Test: `ConnectAccountSyncTest.kt`, parsing the description and choosing the playlist from a fixture library response.

**Interfaces:**
- `suspend fun read(): ConnectGroup?`: look through the signed-in library playlists (`FEmusic_liked_playlists`) for a title equal to "OpenTune Connect", browse it, and parse its description for `opentune-connect:v1:<43 chars>`.
- `suspend fun write(group)`: create the playlist if missing with `playlist/create` `{title:"OpenTune Connect", description, privacyStatus:"PRIVATE"}`. Otherwise use `browse/edit_playlist` with `ACTION_SET_PLAYLIST_DESCRIPTION`.
- **On start** with `account_sync` on and signed in: `read()`. A secret that differs replaces the local one. With none there, `write(current)`.
- **On the web**, calls go through `getSignedInnertube()`, as the library pages do.

- [ ] **Step 1:** Failing parser tests.
- [ ] **Step 2:** Implement it on Android using the existing `Innertube` helpers (`createPlaylist`, `editPlaylist` and the library browse; check the names in `core/.../data/innertube/Innertube.kt`).
- [ ] **Step 3:** Implement it on the web.
- [ ] **Step 4:** Run the tests and **commit** "Connect: keep the group in a private YouTube playlist".

### Task 8: Translations, build, end-to-end

- [ ] Extract the new strings with `scripts/i18n/extract.py missing de` (find the Connect ones). Translate them into the 26 languages. Merge with the UTF-8, LF and sorted rules used before. Translate the web strings the way the web's i18n does (check `web/app/src/i18n`).
- [ ] Run `:core:testDebugUnitTest` and `:app:compileDebugKotlin`, then build and install **OpenTune Dev** (`com.opentune.devtest`) on the phone.
- [ ] Run the web (`pnpm --filter @opentune/app dev`) in the browser pane, with the extension optional for non-YouTube checks.
- [ ] Manual checklist from the spec:
  - pair by QR;
  - see the web's song on the phone;
  - pause/seek/next from the phone;
  - move to the phone and back;
  - close the tab (the device is gone within 75 s);
  - account sync.
- [ ] **Commit** "Connect: translations".
