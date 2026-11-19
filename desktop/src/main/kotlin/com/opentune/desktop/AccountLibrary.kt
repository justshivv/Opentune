package com.opentune.desktop

import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The signed-in account's own library on YouTube Music, read once a sign-in and kept while the
 * app runs, so the Library's tabs and the sidebar show it at once: its playlists, saved albums,
 * the artists it follows, its liked songs and the songs it added to its library. Read off the
 * window's thread, page after page (a big library comes in as it's read), again whenever
 * [AccountStore] signs in, out or switches account, and on [refresh].
 *
 * What's changed here (a like, a new playlist) shows at once and is kept over a read that
 * started before YouTube Music had it.
 */
internal object AccountLibrary {
    data class State(
        val signedIn: Boolean = false,
        /** The first read is under way. */
        val loading: Boolean = false,
        /** Every part has been read at least once this sign-in. */
        val loaded: Boolean = false,
        /** The account's playlists, without the ones OpenTune never lists (see [shownPlaylists]). */
        val playlists: List<ShelfItem> = emptyList(),
        val albums: List<ShelfItem> = emptyList(),
        val artists: List<ShelfItem> = emptyList(),
        /** Liked Music, newest first. */
        val liked: List<Song> = emptyList(),
        /** Songs added to the library (YouTube Music's Library › Songs). */
        val saved: List<Song> = emptyList(),
        /** When the last full read finished, for [refreshIfStale]. */
        val readAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null
    private var reading: Job? = null
    /** Bumped on each sign-in or sign-out, so a read for an account no longer active is dropped. */
    @Volatile private var generation = 0

    /** Likes and unlikes made here lately, kept over a read that may not have them yet. */
    private val likeEdits = java.util.concurrent.ConcurrentHashMap<String, Pair<Song, Boolean>>()
    /** Playlists made or deleted here lately, for the same reason. */
    private val madeHere = java.util.concurrent.CopyOnWriteArrayList<ShelfItem>()
    private val deletedHere: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Follows the account from now on. Called once, after [AccountStore] is read. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (watching != null) return
        watching = scope.launch {
            combine(
                AccountStore.signedIn,
                AccountStore.accounts.map { list -> list.firstOrNull { it.active }?.id },
                AccountStore.listeningAs,
            ) { signedIn, id, profile -> Triple(signedIn, id, profile) }
                .distinctUntilChanged()
                // Signing in sets the session, then the account list: one read for both.
                .debounce(250)
                .collect { (signedIn, _, _) -> onSession(signedIn) }
        }
    }

    private fun onSession(signedIn: Boolean) {
        generation++
        reading?.cancel()
        likeEdits.clear(); madeHere.clear(); deletedHere.clear()
        Pages.forgetListings()
        _state.value = State(signedIn = signedIn, loading = signedIn)
        if (signedIn) read()
    }

    /** Reads it all again in the background; what's shown stays until the new answer comes. */
    fun refresh() {
        if (!_state.value.signedIn || reading?.isActive == true) return
        read()
    }

    /** [refresh] when the last read is older than [maxAgeMs]. */
    fun refreshIfStale(maxAgeMs: Long = 3 * 60_000L) {
        val s = _state.value
        if (s.signedIn && s.loaded && System.currentTimeMillis() - s.readAt > maxAgeMs) refresh()
    }

    private fun read() {
        // Only a session that signs requests has a library to read.
        if (Innertube.cookie == null) return
        val mine = generation
        // The first read shows a big list as it comes in; a later one swaps each list whole once
        // it's read, so what's on screen never shrinks to the first page and grows back.
        val firstRead = !_state.value.loaded
        reading = scope.launch {
            fun publish(change: (State) -> State) { if (generation == mine) _state.update(change) }
            coroutineScope {
                val playlists = async {
                    runCatching { playlists() }.onSuccess { list -> publish { it.copy(playlists = withEdits(list)) } }
                }
                val albums = async {
                    runCatching { items("FEmusic_liked_albums") }.onSuccess { list -> publish { it.copy(albums = list) } }
                }
                val artists = async {
                    runCatching { items("FEmusic_library_corpus_artists").filter { it.browseId?.startsWith("UC") == true } }
                        .onSuccess { list -> publish { it.copy(artists = list) } }
                }
                val liked = async {
                    runCatching { pagedSongs("VLLM") { so -> if (firstRead) publish { it.copy(liked = withLikeEdits(so)) } } }
                        .onSuccess { so -> publish { it.copy(liked = withLikeEdits(so)) } }
                }
                val saved = async {
                    runCatching { pagedSongs("FEmusic_liked_videos") { so -> if (firstRead) publish { it.copy(saved = so) } } }
                        .onSuccess { so -> publish { it.copy(saved = so) } }
                }
                listOf(playlists, albums, artists, liked, saved).forEach { d ->
                    d.await().exceptionOrNull()?.let { e ->
                        if (e is CancellationException) throw e
                        System.err.println("AccountLibrary: couldn't read part of the library: ${e.message}")
                    }
                }
            }
            publish { it.copy(loading = false, loaded = true, readAt = System.currentTimeMillis()) }
        }
    }

    // ---- Reading -----------------------------------------------------------------

    /** Every page of a library feed's cards (albums, artists). */
    private suspend fun items(browseId: String): List<ShelfItem> {
        val first = InnertubeParser.parseLibraryItemPage(Innertube.browse(browseId))
        val all = LinkedHashMap<String, ShelfItem>()
        first.items.forEach { i -> i.browseId?.let { all.putIfAbsent(it, i) } }
        var token = first.continuation
        var pages = 0
        while (token != null && pages++ < MAX_PAGES) {
            val at = token
            val page = runCatching { InnertubeParser.parseLibraryItemPage(Innertube.browseContinuation(at)) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull() ?: break
            val before = all.size
            page.items.forEach { i -> i.browseId?.let { all.putIfAbsent(it, i) } }
            if (all.size == before || page.continuation == token) break
            token = page.continuation
        }
        return all.values.toList()
    }

    /** The account's playlists, every page, as the phone reads them, without the ones never listed. */
    private suspend fun playlists(): List<ShelfItem> {
        val list = items("FEmusic_liked_playlists").ifEmpty {
            InnertubeParser.looseLibraryPlaylists(Innertube.browse("FEmusic_liked_playlists"))
        }
        return shownPlaylists(list)
    }

    /**
     * A list of songs read page by page, [onPage] told the whole list so far after each. A
     * playlist page (Liked Music) is read by its playlist shelf so its suggestions stay out;
     * Library › Songs by its rows.
     */
    private suspend fun pagedSongs(browseId: String, onPage: (List<Song>) -> Unit): List<Song> {
        val first = Innertube.browse(browseId)
        val shelf = InnertubeParser.parsePlaylistShelf(first)
        val songs = LinkedHashMap<String, Song>()
        (shelf?.songs?.takeIf { it.isNotEmpty() } ?: InnertubeParser.collectSongsDeep(first)).forEach { songs.putIfAbsent(it.videoId, it) }
        onPage(songs.values.toList())
        var token = if (shelf != null) shelf.continuation else InnertubeParser.continuationToken(first)
        val seen = HashSet<String>()
        while (token != null && seen.add(token) && seen.size <= MAX_PAGES) {
            val at = token
            val page = runCatching { Innertube.browseContinuation(at) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull() ?: break
            val next = InnertubeParser.parsePlaylistContinuation(page)
            val more = next.songs.ifEmpty { InnertubeParser.collectSongsDeep(page) }
            if (more.isEmpty()) break
            val before = songs.size
            more.forEach { songs.putIfAbsent(it.videoId, it) }
            if (songs.size == before) break
            onPage(songs.values.toList())
            token = next.continuation ?: InnertubeParser.continuationToken(page)
        }
        return songs.values.toList()
    }

    // ---- Changes made here --------------------------------------------------------

    /**
     * Sends a like or unlike to YouTube Music, as the phone and the web player do, and shows it
     * in the account's Liked songs at once; taken back if YouTube Music refuses it.
     */
    fun sendLike(song: Song, liked: Boolean) {
        if (!_state.value.signedIn || !com.opentune.data.isYouTubeId(song.videoId)) return
        val mine = generation
        val before = _state.value.liked.any { it.videoId == song.videoId }
        likeEdits[song.videoId] = song to liked
        _state.update { it.copy(liked = applyLike(it.liked, song, liked)) }
        scope.launch {
            runCatching {
                Innertube.rate(song.videoId, if (liked) com.opentune.data.model.LikeStatus.LIKE else com.opentune.data.model.LikeStatus.INDIFFERENT)
            }.onFailure {
                System.err.println("AccountLibrary: couldn't send the like to YouTube Music: ${it.message}")
                if (generation == mine && likeEdits[song.videoId]?.second == liked) {
                    likeEdits.remove(song.videoId)
                    _state.update { s -> s.copy(liked = applyLike(s.liked, song, before)) }
                }
                Notice.show("Couldn't update Liked songs on YouTube Music")
            }
        }
    }

    private fun withLikeEdits(read: List<Song>): List<Song> =
        likeEdits.values.fold(read) { list, (song, liked) -> applyLike(list, song, liked) }

    private fun withEdits(read: List<ShelfItem>): List<ShelfItem> {
        val ids = read.mapTo(HashSet()) { playlistId(it.browseId) }
        val made = madeHere.filter { playlistId(it.browseId) !in ids }
        return (made + read).filterNot { playlistId(it.browseId) in deletedHere }
    }

    /**
     * Makes a playlist on the account, [first] in it when given, and lists it at once. Its card,
     * to open, is returned; throws when YouTube Music refuses.
     */
    suspend fun createPlaylist(name: String, privacy: PlaylistPrivacy, first: List<Song> = emptyList()): ShelfItem {
        val mine = generation
        val title = name.trim().ifEmpty { "New playlist" }
        val ids = first.map { it.videoId }.filter { com.opentune.data.isYouTubeId(it) }.distinct()
        val id = kotlinx.coroutines.withContext(Dispatchers.IO) { Innertube.createPlaylist(title, privacy, videoIds = ids) }
        val item = ShelfItem(title, "Playlist • ${privacy.label}", first.firstOrNull()?.thumbnailUrl, null, "VL" + id.removePrefix("VL"))
        if (generation == mine) {
            madeHere += item
            _state.update { it.copy(playlists = withEdits(it.playlists)) }
        }
        return item
    }

    /** Takes a deleted playlist off the list at once. */
    fun notePlaylistDeleted(browseId: String) {
        deletedHere += playlistId(browseId)
        madeHere.removeAll { playlistId(it.browseId) == playlistId(browseId) }
        _state.update { it.copy(playlists = withEdits(it.playlists)) }
    }

    /** A playlist renamed here, renamed in the list at once. */
    fun notePlaylistRenamed(browseId: String, title: String) {
        val id = playlistId(browseId)
        _state.update { s -> s.copy(playlists = s.playlists.map { if (playlistId(it.browseId) == id) it.copy(title = title) else it }) }
    }

    /** Adds [song] to the account's playlist [item], off the window's thread, and says how it went. */
    fun addToPlaylist(item: ShelfItem, song: Song) {
        val id = item.browseId ?: return
        scope.launch {
            runCatching { Innertube.addToPlaylist(id, listOf(song.videoId)) }
                .onSuccess { Pages.forgetListing(id); Notice.show("Added to ${item.title}") }
                .onFailure { Notice.show("Couldn't add to ${item.title}") }
        }
    }

    /** For pictures and tests: the account's library as if it had been read. */
    internal fun preload(state: State) {
        generation++
        reading?.cancel()
        _state.value = state
    }

    private const val MAX_PAGES = 100

    // ---- Rules, kept apart so they can be tested ------------------------------------

    /** A playlist's id without the `VL` its page carries. */
    fun playlistId(browseId: String?): String = browseId.orEmpty().removePrefix("VL")

    /**
     * Whether an account playlist is one OpenTune never lists: Liked Music (the Library's own
     * Liked songs is it), and the private one Connect keeps its secret in.
     */
    fun isHidden(item: ShelfItem): Boolean =
        playlistId(item.browseId) == "LM" || item.title == CONNECT_PLAYLIST

    /** The account's playlists as listed: the hidden ones left out, each once. */
    fun shownPlaylists(items: List<ShelfItem>): List<ShelfItem> =
        items.filterNot(::isHidden).filter { it.browseId != null }.distinctBy { playlistId(it.browseId) }

    /** The title of the playlist Connect keeps (ConnectAccountSync.TITLE on the phone and the web). */
    const val CONNECT_PLAYLIST = "OpenTune Connect"

    /** [list] with [song] liked (first) or unliked. */
    fun applyLike(list: List<Song>, song: Song, liked: Boolean): List<Song> {
        val rest = list.filterNot { it.videoId == song.videoId }
        return if (liked) listOf(list.firstOrNull { it.videoId == song.videoId } ?: song) + rest else rest
    }
}

/**
 * Liked songs, one list: signed in, the account's Liked Music, then the songs liked on this
 * computer it doesn't have; signed out, those liked here.
 */
internal fun mergeLiked(account: List<Song>, local: List<Song>): List<Song> {
    if (account.isEmpty()) return local
    val have = account.mapTo(HashSet()) { it.videoId }
    return account + local.filter { have.add(it.videoId) }
}

/** The Library's songs: liked, then added to the account's library, then in a playlist made here; each once. */
internal fun librarySongs(liked: List<Song>, saved: List<Song>, inPlaylists: List<Song>): List<Song> {
    val seen = HashSet<String>()
    return (liked.asSequence() + saved.asSequence() + inPlaylists.asSequence()).filter { seen.add(it.videoId) }.toList()
}

/** A playlist as the Library and the sidebar list it: made on this computer, or on the account. */
internal sealed interface LibPlaylist {
    val title: String
    /** What the sidebar knows it by, to mark it while it's open. */
    val key: Any

    data class Local(val playlist: Library.Playlist) : LibPlaylist {
        override val title: String get() = playlist.name
        override val key: Any get() = PlaylistKey(playlist.id)
    }

    data class OnAccount(val item: ShelfItem) : LibPlaylist {
        override val title: String get() = item.title
        override val key: Any get() = AccountPlaylistKey(AccountLibrary.playlistId(item.browseId))
    }
}

/**
 * Every playlist, in the order the Library and the sidebar both list them: those made here, in
 * their own order, then the account's as YouTube Music orders them, without the ones never
 * listed and each once.
 */
internal fun libraryPlaylists(local: List<Library.Playlist>, account: List<ShelfItem>): List<LibPlaylist> =
    local.map { LibPlaylist.Local(it) } + AccountLibrary.shownPlaylists(account).map { LibPlaylist.OnAccount(it) }

/** The merged liked songs, and their ids for the hearts, kept up to date for every page and row. */
internal object LikedSongs {
    private val _all = MutableStateFlow(mergeLiked(AccountLibrary.state.value.liked, Library.liked.value))
    val all: StateFlow<List<Song>> = _all.asStateFlow()
    private val _ids = MutableStateFlow(_all.value.mapTo(HashSet()) { it.videoId } as Set<String>)
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
            combine(AccountLibrary.state.map { it.liked }.distinctUntilChanged(), Library.liked) { account, local -> mergeLiked(account, local) }
                .collect { merged ->
                    _all.value = merged
                    _ids.value = merged.mapTo(HashSet()) { it.videoId }
                }
        }
    }
}
