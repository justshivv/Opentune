# Spotify recommendations and listening

Selecting Spotify in Settings → Recommendations keeps autoplay, smart-shuffle suggestions and Home's “Because you played” shelf on Spotify's picks. A short or unavailable result does not silently switch to YouTube recommendations. Tracks are still matched to playable recordings in OpenTune; selecting a recommendation provider does not select an audio provider. The separate YouTube home shelves remain YouTube content, and Spotify mixes are labelled separately.

When connected, OpenTune requests the account's Spotify song radio first; public Spotify song recommendations are the fallback. Spotify's ranking is preserved before filtering hidden/recent tracks. Personal radio caches are scoped to the sign-in and cleared on disconnect. Changing the recommendation preference refreshes Home and cancels pending autoplay requests from the previous selection. Songs already added to the queue are not removed.

“Save likes to Spotify” syncs deliberate likes/unlikes to Spotify's library. It does not automatically like every song heard. Spotify's documented API exposes reading recently played tracks, but no endpoint for submitting plays streamed by another service. OpenTune therefore does not claim to update Spotify listening history or Wrapped with its external playback. Actual Spotify playback would require a separate Spotify playback integration.

References:
- https://developer.spotify.com/documentation/web-api/reference/get-recently-played
- https://developer.spotify.com/documentation/android

Player transition fixes in the same change retain the full-cover component while opening lyrics/queue, route the lyric-preview shortcut through the same cover transition as the lyrics button, and prevent cancellation of an old transition from hiding a newer one.
