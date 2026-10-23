# OpenTune lossless add-on

A standalone Cloudflare Worker exposing two independently reorderable OpenTune HTTP sources:

- `/monochrome/manifest.json` — Monochrome's current public Track Streamer interface.
- `/archive/manifest.json` — Internet Archive FLAC recordings.

The root `/manifest.json` aliases Monochrome. This service is an independent adapter, not an official Monochrome service. It does not alter Android playback, install executable extensions, or require a new app version. No keys or sign-in are required by these adapter interfaces; upstream availability can change.

## Run and deploy

```sh
node --test services/lossless-addon/worker.test.mjs
cd services/lossless-addon
npx wrangler dev
# When ready to publish using your own Cloudflare account:
npx wrangler deploy
```

After deployment, copy the **actual HTTPS Worker URL printed by Wrangler** and append either manifest path above. Add each URL in OpenTune → Settings → Audio → Audio add-ons, or distribute it through the existing admin add-on dashboard. Enable Lossless playback and set the order in Sources. There is no deployed add-on URL in this change.

The Worker has no paid library dependency, credentials, storage binding, or audio bandwidth proxy. Each lookup requests upstream metadata and at most 64 KiB of audio for a FLAC-header check. Playback and downloads go directly from the provider to the device. Cloudflare hosting limits and third-party service quotas still apply; this is not unlimited free hosting or a guaranteed free music catalogue.

## How it works

`GET /<source>/search?q=<title artist>&quality=best` returns the add-on `tracks` schema. `GET /<source>/stream/<opaque-id>?quality=best` resolves a recording and reads its native FLAC STREAMINFO. Only then does it return the original media URL, measured bit depth, sample rate, channel count and duration. Best means the original available file; no fake 24-bit tier, conversion, resampling or compressed-to-FLAC transcoding occurs.

Monochrome maps `/search/tracks?q=...&limit=20` and `/track/<id>`, the same interface used by its current `js/tracks-api.js`. It preserves ISRC and recording metadata; OpenTune performs title/performer/duration matching. Search metadata alone does not prove a file is FLAC. Internet Archive searches three audio items per request, expands their metadata, and only exposes explicitly labelled FLAC files with usable track-level title, artist and duration. Ambiguous multi-track files are omitted. Live takes remain live takes; OpenTune should not match them to unrelated studio recordings.

There is no wrap of Monochrome's website HTML, restricted legacy HiFi session, Turnstile challenge or shared account. Requests identify this adapter as OpenTune. Authentication/access refusals are surfaced. HTTP 429 applies a bounded Retry-After cooldown; it does not cause alternate identities or IP rotation. HTML/error bodies and non-FLAC files are rejected. URLs are constructed from fixed upstream hosts and validated opaque IDs, not arbitrary user-supplied media URLs.

Successful searches cache for 60 seconds, Archive item metadata for 120 seconds and measured streams for 30 seconds. Concurrent equivalent lookups coalesce; failed lookups are not cached. These caches and cooldowns are per Worker isolate, not global quota enforcement. Add Cloudflare rate limiting if exposing a busy public service. The service never proxies the audio file, so byte-range seeking and browser CORS depend on the upstream host.

`GET /<source>/health` only indicates adapter readiness. It deliberately does not call a source “working.” Run the bounded live probe to assess current search/file availability:

```sh
node services/lossless-addon/probe.mjs /tmp/lossless-source-report.json
```

The probe checks Wo Noor, Old Skool and an Archive search, recording safe status and measured headers, without saving signed URLs or full recordings. It does not establish full-file integrity, audible quality, worldwide availability or continued playback after a quota changes. The dedicated GitHub workflow uploads this report separately from deterministic test results. A failed upstream probe is an availability report, not a passing playback test.

See [source research](../../docs/lossless-source-research.md) for current limitations and upstream references.
