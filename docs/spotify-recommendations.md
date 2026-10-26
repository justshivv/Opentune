# Spotify recommendations and listening

Selecting Spotify in Settings → Recommendations keeps autoplay, smart-shuffle suggestions and Home's “Because you played” shelf on Spotify's picks. A short or unavailable result does not silently switch to YouTube recommendations. Tracks are still matched to playable recordings in OpenTune; selecting a recommendation provider does not select an audio provider. The separate YouTube home shelves remain YouTube content, and Spotify mixes are labelled separately.

When connected, OpenTune requests the account's Spotify song radio first; public Spotify song recommendations are the fallback. Spotify's ranking is preserved before filtering hidden/recent tracks. Personal radio caches are scoped to the sign-in and cleared on disconnect. Changing the recommendation preference refreshes Home and cancels pending autoplay requests from the previous selection. Songs already added to the queue are not removed.

“Save likes to Spotify” syncs deliberate likes/unlikes to Spotify's library. It does not automatically like every song heard. Spotify's documented API exposes reading recently played tracks, but no endpoint for submitting plays streamed by another service. OpenTune therefore does not claim to update Spotify listening history or Wrapped with its external playback. Actual Spotify playback would require a separate Spotify playback integration.

References:
- https://developer.spotify.com/documentation/web-api/reference/get-recently-played
- https://developer.spotify.com/documentation/android

Player transition fixes in the same change retain the full-cover component while opening lyrics/queue, route the lyric-preview shortcut through the same cover transition as the lyrics button, and prevent cancellation of an old transition from hiding a newer one.

## Listening-history controls

Settings → Sync listening history chooses **YouTube Music** (on by default when an account is signed in) or **This device**. Saved explicit choices are preserved. This is separate from the recommendation provider. Songs with a YouTube identity report actual listening to the signed-in account, including when an add-on supplies the audio. A play qualifies after 30 seconds, or half its length for a shorter song. Seeks do not add listening time, and upgrading a stream does not start another listen. YouTube controls acceptance and when its recommendations update.

**Pause account sync** stops only new YouTube listening reports. Local song and collection history, usage counters (subject to the existing usage preference), Replay, Speed dial and personal recommendations continue. Resuming starts the outbound listening timer at that moment; paused listening is never backfilled. Account or destination changes cancel pending YouTube reports without resetting local listening. Likes and independent scrobblers retain their own settings. Spotify's API still cannot accept external plays.

Speed dial combines current local listening with account picks instead of letting a cached account shelf hide local changes. Artist aggregates retain a representative song and artist ID; older records resolve lazily. Home adds personal top artists and refreshes after a new qualifying listen when uncovered, with a one-minute minimum between listening-triggered refreshes. Signed-in Home keeps its account shelves without adding unrelated guest charts and rotating moods.

## Automatic audio-source updates

While OpenTune is in the foreground, installed personal and community sources are checked at most every ten minutes, including on launch and when opening Audio sources. Android also schedules a network-connected, battery-aware check every 30 minutes; the OS can defer background work. Enabled sources refresh their manifests with at most three requests in parallel. The refresh button also bypasses the manifest cache. Updates preserve source order, listener switches and owner-provided names; offline/invalid responses keep the last working manifest. A response from a removed or replaced source cannot overwrite its replacement. These updates do not change public add-on authentication.
