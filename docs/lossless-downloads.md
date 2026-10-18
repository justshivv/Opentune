# Lossless downloads

Based on main 41054c7 (0.4.18). Open Library → Lossless.

1. Search Amazon Music by song or artist and select the recording.
2. Choose Download FLAC. Connect the provider in the browser if prompted.
3. If the browser does not return, use Copy link, then Paste browser result →
   Paste → Verify link. Complete callback URLs require matching state. An exact
   grant-only code can be submitted explicitly during an active verification.
4. Waiting downloads resume after connection; Resume is also available.
5. The queue shows request, transfer, preparation and validation stages.
6. Completed files show measured FLAC precision and sample rate. Play offline,
   Export to a chosen folder, or Remove the private copy. Exported copies remain.

## Architecture

The native Amazon adapter follows SpotiFLAC's reviewed 2.3.11 download protocol:
encrypted installation/session state, browser verification, signed one-use ticket,
complete audio download, stream-copy container preparation, then FLAC validation.
It does not install/execute arbitrary extensions and currently supports Amazon
Music only. No new Amazon requests run during normal streaming or song changes.
Existing main playback and other provider settings are retained.

WorkManager owns the durable queue, respects the existing unmetered-download
setting, provides a foreground notification and cancellation, and restarts work
after process death. Each job owns a unique directory and generation ID; late
callbacks cannot resurrect removed/replaced entries. State transitions use
AtomicFile; success is persisted only after file preparation and validation.
Signed URLs and preparation keys are never written to the queue. Sessions and
private audio are excluded from backup. Exports use Android's document picker.

Full FLAC decode validation rejects corrupt frames. STREAMINFO provides measured
bit depth/sample rate; known catalog durations must match within three seconds.
No AAC/MP3 fallback, lossy-to-FLAC transcoding or upsampling is performed.

Limits: one active transfer, 512 MiB input per track, 1 GiB free-space requirement,
five-minute transfer deadline and eight-minute overall job budget. Interrupted
transfers restart with a new ticket rather than reusing an expired delivery URL.
Provider access, regional catalog coverage and browser challenges remain external
dependencies. HTTP 403 is a provider denial; a download UI alone cannot fix it.

References:
- https://github.com/spotiflacapp/SpotiFLAC-Extension/tree/main/sources/amazon
- https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/providers/download_queue_provider_single_item.dart
- https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/providers/download_queue_provider_verification.dart

Validation includes protocol/session tests, durable queue recovery/cancellation,
provider response validation and narrow-phone download-control interaction tests.
Real Amazon verification/download still requires a device test; CI does not solve
interactive browser challenges. No merge or official release is authorized.
