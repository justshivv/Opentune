# App configuration dashboard

This extends the VPS service on `claude/wizardly-sagan-8ge2ek`. It does not replace the app or its playback engine. A client release containing this integration is required once; subsequent supported content changes can be published without another APK or web build.

## Install and open

1. Build the archive with `bash services/lossless-addon/vps/pack.sh /tmp`.
2. Copy and unpack `opentune-addon-vps.tar.gz` on your VPS. Run its `sudo bash install.sh`. The installer preserves existing settings and databases. This step deploys server code; it is not performed by merely merging this change.
3. Edit `/etc/opentune-addon.env` using `sudoedit`. Set `CONTROL_ADMIN_USERNAME` (at least 3 characters) and a unique `CONTROL_ADMIN_PASSWORD` (at least 16 characters). Quote values containing spaces using systemd EnvironmentFile syntax. Never commit this file or put its credentials in the app.
4. Run `sudo systemctl restart opentune-addon`.
5. Open **https://addon-opentune.rethinknow.in/control/** and sign in. The usage dashboard also links here. Its login is separate from the usage dashboard's login.

Owner credentials stay on the server. The browser keeps its four-hour bearer session only in memory; reloading requires sign-in. Changing either credential invalidates existing sessions. Empty credentials disable login, without taking down the last published configuration or audio APIs.

## Publish, preview and restore

Move sections up/down, change labels and switches, edit the supported definitions, then use **Refresh preview**. Nothing is live until **Publish changes** succeeds. Reload fetches the published version and discards the current draft. Conflicting edits return an error instead of silently overwriting another tab.

History shows the last 20 revisions. **Restore** publishes that version's content as a new revision. Clients reject lower revision numbers, so rollback never needs to weaken replay protection.

The server accepts only these templates:

| Field | Controls | Bounds / platform |
| --- | --- | --- |
| `sections` | Order, title and visibility | All six IDs exactly once; at least one enabled. Speed dial and Spotify mixes are native-only. Web skips unsupported templates. |
| `banners` | Announcements with an HTTPS action | Up to 3; target `android`, `tv`, `web`. No HTML/scripts. |
| `playlists` | Featured YouTube Music playlist cards | Up to 12; browse ID starts with `VL`. Empty artwork is allowed. |
| `sources` | Admin add-on catalog, including owner pauses | Up to 20. `null` uses the existing usage-dashboard catalog; `[]` removes admin entries. |
| `links` | GitHub, Discord, web player and TV information | HTTPS. GitHub and Discord domains are restricted. |
| `network` | Future add-on metadata request timeout | 4–20 seconds overall; existing per-read limits still apply. `retries` controls Android's short 429 retries, 0–2. Web retains its existing cooldown behavior. |
| `communityPrompts` | Existing monthly invitations | Does not erase the listener's dismissal history. |

Native home sections are `speed-dial`, `quick-picks`, `mixes`, `spotify-mixes`, `featured-playlists`, `recommendations`. Local visibility choices and available listening history still apply. Remote content cannot override a listener's hidden/explicit filters on fetched recommendations. Featured playlists open normally; their tracks go through the usual playlist and playback flow.

Example banner:

```json
[{"id":"community","enabled":true,"title":"Made for music. Built together.","body":"Share your ideas with the community.","action":"Join Discord","url":"https://discord.gg/6udrCVh5n","platforms":["android","tv","web"]}]
```

Example admin catalog:

```json
[{"id":"monochrome","name":"OpenTune Monochrome","url":"https://addon-opentune.rethinknow.in/monochrome/manifest.json","enabled":true}]
```

Use stable IDs. Updating a URL/name preserves the listener's order and their on/off choice. `enabled:false` pauses that admin source without changing the listener's switch; enabling it again respects their previous choice. Personal sources remain untouched. URLs are public configuration, hidden only in the app's presentation: **do not include secrets or promise that users cannot inspect them**.

## Refresh and trust

Android/TV refresh while foregrounded; the web refreshes while visible. Configuration checks are throttled to five minutes, legacy/native catalog synchronization to ten. Opening Audio sources forces a refresh. Server public responses may be cached for five minutes. A valid cached configuration works offline for up to seven days, then the UI/request policy uses built-in defaults. Catalog synchronization retains its last list when offline rather than deleting personal or admin entries. It reconciles again when connectivity returns.

`GET /v1/app-config/key` supplies an ES256 SPKI public key. Clients bootstrap it over the fixed HTTPS origin, then pin it in app/browser storage. This is trust on first use over TLS, not an app-bundled signing root. Clearing app data/site storage resets that pin. `GET /v1/app-config` returns `{payload,signature}`: payload is base64url JSON and the signature is P-256 SHA-256 over the ASCII payload string, encoded as 64-byte P1363 `r||s`. Documents contain `schemaVersion`, increasing `revision`, millisecond `issuedAt`/`expiresAt`, and `data`. Clients reject bad signatures, old revisions, expiry and unsupported schemas/settings.

Public configuration contains no account credentials or tokens. It is intentionally available without device authentication; publishing is protected. The separate first-party audio attestation, request signatures and third-party add-on policy are unchanged. Configuration cannot disable these controls or deliver executable code.

Back up **both** `auth.sqlite` and `control.sqlite` in `/var/lib/opentune-addon`. Stop the service before a file copy, or use SQLite's consistent backup mechanism (copying a live WAL database alone is not safe). Protect backups: `control.sqlite` contains the private signing key. Restore it when moving hosts, because a silently regenerated key will be rejected by previously paired clients. There is no automatic remote key-rotation override.

## What still needs code changes

Provider API normalization, upstream failover and caching can change through a VPS deployment. New native layouts, decoder fixes, playback handoff changes, new schema versions and trust-root migrations still require client updates. This dashboard is a bounded data/configuration system, not remote execution.

Validation includes Node publication/authentication/rollback tests, one real Node signature fixture verified by both Android and WebCrypto, tamper/expiry/replay tests, and add-on catalog preference preservation.
