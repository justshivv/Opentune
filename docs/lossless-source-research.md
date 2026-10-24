# Free lossless source research — 23 October 2026

## Monochrome: two different interfaces

The [README](https://github.com/monochrome-music/monochrome#self-hosting) warns that its official HiFi API accepts only approved instances. That does **not** describe every current playback path. The current [Track Streamer client](https://github.com/monochrome-music/monochrome/blob/main/js/tracks-api.js) declares `https://tracks.monochrome.st`, searches `/search/tracks?q=...&limit=20`, and forms `/track/<id>` media URLs. [MusicAPI](https://github.com/monochrome-music/monochrome/blob/main/js/music-api.js) integrates that provider alongside Tidal and Apple catalogue functions.

The new OpenTune adapter implements that public Track Streamer interface independently. It does not copy Monochrome source or reuse its restricted HiFi authentication, Turnstile tokens, domain identity or user cookies. An access refusal remains an error. Being able to search does not establish that a recording can be played, that downloads are unrestricted, or that an operator offers a service guarantee.

A [third-party Spotube adapter](https://github.com/kipavy/spotube-plugin-lossless-sources) reports successful Monochrome FLAC tests, but its [machine-readable status](https://github.com/kipavy/spotube-plugin-lossless-sources/blob/main/sources.json) was more recent than that prose during this investigation: the 2026-10-23T13:25:46Z snapshot had **zero playable sources**, with Monochrome search succeeding but audio returning **429**. This is third-party evidence, not our own playback verification. It is why we do not publish a blanket “working free lossless catalogue” claim.

Our own [GitHub Actions probe](https://github.com/justshivv/Opentune/actions/runs/40010168839) at **2026-10-23T19:17:07Z** subsequently succeeded for both Monochrome samples:

| Recording | Search | Audio/header | Measured format |
| --- | --- | --- | --- |
| Wo Noor — AP Dhillon | HTTP 200 | HTTP 200 | FLAC, 16-bit, 44,100 Hz, stereo; 199.420 seconds |
| Old Skool — Prem Dhillon, Sidhu Moose Wala, Naseeb | HTTP 200 | HTTP 200 | FLAC, 16-bit, 44,100 Hz, stereo; 253.701 seconds |
| Internet Archive — Grateful Dead search | HTTP 200 | Not attempted: no usable candidates | Unverified |

These are CD-resolution lossless files, **not verified hi-res releases**. No account, key, copied browser session or impersonated Origin header was used. This establishes that the public Track Streamer served native FLAC headers for those recordings from that runner at that time; it does not negate earlier rate limiting or establish an unlimited service. The exact report is saved in `services/lossless-addon/reports/2026-10-23.json`.

Our probe checks Wo Noor by AP Dhillon and Old Skool by Prem Dhillon, then reads only a bounded FLAC header. The GitHub **Lossless add-on** workflow produces `lossless-source-report.json` with current results. See the PR for that run; a green probe job only means the report was generated, not that any provider worked. Actual provider success is recorded in each result's `streamStatus` and `flacHeader` fields.

## Additional probes at 21:15 UTC

The additional [Actions probe](https://github.com/justshivv/Opentune/actions/runs/40025364162) tested public endpoints without accounts or forged client identities:

| Candidate | Observed result | Use in OpenTune |
| --- | --- | --- |
| Monochrome Track Streamer — Blinding Lights / The Weeknd | Search and stream HTTP 200; native FLAC **24-bit / 44.1 kHz**, stereo | Existing adapter already returns this higher-bit-depth file without a new source |
| Lossless Bridge | HTTP 429 | Rate limited; cannot claim current playback availability |
| BluBit Station Hi-Fi | Manifest available, subsequent lookup HTTP 502 | Unavailable in this probe |
| Juanjob hi-res add-on | Manifest and 21 catalogue matches, stream HTTP 404 | Catalogue works; selected audio unavailable |

The exact candidate results are saved in `services/lossless-addon/reports/candidates-2026-10-23.json`. A later result can differ; these are bounded header checks from a GitHub runner, not full listening tests. **24-bit / 44.1 kHz is not evidence of a 96/192 kHz master**, nor can a FLAC header prove the source was never transcoded.

The [Juanjob maintainer](https://github.com/juanjoblol/bitchord-hires-addon) documents a Qobuz proxy that can offer up to 24-bit/192 kHz when available, but currently reports its public proxy down with HTTP 402. Its Tidal route uses Lossless Bridge and its Archive route covers live/independent material. It is not an additional verified working default. Self-hosted Qobuz/Tidal integrations require an entitled account; those are not configuration-free free catalogues.

The official [2L high-resolution test bench](https://www.2l.no/hires/) currently says its free service is unavailable during renovation. It should not be advertised as a working free source. An owner's FLAC library on Jellyfin/Navidrome is a practical separate adapter target, but it supplies music the owner already has rather than a free commercial catalogue.

## Internet Archive catalogue

The [metadata API](https://archive.org/developers/md-read.html) exposes item and file metadata; the [audio guide](https://archivesupport.zendesk.com/hc/en-us/articles/360016549992-Audio-and-Music-Items-A-Basic-Guide) documents FLAC among its lossless formats. The adapter searches audio items containing FLAC, retains their recording identity, and resolves the original download URL. Many recordings are live or independent; this is not the same coverage as a commercial studio catalogue. Permission and download restrictions vary by recording.

The adapter omits ambiguous filenames without track metadata. OpenTune's strict recording matcher remains responsible for deciding whether a result matches the requested song. It must not silently substitute a live performance for a studio recording just because both have the same name.

## Legacy HiFi APIs

Monochrome's [instance list](https://github.com/monochrome-music/monochrome/blob/main/INSTANCES.md) explicitly calls itself outdated. [hifi-api](https://github.com/binimum/hifi-api) requires account credentials for playback and documents shared-account blocking; unauthenticated catalogue search is not proof of playback. The third-party status above also reports playback errors or unreachable hosts across the old public instances. We did not bundle them as working free defaults or implement account/restriction bypasses.

## What this implementation establishes

- Three add-on manifests, search/stream adapters, measured native-FLAC metadata, bounded requests and rate-limit diagnostics.
- Thirteen deterministic adapter regression tests covering mapping, invalid media, malformed IDs, cache/coalescing, response limits and rate limits.
- A reproducible live probe, separate from unit tests, with no full-song downloads or signed-URL logging.
- A standalone Worker was deployed on explicit user request on 23 October 2026. Its live manifests and Monochrome FLAC resolution passed verification; see the service README and deployment report. Catalogue coverage, full-file integrity and device audibility remain unverified.

The initial local live check could not connect to the execution environment's network proxy. That is an environment failure, not evidence of a provider outage. Live verification is therefore delegated to the dedicated GitHub Actions probe report.

## Expanded discovery at 21:49 UTC

[Our source discovery run](https://github.com/justshivv/Opentune/actions/runs/40029489197) tested additional public endpoints. Exact results are in `services/lossless-addon/reports/discovery-2026-10-23.json`.

| Source | Direct observation | Resulting integration |
| --- | --- | --- |
| [Acedreamer community add-on](https://github.com/acedreamer/bitchord-monochrome-addon) | Native FLAC, 24-bit/44.1 kHz stereo, HTTP 206 | Already a compatible public add-on: import `https://bitchord-monochrome-addon.onrender.com/manifest.json`; no OpenTune verification |
| [Musopen Kickstarter collection](https://archive.org/details/musopen-dvd-in-lossless-flac-format) | 32 FLAC files; sampled Goldberg Variations — Aria Da Capo is 16-bit/44.1 kHz stereo, HTTP 206 | New `/musopen/manifest.json` adapter, same security policy as our other first-party sources |
| DAB public endpoints | Main endpoint HTTP 403; alternate could not complete | Not integrated as a working source |
| Squid Qobuz | Request could not complete | Not integrated as a working source |
| Lucida | Anonymous search webpage HTTP 200; no audio verified | Not advertised as working audio |
| [Free To Use API](https://freetouse.com/api) | Public track response advertised MP3 only | Not a verified lossless source |

Acedreamer serves the same Monochrome/HiFi network, so it must not be counted as an independent catalogue. Its public add-on worked with our transparent OpenTune research User-Agent. No impersonated client headers were needed. We used its published HTTP interface and did not copy its implementation.

The latest third-party [status snapshot](https://github.com/kipavy/spotube-plugin-lossless-sources/blob/main/sources.json), timestamp 18:20:41 UTC, lists Monochrome as its only playable source among 22 candidates. That improves on the earlier rate-limited snapshot but still does not verify another independent broad catalogue. The measured 24-bit/44.1 kHz result has greater bit depth than CD; we did not establish 96/192 kHz availability or prove original mastering provenance.

Other projects investigated do not provide anonymous free catalogues: [divyanshxyz/bitchord-addon](https://github.com/divyanshxyz/bitchord-addon) primarily serves a user's existing FLAC/Navidrome library, while [R1Blmmakan/music_addon](https://github.com/R1Blmmakan/music_addon) requires provider account credentials. The [Lucida client](https://github.com/Jude-A/lucidadl) contains a separate job and browser-session workflow; a successful search page is insufficient evidence for an adapter. We did not bundle unverified backends or turn lossy audio into nominal FLAC.

All measurements are bounded FLAC-header checks, not full decoding/listening tests. Public source availability, CORS and quotas can change. None of these third-party add-ons inherit OpenTune's first-party verification requirement.

The deployed Musopen adapter passed its own catalogue-to-resolution check at 21:56 UTC: one matching Aria Da Capo result and HTTP 200 native FLAC 16-bit/44.1 kHz. All three deployed manifests returned HTTP 200. Reports: `expanded-sources-2026-10-23.json`, `discovery-expanded-2026-10-23.json` and `expanded-deployment-2026-10-23.json` in `services/lossless-addon/reports`, from [the expanded probe](https://github.com/justshivv/Opentune/actions/runs/40030304908) and [deployment](https://github.com/justshivv/Opentune/actions/runs/40030304919). The general Archive query also resolved a recording by FAAFO; that is a different artist and is not evidence of a matching Grateful Dead recording. The Musopen item metadata declares the Public Domain Mark 1.0.
