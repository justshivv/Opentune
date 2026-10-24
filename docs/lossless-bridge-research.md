# Lossless Bridge source investigation — 24 October 2026

OpenTune can resolve the same NetEase/Meting source directly, without calling Lossless Bridge. The new `/netease` adapter is implemented on `feat/first-party-lossless-addon`; it has not been deployed. Monochrome and Archive were already implemented. The existing strict verification layer protects the new adapter on the first-party host, while public third-party add-ons remain unchanged.

## Live deployment evidence

Read-only requests to [the manifest](https://losslessbridge.de.deplexo.com/manifest.json), [sources](https://losslessbridge.de.deplexo.com/sources) and [health](https://losslessbridge.de.deplexo.com/health) returned 200 at 01:04 UTC. These are the actual deployed source settings, not guesses from a frontend bundle:

| Source | Upstream | Observed state |
| --- | --- | --- |
| Monochrome | `https://tracks.monochrome.st` | API, healthy; already in OpenTune |
| Internet Archive | `https://archive.org` | Bridge reports timeouts/cooldown; already in OpenTune |
| NetEase catalogue | `https://music.163.com/api/cloudsearch/pc` | Enabled |
| NetEase audio | `https://api.qijieya.cn/meting/` → `*.music.126.net` | Bridge reports a recent non-FLAC result; our two direct probes succeeded |
| GD Studio | `https://music.gdstudio.xyz`, `https://music.gdstudio.org` | Tidal, NetEase and Kuwo routes all report HTTP 403/challenge blocks |
| HiFi API / Qobuz | No configured bases | Disabled |
| Jamendo | `https://api.jamendo.com/v3.0` | Disabled |

The repository linked by indexed documentation, `https://github.com/ElioXY/TestingAddon`, currently returns 404. Indexed README text provided discovery hints; the live `/sources` response independently confirmed the endpoints. No unavailable source code was copied, and no claimed hi-res tier was inferred from that README.

Evidence: `services/lossless-addon/reports/bridge-research-2026-10-24.json` and [probe run 40048066675](https://github.com/justshivv/Opentune/actions/runs/40048066675). Source health varies per recording and time; a Bridge cooldown does not prove that every NetEase recording is unavailable.

## Independently verified NetEase flow

1. Search the public catalogue with `s`, `type=1` and a bounded `limit`.
2. Preserve title, credited artists, album and duration; do not claim the catalogue's quality flags establish audio quality.
3. Request the documented Meting URL endpoint with `server=netease`, `type=url`, the numeric recording ID and `br=999000`.
4. Read its redirect manually. Accept only HTTPS NetEase media hosts, with no credentials or nonstandard port. Every subsequent redirect is validated too.
5. Read at most 64 KiB, validate native FLAC STREAMINFO and return measured bit depth, sample rate, channels and duration. MP3, HTML, malformed ranges, access refusals and quota errors are not promoted to lossless.

The [Meting operator's public documentation](https://api.qijieya.cn/meting/) describes the service and its migration/availability caveats. This is a third-party hosted resolver: OpenTune sends no account credentials, but availability depends on the operator and upstream catalogue. An anonymous client API does not mean the upstream has no account dependency.

Direct measurements from bounded original-file headers:

| Recording | NetEase ID | Measured format |
| --- | --- | --- |
| 295 — Sidhu Moose Wala | 2726173581 | FLAC, 24-bit / 44.1 kHz, stereo, 270 s |
| Blinding Lights — The Weeknd | 1406633327 | FLAC, 16-bit / 44.1 kHz, stereo, 200.046 s |

This establishes readable FLAC headers, not audible quality, original mastering provenance, full-file integrity, or 96/192 kHz availability. Signed media URLs are omitted from reports. The new adapter uses the same source API, not the Bridge service or an access-control bypass.

## Adapter validation

The completed OpenTune adapter returned HTTP 200 for search and stream resolution for both recordings, with the same measured formats, in [run 40048382526](https://github.com/justshivv/Opentune/actions/runs/40048382526). The same run passed all 33 server tests plus the actual Workers/SQLite signature checks for native and web clients. The NetEase tests cover redirect host validation, credentials/insecure URLs, format rejection, cross-source IDs and rate-limit cooldown. The access test ensures unsigned NetEase requests never reach upstreams. Evidence: `services/lossless-addon/reports/netease-adapter-2026-10-24.json`.

## Installation after deployment

The manifest path is `/netease/manifest.json` on OpenTune's existing first-party Worker. It must be deployed before importing that path. The currently installed verification preview already signs requests for this exact origin; no app code change is needed for this adapter. Search and stream resolution remain unavailable to unverified clients. Public manifests remain importable, and returned public upstream media URLs are outside the API access lock.
