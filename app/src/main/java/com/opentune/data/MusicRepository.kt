package com.opentune.data

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.MoodGenreSection
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import com.opentune.data.history.History
import com.opentune.data.listenbrainz.ListenBrainz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext

/** An album or playlist page, ready to show. */
data class Collection(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
)

/**
 * The screens' one way into Innertube: every call runs off the main thread
 * and returns parsed models.
 */
object MusicRepository {
    private var homeFile: File? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What Home showed last time, kept on disk so the app opens on it. */
    @Serializable
    private class SavedHome(val first: List<HomeShelf>, val more: List<HomeShelf> = emptyList())

    private val _homeMore = MutableStateFlow<List<HomeShelf>>(emptyList())

    /**
     * Home's shelves past the first page. YouTube Music sends Home a few
     * shelves at a time; [home] returns the first batch at once and the rest
     * arrive here, page by page, while the first is already on screen.
     */
    val homeMore: StateFlow<List<HomeShelf>> = _homeMore.asStateFlow()
    private var homeMoreJob: Job? = null

    /**
     * Remembers where Home is kept, and returns the first page saved last
     * time, so the app can open on it while the fresh one loads.
     */
    fun init(context: Context): List<HomeShelf>? {
        val f = File(context.filesDir, "home.json")
        homeFile = f
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val saved = runCatching { json.decodeFromString(SavedHome.serializer(), text) }.getOrNull()
            // Copies saved before Home had more pages were a bare list.
            ?: runCatching { SavedHome(json.decodeFromString(ListSerializer(HomeShelf.serializer()), text)) }.getOrNull()
            ?: return null
        _homeMore.value = saved.more
        return saved.first
    }

    suspend fun home(): List<HomeShelf> = io {
        val response = Innertube.browse("FEmusic_home")
        val first = InnertubeParser.parseHome(response).filter { it.items.isNotEmpty() }
        homeMoreJob?.cancel()
        homeMoreJob = scope.launch { followHome(first, InnertubeParser.sectionListContinuation(response)) }
        first
    }

    /**
     * Fills in the rest of Home while its first page is on screen.
     *
     * YouTube only pages Home further for a signed-in account; signed out,
     * the first page is all it sends, about three shelves. So besides those
     * pages, Home takes shelves from other places: a radio off the last song
     * played, albums, singles and similar artists for the artists played
     * most, new releases, the charts, Explore, and playlists for a couple of
     * moods and genres. They load side by side and are shown in that fixed
     * order as each one lands.
     *
     * The shelves from the last load stay up until the first new batch
     * arrives, so a refresh doesn't empty the bottom of the page.
     */
    private suspend fun followHome(first: List<HomeShelf>, token: String?) = coroutineScope {
        val lock = Any()
        val pages = mutableListOf<HomeShelf>()
        val extras = arrayOfNulls<List<HomeShelf>>(HomeSource.entries.size)
        fun publish() = synchronized(lock) {
            val seen = first.mapTo(HashSet()) { it.dedupeKey() }
            _homeMore.value = (pages + extras.filterNotNull().flatten())
                .filter { it.items.isNotEmpty() && seen.add(it.dedupeKey()) }
        }
        launch {
            var next = token
            var count = 0
            while (next != null && count < MAX_HOME_PAGES) {
                val response = try {
                    Innertube.browseContinuation(next)
                } catch (e: Exception) {
                    ensureActive()
                    DebugLog.w("MusicRepository", "Home page ${count + 2} failed", e)
                    break
                }
                ensureActive()
                synchronized(lock) { pages += InnertubeParser.parseHomeContinuation(response) }
                publish()
                next = InnertubeParser.sectionListContinuation(response)
                count++
            }
        }
        HomeSource.entries.forEach { source ->
            launch {
                extras[source.ordinal] = extraShelves(source)
                publish()
            }
        }
    }.also {
        saveHome(SavedHome(first, _homeMore.value))
    }

    /** Where Home's extra shelves come from, in the order they're shown. */
    private enum class HomeSource { RADIO, LISTENBRAINZ, ARTISTS, NEW_RELEASES, CHARTS, EXPLORE, MOODS }

    private class Fetched(val shelves: List<HomeShelf>, val at: Long)
    private val extrasCache = java.util.concurrent.ConcurrentHashMap<HomeSource, Fetched>()

    /**
     * One source's shelves; reused for [EXTRAS_FRESH_MS] so opening Home
     * again doesn't fetch twenty pages. A source that fails just adds nothing.
     */
    private suspend fun extraShelves(source: HomeSource): List<HomeShelf> {
        val now = System.currentTimeMillis()
        extrasCache[source]?.takeIf { now - it.at < EXTRAS_FRESH_MS }?.let { return it.shelves }
        val shelves = try {
            when (source) {
                HomeSource.RADIO -> radioShelf()
                HomeSource.LISTENBRAINZ -> listenBrainzShelf()
                HomeSource.ARTISTS -> artistShelves()
                HomeSource.NEW_RELEASES -> shelvesOf("FEmusic_new_releases")
                HomeSource.CHARTS -> shelvesOf("FEmusic_charts")
                HomeSource.EXPLORE -> shelvesOf("FEmusic_explore")
                HomeSource.MOODS -> moodShelves()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("MusicRepository", "Home's ${source.name.lowercase()} shelves failed", e)
            return extrasCache[source]?.shelves.orEmpty()
        }
        extrasCache[source] = Fetched(shelves, now)
        return shelves
    }

    private suspend fun shelvesOf(browseId: String): List<HomeShelf> {
        val response = Innertube.browse(browseId)
        return InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) }
            .filter { it.items.isNotEmpty() }
    }

    /** "Because you played …": the radio YouTube queues after the last song heard. */
    private suspend fun radioShelf(): List<HomeShelf> {
        val last = History.records.value.firstOrNull() ?: return emptyList()
        val songs = InnertubeParser.parseWatchQueue(Innertube.next(last.videoId))
            .filter { it.videoId != last.videoId }
            .take(20)
        if (songs.size < 4) return emptyList()
        val items = songs.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        return listOf(HomeShelf("Because you played ${last.title}", items, "Picked for you"))
    }

    /**
     * ListenBrainz's picks for the signed-in user, each found on YouTube
     * Music so it plays like any other song. Nothing until ListenBrainz has
     * enough listens to recommend from.
     */
    private suspend fun listenBrainzShelf(): List<HomeShelf> = coroutineScope {
        if (ListenBrainz.account.value == null) return@coroutineScope emptyList()
        val picks = ListenBrainz.recommendations(MAX_PICKS)
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val songs = picks.map { pick ->
            async {
                gate.acquire()
                try {
                    runCatching {
                        val response = Innertube.search("${pick.title} ${pick.artist}", SearchFilter.SONGS.params)
                        InnertubeParser.parseSearchPage(response, includeVideos = false).rows
                            .firstNotNullOfOrNull { (it as? SearchResult.Track)?.song }
                    }.getOrNull()
                } finally {
                    gate.release()
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.videoId }
        if (songs.size < 4) return@coroutineScope emptyList()
        val items = songs.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        listOf(HomeShelf("Recommended by ListenBrainz", items, "From your listens"))
    }

    /** Albums, singles and look-alikes for the two artists played most this month. */
    private suspend fun artistShelves(): List<HomeShelf> = coroutineScope {
        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val top = History.replay(History.records.value, since).topArtists.take(2)
        top.map { entry ->
            async {
                val song = entry.song ?: return@async emptyList()
                val id = artistIdFor(song) ?: return@async emptyList()
                val page = InnertubeParser.parseArtistPage(Innertube.browse(id))
                val name = page.name ?: entry.title
                page.sections
                    // Cards that open a page: albums, singles, playlists, artists.
                    .filter { shelf -> shelf.items.isNotEmpty() && shelf.items.all { it.browseId != null } }
                    .take(3)
                    .map { it.copy(subtitle = "Because you like $name") }
            }
        }.awaitAll().flatten()
    }

    /** Playlists for two moods or genres, a different pair each day. */
    private suspend fun moodShelves(): List<HomeShelf> = coroutineScope {
        val moods = InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
            .flatMap { it.items }
        if (moods.isEmpty()) return@coroutineScope emptyList()
        val day = (System.currentTimeMillis() / (24 * 60 * 60 * 1000)).toInt()
        listOf(day, day * 7 + 3).map { (it % moods.size + moods.size) % moods.size }.distinct().map { i ->
            async {
                val mood = moods[i]
                val response = Innertube.browse(mood.browseId, mood.params)
                InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) }
                    .filter { it.items.isNotEmpty() }
                    .take(2)
                    .map { it.copy(subtitle = mood.title) }
            }
        }.awaitAll().flatten()
    }

    /** Shelves from different artists or moods may share a title like "Albums". */
    private fun HomeShelf.dedupeKey() = "${subtitle.lowercase()}|${title.lowercase()}"

    private fun saveHome(saved: SavedHome) {
        val f = homeFile ?: return
        runCatching {
            val tmp = File(f.parentFile, "home.json.tmp")
            tmp.writeText(json.encodeToString(SavedHome.serializer(), saved))
            tmp.renameTo(f)
        }
    }

    private const val MAX_HOME_PAGES = 8
    private const val EXTRAS_FRESH_MS = 30 * 60 * 1000L
    private const val MAX_PICKS = 16

    suspend fun moodsAndGenres(): List<MoodGenreSection> = io {
        InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
    }

    /** A mood or genre page is laid out like Home: carousels of playlists. */
    suspend fun shelves(browseId: String, params: String?): List<HomeShelf> = io {
        val response = Innertube.browse(browseId, params)
        InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) }
            .filter { it.items.isNotEmpty() }
    }

    suspend fun search(query: String, filter: SearchFilter): List<SearchResult> = io {
        val response = Innertube.search(query, filter.params)
        InnertubeParser.parseSearchPage(response, includeVideos = filter == SearchFilter.VIDEOS).rows
    }

    suspend fun suggestions(input: String): List<String> = io {
        InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input))
    }

    suspend fun collection(browseId: String): Collection = io {
        val response = Innertube.browse(browseId)
        val header = InnertubeParser.parseBrowseHeader(response)
        val songs = InnertubeParser.parsePlaylistShelf(response)?.songs?.takeIf { it.isNotEmpty() }
            ?: InnertubeParser.collectSongsDeep(response)
        Collection(
            browseId = browseId,
            title = header?.title.orEmpty(),
            subtitle = header?.subtitle.orEmpty(),
            // Album rows carry no art of their own; the cover is the header's.
            thumbnailUrl = header?.thumbnailUrl,
            songs = songs.map { song -> if (song.thumbnailUrl == null) song.copy(thumbnailUrl = header?.thumbnailUrl) else song },
        )
    }

    suspend fun artist(browseId: String): ArtistPage = io {
        InnertubeParser.parseArtistPage(Innertube.browse(browseId))
    }

    /** The signed-in account's playlists on YouTube Music, without the "New playlist" tile. */
    suspend fun libraryPlaylists(): List<ShelfItem> = io {
        InnertubeParser.parseLibraryItems(Innertube.browse("FEmusic_liked_playlists")).filter { it.browseId != null }
    }

    /** The song and the radio YouTube Music queues after it. */
    suspend fun watchQueue(videoId: String): List<Song> = io {
        InnertubeParser.parseWatchQueue(Innertube.next(videoId))
    }

    /**
     * The artist page for [song]: its own id when the row carried one, else
     * the one YouTube Music's watch data credits, else the top artist result
     * for the name.
     */
    suspend fun artistIdFor(song: Song): String? = song.artistId ?: io {
        runCatching { InnertubeParser.parseWatchQueue(Innertube.next(song.videoId)).firstOrNull { it.videoId == song.videoId }?.artistId }
            .getOrNull()
            ?: runCatching {
                val name = song.artist.split(", ", " & ").first().trim()
                InnertubeParser.parseSearchPage(Innertube.search(name, SearchFilter.ARTISTS.params)).rows
                    .firstNotNullOfOrNull { (it as? SearchResult.Browse)?.item?.takeIf { b -> b.type == BrowseType.ARTIST }?.browseId }
            }.getOrNull()
    }

    /** What a browse id opens, read off its prefix. */
    fun typeOf(browseId: String): BrowseType = when {
        browseId.startsWith("UC") -> BrowseType.ARTIST
        browseId.startsWith("MPREb") -> BrowseType.ALBUM
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") ||
            browseId.startsWith("OLAK") || browseId.startsWith("MPSP") -> BrowseType.PLAYLIST
        else -> BrowseType.OTHER
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
