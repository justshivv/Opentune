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
    /** A row of cards; [subtitle] is YouTube's strapline, the grey line under its heading. */
    data class Shelf(val title: String, val items: List<ShelfItem>, val subtitle: String = "")

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
        .map { Shelf(it.title, it.items.filter { i -> i.videoId != null || (i.browseId != null && !i.browseId!!.startsWith("FEmusic_")) }, it.subtitle) }
        .filter { it.items.isNotEmpty() }

    /** Moods and genres, for Explore's tiles and Home's row of them; read once a run. */
    @Volatile private var moodCache: List<com.opentune.data.model.MoodGenre>? = null

    /** Each mood's own colour, as YouTube Music paints the stripe on its button, by the mood's name. */
    private val moodColours = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun moods(): List<com.opentune.data.model.MoodGenre> = moodCache ?: Innertube.browse("FEmusic_moods_and_genres").let { page ->
        runCatching { collectStripes(page) }
        InnertubeParser.parseMoodAndGenres(page).flatMap { it.items }.distinctBy { it.title }.take(24)
    }.also { moodCache = it }

    /** For pictures and tests: moods as if YouTube Music had sent them, with their colours and covers. */
    internal fun preloadMoods(moods: List<com.opentune.data.model.MoodGenre>, colours: Map<String, Long> = emptyMap(), covers: Map<String, String> = emptyMap()) {
        moodCache = moods
        moodColours.putAll(colours)
        moodCovers.putAll(covers)
    }

    /** The colour YouTube Music gives a mood's button, as 0xAARRGGBB, if it gave one. */
    fun moodColour(title: String): Long? = moodColours[title]

    /** Every navigation button's name and stripe colour in [json], wherever in the page it sits. */
    private fun collectStripes(json: kotlinx.serialization.json.JsonElement) {
        when (json) {
            is kotlinx.serialization.json.JsonArray -> json.forEach(::collectStripes)
            is kotlinx.serialization.json.JsonObject -> {
                (json["musicNavigationButtonRenderer"] as? kotlinx.serialization.json.JsonObject)?.let { button ->
                    val runs = ((button["buttonText"] as? kotlinx.serialization.json.JsonObject)?.get("runs") as? kotlinx.serialization.json.JsonArray)
                    val name = runs?.joinToString("") { ((it as? kotlinx.serialization.json.JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                    val stripe = ((button["solid"] as? kotlinx.serialization.json.JsonObject)?.get("leftStripeColor") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    if (!name.isNullOrBlank() && stripe != null) moodColours[name] = stripe or 0xFF000000L
                    return
                }
                json.values.forEach(::collectStripes)
            }
            else -> Unit
        }
    }

    /** A mood's picture: the cover of the first playlist on its page, fetched once a run. */
    private val moodCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun moodCover(mood: com.opentune.data.model.MoodGenre): String? {
        mood.thumbnailUrl?.let { return it }
        moodCovers[mood.title]?.let { return it.ifEmpty { null } }
        val found = runCatching { shelves(mood.browseId, mood.params).firstNotNullOfOrNull { s -> s.items.firstNotNullOfOrNull { it.thumbnailUrl } } }.getOrNull()
        moodCovers[mood.title] = found.orEmpty()
        return found
    }

    /** A page of shelves: a mood or genre, New releases, Charts. */
    suspend fun shelves(browseId: String, params: String? = null): List<Shelf> {
        val response = Innertube.browse(browseId, params)
        return shelves(InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) })
    }

    /** What Home carries on with once YouTube's own rows run out, as the phone's Home does. */
    suspend fun moreForHome(): List<Shelf> =
        listOf("FEmusic_new_releases", "FEmusic_charts").flatMap { id -> runCatching { shelves(id) }.getOrDefault(emptyList()) }

    /** A song as a Home card, for the shelves made from your own library. */
    internal fun cardOf(song: Song): ShelfItem =
        ShelfItem(song.title, listOf("Song", song.artist).filter { it.isNotBlank() }.joinToString(" • "), song.thumbnailUrl, song.videoId, null, song.isExplicit == true)

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

    /** What YouTube Music suggests for what's typed so far. */
    suspend fun suggestions(input: String): List<String> =
        InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input)).distinct().take(8)

    /** The songs behind a card: an album or playlist's tracks (every page of them), an artist's top songs, a podcast's episodes. */
    suspend fun songsOf(browseId: String): List<Song> {
        if (browseId.startsWith("VL")) return listing(browseId).songs
        val page = Innertube.browse(browseId)
        if (browseId.startsWith("UC")) return InnertubeParser.parseArtistPage(page).songs
        if (browseId.startsWith("MPSP")) return PodcastParser.parseShow(browseId, page).episodes.map { it.toSong() }
        return InnertubeParser.parsePlaylistShelf(page)?.songs?.takeIf { it.isNotEmpty() }
            ?: InnertubeParser.collectSongsDeep(page)
    }

    /**
     * An album or playlist as its page shows it: its songs, and for a playlist page whether the
     * signed-in account owns it (and so can rename it, change who sees it, take songs out or
     * delete it) and who can see it. [complete] is false while later pages are still coming.
     */
    data class Listing(
        val songs: List<Song>,
        val owned: Boolean = false,
        val privacy: com.opentune.data.model.PlaylistPrivacy? = null,
        val complete: Boolean = true,
        val at: Long = System.currentTimeMillis(),
    )

    /** Lists read lately, by browse id, so going back to one (or opening it again) shows it at once. */
    private val listings = object : LinkedHashMap<String, Listing>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>?) = size > 40
    }

    /** The list last read for [browseId], when it was read whole in the last [maxAgeMs]. */
    fun cachedListing(browseId: String, maxAgeMs: Long = 10 * 60_000L): Listing? = synchronized(listings) {
        listings[browseId]?.takeIf { it.complete && System.currentTimeMillis() - it.at <= maxAgeMs }
    }

    /** Drops what was read for [browseId], after it's changed. */
    fun forgetListing(browseId: String) {
        synchronized(listings) { listings.remove(browseId); listings.remove("VL" + browseId.removePrefix("VL")) }
    }

    /** Drops every list read, as the account changes. */
    fun forgetListings() {
        synchronized(listings) { listings.clear() }
    }

    /** Keeps [listing] as read, as after an edit made here. */
    fun keepListing(browseId: String, listing: Listing) {
        synchronized(listings) { listings[browseId] = listing }
    }

    /**
     * Reads [browseId]'s page and, for a playlist, every later page of its songs (a long one
     * comes 100 at a time), telling [onPage] the list so far after each.
     */
    suspend fun listing(browseId: String, onPage: (Listing) -> Unit = {}): Listing {
        if (!browseId.startsWith("VL")) return Listing(songsOf(browseId)).also { keepListing(browseId, it) }
        val page = Innertube.browse(browseId)
        val shelf = InnertubeParser.parsePlaylistShelf(page)
        val songs = LinkedHashMap<String, Song>()
        (shelf?.songs?.takeIf { it.isNotEmpty() } ?: InnertubeParser.collectSongsDeep(page)).forEach { songs.putIfAbsent(it.videoId, it) }
        val owned = Innertube.cookie != null && InnertubeParser.isEditablePlaylist(browseId) && InnertubeParser.parsePlaylistOwned(page) == true
        val privacy = if (owned) InnertubeParser.parsePlaylistPrivacy(page) else null
        fun now(complete: Boolean) = Listing(songs.values.toList(), owned, privacy, complete)
        var token = shelf?.continuation
        if (token != null) onPage(now(complete = false))
        val seen = HashSet<String>()
        while (token != null && seen.add(token) && seen.size <= 100) {
            val at = token
            val next = runCatching { InnertubeParser.parsePlaylistContinuation(Innertube.browseContinuation(at)) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull() ?: break
            val before = songs.size
            next.songs.forEach { songs.putIfAbsent(it.videoId, it) }
            if (songs.size == before) break
            onPage(now(complete = false))
            token = next.continuation
        }
        return now(complete = true).also { keepListing(browseId, it) }
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
