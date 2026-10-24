# OpenTune lossless add-on

A standalone Cloudflare Worker exposing three independently reorderable OpenTune HTTP sources:

- `/monochrome/manifest.json` — Monochrome's current public Track Streamer interface.
- `/archive/manifest.json` — Internet Archive FLAC recordings.
- `/musopen/manifest.json` — curated Musopen classical collection hosted on Internet Archive.

The root `/manifest.json` aliases Monochrome. This service is an independent adapter, not an official Monochrome service. It does not alter Android playback, install executable extensions, or require a new app version. The upstream adapters use public interfaces without shared account credentials. OpenTune-owned manifests use the staged verification rollout described below; upstream availability can change.

## Live deployment

Deployed on 23 October 2026:

- [Monochrome manifest](https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev/monochrome/manifest.json)
- [Internet Archive manifest](https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev/archive/manifest.json)
- [Musopen Classical manifest](https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev/musopen/manifest.json)

Paste the Monochrome URL in OpenTune → Settings → Audio → Audio add-ons, or publish it using the existing admin dashboard. These URLs have not been automatically added to users' source lists.

[Latest deployment verification](https://github.com/justshivv/Opentune/actions/runs/40030304919) confirmed all three manifests returned HTTP 200 with browser CORS. The deployed Monochrome adapter resolved Wo Noor to FLAC 16-bit/44.1 kHz stereo, and Musopen resolved Goldberg Variations — Aria Da Capo to FLAC 16-bit/44.1 kHz. The report is in `reports/expanded-deployment-2026-10-23.json`; header verification does not establish audible playback or full-file integrity.

## Run and deploy

```sh
node --test services/lossless-addon/worker.test.mjs
cd services/lossless-addon
npx wrangler dev
# When ready to publish using your own Cloudflare account:
npx wrangler deploy
```

After deployment, copy the **actual HTTPS Worker URL printed by Wrangler** and append a manifest path above. Add each URL in OpenTune → Settings → Audio → Audio add-ons, or distribute it through the existing admin add-on dashboard. Enable Lossless playback and set the order in Sources. The production URLs below were deployed from the feature branch; the PR remains unmerged.

The Worker has no paid audio-library dependency or upstream account credential. Device verification uses Cloudflare Durable Objects and a public app-signing certificate pin. Each lookup requests upstream metadata and at most 64 KiB of audio for a FLAC-header check. Playback and downloads go directly from the provider to the device. Cloudflare hosting limits and third-party service quotas still apply; this is not unlimited free hosting or a guaranteed free music catalogue.

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

## Abuse protection and app authentication

The public Worker now has a Cloudflare rate-limit binding (240 requests per minute per address per Cloudflare location). It uses the edge-provided client address, hashed by day, instead of a caller-supplied device ID or User-Agent. Limits are approximate and local to each Cloudflare location; shared networks share the allowance. Missing/broken limiter bindings fail closed. Excess traffic receives HTTP 429 with Retry-After before any upstream work. This is abuse protection, **not proof that the caller uses OpenTune**.

The owner selected strict hardware verification for Android/TV and verified-app approval for web. The implementation and compatible clients are on this branch. The production configuration uses `VERIFICATION_MODE = "strict"`: **unverified search and stream-resolution requests are rejected**. Public manifests can still be imported by other apps, but do not grant access. Previously returned public upstream media URLs are outside this API access control. The owner confirmed playback in the signed Android preview; TV hardware verification and browser approval still need physical-device checks, and the compatible web build has not replaced the production site. Strict mode rejects unsupported devices and old clients. No embedded APK secret, spoofable User-Agent, browser Origin, public usage UUID or unverified key registration is treated as app identity. See [access security and rollout](../../docs/addon-access-security.md) for the protocol, build artifacts, cutover checks and limitations.

## Additional sources and public add-on compatibility

The Musopen adapter searches the fixed `musopen-dvd-in-lossless-flac-format` collection. It uses descriptive filenames when the collection lacks track titles, preserves supplied creator credits instead of inventing performers, filters search terms, and reads the original FLAC header. It does not match classical performances to unrelated studio recordings. This is a small classical collection (32 FLAC files in the probe), not another popular-music catalogue. Sample: Goldberg Variations, BWV 988 — Aria Da Capo, measured 16-bit/44.1 kHz stereo.

Import `/musopen/manifest.json` on the deployed OpenTune service as a separate source. Like the other OpenTune-owned manifests, it follows the same first-party verification policy.

[Acedreamer's public community add-on](https://github.com/acedreamer/bitchord-monochrome-addon) already implements the search/stream protocol. Import `https://bitchord-monochrome-addon.onrender.com/manifest.json` directly. Our anonymous probe returned native 24-bit/44.1 kHz FLAC for Blinding Lights / The Weeknd. It uses the Monochrome/HiFi network, so it is another public route, not independent catalogue coverage. We did not fork its server or add OpenTune verification to it.

Verification is bound to the exact HTTPS origin of OpenTune's Worker, not to source names, codec, or whether an add-on was added by an admin. Public add-ons do not need an OpenTune hardware key, a paired browser, or verification storage. Their existing request and fallback behavior remains unchanged. The web verification panel appears only when an OpenTune-owned source is installed. Android/TV and web regression tests cover public hosts, other Workers, ports, HTTP, lookalike domains and URL substrings.

## NetEase source from the Lossless Bridge investigation

Implemented, not yet deployed: `/netease/manifest.json`. This independently queries NetEase's catalogue and the public Meting resolver used by Lossless Bridge, then validates the returned native FLAC. Measured source samples: 295 at 24-bit/44.1 kHz and Blinding Lights at 16-bit/44.1 kHz. It shares the existing strict first-party verification; it does not wrap Lossless Bridge or affect public add-ons. See [investigation and limitations](../../docs/lossless-bridge-research.md).
