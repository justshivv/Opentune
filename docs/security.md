# Security

What OpenTune does to protect the people who use it, and the rules new code follows. This covers the desktop app in detail; the Android apps follow the same rules where they apply.

## Rules for new code

- Secrets are never stored in plain text without the listener agreeing to it. On the desktop they go through `SecretVault`.
- No secrets in the source. Application ids and keys come from CI secrets or variables at build time, or from the listener's own settings.
- Nothing fetched from the network is run as code without a pinned signature or hash.
- No automation of user accounts against a platform's terms: no self-bots, no Discord user-token gateway connections, no scraping of private web-player tokens, no reading of browser cookie stores.
- No sources that get around DRM or paywalls.
- Telemetry is opt-in, and sends an anonymous installation ID and the version only.
- Code from decompiled binaries is never copied in.

[desktop-upgrade-plan.md](desktop-upgrade-plan.md) lists the places where the phone app and the web player don't follow these rules yet, waiting on a decision.

## Sign-ins at rest on the desktop

`SecretVault` (`desktop/src/main/kotlin/com/opentune/desktop/SecretVault.kt`) keeps the files that hold sign-ins:

- the YouTube Music cookies (`prefs/account.properties`)
- the Subsonic token and salt (`prefs/subsonic.properties`)
- the Last.fm session key (`lastfm.json`)
- the add-on device key (`addon-device.json`)

Each is sealed with AES-256-GCM under a random 256-bit key, with a fresh 12-byte nonce on every write. The file's name is bound in as associated data, so one sealed file can't be swapped for another. The file is created readable and writable by its owner only before anything is written to it.

The key is kept by the system ([KeyBackends.kt](../desktop/src/main/kotlin/com/opentune/desktop/KeyBackends.kt)):

| System | Where the key is |
|---|---|
| Windows | encrypted with DPAPI (`CryptProtectData`) for the Windows user, in `~/.opentune/vault.key` |
| macOS | the login Keychain, as a generic password under the service "OpenTune Desktop" |
| Linux | the Secret Service (GNOME Keyring, KWallet and others) through libsecret, labelled "OpenTune Desktop" |

A file left in plain text by an older version is read once and sealed in its place.

If the keyring doesn't answer within 60 seconds at start-up (none installed, or a locked one nobody unlocks), the app writes no sign-in to disk and asks:

- **Store unencrypted**: the files are kept in plain text, readable by the user only. This is the same as versions before the vault.
- **Keep until I quit**: sign-ins are held in memory, and the files are deleted. The listener signs in again after each start.

On macOS an unsigned build looks like a new app to the Keychain after each update, so it may ask once whether OpenTune can use its key.

The answer is kept in `~/.opentune/vault-choice` and can be changed in Settings → About → Saved sign-ins. Until it's given, sign-ins are held in memory.

What this protects against: other users on the computer, backups and copies of the home folder, and malware that reads files without running as the user. It does not protect against code running as the same user, which can ask the keyring for the key the same way the app does.

## Network

- **Update check** (Settings → About → Check for updates, on by default): a `HEAD` request to `https://github.com/justshivv/Opentune/releases/latest` at most twice a day, to read the version from the redirect. Nothing is downloaded or installed; a banner links to the release page.
- **Install count** (Settings → About → Count this install, off by default): once a day, a random UUID made on the computer, the version with `-desktop` added, and a play count of zero, to the `apiBase` from `https://justshivv.github.io/Opentune/usage-config.json`. That address must be HTTPS or nothing is sent. Turning it off stops reports. The ID is kept, so turning it back on doesn't count a second install.
- **Discord**: the local IPC socket of the Discord app, with OpenTune's own application id. No token.
- **Local servers**: the stream relay (`Relay.kt`) listens on the loopback address only.

## One instance and links

- A second start talks to the first over the loopback address. The port and a random 256-bit token are in `~/.opentune/instance.json`, created readable by its owner only. A message without the token is dropped, and the token is compared in constant time. At most eight arguments of 512 characters are taken, and each must parse as an opentune:// link, or it's ignored.
- opentune:// links open only fixed kinds of page, each id matched against a strict pattern (`DeepLinks.parse`). Nothing in a link is run, fetched as code, or turned into a file path. A song link plays the song, a room link joins the room, and the rest open a page.
- Registering the scheme writes only per-user places: `HKCU\Software\Classes\opentune` on Windows, `~/.local/share/applications/opentune-links.desktop` on Linux. It's done only when running from an install, so a build run from source changes nothing.
- MPRIS is on the session bus, which only this user's processes can reach. Of its settable properties, only Volume is taken.

## JavaScript

YouTube's player script is run to work out stream addresses (`desktop/src/shim/java/org/schabi/newpipe/extractor/utils/JavaScript.java`). GraalJS runs it with `allowAllAccess(false)`, so it gets no Java classes, files, threads or network. Rhino runs it with `initSafeStandardObjects()`. No add-on code runs. Add-ons are JSON manifests over HTTPS.

## Releases

Every desktop file attached to a release has a `.sha256` beside it, and the release carries `OpenTune-<tag>-desktop-SHA256SUMS.txt` with them all. To check a download:

```sh
sha256sum -c OpenTune-v1.2.0-linux-x86_64.AppImage.sha256      # Linux
shasum -a 256 -c OpenTune-v1.2.0-macos-arm64.dmg.sha256          # macOS
Get-FileHash .\OpenTune-v1.2.0-windows-x64-setup.exe -Algorithm SHA256   # Windows, compare by eye
```

Signing runs in `.github/workflows/desktop-release.yml` when its repository secrets are set, and is skipped otherwise:

| System | Secrets | What's signed |
|---|---|---|
| Windows | `WINDOWS_CERT_PFX_BASE64`, `WINDOWS_CERT_PASSWORD` | `OpenTune.exe` in the portable zip, the MSI and the setup EXE, with signtool, SHA-256 and a DigiCert timestamp. The launcher inside the MSI is not yet signed, because jpackage builds the MSI before the signing step. |
| macOS | `MACOS_CERT_P12_BASE64`, `MACOS_CERT_PASSWORD`, `MACOS_SIGN_IDENTITY`, and for notarization `APPLE_ID`, `APPLE_APP_PASSWORD`, `APPLE_TEAM_ID` | The app and DMG, by Compose's signing with the Developer ID certificate. Notarized with `notarytool` and stapled when the Apple account secrets are set. |
| Linux | `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` | A detached ASCII signature (`.asc`) beside each DEB, RPM and AppImage. |

The checksums are written after signing, so they match what people download.

## Reporting a problem

Open a private security advisory on the GitHub repository rather than a public issue.
