# Desktop architecture

How the desktop app (`:desktop`) is put together and how it shares code with the phone, TV and watch apps. The upgrade plan is in [desktop-upgrade-plan.md](desktop-upgrade-plan.md); security is in [security.md](security.md).

## Modules

| Module | Plugin | What it holds |
|---|---|---|
| `:shared` | Kotlin Multiplatform, `jvm` target | Code every app runs unchanged: song and album models (`data/model`), lyrics parsing (`Lyrics.kt`, `SongParts.kt`), album matching (`AlbumMatch.kt`), and the Listen Together room crypto (`RoomCode.kt`, `Schnorr.kt`). No Android, no UI. |
| `:core` | Android library | Everything the Android apps share: YouTube Music, the other services, settings, downloads, the player service. Depends on `:shared` with `api`, so the apps see its types. |
| `:app`, `:tv`, `:wear` | Android application | The phone, TV and watch apps, on top of `:core`. |
| `:desktop` | Kotlin/JVM with Compose Multiplatform | The desktop app. Depends on `:shared` directly. |

### Moving code into `:shared`

`:desktop` still compiles some of `:core`'s files from their source folder. The `sharedFromCore` and `sharedFromApp` lists in `desktop/build.gradle.kts` name them. Android classes they touch are covered by stand-ins under `desktop/src/shim/`.

Each phase of the upgrade moves the files it works on out of those lists and into `:shared`, until the lists are empty. To move a file:

1. `git mv` it from `core/src/main/java/...` to `shared/src/jvmMain/kotlin/...`, keeping the package.
2. Take it out of `sharedFromCore`.
3. Make public anything other modules used that was `internal`. `internal` now means `:shared` only.
4. Build `:core`, `:app`, `:tv`, `:wear` and `:desktop`. A property from another module can't be smart-cast, so a few `x != null && x.foo()` checks in `:core` become `x?.foo() == true` or a local `val`.

The files are in `jvmMain`, not `commonMain`, because they still use `java.util.Locale`, `java.security` and `javax.crypto`. They move to `commonMain` once those uses are replaced. That only matters if a non-JVM target (iOS, the web) starts using them.

## Desktop app layout

All under `desktop/src/main/kotlin/com/opentune/desktop/`.

| Area | Files |
|---|---|
| Start-up | `Main.kt`: logging, crash log, vault, renderer choice, shared services, then the window. |
| Window | `DesktopWindow.kt`, `WindowsFrame.kt`, `Chrome.kt`, `Shell.kt` |
| Pages | `DesktopApp.kt` (the root), `Pages.kt`, `PageStack.kt`, `PageKit.kt`, `HomeLibrary.kt`, `ArtistView.kt`, `Playlists.kt`, `SettingsPage.kt` |
| Playback | `Player.kt`, `AudioBus.kt`, `FfmpegEngine.kt`, `FlacEngine.kt`, `NativeAudio.kt`, `Loudness.kt`, `Streams.kt`, `Relay.kt`, `AudioDevices.kt`, and `desktop/native/otaudio.c` |
| Sign-ins and secrets | `SecretVault.kt`, `KeyBackends.kt`, `VaultUi.kt`, `Account.kt`, `LastFmDesktop.kt` |
| Services | `DesktopServices.kt` (logging setup, update check, opt-in install count), `DiscordPresence.kt`, `Addons.kt`, `ListenTogether.kt` |
| Diagnostics | `CrashLog` in `Main.kt` |

### Playback

```
source ─► FfmpegEngine (decode, resample to the device rate, EQ/speed filters) ─► Voice ─┐
source ─► FfmpegEngine ─► Voice (queued: the next song) ────────────────────────────────┤
                                                                                         ▼
                               AudioBus mixer thread: sum voices in float, crossfade ramps,
                               loudness gain, master volume, soft limit ─► BusDevice
                                                                             │
                     NativeBusDevice (otaudio ► miniaudio ► WASAPI / Core Audio / PulseAudio / ALSA)
                     or JavaSoundBusDevice where otaudio won't load

add-on FLAC with "Bit-perfect lossless" on ─► FlacEngine (jflac) ─► NativePcmOut, the file's own format,
                                                                    exclusive where the system allows
```

- **otaudio** (`desktop/native/otaudio.c`) is a small C library on miniaudio (`third_party/miniaudio`): a playback device with a ring buffer in front of it. Kotlin writes into the ring through JNA and never runs on the audio thread. The `nativeAudio` Gradle task builds it with the system's C compiler (cc, or MinGW's gcc on Windows) into the resources under JNA's platform folder. Without a compiler a local build still works and plays through Java Sound; on CI a missing compiler fails the build.
- **The bus** opens the device once, at the device's own rate, shared. Every song is decoded by FFmpeg into a voice at that rate, so any song can follow any other.
- **Gapless**: about 30 seconds before the end, `Player.tick` decodes the next song into a queued voice and chains it. The mixer starts it on the sample after the last one of the song before, inside the same 10 ms chunk.
- **Crossfade** (Settings → Playback): the fade is scheduled on the frame where it should begin, and the next song shows in the app when it starts coming in. Equal-power curves.
- **Loudness**: YouTube's `loudnessDb` from the player response, applied as the voice's gain, with the phone's formula and levels. A soft limiter above 0.95 keeps boosts from clipping. Below it, samples pass unchanged.
- **Bit-perfect lossless** (off by default): an add-on's FLAC skips the bus. The bus gives the device up, and the FLAC opens it at the file's rate and depth: exclusive on WASAPI and ALSA, shared elsewhere. No EQ, crossfade or levelling applies to it, and it isn't made ready ahead, so there's a short gap into and out of it.
- **Tests** run on miniaudio's silent backend (`-Dopentune.audio=null`), which plays in real time to nothing. `CaptureDevice` stands in for the bus's device where a test needs to look at the samples.

### What starts, in order

1. `Logging.install()`: SLF4J's simple logger writes to standard error.
2. `CrashLog`: uncaught exceptions go to `crash.log`. Standard output and error are copied to `desktop.log`, with the previous run kept as `desktop.log.1`. A watchdog logs the window thread when it stalls.
3. `WindowsFrame.chooseRenderer()`: on Windows, the renderer for this start.
4. `SecretVault.start()`: finds or makes the key the sign-ins are sealed with. See [security.md](security.md).
5. Settings, Subsonic, the remote configuration, the YouTube Music account, the account library.
6. `Updates.start()` and `DesktopUsage.start()`.
7. Listen Together, lyrics translation, Discord, then the window.

### Files on disk

Everything is under `~/.opentune/`:

| File | What | Sealed by the vault |
|---|---|---|
| `prefs/account.properties` | YouTube Music cookies and profiles | yes |
| `prefs/subsonic.properties` | Subsonic server, user, token and salt | yes |
| `lastfm.json` | Last.fm session and the queue of scrobbles | yes |
| `addon-device.json` | the add-on device key and its session | yes |
| `vault.key` | Windows only: the vault key, encrypted with DPAPI | n/a |
| `vault-choice` | where there's no keyring: the listener's choice | no, holds no secret |
| `prefs/*.properties` (others), `window.json`, `discord.json`, `sources.json` | settings | no |
| `library.db` | the local library (SQLite) | no |
| `desktop.log`, `desktop.log.1`, `crash.log` | logs | no |

## Packaging

`compose.desktop.application.nativeDistributions` in `desktop/build.gradle.kts` makes the MSI, EXE, DMG, DEB and RPM with jpackage. CI wraps the app image into an AppImage (`desktop/packaging/appimage.sh`) and a Windows portable zip.

`.github/workflows/desktop-release.yml` builds them for a release, names them for its tag, signs them where the signing secrets are set, writes a `.sha256` beside each file, and attaches them all to the release. A last job collects the checksums into `OpenTune-<tag>-desktop-SHA256SUMS.txt`. The signing secrets are listed in [security.md](security.md).
