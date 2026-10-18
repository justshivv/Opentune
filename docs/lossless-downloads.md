# Lossless downloads

Based on main 41054c7 (0.4.18). Open Library → Lossless.

1. Open Providers, import the SpotiFLAC registry URL, and install Amazon Music, Qobuz, or Tidal.
   Packages are downloaded, checked against the registry SHA-256, inspected and compiled.
2. Select an installed provider, search by song or artist and select the recording.
3. Choose a lossless quality and Download FLAC. Connect the provider in the browser if prompted.
4. If the browser does not return, use Copy link, then Paste browser result →
   Paste → Verify link. Complete callback URLs require matching state. An exact
   grant-only code can be submitted explicitly during an active verification.
5. Waiting downloads resume after connection; Resume is also available.
6. The queue shows request, transfer, preparation and validation stages.
7. Completed files show measured FLAC precision and sample rate. Play offline,
   Export to a chosen folder, or Remove the private copy. Exported copies remain.

## Architecture

The installed package's original JavaScript implements search and download. A Rhino
host provides the SpotiFLAC APIs for HTTP, per-provider signed sessions, isolated
file downloads, sequential segments, hashing, storage and cancellation. The host
uses the manifest's app-version scope and the upstream runtime request headers.
It supports the reviewed Amazon, Qobuz and Tidal packages; other registry entries
show a compatibility message and cannot be installed in this Lossless section.

Package import is limited to the two requested SpotiFLAC repository aliases.
SHA-256, package identity/version, required runtime features, archive paths and
size limits are checked before activation. Scripts have no Java bridge. HTTP is
restricted to manifest-declared HTTPS hosts, each redirect is checked, private IP
addresses are rejected, and file access is confined to the active job directory.
Instruction/deadline limits stop scripts that run indefinitely. The JavaScript
engine runs in-process; only explicitly selected packages from these repositories
are accepted. Installed packages and their storage are excluded from backup.

Browser verification is separate for each provider/version. Sessions remain
Keystore encrypted; callbacks match the active verification state. HTTP 403
preserves safe structured gateway/provider codes in Details without publishing
response bodies, signed URLs, grants, session secrets or download keys.
No new provider requests run during ordinary streaming or song changes.

WorkManager owns the durable queue, respects the existing unmetered-download
setting, provides a foreground notification and cancellation, and restarts work
after process death. Each job owns a unique directory and generation ID; late
callbacks cannot resurrect removed/replaced entries. State transitions use
AtomicFile; success is persisted only after file preparation and validation.
Queue cards explain offline, metered-network and scheduler waits. A per-item mobile
data override reschedules that job; changing the global download network setting
reschedules queued jobs. Details includes Android worker state, current network,
package/version/checksum and timestamped request stages. Signed URLs and preparation keys are never written to the queue. Sessions and
private audio are excluded from backup. Exports use Android's document picker.

Full FLAC decode validation rejects corrupt frames. STREAMINFO provides measured
bit depth/sample rate; known catalog durations must match within three seconds.
No AAC/MP3 fallback, lossy-to-FLAC transcoding or upsampling is performed.

Limits: one active transfer, 512 MiB input per track, 1 GiB free-space requirement,
five-minute transfer deadline and a bounded provider execution budget. Interrupted
transfers restart with a new ticket rather than reusing an expired delivery URL.
Provider access, regional catalog coverage and browser challenges remain external
dependencies. HTTP 403 is a provider denial; a download UI alone cannot fix it.

References:
- https://github.com/spotiflacapp/SpotiFLAC-Extension/tree/main/sources/amazon
- https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/providers/download_queue_provider_single_item.dart
- https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/providers/download_queue_provider_verification.dart

Validation includes protocol/session tests, durable queue recovery/cancellation,
provider response validation and narrow-phone download-control interaction tests.
Real provider verification/download still requires a device test; CI does not solve
interactive browser challenges. No merge or official release is authorized.
