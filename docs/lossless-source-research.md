# Free lossless source research — 23 October 2026

## Monochrome: two different interfaces

The [README](https://github.com/monochrome-music/monochrome#self-hosting) warns that its official HiFi API accepts only approved instances. That does **not** describe every current playback path. The current [Track Streamer client](https://github.com/monochrome-music/monochrome/blob/main/js/tracks-api.js) declares `https://tracks.monochrome.st`, searches `/search/tracks?q=...&limit=20`, and forms `/track/<id>` media URLs. [MusicAPI](https://github.com/monochrome-music/monochrome/blob/main/js/music-api.js) integrates that provider alongside Tidal and Apple catalogue functions.

The new OpenTune adapter implements that public Track Streamer interface independently. It does not copy Monochrome source or reuse its restricted HiFi authentication, Turnstile tokens, domain identity or user cookies. An access refusal remains an error. Being able to search does not establish that a recording can be played, that downloads are unrestricted, or that an operator offers a service guarantee.

A [third-party Spotube adapter](https://github.com/kipavy/spotube-plugin-lossless-sources) reports successful Monochrome FLAC tests, but its [machine-readable status](https://github.com/kipavy/spotube-plugin-lossless-sources/blob/main/sources.json) was more recent than that prose during this investigation: the 2026-10-23T13:25:46Z snapshot had **zero playable sources**, with Monochrome search succeeding but audio returning **429**. This is third-party evidence, not our own playback verification. It is why we do not publish a blanket “working free lossless catalogue” claim.

Our probe checks Wo Noor by AP Dhillon and Old Skool by Prem Dhillon, then reads only a bounded FLAC header. The GitHub **Lossless add-on** workflow produces `lossless-source-report.json` with current results. See the PR for that run; a green probe job only means the report was generated, not that any provider worked. Actual provider success is recorded in each result's `streamStatus` and `flacHeader` fields.

## Internet Archive

The [metadata API](https://archive.org/developers/md-read.html) exposes item and file metadata; the [audio guide](https://archivesupport.zendesk.com/hc/en-us/articles/360016549992-Audio-and-Music-Items-A-Basic-Guide) documents FLAC among its lossless formats. The adapter searches audio items containing FLAC, retains their recording identity, and resolves the original download URL. Many recordings are live or independent; this is not the same coverage as a commercial studio catalogue. Permission and download restrictions vary by recording.

The adapter omits ambiguous filenames without track metadata. OpenTune's strict recording matcher remains responsible for deciding whether a result matches the requested song. It must not silently substitute a live performance for a studio recording just because both have the same name.

## Legacy HiFi APIs

Monochrome's [instance list](https://github.com/monochrome-music/monochrome/blob/main/INSTANCES.md) explicitly calls itself outdated. [hifi-api](https://github.com/binimum/hifi-api) requires account credentials for playback and documents shared-account blocking; unauthenticated catalogue search is not proof of playback. The third-party status above also reports playback errors or unreachable hosts across the old public instances. We did not bundle them as working free defaults or implement account/restriction bypasses.

## What this implementation establishes

- Two add-on manifests, search/stream adapters, measured native-FLAC metadata, bounded requests and rate-limit diagnostics.
- Twelve deterministic regression tests covering mapping, invalid media, malformed IDs, cache/coalescing, response limits and rate limits.
- A reproducible live probe, separate from unit tests, with no full-song downloads or signed-URL logging.
- Deployable source for the user's own Worker. No live endpoint, production deployment, guaranteed catalogue coverage, full-file integrity or device audibility is implied.

The initial local live check could not connect to the execution environment's network proxy. That is an environment failure, not evidence of a provider outage. Live verification is therefore delegated to the dedicated GitHub Actions probe report.
