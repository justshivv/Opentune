# Desktop upgrade plan

Status: approved. Phases 1 and 2 are done (see "as built" under each). The decisions in "Conflicts that need a decision" are still open.

Estimates are in working days for one engineer who knows the codebase, and include tests. They are rough, and the native work (Phases 2 and 3) carries the most risk.

## What is there today

### Modules and build

- Gradle modules: `:app` (Android phone), `:core` (Android library), `:wear`, `:tv`, `:desktop`. The repo also holds `web/` (web player and browser extension), `site/`, `services/lossless-addon` and `usage-api/` (Cloudflare Workers), `ios/` and `third_party/`.
- Plugins: AGP 8.10.1, Kotlin 2.4.10, Compose Multiplatform 1.12.2.
- There is no Kotlin Multiplatform module. `:desktop` is a plain Kotlin/JVM module that compiles chosen files from `:core` and `:app` straight from their source folders. Two lists in `desktop/build.gradle.kts` name them: `sharedFromCore` (models, HTTP, InnerTube, settings, lyrics, Subsonic, account pairing, and more) and `sharedFromApp` (some UI). Android APIs those files touch are covered by hand-written stand-ins in `desktop/src/shim/java` (for example `android.content.Context` and a `SharedPreferences` backed by `.properties` files) and `desktop/src/shim/kotlin` (Downloads, LocalMusic, Podcasts, Together, StreamResolver and others, mostly empty or reduced).
- This works, but it is fragile. A shared file that gains an Android import breaks the desktop build. Nothing stops a desktop-only change from drifting from the phone's copy of a shim.
- Desktop sources: 52 files and about 19,500 lines in `desktop/src/main/kotlin/com/opentune/desktop`, plus 16 test files.

### Desktop features that already exist

| Area | Where | Notes |
|---|---|---|
| Window | `DesktopWindow.kt`, `WindowsFrame.kt`, `Chrome.kt` | Own title bar. Mica and Acrylic through DWM by JNA. A choice of renderer (OpenGL by default on Windows, Direct3D, software) that falls back after a start that didn't last. |
| Playback | `Player.kt`, `FfmpegEngine.kt`, `FlacEngine.kt`, `Streams.kt`, `Relay.kt` | Three engines: JavaFX `MediaPlayer`, an FFmpeg engine (JavaCPP FFmpeg 7.1.1), and a FLAC engine (jflac). The last two write to Java Sound `SourceDataLine`. `Relay.kt` serves streams to JavaFX from a loopback-only `HttpServer`. |
| Audio devices | `AudioDevices.kt` | Picks a Java Sound mixer. No exclusive mode. |
| EQ | `FfmpegEngine.filterChain`, JavaFX `EqualizerBand` | Peaking bands, balance and tempo through an FFmpeg filter graph. Uses the phone's `EqualizerSettings`. |
| Gapless, crossfade, automix | none | The phone has crossfade and Automix (in `:core` PlaybackService). The desktop has neither. |
| Tray | `Tray.kt` | AWT tray with play controls. |
| Shortcuts | `Shortcuts.kt` | In-window keys only. No global media keys. |
| Discord | `DiscordPresence.kt` | Local IPC to the Discord app with OpenTune's own application id. No token. This already matches the rules below. |
| Last.fm | `LastFmDesktop.kt` | Desktop auth flow, session key in `~/.opentune/lastfm.json` with owner-only permissions where the OS supports POSIX permissions. Plaintext. |
| YouTube account | `Account.kt`, shared `AccountStore`, `SessionPairing` | Signed in by scanning a QR code with the phone. The cookie lands in the desktop's `SharedPreferences` stand-in, a `.properties` file. Plaintext. |
| Library | `Library.kt` (SQLite via sqlite-jdbc), `AccountLibrary.kt`, `Playlists.kt` | Local likes, history and playlists, and the YouTube Music library. |
| Lyrics | shared `LyricsRepository`, `LyricsPane.kt` | BetterLyrics, KuGou, LyricsPlus and others from `:core`. No LRCLIB. TTML is read with regular expressions, not an XML parser, so XXE cannot happen today, but the parsing is loose. |
| Sources | `Addons.kt`, `JioSaavn.kt`, `SourcesPage.kt` | HTTP audio add-ons by manifest URL, a published list from `usage-config.json`, FLAC header check. Add-ons are data-only (JSON over HTTPS), not code. |
| Listen Together | `ListenTogether.kt`, `RoomPlayer.kt`, shim `Together.kt` | Rooms over Nostr relays, same protocol as the phone. |
| Diagnostics | `Main.kt` `CrashLog` | `~/.opentune/crash.log`, `desktop.log` with the previous run kept, a stall watchdog, `hs_err` file next to the app. |
| Packaging | `desktop/build.gradle.kts`, `.github/workflows/ci.yml`, `desktop-release.yml` | jpackage MSI, EXE, DMG, DEB, RPM, plus an AppImage and a Windows portable zip. Unsigned. No checksums published. |

### Missing on desktop

Downloads and offline files, local music folders, SMTC on Windows, MPRIS on Linux, macOS Now Playing, global media keys, single instance, `opentune://` deep links, an update check (Settings only links to the release page), gapless, crossfade, Automix, scrobbling to ListenBrainz, WebDAV backup, listening stats, an SLF4J backend (the log shows the NOP warning), and a secret store.

### JavaScript engines

`desktop/src/shim/java/org/schabi/newpipe/extractor/utils/JavaScript.java` runs NewPipe's player-script deobfuscation. GraalJS is built with `allowAllAccess(false)` and Rhino with `initSafeStandardObjects()`. Neither sets a time or memory limit, and the script itself comes from YouTube, not from add-ons. No add-on code runs anywhere on desktop today.

## Conflicts that need a decision

The upgrade's rules forbid some things the repo already does, mostly in the phone app, web player and services. The desktop itself is clean on most of them. I won't change any of these without your answer, because several are features you asked for earlier.

1. **Spotify `sp_dc` with TOTP minting.** `core/src/main/java/com/opentune/data/spotify/SpotifyWebPlayer.kt` and `web/extension/lib/spotify.ts` read the `sp_dc` cookie and mint web-player tokens with a TOTP secret table. The rules name this exact method. The desktop does not compile it. Options: remove it, replace it with Spotify's official Web API and PKCE sign-in (which gives playlists and library, but no Canvas), or keep it off the desktop only.
2. **Discord user-token gateway.** `core/.../discord/DiscordRpc.kt` and `DiscordLogin.kt` (phone), and `web/extension/lib/discord.ts`, connect to `gateway.discord.gg` with the account's own token. The phone-to-web sign-in sharing also passes that token. The rules forbid this. The desktop already uses IPC only. The phone has no Discord IPC to fall back to, so dropping the gateway removes Discord status on Android and the web player.
3. **Monochrome add-on.** `services/lossless-addon` adapts `tracks.monochrome.st`, Monochrome's Track Streamer. Monochrome is a client for TIDAL's catalogue (see `docs/lossless-source-research.md`), and I can't confirm where the Track Streamer's files come from. If they come from TIDAL, it falls under the rule against "unofficial proxies for paid lossless catalogs". The Internet Archive and Musopen adapters are fine. Options: drop the Monochrome manifest from the published list and the desktop defaults, or leave it as a URL a user types in themselves (still a conflict, but not one we hand out).
4. **Usage counting is on by default.** `core/src/main/java/com/opentune/data/Usage.kt` sends an anonymous random install ID, gated by `countUsage`, which defaults to `true`. The rules want opt-in only. The desktop doesn't send usage at all yet. Turning the default off will make the README users badge drop, since new installs won't be counted until they agree.
5. **Plaintext secrets.** Last.fm session key, YouTube cookie, and the Discord token on the phone side are stored as plaintext files on desktop. Phase 1 moves them into the OS keystore. When no keystore is reachable (for example a Linux box without a Secret Service daemon), the rules say to ask the user. I plan a one-time prompt: "Keep signed in without a keyring (stored unencrypted in your home folder)" or "Sign in each time". Say if you want a different choice.

Not conflicts, listed so you know I checked: no browser cookie-store decryption anywhere; no hard-coded API secrets in desktop code (the Discord application id is public and comes from CI); add-ons are JSON, not code.

## Phased plan

Each phase is one PR into `fix/crossfade-automix` unless you want them against `main`. Each PR keeps the app building and the existing CI jobs green.

### Phase 1: Foundation and packaging (8 to 11 days)

- Start a `:shared` KMP module (targets `jvm` and `android`) and move pure Kotlin files out of the `sharedFromCore` list into it in small batches, starting with models, settings data classes and lyrics parsing. The source-list include stays for files not yet moved. A full move is out of scope here; each later phase moves the files it touches. (4 days for the module and first batch)
- `SecretStore` interface with three backends: Windows Credential Manager through JNA (`CredWriteW`/`CredReadW`, which DPAPI-protects per user), macOS Keychain through the `security` framework by JNA, Linux Secret Service over D-Bus (`org.freedesktop.secrets`). Move Last.fm, the YouTube cookie and any future token into it with a one-time migration that deletes the old file. Add the no-keyring prompt from point 5. (3 days)
- Logging: add `slf4j-simple` (or Logback) writing to `desktop.log`, so library logs stop going to NOP. (0.5 day)
- Update check: read the latest GitHub release, compare versions, show a banner with a link. No silent download or self-install in this phase. (1 day)
- Packaging: SHA-256 checksum file for every artifact in `desktop-release.yml`. Code signing steps for Windows (signtool with a certificate from secrets) and macOS (codesign and notarytool) that run only when the secrets exist, so forks still build. Linux packages get a detached GPG signature if a key is set. (2 days, more if certificates need buying)
- Opt-in telemetry on desktop, matching whatever you decide for point 4. (0.5 day)
- Write `docs/architecture-desktop.md` and `docs/security.md`, update `THIRD_PARTY_NOTICES.md`.

#### Phase 1 as built

- `:shared` exists (Kotlin Multiplatform, `jvm` target) with the models, lyrics parsing, album matching and room crypto. `:core` passes it on with `api`; the desktop depends on it directly. See [architecture-desktop.md](architecture-desktop.md).
- `SecretVault` seals the YouTube Music cookies, the Subsonic token, the Last.fm session and the add-on device key with AES-256-GCM, the key kept by DPAPI, the macOS Keychain or the Secret Service. Without a keyring the app asks, as in point 5. Tested against a real GNOME Keyring on Linux. The Windows and macOS backends are untested until someone runs a build on those systems.
- SLF4J's simple logger writes library logs into `desktop.log`.
- An update check reads GitHub's latest release twice a day and shows a banner. It can be turned off.
- The desktop install count is off unless turned on. It sends a random ID and the version, marked `-desktop`. The phone's default is unchanged until point 4 is decided.
- Release files get a `.sha256` each and one `SHA256SUMS` list. Windows, macOS and Linux signing steps run when their secrets are set. None are set yet.
- [security.md](security.md) and the desktop libraries in `THIRD_PARTY_NOTICES.md`.

### Phase 2: Audio engine (10 to 14 days, highest risk)

- One output path. Retire the JavaFX `MediaPlayer` engine, so every format goes through FFmpeg decode into a single sink. This drops JavaFX and its native libraries from the installers. (3 days)
- Native output sink behind an interface: WASAPI shared and exclusive on Windows, CoreAudio on macOS, PulseAudio or PipeWire on Linux. My suggestion is to bundle miniaudio (public domain, one C file) through a small JNI or JNA binding built in CI for each OS, instead of hand-writing three COM and C APIs. Java Sound stays as the fallback. (5 to 7 days)
- Gapless: decode the next track ahead and hand frames to the sink without a gap, using the encoder delay and padding FFmpeg reports. (2 days)
- Crossfade on the same pipeline, reusing the phone's settings. (1 day)
- EQ: a 10-band parametric EQ and preamp in the decode path (biquads in Kotlin, or FFmpeg `equalizer` filters as today), with presets shared with the phone. ReplayGain or loudness normalisation from YouTube's `loudnessDb`. (2 days)

#### Phase 2 as built

- otaudio on miniaudio 0.11.26, built per system by Gradle and loaded through JNA. Java Sound is the fallback. Checked here: built and run on Linux (ALSA, and the silent backend in tests), cross-compiled for Windows with MinGW (depends on KERNEL32 and msvcrt only). macOS is built by CI only.
- JavaFX is gone. Everything plays through FFmpeg onto one bus at the device's rate: YouTube Opus with AAC as the fallback, JioSaavn, a Subsonic server's own file (no longer transcoded to MP3), and an add-on's FLAC.
- Gapless and crossfade between any two songs, using the phone's crossfade setting. Skipping to the song already made ready starts it at once.
- EQ: a real preamp, bass and treble as shelves, and the phone's parametric headphone filters (`HeadphoneEq`), all applied before boosts are taken back off. The desktop has a preamp slider. There's no headphone-curve picker yet; a curve set elsewhere applies.
- Loudness levelling from YouTube's own figure, with Quiet, Normal and Loud levels.
- Bit-perfect lossless as an opt-in, exclusive on WASAPI and ALSA. Core Audio and PulseAudio have no exclusive mode in miniaudio, so there it's the file's own format, shared.
- Not done: WASAPI exclusive for the whole bus, since it would block other apps' sound and is only worth it for lossless, which bit-perfect covers. Also not done: ReplayGain tags from local files, which depends on the Phase 4 local library.

### Phase 3: Native integration (7 to 9 days)

- Windows SMTC through `SystemMediaTransportControls` over WinRT. That needs either a small C++/WinRT helper DLL or a JNA COM binding; I'd build the helper DLL in CI. Covers media keys, the Windows media flyout and lock screen. (3 days)
- Linux MPRIS over D-Bus (same D-Bus library as the Secret Service backend). (1.5 days)
- macOS `MPNowPlayingInfoCenter` and remote commands through a small Objective-C helper. (1.5 days)
- Single instance: a lock file plus a loopback socket in `~/.opentune`. A second launch passes its arguments to the first and exits. (0.5 day)
- `opentune://` deep links: registered by the MSI/EXE installer, the DMG's `Info.plist`, and a `.desktop` file for DEB/RPM/AppImage. Links are parsed against a fixed list of routes (song, album, artist, playlist, Listen Together room). (1.5 days)
- Tray: keep the AWT tray, add the current song and a quit item. (0.5 day)

### Phase 4: Content and sources (10 to 13 days)

- InnerTube hardening: retries with back-off, client version refresh from the remote config, clear errors when a stream needs sign-in, and tests against saved responses. Mostly in shared code, so the phone gains too. (3 days)
- Source registry: one place that lists YouTube, add-ons, JioSaavn, Subsonic and local files with order and on/off, replacing the separate lists now in `Addons.kt` and `SourcesPage.kt`. (2 days)
- Track matcher: one scored matcher (title, artists, duration, album, ISRC when known) used by add-ons and lossless lookups, built from the existing `matches` and `AlbumMatch` code. (2 days)
- Local library: watch folders, read tags (jaudiotagger or FFmpeg metadata), store in the existing SQLite library, play through the Phase 2 engine. (3 days)
- Downloads: save the stream as it comes, a queue with progress, a folder setting, and offline play. Only sources whose terms allow saving: YouTube with a signed-in account follows the same rules as the phone app today; add-on files only where the manifest says downloads are allowed. (3 days)

Plug-in modules with code (signed, sandboxed JS) are not in this plan. Add-ons stay data-only. If you want code plug-ins later, they get their own phase with Ed25519-signed modules, a pinned key, per-module opt-in, a locked GraalJS context with time and memory limits, and an HTTP allowlist that blocks localhost, private ranges and `file:`.

### Phase 5: Lyrics (3 to 4 days)

- LRCLIB as a source (public API, no key), placed by the user in the lyrics source order. (1 day)
- Replace the regex TTML reading in `BetterLyrics.kt` and others with a real parser that has DTDs, external entities and XInclude turned off, plus size and depth limits. Tests with hostile input (entity expansion, external entity, deep nesting). Shared with the phone. (2 days)
- Word-timed lyrics on the desktop pane where the source gives them. (1 day)

### Phase 6: Automix with ONNX (8 to 12 days)

- Port the phone's Automix planning (ending and opening analysis, the blend plan) onto the Phase 2 pipeline. Most of it is in shared code already. (3 days)
- ONNX Runtime for Java (MIT) to run a small model for beat and downbeat tracking or vocal activity, to place mix points better than the current heuristics. The model has to be one with a licence that allows shipping it, bundled in the installer and checked by SHA-256 at load; nothing is downloaded at run time without a pinned hash. I don't have a model picked yet; finding one with a usable licence is part of this phase, and if none fits, the phase ships the heuristic port only. (5 to 9 days)

### Phase 7: Social (7 to 9 days)

- Discord: keep IPC only. Add buttons and the album art the phone shows. (0.5 day)
- Last.fm scrobbling on desktop using the existing session (now in the keystore), with an offline queue. (1.5 days)
- ListenBrainz scrobbling with a user token from their settings page, stored in the keystore. (1.5 days)
- Listen Together: parity with the phone's current room features and tests for clock offset and rejoin. (2 days)
- WebDAV backup and restore of the library, playlists and settings (never secrets), with a server URL the user enters, HTTPS required. (2 days)

### Phase 8: UI polish and stats (5 to 7 days)

- Listening stats page from the local history: top songs, artists and albums by week, month and year, listening time. (2 days)
- Fonts: switch to an open font set (Inter, or similar under OFL) bundled with the app; nothing from Apple's SF family. (0.5 day)
- Keyboard navigation and screen reader labels across the main pages. (2 days)
- A pass over spacing, empty states and loading states on pages added in Phases 4 to 7. (1.5 days)

### Totals

About 58 to 79 working days for all eight phases. Phases 2 and 6 are the ones most likely to run long.

## What I need from you before Phase 1

1. A decision on each of the five conflicts above.
2. Whether the PRs should target `fix/crossfade-automix` or `main`.
3. Whether you have, or want to buy, Windows and Apple signing certificates. Without them Phase 1 adds the signing steps but they stay off.
4. Whether miniaudio through a small native binding is acceptable for Phase 2, or whether you'd rather stay pure JVM (Java Sound), which rules out WASAPI exclusive mode.
