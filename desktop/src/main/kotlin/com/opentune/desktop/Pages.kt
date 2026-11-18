package com.opentune.desktop

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.podcasts.PodcastParser
import kotlinx.coroutines.async

/** What the desktop pages show, from YouTube Music through the shared client. */
object Pages {
    data class Shelf(val title: String, val items: List<ShelfItem>)

    /** A page of Home's rows, and the token for the next page, null at the end. */
    data class HomePage(val shelves: List<Shelf>, val next: String?)

    /** Home's first rows, each card a song, album, playlist or artist. */
    suspend fun home(): HomePage {
        val page = Innertube.browse("FEmusic_home")
        return HomePage(shelves(InnertubeParser.parseHome(page)), InnertubeParser.sectionListContinuation(page))
    }

    /** Home's next rows, as the phone loads them while you scroll. */
    suspend fun homeMore(token: String): HomePage {
        val page = Innertube.browseContinuation(token)
        return HomePage(shelves(InnertubeParser.parseHomeContinuation(page)), InnertubeParser.sectionListContinuation(page))
    }

    private fun shelves(rows: List<com.opentune.data.model.HomeShelf>): List<Shelf> = rows
        // A genre's own card (FEmusic_…) needs the parameters a card doesn't carry, so it can't be opened from here.
        .map { Shelf(it.title, it.items.filter { i -> i.videoId != null || (i.browseId != null && !i.browseId!!.startsWith("FEmusic_")) }) }
        .filter { it.items.isNotEmpty() }

    /** Moods and genres, for the chips at the top of Home; read once a run. */
    @Volatile private var moodCache: List<com.opentune.data.model.MoodGenre>? = null

    suspend fun moods(): List<com.opentune.data.model.MoodGenre> = moodCache ?: InnertubeParser
        .parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
        .flatMap { it.items }.distinctBy { it.title }.take(24)
        .also { moodCache = it }

    /** A page of shelves: a mood or genre, New releases, Charts. */
    suspend fun shelves(browseId: String, params: String? = null): List<Shelf> {
        val response = Innertube.browse(browseId, params)
        return shelves(InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) })
    }

    /** What Home carries on with once YouTube's own rows run out, as the phone's Home does. */
    suspend fun moreForHome(): List<Shelf> =
        listOf("FEmusic_new_releases", "FEmusic_charts").flatMap { id -> runCatching { shelves(id) }.getOrDefault(emptyList()) }

    /** A song as a Home card, for the shelves made from your own library. */
    internal fun cardOf(song: Song): ShelfItem = ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song.isExplicit == true)

    /**
     * Songs for [query]: YouTube Music's songs-only search, as the phone asks
     * it, and its mixed results when that finds none.
     */
    suspend fun search(query: String): List<Song> =
        songsIn(InnertubeParser.parseSearchPage(Innertube.search(query, SearchFilter.SONGS.params)).rows)
            .ifEmpty { songsIn(InnertubeParser.parseSearchPage(Innertube.search(query)).rows) }

    /** A search's answer by kind, as the web player shows it: songs first, then each kind on a row of its own. */
    data class Found(
        val songs: List<Song>,
        val artists: List<ShelfItem>,
        val albums: List<ShelfItem>,
        val playlists: List<ShelfItem>,
        val videos: List<ShelfItem>,
    ) {
        val isEmpty get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && videos.isEmpty()
    }

    /**
     * Everything [query] finds: songs, artists, albums, playlists and videos, each from
     * YouTube Music's own filtered search, asked at once. A kind that fails is left out;
     * only when every one fails does the search fail.
     */
    suspend fun searchAll(query: String): Found = kotlinx.coroutines.coroutineScope {
        fun browse(filter: SearchFilter): kotlinx.coroutines.Deferred<Result<List<ShelfItem>>> = async {
            runCatching {
                InnertubeParser.parseSearchPage(Innertube.search(query, filter.params)).rows
                    .filterIsInstance<SearchResult.Browse>().map { it.item }
                    .map { ShelfItem(it.title, it.subtitle, it.thumbnailUrl, null, it.browseId, it.explicit) }
                    .distinctBy { it.browseId }.take(20)
            }
        }
        val songs = async { runCatching { search(query) } }
        val artists = browse(SearchFilter.ARTISTS)
        val albums = browse(SearchFilter.ALBUMS)
        val playlists = browse(SearchFilter.PLAYLISTS)
        val videos = async {
            runCatching {
                songsIn(InnertubeParser.parseSearchPage(Innertube.search(query, SearchFilter.VIDEOS.params), includeVideos = true).rows)
                    .take(20).map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null, it.isExplicit == true) }
            }
        }
        val all: List<Result<List<Any>>> = listOf(songs.await(), artists.await(), albums.await(), playlists.await(), videos.await())
        all.firstOrNull { it.isSuccess }
            ?: throw (all.first().exceptionOrNull() ?: IllegalStateException("search failed"))
        Found(
            songs.await().getOrDefault(emptyList()),
            artists.await().getOrDefault(emptyList()),
            albums.await().getOrDefault(emptyList()),
            playlists.await().getOrDefault(emptyList()),
            videos.await().getOrDefault(emptyList()),
        )
    }

    /** The signed-in account's own library on YouTube Music: its playlists, albums and artists, each a row. */
    suspend fun accountLibrary(): List<Shelf> = kotlinx.coroutines.coroutineScope {
        listOf("Your YouTube Music playlists" to "FEmusic_liked_playlists", "Saved albums" to "FEmusic_liked_albums", "Artists you follow" to "FEmusic_library_corpus_artists")
            .map { (title, id) ->
                async {
                    runCatching { InnertubeParser.parseLibraryItems(Innertube.browse(id)) }.getOrDefault(emptyList())
                        // The "New playlist" tile has nothing to open.
                        .filter { it.browseId != null || it.videoId != null }
                        .let { Shelf(title, it) }
                }
            }.map { it.await() }.filter { it.items.isNotEmpty() }
    }

    /** What YouTube Music suggests for what's typed so far. */
    suspend fun suggestions(input: String): List<String> =
        InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input)).distinct().take(8)

    /** The songs behind a card: an album or playlist's tracks, an artist's top songs, a podcast's episodes. */
    suspend fun songsOf(browseId: String): List<Song> {
        val page = Innertube.browse(browseId)
        if (browseId.startsWith("UC")) return InnertubeParser.parseArtistPage(page).songs
        if (browseId.startsWith("MPSP")) return PodcastParser.parseShow(browseId, page).episodes.map { it.toSong() }
        return InnertubeParser.parsePlaylistShelf(page)?.songs?.takeIf { it.isNotEmpty() }
            ?: InnertubeParser.collectSongsDeep(page)
    }

    /** A card that plays on its own, as a song. */
    internal fun songOf(item: ShelfItem): Song? = item.videoId?.let { id ->
        Song(id, item.title, artistIn(item.subtitle), item.thumbnailUrl, isExplicit = item.explicit)
    }

    /** The artist in a card's grey line, such as "Song • Artist • 2.1M plays". */
    internal fun artistIn(subtitle: String): String =
        subtitle.split(" • ").map { it.trim() }.firstOrNull { it.isNotEmpty() && it !in KINDS && !it.endsWith("plays") && !it.endsWith("views") }
            ?: subtitle

    private val KINDS = setOf("Song", "Video", "Single", "Album", "EP", "Playlist")

    internal fun songsIn(rows: List<SearchResult>): List<Song> = rows.mapNotNull {
        when (it) {
            is SearchResult.TopTrack -> it.song
            is SearchResult.Track -> it.song
            is SearchResult.Browse -> null
        }
    }.distinctBy { it.videoId }
}
