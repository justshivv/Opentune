# Spotify recommendations and listening

Selecting Spotify in Settings → Recommendations keeps autoplay, smart-shuffle suggestions and Home's “Because you played” shelf on Spotify's picks. A short or unavailable result does not silently switch to YouTube recommendations. Tracks are still matched to playable recordings in OpenTune; selecting a recommendation provider does not select an audio provider. The separate YouTube home shelves remain YouTube content, and Spotify mixes are labelled separately.

When connected, OpenTune requests the account's Spotify song radio first; public Spotify song recommendations are the fallback. Spotify's ranking is preserved before filtering hidden/recent tracks. Personal radio caches are scoped to the sign-in and cleared on disconnect. Changing the recommendation preference refreshes Home and cancels pending autoplay requests from the previous selection. Songs already added to the queue are not removed.

“Save likes to Spotify” syncs deliberate likes/unlikes to Spotify's library. It does not automatically like every song heard. Spotify's documented API exposes reading recently played tracks, but no endpoint for submitting plays streamed by another service. OpenTune therefore does not claim to update Spotify listening history or Wrapped with its external playback. Actual Spotify playback would require a separate Spotify playback integration.

References:
- https://developer.spotify.com/documentation/web-api/reference/get-recently-played
- https://developer.spotify.com/documentation/android

Player transition fixes in the same change retain the full-cover component while opening lyrics/queue, route the lyric-preview shortcut through the same cover transition as the lyrics button, and prevent cancellation of an old transition from hiding a newer one.

## Listening-history controls

Settings → Sync listening history chooses **This device** (the existing default) or **YouTube Music**. This is separate from the recommendation provider. With YouTube Music selected and signed in, songs with a YouTube identity report actual listening to that account, including when an add-on supplies the audio. A play qualifies after 30 seconds, or half its length for a shorter song. Seeks do not add listening time, and upgrading a stream does not start another listen. Account history is best-effort: YouTube controls acceptance and when its recommendations update.

Pause listening history stops new local song/collection records and new listening reports to YouTube Music and connected scrobblers. Turning it back on starts counting from that moment; paused time is never backfilled. Switching account or history destination cancels pending YouTube reports. This does not delete earlier history, remove likes, or change the account's history setting in other apps. Previously queued scrobbles from before the pause retain their existing delivery behavior. Podcast resume positions still work.

## Automatic audio-source updates

While OpenTune is in the foreground, installed personal and community sources are checked at most every ten minutes, including on launch and when opening Audio sources. Enabled sources refresh their manifests with at most three requests in parallel. The refresh button also bypasses the manifest cache. Updates preserve source order, listener switches and owner-provided names; offline/invalid responses keep the last working manifest. A response from a removed or replaced source cannot overwrite its replacement. These updates do not change public add-on authentication.
