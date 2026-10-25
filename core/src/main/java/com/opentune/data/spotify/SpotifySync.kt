package com.opentune.data.spotify

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SongRef
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps a copy of every playlist in the listener's Spotify library (Liked
 * Songs too) in Library, marked as from Spotify. Each Spotify song is found
 * on YouTube Music the way an import finds it ([PlaylistImport.match]), and
 * what was found is remembered by Spotify's id for that song, so a playlist
 * that changed only costs its new songs. A playlist whose Spotify version
 * hasn't changed isn't read again; one taken out of the Spotify library is
 * taken out here too.
 *
 * Spotify's mixes made for the listener (Daily Mixes, Discover Weekly,
 * Release Radar and the like) are brought over the same way for Home, but
 * kept apart from Library, since Spotify remakes them every day or week.
 */
object SpotifySync {
    private const val TAG = "SpotifySync"
    private const val PARALLEL = 4
    /** How often opening the app looks for changes on Spotify. */
    private const val EVERY_MS = 6 * 60 * 60_000L

    data class Status(
        val running: Boolean = false,
        /** The playlist being brought over, and where it is in the library. */
        val current: String? = null,
        val index: Int = 0,
        val count: Int = 0,
        val lastSynced: Long = 0,
        val error: String? = null,
    )

    /** One of Spotify's mixes made for the listener, with its songs found on YouTube Music. */
    @Serializable
    data class Mix(
        val id: String,
        val name: String,
        val description: String? = null,
        val imageUrl: String? = null,
        val revision: String? = null,
        val songs: List<SongRef> = emptyList(),
    )

    @Serializable
    private data class Saved(val lastSynced: Long = 0, val found: Map<String, SongRef> = emptyMap(), val mixes: List<Mix> = emptyList())

    /** The fewest songs found for a mix to be worth showing. */
    private const val MIX_MIN = 6
    private const val MAX_MIXES = 12

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var file: File? = null
    /** YouTube Music's song for each Spotify song id found so far. */
    private val found = ConcurrentHashMap<String, SongRef>()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    /** Spotify's mixes for the listener, in Spotify's order, as last brought over. */
    val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()

    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, "spotify-sync.json")
        file = f
        scope.launch {
            runCatching { json.decodeFromString(Saved.serializer(), f.readText()) }.getOrNull()?.let { saved ->
                found.putAll(saved.found)
                _mixes.value = saved.mixes
                _status.update { it.copy(lastSynced = saved.lastSynced) }
            }
        }
    }

    /**
     * Brings the library over, unless it was done within the last few hours
     * and [force] isn't set. [onSignedOut] hears when Spotify no longer
     * accepts [cookie].
     */
    fun sync(cookie: String, force: Boolean = false, onSignedOut: (String?) -> Unit = {}) {
        if (job?.isActive == true) return
        if (!force && System.currentTimeMillis() - _status.value.lastSynced < EVERY_MS) return
        job = scope.launch {
            _status.update { it.copy(running = true, current = null, index = 0, count = 0, error = null) }
            try {
                run(cookie, force)
                val now = System.currentTimeMillis()
                _status.update { it.copy(running = false, current = null, lastSynced = now) }
                save(now)
            } catch (e: CancellationException) {
                _status.update { it.copy(running = false, current = null) }
                throw e
            } catch (e: SpotifyWebPlayer.SignedOut) {
                _status.update { it.copy(running = false, current = null, error = e.message) }
                onSignedOut(e.message)
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
                _status.update { it.copy(running = false, current = null, error = e.message ?: "Couldn't reach Spotify") }
            }
        }
    }

    fun cancel() = job?.cancel()

    private suspend fun run(cookie: String, force: Boolean) {
        if (com.opentune.data.settings.AppSettings.ui.value.spotifyMixes) {
            try {
                syncMixes(cookie, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpotifyWebPlayer.SignedOut) {
                throw e
            } catch (e: Exception) {
                // The library still comes over without them.
                Log.w(TAG, "mixes failed", e)
            }
        }
        val lists = SpotifyLibrary.playlists(cookie)
        val have = LibraryStore.playlists.value.filter { it.spotifyId != null }.associateBy { it.spotifyId }
        lists.forEachIndexed { i, list ->
            _status.update { it.copy(current = list.name, index = i + 1, count = lists.size) }
            val mine = have[list.id]
            if (mine != null && !force) {
                val revision = SpotifyLibrary.revision(cookie, list.id)
                if (revision != null && revision == mine.spotifyRevision) {
                    if (mine.name != list.name || mine.coverUrl != list.imageUrl) {
                        LibraryStore.putSpotifyPlaylist(list.id, list.name, mine.songs.map { it.toSong() }, revision, list.imageUrl)
                    }
                    return@forEachIndexed
                }
            }
            val (items, revision) = SpotifyLibrary.tracks(cookie, list.id)
            val songs = matchAll(items)
            LibraryStore.putSpotifyPlaylist(list.id, list.name, songs.map { it.toSong() }, revision, list.imageUrl)
            save(_status.value.lastSynced)
        }
        // Only after the whole library was read: a failed read must not empty Library.
        LibraryStore.dropSpotifyPlaylists(lists.mapTo(HashSet()) { it.id })
    }

    private suspend fun syncMixes(cookie: String, force: Boolean) {
        val lists = SpotifyLibrary.madeForYou(cookie).take(MAX_MIXES)
        val had = _mixes.value.associateBy { it.id }
        val out = mutableListOf<Mix>()
        lists.forEachIndexed { i, list ->
            _status.update { it.copy(current = list.name, index = i + 1, count = lists.size) }
            val mine = had[list.id]
            if (mine != null && !force) {
                val revision = SpotifyLibrary.revision(cookie, list.id)
                if (revision != null && revision == mine.revision) {
                    out += mine.copy(name = list.name, description = list.owner, imageUrl = list.imageUrl)
                    return@forEachIndexed
                }
            }
            val (items, read) = SpotifyLibrary.tracks(cookie, list.id)
            out += Mix(list.id, list.name, list.owner, list.imageUrl, read, matchAll(items))
        }
        _mixes.value = out.filter { it.songs.size >= MIX_MIN }
        save(_status.value.lastSynced)
    }

    /** Each song found on YouTube Music, in order; ones not found are left out. */
    private suspend fun matchAll(items: List<SpotifyLibrary.Item>): List<SongRef> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        items.map { item ->
            async {
                found[item.uri] ?: gate.withPermit {
                    runCatching { PlaylistImport.match(item.track) }.getOrNull()?.let(SongRef::of)?.also { found[item.uri] = it }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun save(lastSynced: Long) {
        val f = file ?: return
        runCatching { f.writeText(json.encodeToString(Saved.serializer(), Saved(lastSynced, HashMap(found), _mixes.value))) }
            .onFailure { Log.w(TAG, "couldn't save", it) }
    }
}
