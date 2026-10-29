package com.opentune.data

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.MoodGenreSection
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import com.opentune.data.history.History
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.reco.HomeSeed
import com.opentune.data.reco.Recommendations
import com.opentune.data.releases.NewReleases
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.Recommender
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.sync.withLock

/** An album or playlist page, ready to show. */
@Serializable
data class Collection(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
    /**
     * Made by the signed-in account, so it can be edited; null when the page
     * didn't say. See [InnertubeParser.parsePlaylistOwned].
     */
    val owned: Boolean? = null,
    /** Who can see it, when the account owns it and the page said. */
    val privacy: PlaylistPrivacy? = null,
)

/**
 * One of the account's own playlists as it stands on YouTube Music, read to
 * bring a linked playlist up to date: every row, unfiltered, each with the
 * set-video-id that removing or moving it takes.
 */
class RemotePlaylist(val title: String, val privacy: PlaylistPrivacy?, val songs: List<Song>)

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
    private val _seeds = MutableStateFlow<List<HomeSeed.Artist>>(emptyList())

    /**
     * The followed artists' songs Home seeds itself from while history is
     * short (see [HomeSeed]); Home makes mixes of them. Empty otherwise.
     */
    val seeds: StateFlow<List<HomeSeed.Artist>> = _seeds.asStateFlow()
    private var homeMoreJob: Job? = null
    private var libraryWarmJob: Job? = null

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

    private val homeRefreshRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Keep visible content until refreshed recommendations arrive. */
    fun refreshHomeRecommendations() {
        homeRefreshRequested.set(true)
        homeRefreshes.incrementAndGet()
    }

    /** Picks made ahead by [refreshPicks], for the reload that follows to show the same ones. */
    @Volatile private var nextPicks: kotlinx.coroutines.Deferred<List<HomeShelf>>? = null

    /**
     * Home pulled down: new picks off the next recent song, straight away, without
     * waiting for the rest of the page. The reload that follows takes these same picks.
     */
    fun refreshPicks(): kotlinx.coroutines.Deferred<List<HomeShelf>> {
        refreshHomeRecommendations()
        val engine = AppSettings.playback.value.recommender
        return scope.async {
            try { radioShelf(engine) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }.also { nextPicks = it }
    }

    /** How many times Home has been refreshed: each refresh seeds its picks from a different recent song. */
    private val homeRefreshes = java.util.concurrent.atomic.AtomicInteger(0)
    /**
     * Mixed into the picks' shuffle, different each time the app starts: with the refresh
     * count alone, every launch drew the same songs in the same order off the same last song.
     */
    private val launchShuffle = kotlin.random.Random.nextInt(1, Int.MAX_VALUE / 2)
    /** The songs the last picks shelf showed, so a refresh brings new ones forward. */
    @Volatile private var lastPicks: Set<String> = emptySet()

    suspend fun home(): List<HomeShelf> = io { coroutineScope {
        if (homeRefreshRequested.getAndSet(false)) {
            // Join before invalidating, so an older load cannot repopulate stale caches.
            homeMoreJob?.cancelAndJoin()
            extrasCache.clear()
            seedCache = null
        }
        // Resolve personal picks alongside the feed so the page publishes one set of picks,
        // rather than briefly restoring the old radio shelf before replacing it later.
        val ahead = nextPicks.also { nextPicks = null }
        val personal = async {
            ahead?.let { kotlinx.coroutines.withTimeoutOrNull(5_000) { it.await() } }?.takeIf { it.isNotEmpty() }
                ?.also { extrasCache[HomeSource.RADIO] = Fetched(it, System.currentTimeMillis()) }
                ?: kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    try { extraShelves(HomeSource.RADIO) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }.orEmpty()
        }
        val response = Innertube.browse("FEmusic_home")
        val first = personal.await() + InnertubeParser.parseHome(response).filter { it.items.isNotEmpty() }

        // In the exported log, so a Home that looks wrong can be compared with what YouTube sent.
        DebugLog.d("Home", "first page: " + first.joinToString { "${it.title} (${it.items.size}${if (it.personal) ", for the account" else ""})" })
        homeMoreJob?.cancel()
        homeMoreJob = scope.launch { followHome(first, InnertubeParser.sectionListContinuation(response)) }
        first
    } }

    /**
     * Fills in the rest of Home while its first page is on screen.
     *
     * YouTube only pages Home further for a signed-in account; signed out,
     * the first page is all it sends, about three shelves. So besides those
     * pages, Home takes shelves from other places: while history is short,
     * songs from the artists followed (the welcome's picks); a radio off the last song
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
    private enum class HomeSource { SEED, RADIO, LISTENBRAINZ, ARTISTS, NEW_RELEASES, CHARTS, EXPLORE, MOODS }

    private class Fetched(val shelves: List<HomeShelf>, val at: Long, val engine: Recommender = AppSettings.playback.value.recommender)
    private val extrasCache = java.util.concurrent.ConcurrentHashMap<HomeSource, Fetched>()

    /**
     * One source's shelves; reused for [EXTRAS_FRESH_MS] so opening Home
     * again doesn't fetch twenty pages. A source that fails just adds nothing.
     */
    private suspend fun extraShelves(source: HomeSource): List<HomeShelf> {
        // Signed-in Home already supplies the account's interests. Do not bury them
        // under generic charts and rotating moods from the guest feed.
        if (com.opentune.data.account.AccountStore.signedIn.value &&
            source in setOf(HomeSource.CHARTS, HomeSource.EXPLORE, HomeSource.MOODS, HomeSource.NEW_RELEASES)) return emptyList()
        if (History.records.value.isNotEmpty() && source in setOf(HomeSource.CHARTS, HomeSource.EXPLORE)) return emptyList()
        val now = System.currentTimeMillis()
        val engine = AppSettings.playback.value.recommender
        val cached = extrasCache[source]?.takeIf {
            source != HomeSource.RADIO || it.engine == engine
        }
        // The seed follows whoever is followed now, so it keeps its own cache.
        if (source != HomeSource.SEED) cached?.takeIf { now - it.at < EXTRAS_FRESH_MS }?.let { return it.shelves }
        val shelves = try {
            when (source) {
                HomeSource.SEED -> seedShelves()
                HomeSource.RADIO -> radioShelf(engine)
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
            return cached?.shelves.orEmpty()
        }
        extrasCache[source] = Fetched(shelves, now, engine)
        return shelves
    }

    private suspend fun shelvesOf(browseId: String): List<HomeShelf> {
        val response = Innertube.browse(browseId)
        return InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) }
            .filter { it.items.isNotEmpty() }
    }

    private var seedCache: Pair<List<String>, Fetched>? = null

    /**
     * "Because you like …": while history is short, the top songs of the
     * artists followed most recently and YouTube Music's radio off each,
     * so a new install's Home starts from the artists picked in the
     * welcome. The same songs make Home's seeded mixes ([seeds]).
     */
    private suspend fun seedShelves(): List<HomeShelf> = coroutineScope {
        val artists = HomeSeed.artists(NewReleases.followed.value, History.records.value.size)
        val ids = artists.map { it.browseId }
        val now = System.currentTimeMillis()
        val cached = seedCache?.takeIf { it.first == ids && now - it.second.at < EXTRAS_FRESH_MS }
        if (cached != null) return@coroutineScope cached.second.shelves
        val seeds = artists.map { a ->
            async {
                try {
                    val page = InnertubeParser.parseArtistPage(Innertube.browse(a.browseId))
                    val top = ContentFilter.songs(page.songs)
                    val radio = top.firstOrNull()?.let { runCatching { Recommendations.youtube(it.videoId) }.getOrNull() }.orEmpty()
                    HomeSeed.Artist(a.browseId, page.name ?: a.name, top, radio)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.w("MusicRepository", "Couldn't seed Home from ${a.name}", e)
                    null
                }
            }
        }.awaitAll().filterNotNull()
        _seeds.value = seeds
        val shelves = listOfNotNull(HomeSeed.quickPicks(seeds, launchShuffle + homeRefreshes.get()))
        seedCache = ids to Fetched(shelves, now)
        shelves
    }

    /**
     * "Because you played …": what the chosen recommendation engine (YouTube
     * Music, Spotify or JioSaavn) suggests after the last song heard.
     */
    private suspend fun radioShelf(engine: Recommender): List<HomeShelf> {
        // A hidden song or artist isn't a seed for more of the same.
        val hidden = com.opentune.data.library.LibraryStore.hidden.value
        // Each refresh turns to the next of the last few songs heard, so pulling
        // Home down brings different picks, not the same radio again.
        val recent = History.records.value.asSequence()
            .filter { !hidden.hides(it.videoId, it.artist) }
            .distinctBy { it.videoId }
            .take(RECENT_SEEDS)
            .toList()
        if (recent.isEmpty()) return emptyList()
        val turn = homeRefreshes.get()
        val last = recent[turn % recent.size]
        val result = Recommendations.after(last.toSong(), engine)
        val heard = recent.mapTo(HashSet()) { it.videoId }
        val fresh = result.songs.filter { it.videoId != last.videoId && it.videoId !in heard }
        // What the last shelf showed goes to the back, and the order changes with each refresh.
        val (seen, unseen) = fresh.partition { it.videoId in lastPicks }
        val songs = if (engine == Recommender.SPOTIFY) fresh.take(20)
            else (unseen.shuffled(kotlin.random.Random(launchShuffle + turn)) + seen).take(20)
        if (songs.isEmpty() || (engine != Recommender.SPOTIFY && songs.size < 4)) return emptyList()
        lastPicks = songs.mapTo(HashSet()) { it.videoId }
        val items = songs.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        val by = if (result.engine == Recommender.YOUTUBE) "Picked for you" else "Picked by ${result.engine.label}"
        return listOf(HomeShelf("Because you played ${last.title}", items, by))
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
            .let { com.opentune.data.library.LibraryStore.hidden.value.songs(it) }
        if (songs.size < 4) return@coroutineScope emptyList()
        val items = songs.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        listOf(HomeShelf("Recommended by ListenBrainz", items, "From your listens"))
    }

    /** Albums, singles and look-alikes for the two artists played most this month. */
    private suspend fun artistShelves(): List<HomeShelf> = coroutineScope {
        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        // A hidden artist gets no shelves of their own.
        val hidden = com.opentune.data.library.LibraryStore.hidden.value
        val top = History.replay(History.records.value, since).topArtists.filterNot { hidden.hidesArtist(it.title) }.take(2)
        val personal = personalArtists()
        val topShelf = if (personal.isEmpty()) emptyList() else listOf(HomeShelf("Your top artists", personal.map {
            ShelfItem(it.name, "From your listening", it.thumbnailUrl, null, it.browseId)
        }))
        topShelf + top.map { entry ->
            async {
              try {
                val song = entry.song ?: return@async emptyList()
                val id = artistIdFor(song) ?: return@async emptyList()
                val page = personalArtistPage(id)
                val name = page.name ?: entry.title
                page.sections
                    // Cards that open a page: albums, singles, playlists, artists.
                    .filter { shelf -> shelf.items.isNotEmpty() && shelf.items.all { it.browseId != null } }
                    .take(3)
                    .map { it.copy(subtitle = "Because you like $name") }
              } catch (e: CancellationException) { throw e }
              catch (_: Exception) { emptyList<HomeShelf>() }
            }
        }.awaitAll().flatten()
    }

    private val personalArtistPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ArtistPage>>()
    private val personalArtistLocks = Array(8) { kotlinx.coroutines.sync.Mutex() }
    private suspend fun personalArtistPage(id: String): ArtistPage {
        val key = id + ":" + java.util.Locale.getDefault().language
        return personalArtistLocks[key.hashCode().ushr(1) % personalArtistLocks.size].withLock {
            val now = System.currentTimeMillis()
            personalArtistPages[key]?.takeIf { now - it.first < 6 * 60 * 60_000L }?.let { return@withLock it.second }
            val page = InnertubeParser.parseArtistPage(Innertube.browse(id))
            if (personalArtistPages.size >= 64) personalArtistPages.clear()
            personalArtistPages[key] = now to page
            page
        }
    }

    private val personalArtistIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A representative song is retained even in artist aggregates and old history is resolved lazily. */
    suspend fun personalArtists(): List<ExploreFeed.Artist> = coroutineScope {
        val hidden = com.opentune.data.library.LibraryStore.hidden.value
        val since = System.currentTimeMillis() - 90L * 86_400_000
        val slots = kotlinx.coroutines.sync.Semaphore(3)
        History.replay(History.records.value, since).topArtists
            .filterNot { hidden.hidesArtist(it.title) }.take(12).map { entry -> async {
                slots.acquire()
                try {
                    val song = entry.song ?: return@async null
                    val id = personalArtistIds[entry.key] ?: artistIdFor(song)?.also {
                        if (personalArtistIds.size > 100) personalArtistIds.clear()
                        personalArtistIds[entry.key] = it
                    } ?: return@async null
                    val portrait = try { personalArtistPage(id).thumbnailUrl } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    ExploreFeed.Artist(id, entry.title, portrait)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                finally { slots.release() }
            } }.awaitAll().filterNotNull().distinctBy { it.browseId }
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
    /** How many recent songs Home's picks take turns to start from. */
    private const val RECENT_SEEDS = 6
    private const val MAX_PICKS = 16

    /**
     * Popular artists for [country] (an ISO code, or null), from the
     * charts' "Top artists"; the default charts' when that country's have none.
     */
    suspend fun chartArtists(country: String?): List<ExploreFeed.Artist> = io {
        suspend fun of(c: String?) = ExploreFeed.artistsOf(InnertubeParser.parseHome(Innertube.charts(c)))
        val local = country?.let {
            try { of(it) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }.orEmpty()
        local.ifEmpty { of(null) }
    }

    suspend fun moodsAndGenres(): List<MoodGenreSection> = io {
        InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
    }

    /** A mood or genre page is laid out like Home: carousels of playlists. */
    suspend fun shelves(browseId: String, params: String?): List<HomeShelf> = io {
        val response = Innertube.browse(browseId, params)
        ContentFilter.shelves(
            InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) }
                .filter { it.items.isNotEmpty() },
        )
    }

    suspend fun search(query: String, filter: SearchFilter): List<SearchResult> = io {
        val response = Innertube.search(query, filter.params)
        ContentFilter.results(InnertubeParser.parseSearchPage(response, includeVideos = filter == SearchFilter.VIDEOS).rows)
    }

    suspend fun suggestions(input: String): List<String> = io {
        InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input))
    }

    suspend fun collection(browseId: String, onPage: ((Collection) -> Unit)? = null): Collection = io {
        val owner = com.opentune.data.library.LibraryCache.owner()
        val mixId = browseId.removePrefix("VL").takeIf { it.startsWith("RD") }
        val response = try {
            Innertube.browse(browseId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A mix whose page is refused still plays: its songs come as the player's queue.
            mixId?.let { id -> mixCollection(browseId, id)?.let { return@io it } }
            throw e
        }
        val header = InnertubeParser.parseBrowseHeader(response)
        val page = InnertubeParser.parsePlaylistShelf(response)
        // A song on two pages of the playlist, as on one, shows once.
        val songs = (page?.songs?.takeIf { it.isNotEmpty() } ?: InnertubeParser.collectSongsDeep(response)).distinctBy { it.videoId }.toMutableList()
        // Signed out, no page has a save button, which would read as "owned".
        val owned = if (Innertube.cookie != null && InnertubeParser.isEditablePlaylist(browseId)) {
            InnertubeParser.parsePlaylistOwned(response)
        } else {
            false
        }
        fun snapshot() = Collection(
            browseId = browseId,
            title = header?.title.orEmpty(),
            subtitle = header?.subtitle.orEmpty(),
            // Album rows carry no art of their own; the cover is the header's.
            thumbnailUrl = header?.thumbnailUrl,
            songs = ContentFilter.songs(songs.map { song -> if (song.thumbnailUrl == null) song.copy(thumbnailUrl = header?.thumbnailUrl) else song }),
            owned = owned,
            privacy = if (owned == true) InnertubeParser.parsePlaylistPrivacy(response) else null,
        )
        onPage?.invoke(snapshot())
        var token = page?.continuation
        val seen = HashSet<String>()
        while (token != null && seen.add(token) && seen.size <= 100) {
            val next = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                InnertubeParser.parsePlaylistContinuation(Innertube.browseContinuation(token!!))
            } ?: throw java.io.IOException("Playlist page timed out")
            val known = songs.mapTo(HashSet()) { it.videoId }
            songs += next.songs.filter { known.add(it.videoId) }
            onPage?.invoke(snapshot())
            token = next.continuation
        }
        snapshot().also { com.opentune.data.library.LibraryCache.save(it, owner) }
    }

    /** A mix read as the player's queue for it, or null when that has no songs either. */
    private suspend fun mixCollection(browseId: String, playlistId: String): Collection? {
        val response = Innertube.mixQueue(playlistId)
        val songs = ContentFilter.songs(InnertubeParser.parseWatchQueue(response))
        if (songs.isEmpty()) return null
        val title = InnertubeParser.mixTitle(response)
        return Collection(
            browseId = browseId,
            title = title.orEmpty().ifBlank { "Mix" },
            subtitle = "Mix · YouTube Music",
            thumbnailUrl = songs.first().thumbnailUrl,
            songs = songs,
            owned = false,
        )
    }

    /** The rest of a playlist's songs, page by page after the first. */
    private suspend fun laterPages(first: String?): List<Song> {
        val songs = mutableListOf<Song>()
        var token = first
        val seen = HashSet<String>()
        while (token != null && seen.add(token) && seen.size <= 100) {
            val next = InnertubeParser.parsePlaylistContinuation(Innertube.browseContinuation(token))
            songs += next.songs
            token = next.continuation
        }
        return songs
    }

    /**
     * An own playlist as YouTube Music has it now, for syncing. Unlike
     * [collection] nothing is filtered out, since a hidden row is still an
     * entry on the account; and a page with no playlist list on it is an
     * error rather than an empty playlist, so a cut-down answer can't read
     * as "every song was removed".
     */
    suspend fun remotePlaylist(playlistId: String): RemotePlaylist = io {
        val response = Innertube.browse("VL${playlistId.removePrefix("VL")}")
        val page = InnertubeParser.parsePlaylistShelf(response) ?: error("no playlist on the page")
        RemotePlaylist(
            title = InnertubeParser.parseBrowseHeader(response)?.title.orEmpty(),
            privacy = InnertubeParser.parsePlaylistPrivacy(response),
            songs = (page.songs + laterPages(page.continuation)).distinctBy { it.videoId },
        )
    }

    suspend fun artist(browseId: String): ArtistPage = io {
        ContentFilter.artist(InnertubeParser.parseArtistPage(Innertube.browse(browseId)))
    }

    /**
     * Everything behind an artist row's "Show all" ([HomeShelf.moreBrowseId]
     * and its params). A long discography comes in pages; a few are followed,
     * which covers all but the very largest.
     */
    suspend fun artistItems(browseId: String, params: String?): List<ShelfItem> = io {
        val first = InnertubeParser.parseShelfItemsPage(Innertube.browse(browseId, params))
        val items = first.items.toMutableList()
        var token = first.continuation
        val seen = HashSet<String>()
        while (token != null && seen.add(token) && seen.size <= 10) {
            val next = InnertubeParser.parseShelfItemsPage(Innertube.browseContinuation(token))
            items += next.items
            token = next.continuation
        }
        val hide = ContentFilter.hideExplicit
        items.distinctBy { it.browseId }.filterNot { hide && it.explicit }
    }

    /** The signed-in account's playlists on YouTube Music, without the "New playlist" tile. */
    suspend fun libraryPlaylists(): List<ShelfItem> = io {
        val owner = com.opentune.data.library.LibraryCache.owner()
        InnertubeParser.parseLibraryItems(Innertube.browse("FEmusic_liked_playlists")).filter { it.browseId != null }
            .also { items ->
                com.opentune.data.library.LibraryCache.savePlaylists(items, owner)
                // Fill saved playlists one at a time, without blocking the Library screen.
                libraryWarmJob?.cancel()
                libraryWarmJob = scope.launch {
                    for (item in items) {
                        val id = item.browseId ?: continue
                        if (com.opentune.data.library.LibraryCache.owner() != owner) break
                        if (!com.opentune.data.library.LibraryCache.fresh(id, owner)) {
                            try { collection(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        }
                    }
                }
            }
    }

    /**
     * The song and the radio YouTube Music queues after it; the song itself
     * is always kept, even when hidden, since it was asked for.
     */
    suspend fun watchQueue(videoId: String): List<Song> = io {
        val queue = InnertubeParser.parseWatchQueue(Innertube.next(videoId))
        queue.filter { it.videoId == videoId } +
            com.opentune.data.library.LibraryStore.hidden.value.songs(ContentFilter.songs(queue.filter { it.videoId != videoId }))
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

    /**
     * The page of one of [song]'s artists by [name], when a song has several:
     * the row's own artist id for the first name, else YouTube Music's artist
     * search, taking the result whose name matches before the top one.
     */
    suspend fun artistIdFor(song: Song, name: String): String? {
        val first = song.artist.split(", ", " & ").first().trim()
        if (name.equals(first, ignoreCase = true)) song.artistId?.let { return it }
        val key = name.trim().lowercase()
        synchronized(artistIds) { artistIds[key] }?.let { return it }
        // A search can fail on a slow network: tried twice, then, for the first artist, the player's own queue.
        val found = io {
            repeat(2) {
                runCatching {
                    val found = InnertubeParser.parseSearchPage(Innertube.search(name, SearchFilter.ARTISTS.params)).rows
                        .mapNotNull { (it as? SearchResult.Browse)?.item?.takeIf { b -> b.type == BrowseType.ARTIST } }
                    (found.firstOrNull { it.title.equals(name, ignoreCase = true) } ?: found.firstOrNull())?.browseId
                }.getOrNull()?.let { return@io it }
            }
            null
        } ?: if (name.equals(first, ignoreCase = true)) artistIdFor(song) else null
        if (found != null) synchronized(artistIds) { artistIds[key] = found }
        return found
    }

    /** Artist pages found by name, so a second tap on the same name opens at once. */
    private val artistIds = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
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
