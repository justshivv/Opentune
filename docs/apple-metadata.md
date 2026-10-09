# Apple catalog metadata

Track details looks up Apple catalog metadata when opened, independently of MusicBrainz.
It uses Apple's documented public iTunes Search API (`https://itunes.apple.com/search`),
with the device's country storefront and a US fallback. No Apple login or developer token is needed.
It shows artist credit, album, release date, genre, explicitness, track/disc number and the returned Apple link.
Requests are cancellable, limited to a 1 MiB response and five seconds, spaced at least 3.1 seconds apart,
and cached for 24 hours in a bounded in-memory cache. Failures remain retryable.

Matches require title, artist, duration within three seconds and album when known. Common
soundtrack/Single/EP album suffixes are normalized; remix and edition names are preserved.
Ambiguous matches are omitted. Catalog labels do not imply anything about the audio stream;
actual provider/codec/bitrate remain in the source badge and Signal path. The Search API does
not supply ISRCs, so MusicBrainz remains the source for those. Preview audio is not used.

Research reference: [Echo Music](https://github.com/EchoMusicApp/Echo-Music) uses
[Apple chart feeds](https://github.com/EchoMusicApp/Echo-Music/blob/main/app/src/main/kotlin/com/music/echo/ui/screens/search/suggestions/AppleMusicScraper.kt),
and its [album descriptions](https://github.com/EchoMusicApp/Echo-Music/blob/main/app/src/main/kotlin/com/music/echo/utils/AppleMusicAboutAlbum.kt)
and [motion artwork](https://github.com/EchoMusicApp/Echo-Music/blob/main/applecanvas/src/main/kotlin/com/music/echo/applecanvas/AppleMusicCanvasProvider.kt)
use the Apple web catalog with a token extracted from the web player. OpenTune's implementation
uses the documented Search API and does not copy that token extraction or bundle a third-party token.
Apple charts, editorial descriptions and motion artwork are not included in this change.

API documentation: https://performance-partners.apple.com/search-api
