package com.opentune.data.library

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.isYouTubeId
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.Song
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Keeps playlists linked to YouTube Music in step with their copies there.
 *
 * Every change is made on the phone first, at once, and queued here as a
 * [PlaylistEdit]; the queue is sent in order in the background whenever
 * there is a session. One that can't go now (offline, signed out) waits on
 * disk for the next try: the app starting, signing in, coming back to the
 * front, a refresh, or the next change. One YouTube Music refuses a few
 * times is given up on. The rules for the queue itself are in [SyncQueue].
 *
 * The other direction is [refresh]: what changed on YouTube Music is folded
 * into the linked playlist, once nothing from here is still on its way.
 *
 * Queue changes are locked on [LibraryStore], the same lock its playlist
 * changes take, so a change and its edit always arrive together.
 */
object PlaylistSync {
    private const val TAG = "PlaylistSync"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private var file: File? = null

    private val _pending = MutableStateFlow<List<PlaylistEdit>>(emptyList())
    /** Changes made here and not yet made on YouTube Music, oldest first. */
    val pending: StateFlow<List<PlaylistEdit>> = _pending.asStateFlow()

    /** The edit being sent right now, which nothing new may fold into. */
    @Volatile private var busy: Long? = null

    fun init(context: Context) {
        val f = File(context.filesDir, "playlist-sync.json")
        file = f
        _pending.value = runCatching { json.decodeFromString(ListSerializer(PlaylistEdit.serializer()), f.readText()) }
            .getOrDefault(emptyList())
        // At start when signed in, and again on each sign-in. A different
        // account than the one a playlist was linked in takes the link away.
        scope.launch {
            AccountStore.signedIn.collect { signedIn ->
                if (!signedIn) return@collect
                forgetOtherAccounts()
                flush()
            }
        }
        scope.launch { AccountStore.account.collect { forgetOtherAccounts() } }
    }

    /** Whether changes to playlist [id] are still waiting to be made on YouTube Music. */
    fun isWaiting(id: String): Boolean = _pending.value.any { it.playlist == id }

    /** Whether playlist [id]'s copy on YouTube Music is still waiting to be made. */
    fun isCreating(id: String): Boolean = _pending.value.any { it.playlist == id && it.kind == PlaylistEdit.Kind.CREATE }

    internal fun enqueue(edit: PlaylistEdit) {
        change { SyncQueue.add(it, edit, busy) }
        flush()
    }

    /** Sends whatever is waiting, when signed in. */
    fun flush(): Job = scope.launch { sending.withLock { send() } }

    /**
     * Sends what's waiting, then brings linked playlist [id] (or, with
     * null, every linked one) up to date with what YouTube Music has.
     */
    fun refresh(id: String? = null): Job = scope.launch {
        sending.withLock {
            send()
            if (!AccountStore.signedIn.value) return@withLock
            val linked = LibraryStore.playlists.value.filter { it.remoteId != null && (id == null || it.id == id) }
            for (p in linked) {
                if (isWaiting(p.id)) continue
                try {
                    LibraryStore.applyRemote(p.id, MusicRepository.remotePlaylist(p.remoteId ?: continue))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't read ${p.name} from YouTube Music", e)
                }
            }
        }
    }

    /**
     * Adds [song] straight to one of the account's own playlists, for one
     * with no copy here; [done] hears, on the main thread, whether it went.
     */
    fun addToAccountPlaylist(playlistId: String, song: Song, done: (Boolean) -> Unit) =
        addAllToAccountPlaylist(playlistId, listOf(song), done)

    /** [addToAccountPlaylist] for several songs at once, in one request. */
    fun addAllToAccountPlaylist(playlistId: String, songs: List<Song>, done: (Boolean) -> Unit) {
        val ids = songs.map { it.videoId }.filter { com.opentune.data.isYouTubeId(it) }.distinct()
        if (ids.isEmpty()) { done(false); return }
        scope.launch {
            val ok = try {
                Innertube.addToPlaylist(playlistId, ids)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't add ${ids.size} songs to $playlistId", e)
                false
            }
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    /**
     * Takes a playlist or album someone else made out of the account's library on YouTube Music
     * (on every device), and out of the library list kept here. [playlistId] is the id the page
     * names for it (an album's is not its browse id). True if YouTube Music took it.
     */
    suspend fun removeFromAccountLibrary(browseId: String, playlistId: String): Boolean {
        val ok = try {
            Innertube.ratePlaylist(playlistId, saved = false)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't remove $playlistId from the library", e)
            false
        }
        if (ok) {
            val key = browseId.removePrefix("VL")
            LibraryCache.savePlaylists(LibraryCache.playlists.value.filterNot { it.browseId?.removePrefix("VL") == key }, LibraryCache.owner())
        }
        return ok
    }

    private fun currentAccount(): String? = AccountStore.account.value?.email?.takeIf { it.isNotBlank() }

    /** Unlinks playlists made in another account than the one signed in, and drops their waiting changes. */
    private fun forgetOtherAccounts() {
        if (!AccountStore.signedIn.value) return
        val account = currentAccount() ?: return
        change { edits ->
            val dropped = LibraryStore.keepLinksFor(account)
            if (dropped.isEmpty()) edits else edits.filterNot { it.playlist in dropped }
        }
    }

    private fun change(t: (List<PlaylistEdit>) -> List<PlaylistEdit>) = synchronized(LibraryStore) {
        val next = t(_pending.value)
        if (next == _pending.value) return@synchronized
        _pending.value = next
        val f = file ?: return@synchronized
        runCatching {
            val tmp = File(f.parentFile, "playlist-sync.json.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(PlaylistEdit.serializer()), next))
            check(tmp.renameTo(f))
        }.onFailure { Log.w(TAG, "Couldn't save the playlist changes still to send", it) }
    }

    /**
     * Sends the queue in order. A change that can't go now stops the lot,
     * since the rest would meet the same; one YouTube Music refuses holds
     * back only the rest of its own playlist's, which may depend on it.
     */
    private suspend fun send() {
        if (!AccountStore.signedIn.value) return
        val held = HashSet<String>()
        while (true) {
            val edit = _pending.value.firstOrNull { it.playlist !in held } ?: return
            busy = edit.seq
            val error = try {
                apply(edit)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } finally {
                busy = null
            }
            when {
                error == null -> change { SyncQueue.done(it, edit.seq) }
                SyncQueue.isTransient(error) -> {
                    Log.w(TAG, "Playlist changes wait for later: ${error.message}")
                    return
                }
                else -> {
                    Log.w(TAG, "YouTube Music refused ${edit.kind} on ${edit.playlist}", error)
                    held += edit.playlist
                    change { SyncQueue.refused(it, edit.seq) }
                    // A copy that will never be made: offer "Save to YouTube Music" again.
                    if (edit.kind == PlaylistEdit.Kind.CREATE && _pending.value.none { it.seq == edit.seq }) {
                        LibraryStore.unlink(edit.playlist)
                    }
                }
            }
        }
    }

    private suspend fun apply(edit: PlaylistEdit) {
        if (edit.kind == PlaylistEdit.Kind.CREATE) return create(edit)
        val local = LibraryStore.playlist(edit.playlist)
        // Nothing on YouTube Music to change.
        val remote = edit.remoteId ?: local?.remoteId ?: return
        when (edit.kind) {
            PlaylistEdit.Kind.ADD ->
                LibraryStore.rememberEntries(edit.playlist, Innertube.addToPlaylist(remote, edit.videoIds))
            PlaylistEdit.Kind.REMOVE -> {
                val videoId = edit.videoIds.firstOrNull() ?: return
                // Not on YouTube Music: nothing to take out.
                val entry = edit.setVideoId ?: entries(remote, edit.playlist)[videoId] ?: return
                Innertube.removeFromPlaylist(remote, listOf(entry to videoId))
            }
            PlaylistEdit.Kind.MOVE -> {
                val moved = edit.videoIds.firstOrNull() ?: return
                var known = local?.songs.orEmpty().mapNotNull { s -> s.setVideoId?.let { s.videoId to it } }.toMap()
                if (moved !in known || (edit.before != null && edit.before !in known)) known = entries(remote, edit.playlist)
                val entry = known[moved] ?: return
                Innertube.movePlaylistItem(remote, entry, edit.before?.let { known[it] })
            }
            PlaylistEdit.Kind.RENAME -> Innertube.renamePlaylist(remote, edit.name ?: return)
            PlaylistEdit.Kind.PRIVACY -> Innertube.setPlaylistPrivacy(remote, edit.privacy ?: return)
            PlaylistEdit.Kind.DELETE -> Innertube.deletePlaylist(remote)
            PlaylistEdit.Kind.CREATE -> Unit
        }
    }

    /**
     * Makes the copy on YouTube Music, songs and all, in one request, then
     * links it. Changes made here while that was on its way were left out of
     * the queue (see [SyncQueue.add]), so they're worked out from what was
     * sent and queued now.
     */
    private suspend fun create(edit: PlaylistEdit) {
        val p = LibraryStore.playlist(edit.playlist) ?: return
        if (p.remoteId != null) return
        val sent = p.songs.map { it.videoId }.filter(::isYouTubeId)
        val privacy = p.privacy ?: PlaylistPrivacy.PRIVATE
        val remote = Innertube.createPlaylist(p.name, privacy, videoIds = sent)
        val linked = synchronized(LibraryStore) {
            val now = LibraryStore.playlist(edit.playlist) ?: return@synchronized false
            LibraryStore.link(now.id, remote, currentAccount())
            change { SyncQueue.done(it, edit.seq) }
            val have = now.songs.map { it.videoId }.filter(::isYouTubeId)
            val follow = buildList {
                add(PlaylistEdit(PlaylistEdit.Kind.ADD, now.id, remote, videoIds = have - sent.toSet()))
                (sent - have.toSet()).forEach { add(PlaylistEdit(PlaylistEdit.Kind.REMOVE, now.id, remote, videoIds = listOf(it))) }
                if (now.name != p.name) add(PlaylistEdit(PlaylistEdit.Kind.RENAME, now.id, remote, name = now.name))
                if (now.privacy != null && now.privacy != privacy) add(PlaylistEdit(PlaylistEdit.Kind.PRIVACY, now.id, remote, privacy = now.privacy))
            }
            follow.forEach { e -> change { SyncQueue.add(it, e) } }
            true
        }
        if (!linked) {
            // Deleted while it was being made: it goes from YouTube Music too.
            change { SyncQueue.add(it, PlaylistEdit(PlaylistEdit.Kind.DELETE, edit.playlist, remote)) }
            return
        }
        // The entries' ids, for removing and moving them later; they'll be read then if this fails.
        try {
            entries(remote, edit.playlist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the new playlist back", e)
        }
    }

    /** Reads which set-video-id each song has on YouTube Music, and keeps them on playlist [id]. */
    private suspend fun entries(remoteId: String, id: String): Map<String, String> {
        val found = MusicRepository.remotePlaylist(remoteId).songs
            .mapNotNull { s -> s.setVideoId?.let { s.videoId to it } }
            .toMap()
        LibraryStore.rememberEntries(id, found)
        return found
    }
}
