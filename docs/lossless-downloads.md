# Lossless downloads

Based on main 41054c7 (0.4.18), available on the preview branch only.

1. Open Settings → Audio → Download providers. Import the SpotiFLAC registry URL,
   then install Amazon Music, Qobuz or Tidal. Each package is checked before use.
2. Manage connection opens browser verification. Auth Tab-capable browsers return
   automatically to the private activity; older browsers retain the callback/paste
   fallback. Sessions remain scoped to the installed provider version.
3. Tap Download in a song's menu, player menu or playlist. A bottom sheet offers
   standard compressed qualities and installed providers' FLAC tiers. Standard
   quality is stored per job and preserved on retry, independent of later settings.
4. Selecting a connected provider checks each song's title, credited artists and
   duration against its catalog. Missing or failed matches are shown and skipped,
   never silently replaced with another song or lossy audio. Catalog support does
   not guarantee a particular sample rate; the completed file is measured.
5. Library → Downloads contains the ordinary downloads, FLAC queue and saved files.
   Older saved FLAC remains available. Progress, network waits, Retry, verification,
   Cancel, Details, Play, Export and Remove remain accessible here.
6. New FLAC downloads are linked to the original song so playing that song again
   can use the checked offline file. Imported older files still play from Downloads.
   Exported copies remain untouched when the app's private copy is removed.

There is no standalone Lossless destination. Provider import and verification are
settings; quality choice belongs to Download; saved music belongs to Downloads.

## Architecture

The installed package's original JavaScript implements search and download. A Rhino
host provides the SpotiFLAC APIs for HTTP, per-provider signed sessions, isolated
file downloads, sequential segments, hashing, storage and cancellation. The host
uses the manifest's app-version scope and the upstream runtime request headers.
It supports the reviewed Amazon, Qobuz and Tidal packages; other registry entries
show a compatibility message and cannot be installed.

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

Playback stages new PCM formats until the sink flushes the preceding track,
and clears spatial delay lines on seeks/transitions. Android's software FLAC
decoder is preferred when available, with initialization fallback retained.
Signal path shows the decoder and per-track underruns. Release instrumentation
checks CD and 24-bit high-resolution FLAC through the same renderer/DSP factory,
comparing both channels' decoded samples with generated reference tones.
These tests do not replace listening to the reported Tidal files on the user's device.

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
