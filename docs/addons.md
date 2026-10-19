# Addons

An addon puts a music catalogue into OpenTune: a label's releases, a band's
albums, a Creative Commons library, or your own recordings. It is a web server
that answers three `GET` requests with JSON. OpenTune reads the JSON and plays
the audio file it points to. It never downloads or runs addon code.

The full guide for addon authors is on the website:
https://justshivv.github.io/Opentune/addons/ (source: `site/addons/index.html`).
A working example that shares a folder of files is in
[`addons/example_server.py`](addons/example_server.py).

## Protocol, version 1

| Request | Answer |
| --- | --- |
| `GET /manifest.json` | `{"opentune": 1, "id", "name", "catalogue", "version"?, "description"?, "homepage"?}` |
| `GET /search?q=…&limit=…` | `{"tracks": [{"id", "title", "artist"?, "album"?, "duration"?, "artwork"?}]}` |
| `GET /stream/{id}` | `{"url", "mimeType"?, "bitrate"?, "sampleRate"?, "bitDepth"?}` or `404` |

- `catalogue` is required: one sentence saying whose music this is and why
  the addon may share it. Addons may only serve music they have the right to
  share.
- `url` must be a plain audio file over HTTPS. Playlists and stream manifests
  (HLS, DASH), non-audio types, `"encrypted": true` and any `drm` field are
  refused. OpenTune does not decrypt audio.
- Plain `http://` is accepted only for localhost, `.local` names and
  home-network addresses, for testing.
- The three routes are fetched without following redirects, with a 12 second
  timeout and a 1 MB limit on each answer.

## In the app

- Settings → Connected services → Addons adds an addon by its root or
  `manifest.json` address, after reading the manifest and running a test
  search. Addons can be switched off or removed there.
- Search sends the query to every switched-on addon alongside YouTube Music.
  Their songs appear after YouTube's results under "From <addon name>".
  An addon that fails or is slow only drops its own results.
- Addon songs have ids `addon:<addon id>:<track id>`. They queue as
  `opentune://track/<id>` and ask `/stream/{id}` when they open, so links may
  expire. The song cache keeps the audio under the song id.
- Addon songs can be liked, queued, added to local playlists and cast. They
  aren't offered for download or sent to YouTube playlists, the same as other
  non-YouTube songs.

Code: `data/addons/Addons.kt` (requests and storage) and its
`AddonProtocol` (URL building and answer checks, covered by
`AddonProtocolTest`), `ui/settings/AddonsDialog.kt`, plus small hooks in
`TrackIds.kt`, `MediaItems.kt`, `SongSources.kt`, `PlaybackService.kt`,
`Cast.kt` and the search screen.
