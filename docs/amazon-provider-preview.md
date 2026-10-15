# Amazon Music provider preview

This is a native adapter for the Amazon 2.3.11 protocol documented by
[SpotiFLAC Extension](https://github.com/spotiflacapp/SpotiFLAC-Extension/tree/main/sources/amazon).
It is separate from OpenTune's older community SpotiFLAC connection.

## Try it

1. Open Settings → Audio → SpotiFLAC providers.
2. Tap **Connect in browser**, complete the provider's verification, and allow
   the browser to return to OpenTune. Only a successful grant exchange marks
   the provider connected.
3. Use Max audio quality (or enable Lossless below Max), then play **Wo Noor —
   AP Dhillon**, the track reported working in SpotiFLAC by the tester.
4. Audio starts through the existing fast path. Amazon is checked in the
   background. Open Signal path to inspect the selected provider and the
   measured bit depth/sample rate. It must actually say Amazon Music/FLAC;
   a Connected badge alone does not confirm lossless playback.

Both supplied registry URLs can be imported in the provider dialog. This
preview implements Amazon only. Other catalogue entries are clearly marked
unsupported. It does not execute downloaded JavaScript or .sflx packages.

## Behaviour and limits

- Browser state and session secrets are encrypted using Android Keystore and
  excluded from cloud backup/device transfer. OpenTune has its own callback
  scheme; it does not take over SpotiFLAC's callback.
- Only the pinned Zarz HTTPS gateway receives signed requests. Redirects are
  disabled for signed requests. A provider outage does not erase a valid session.
- Verification requires a user tap; playback never repeatedly launches a browser.
- Catalog title/artist and returned album metadata must match. The final FLAC
  STREAMINFO duration must match the selected recording within three seconds.
- Amazon can return FLAC inside MP4. The same audio-only FFmpegKit dependency
  used by SpotiFLAC Mobile prepares this locally with stream copy, without
  changing sample rate or bit depth. Non-FLAC responses are rejected.
- Container preparation downloads the complete input in the background, with
  a 256 MiB input limit, a 45 second download deadline and cancellation when the
  playback lookup is cancelled. It can use substantially more data than AAC.
  Prepared audio lives in the private cache; old files are cleaned on the next
  preparation. Playback continues through its current source while this runs.
- Upstream service availability, verification redirects and actual device
  playback remain external integration checks, not claims made by unit tests.

## Regression checks

`ExtensionProtocolTest` checks an independently calculated signing vector,
issuer/callback validation, expired credentials and error classification.
`AmazonAdapterTest` checks exact recording selection, codec/delivery URL
validation, non-transcoding preparation and registry compatibility checks.
Existing startup-selection and lossless tests must continue passing.

No main merge or release is part of this preview.
