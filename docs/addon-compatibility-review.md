# Add-on compatibility and upgrade review

Reviewed 25 October 2026 against OpenTune main `5806de0` and
[BitChord `1189e6a`](https://github.com/kushagrasinghx/BitChord/tree/1189e6a566c9181111205f26a983f7984f9393ea).

Applied to `claude/wizardly-sagan-8ge2ek` at `ffc77f8`, preserving its newer Spotify, artwork, VPS authentication and owner-pause changes.

## Qobuz TV response

The public Qobuz TV add-on at `https://bppcaf.bot-keep.xyz/manifest.json`
returns a stream response containing `url`, `manifest: "none"`,
`encrypted: false`, and `quality: "LOSSLESS"`. It does not supply a codec.
OpenTune previously rejected this response before inspecting the media.

The parser now accepts a lossless quality hint when no stronger codec evidence
is present. An explicit codec, recognized format/container/MIME, or concrete
audio filename takes precedence. The resolver must still validate native FLAC
STREAMINFO and recording duration before offering that file as a playback
upgrade. Unknown bit depth and sample rate stay unknown until measured.
Encrypted renditions and invalid URLs remain rejected. Fixed diagnostic reasons
avoid exposing signed URLs or private add-on tokens.

The bounded [live probe](https://github.com/justshivv/Opentune/actions/runs/40176343640)
inspected the first 64 KiB of “295” by Sidhu Moose Wala: native FLAC,
24-bit, 44.1 kHz, stereo, 270 seconds, served by Qobuz's CDN. This proves that
sample's format, not catalogue-wide availability or an audible Android handoff.
The manifest disables downloads; playback support must preserve that policy.

## Requests and quality upgrades

| Area | BitChord | OpenTune |
| --- | --- | --- |
| Quality negotiation | Maps requested tier onto manifest options | Already does this; hi-res falls back to the declared `lossless` option |
| Sparse stream metadata | Infers FLAC from a lossless quality tier | Fixed here; direct FLAC still needs byte and duration verification |
| Requests | Shared in-flight calls; manifest/search 10-minute and stream 5-minute caches | Existing per-URL mutexes and successful-response caches use the same lifetimes |
| Source selection | Parallel candidates; background upgrades await the full candidate set | Parallel candidates; first verified match has a 250 ms grace, slower results continue through the late-result callback |
| Upgrade safety | Compares candidate against current playback and isolates rendition cache entries | Existing prebuffered handoff, distinct rendition keys, decoder-format confirmation and no replacement by a lower measured lossless tier |
| Alignment | Playback handoff is separate from catalogue resolution | Existing handoff blends close positions or uses a short fade/replacement when alignment is imperfect |
| Rate limits | Shared per-client quiet period after HTTP 429 | Current resolver holds limits per song; this remains a difference, not changed by the parsing fix |

This change does not transplant BitChord's playback engine. The failing response
never reached OpenTune's upgrade engine. Regression coverage exercises the exact
response shape, misleading codec labels, invalid media, duration mismatch,
streaming-only policy, and the existing delayed-better-candidate path.

The web player already probes direct stream bytes before classifying FLAC and
does not have this Android codec gate. Browser playback still depends on CDN
CORS and codec support. No first-party verification or public-source access
policy is changed.
