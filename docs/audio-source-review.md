# Audio source review — 15 October 2026

Source code and current READMEs were reviewed for the projects below. Advertised bitrates do not prove the quality of a returned stream. No live subscription account or configured ClashFLAC server was available for end-to-end FLAC verification in this session.

| Reference | What supplies audio / account data | OpenTune decision |
| --- | --- | --- |
| [Stash](https://github.com/rawnaldclark/Stash) | YouTube fallback, Qobuz account/custom relay, and a project-operated relay; Spotify is library metadata. | Keep fast fallback, validate FLAC bytes, isolate lookup failures. Do not reuse its private release configuration. |
| [YumaPlayer](https://github.com/MuwMx/YumaPlayer) | Qobuz/QBDLX FLAC and YouTube; Spotify web-session authentication supplies library/discovery. | Add official read-only Spotify PKCE library import rather than coupling OpenTune to web-player cookie/TOTP internals. |
| [Monochrome Android wrapper](https://github.com/fabiodalez-dev/Monochrome-Android-APK) | Wraps Monochrome's web engine; its OAuth integration describes Last.fm/Libre.fm. | No independent new audio backend to add. |
| [Monochrome](https://github.com/monochrome-music/monochrome) | Tidal-compatible APIs, including FLAC/DASH. Hosted APIs explicitly restrict clients to official instances. | Do not impersonate an approved instance. An authorized endpoint and DASH integration would be separate work; current direct-FLAC validation remains strict. |
| [VxMusic](https://github.com/ABCGop/VxMusic) | Configured Qobuz/ClashFLAC-style backend, with quality-tier fallbacks and YouTube. | Implement the documented upstream ClashFLAC protocol against a listener-configured server, not its app-specific keyed backend. |
| [ClashFLAC](https://github.com/ajisth69/ClashFLAC) | User-supplied Qobuz/Tidal/Amazon accounts. Some browser playback is JioSaavn, MP3 or short previews; FLAC/download claims cover different routes. | Add Qobuz search + direct stream route, strict catalog/duration checks, FLAC header verification, 8-second background budget and server cooldown. No DRM decryption paths or credentials copied. |
| [SpotiFLAC-Mobile](https://github.com/spotiflacapp/SpotiFLAC-Mobile) | Current mobile app uses user-installed extension repositories, with signed-session error contracts separating gateway session errors from provider outages. The extension HMAC signer differs from the existing community signer. | Adopt explicit error classification and one bounded provider retry. Preserve the community signer and reject unconfigured extension runtimes. No claim that a particular extension/provider is live. |
| [SpotiFLAC website](https://spotiflac.com/) | Describes extension-based providers and account/source-dependent downloads; it is not a streaming API endpoint. | Existing verification integration retained. Installing random extensions is not a reliable drop-in source. |
| [Musly](https://github.com/dddevid/musly) | User-owned Subsonic/Navidrome/Jellyfin/Emby/local files. CC BY-NC-SA license. | OpenTune already has Subsonic/local playback. No source code copied; server content determines quality. |
| [Spotube](https://github.com/team-spotube/spotube) | Metadata/auth plugins are separate from audio-source plugins. | Keep Spotify library access separate from playback and honest source/quality labels. A plugin framework is not itself a new high-quality source. |
| [Musify](https://github.com/gokadzev/Musify) | YouTube extraction/plugins and Spotify playlist import. | Existing YouTube/JioSaavn path already covers this class of source; no evidence of a separate lossless source to add. |
| [Freyr](https://github.com/miraclx/freyr-js) | Spotify/Apple/Deezer metadata, matching audio from other sources, then AAC encoding. | Re-encoding to 320 kbps cannot improve source fidelity. Do not add a transcoding step or label it a quality upgrade. |
| [Muffon](https://github.com/staniel359/muffon) | Multiple sources; README explicitly describes basic-account low-quality streaming. AGPL-3.0. | No lossless upgrade added. |
| `https://github.com/unicellular-familytheophNC` | Returned 404; URL identifies a profile, not a repository. | A valid repository URL is needed to review it. |

## Configure ClashFLAC

Deploy your own [ClashFLAC](https://github.com/ajisth69/ClashFLAC) instance and configure its Qobuz account on the server. Use a protected deployment appropriate for your account; upstream also exposes account-management routes, so do not publish those routes indiscriminately. OpenTune only needs GET `/api/qobuz/search` and `/api/qobuz/stream/{id}`.

In OpenTune, open Settings → Audio → Higher-quality sources → ClashFLAC server, and enter the HTTPS base URL. Enable Lossless streaming or choose Max audio quality. Blank removes this optional source. No server is bundled or contacted until configured. Song titles/artists go to the selected server, and audio comes from the verified returned HTTPS URL. Inline credentials/query tokens are not accepted.

Startup still uses the JioSaavn fast path and available fallback; lossless resolves in the existing background-upgrade path. The app accepts only an exact recording match and full-length raw FLAC with a valid STREAMINFO header. Claimed 24-bit quality, MP3 fallback, 30-second samples and encrypted manifests do not qualify. Current downloads continue through the existing download engine; this integration does not promise lossless downloads.

## Verification limits

Fixture tests cover catalog mismatches, lossy/preview/encrypted responses, unsafe configuration URLs, and independence of JioSaavn from FLAC lookup misses. A configured server and physical-device playback are still needed to confirm real provider availability and skip latency. Spotify account setup and verification requirements are in [spotify-connect.md](spotify-connect.md).

All new adapters are independently implemented from documented request/response shapes. No third-party account tokens, client secrets or private endpoint configuration were imported.
