package com.opentune.data.library

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.isYouTubeId
import com.opentune.data.model.LikeStatus
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.Song
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A track as stored on the device: enough to show it and play it again. */
@Serializable
data class SongRef(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    /** YouTube Music's "E" mark; null for songs saved before it was kept. */
    val explicit: Boolean? = null,
    /**
     * In a playlist linked to YouTube Music, this entry's id there, which
     * removing or moving it takes; null until YouTube Music has said, and
     * everywhere else. Never copied from a [Song], whose own one names a
     * slot in some other playlist.
     */
    val setVideoId: String? = null,
) {
    fun toSong() = Song(videoId, title, artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit)

    companion object {
        fun of(song: Song) = SongRef(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName, song.isExplicit)
    }
}

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<SongRef> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * The copy on YouTube Music this playlist is kept in step with (its id,
     * no `VL`); null for one that lives only on this phone, or whose copy
     * hasn't been made yet. See [PlaylistSync].
     */
    val remoteId: String? = null,
    /** Who can see the copy on YouTube Music; null when there isn't meant to be one. */
    val privacy: PlaylistPrivacy? = null,
    /** The account (its email) the copy on YouTube Music was made in; null when not known. */
    val account: String? = null,
    /**
     * The Spotify playlist this one is a copy of (its id, or
     * [com.opentune.data.spotify.SpotifyLibrary.LIKED_ID]); null for every
     * other. Its songs are replaced when Spotify's change. See [com.opentune.data.spotify.SpotifySync].
     */
    val spotifyId: String? = null,
    /** Spotify's version of it when last brought over, so an unchanged one isn't read again. */
    val spotifyRevision: String? = null,
    /** Spotify's cover for it. */
    val coverUrl: String? = null,
) {
    /** Kept in step with YouTube Music, or waiting for its copy to be made there. */
    val synced: Boolean get() = remoteId != null || privacy != null
}

/** An album or playlist from YouTube Music saved to the library with its heart. */
@Serializable
data class SavedList(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class LibraryDocument(
    val liked: List<SongRef> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    /** Songs marked "Dislike": kept out of autoplay. */
    val disliked: Set<String> = emptySet(),
    /** Albums and playlists saved with their heart, newest first. */
    val savedLists: List<SavedList> = emptyList(),
    /** "Not interested": kept out of suggestions, newest first. */
    val hiddenSongs: List<HiddenSong> = emptyList(),
    /** "Don't play [artist]": their songs kept out of suggestions, newest first. */
    val hiddenArtists: List<HiddenArtist> = emptyList(),
)

/**
 * Liked songs and playlists made in the app, kept on the device. Liking also
 * rates the track on YouTube Music when signed in, so likes follow the
 * account too, and a playlist can be linked to a copy on YouTube Music that
 * every change here is passed on to (see [PlaylistSync]).
 */
object LibraryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val doc = MutableStateFlow(LibraryDocument())

    private val _liked = MutableStateFlow<List<SongRef>>(emptyList())
    /** Newest like first. */
    val liked: StateFlow<List<SongRef>> = _liked.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _savedLists = MutableStateFlow<List<SavedList>>(emptyList())
    /** Albums and playlists saved with their heart, newest first. */
    val savedLists: StateFlow<List<SavedList>> = _savedLists.asStateFlow()

    private val _disliked = MutableStateFlow<Set<String>>(emptySet())
    /** The videoIds marked "Dislike". */
    val disliked: StateFlow<Set<String>> = _disliked.asStateFlow()

    private val _hidden = MutableStateFlow(HiddenList.EMPTY)
    /** Songs and artists kept out of autoplay, radio, smart shuffle and Home's picks. */
    val hidden: StateFlow<HiddenList> = _hidden.asStateFlow()

    /** Keeps [song] out of suggestions; undone with [unhideSong]. */
    fun hideSong(song: Song): HiddenSong {
        val entry = HiddenSong(song.videoId, song.title, song.artist, song.thumbnailUrl, System.currentTimeMillis())
        update { d -> d.copy(hiddenSongs = listOf(entry) + d.hiddenSongs.filterNot { it.videoId == song.videoId }) }
        return entry
    }

    fun unhideSong(videoId: String) = update { d -> d.copy(hiddenSongs = d.hiddenSongs.filterNot { it.videoId == videoId }) }

    /**
     * Keeps every song by [song]'s first credited artist out of suggestions;
     * null when the song names no artist. Undone with [unhideArtist].
     */
    fun hideArtist(song: Song): HiddenArtist? {
        val name = HiddenList.mainArtist(song.artist).takeIf { it.isNotBlank() } ?: return null
        val entry = HiddenArtist(name, song.artistId, System.currentTimeMillis())
        update { d -> d.copy(hiddenArtists = listOf(entry) + d.hiddenArtists.filterNot { it.key == entry.key }) }
        return entry
    }

    fun unhideArtist(key: String) = update { d -> d.copy(hiddenArtists = d.hiddenArtists.filterNot { it.key == key }) }

    fun isSaved(browseId: String): Boolean = _savedLists.value.any { it.browseId == browseId }

    /** Saves [list] to the library, or with [saved] false takes it out. */
    fun setSaved(list: SavedList, saved: Boolean) = update { d ->
        val rest = d.savedLists.filterNot { it.browseId == list.browseId }
        d.copy(savedLists = if (saved) listOf(list.copy(savedAt = System.currentTimeMillis())) + rest else rest)
    }

    private var saved: com.opentune.data.SavedFile? = null

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val f = com.opentune.data.SavedFile(File(context.filesDir, "library.json"), "LibraryStore").also { saved = it }
        // A library that won't read is kept aside, never saved over with an empty one.
        publish(f.read(LibraryDocument()) { json.decodeFromString(LibraryDocument.serializer(), it) })
        scope.launch {
            doc.drop(1).onEach { f.dirty = true }.debounce(800).collect { d ->
                runCatching { f.write(json.encodeToString(LibraryDocument.serializer(), d)) }
                    .onFailure { Log.w("LibraryStore", "Couldn't save the library", it) }
            }
        }
    }

    /** Saves at once a change still waiting for its save: the app is leaving the screen or closing. */
    fun flush() { saved?.flushIfDirty { json.encodeToString(LibraryDocument.serializer(), doc.value) } }

    private fun publish(d: LibraryDocument) {
        doc.value = d
        _liked.value = d.liked
        _playlists.value = d.playlists
        _savedLists.value = d.savedLists
        _hidden.value = HiddenList(d.hiddenSongs, d.hiddenArtists)
        _disliked.value = d.disliked
        LibraryArtwork.keep(d.liked.map { it.thumbnailUrl } + d.playlists.flatMap { p -> p.songs.map { it.thumbnailUrl } })
    }

    // Locked: the playlist sync writes back from its own thread.
    @Synchronized
    private fun update(t: (LibraryDocument) -> LibraryDocument) = publish(t(doc.value))

    fun isLiked(videoId: String): Boolean = _liked.value.any { it.videoId == videoId }

    fun isDisliked(videoId: String): Boolean = videoId in doc.value.disliked

    /**
     * Marks [song] as one not to hear again: it's taken out of Liked, kept out
     * of autoplay from now on, and rated down on YouTube Music when signed in.
     */
    fun dislike(song: Song) {
        update { d -> d.copy(liked = d.liked.filterNot { it.videoId == song.videoId }, disliked = d.disliked + song.videoId) }
        if (AccountStore.signedIn.value && !song.videoId.startsWith("local:")) {
            scope.launch {
                runCatching { Innertube.rate(song.videoId, LikeStatus.DISLIKE) }
                    .onFailure { Log.w("LibraryStore", "Couldn't rate ${song.videoId} down on YouTube Music", it) }
            }
        }
    }

    fun setLiked(song: Song, liked: Boolean) {
        update { d ->
            val rest = d.liked.filterNot { it.videoId == song.videoId }
            d.copy(liked = if (liked) listOf(SongRef.of(song)) + rest else rest, disliked = if (liked) d.disliked - song.videoId else d.disliked)
        }
        if (AccountStore.signedIn.value && !song.videoId.startsWith("local:")) {
            scope.launch {
                runCatching { Innertube.rate(song.videoId, if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT) }
                    .onFailure { Log.w("LibraryStore", "Couldn't rate ${song.videoId} on YouTube Music", it) }
            }
        }
        // And in Spotify's Liked Songs, when Spotify is connected.
        com.opentune.data.spotify.SpotifyLikes.liked(song, liked)
    }

    // Every change to a playlist below is made here at once and, for one kept
    // in step with YouTube Music, queued to be made there too (see
    // [PlaylistSync]). Each is one locked step, change and queueing both, so
    // the sync never sees a change it wasn't also handed, or the reverse.

    /**
     * A new playlist; with [privacy], also made on YouTube Music with that
     * privacy and kept in step with it from then on.
     */
    @Synchronized
    fun createPlaylist(name: String, first: Song? = null, privacy: PlaylistPrivacy? = null): String {
        val id = UUID.randomUUID().toString()
        val p = LocalPlaylist(id, name.trim().ifEmpty { "New playlist" }, listOfNotNull(first?.let(SongRef::of)), privacy = privacy)
        update { it.copy(playlists = listOf(p) + it.playlists) }
        if (privacy != null) PlaylistSync.enqueue(PlaylistEdit(PlaylistEdit.Kind.CREATE, id))
        return id
    }

    /** Makes a YouTube Music copy of a playlist that so far lived only here, songs and all, and links the two. */
    @Synchronized
    fun saveToAccount(id: String, privacy: PlaylistPrivacy) {
        if (playlist(id)?.synced != false) return
        editPlaylist(id) { it.copy(privacy = privacy) }
        PlaylistSync.enqueue(PlaylistEdit(PlaylistEdit.Kind.CREATE, id))
    }

    /** Adds [songs] in order, skipping any already there; one write for the lot. */
    @Synchronized
    fun addAllToPlaylist(id: String, songs: List<Song>) {
        if (playlist(id)?.spotifyId != null) {
            com.opentune.data.spotify.SpotifyPlaylistWrites.add(id, songs)
            return
        }
        var added = emptyList<SongRef>()
        editPlaylist(id) { p ->
            val have = p.songs.mapTo(HashSet()) { it.videoId }
            added = songs.filter { have.add(it.videoId) }.map(SongRef::of)
            p.copy(songs = p.songs + added)
        }
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.ADD, id, remote, videoIds = added.map { it.videoId }.filter(::isYouTubeId)) }
    }

    fun addToPlaylist(id: String, song: Song) = addAllToPlaylist(id, listOf(song))

    /** Only called after the remote service has accepted the write. */
    @Synchronized
    internal fun confirmSpotifyAddition(id: String, spotifyId: String, song: Song) {
        editPlaylist(id) { p ->
            if (p.spotifyId != spotifyId || p.songs.any { it.videoId == song.videoId }) p
            else p.copy(songs = p.songs + SongRef.of(song), spotifyRevision = null)
        }
    }

    @Synchronized
    fun removeFromPlaylist(id: String, index: Int) {
        val gone = playlist(id)?.songs?.getOrNull(index) ?: return
        editPlaylist(id) { p -> p.copy(songs = p.songs.filterIndexed { i, _ -> i != index }) }
        if (!isYouTubeId(gone.videoId)) return
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.REMOVE, id, remote, videoIds = listOf(gone.videoId), setVideoId = gone.setVideoId) }
    }

    /** A song just taken out of a playlist, which [undoRemove] can put back where it was. */
    class Removed internal constructor(val playlistId: String, val index: Int, val song: SongRef, internal val token: Long)

    /** How long a removal waits before it reaches YouTube Music, so Undo can call it off there. */
    const val UNDO_MS = 8_000L
    private var removals = 0L
    private val waiting = HashMap<Long, kotlinx.coroutines.Job>()

    /**
     * Takes the song at [index] out of playlist [id] here at once, and on
     * YouTube Music only once [UNDO_MS] has passed without [undoRemove].
     */
    @Synchronized
    fun removeUndoably(id: String, index: Int): Removed? {
        val gone = playlist(id)?.songs?.getOrNull(index) ?: return null
        editPlaylist(id) { p -> p.copy(songs = p.songs.filterIndexed { i, _ -> i != index }) }
        val removed = Removed(id, index, gone, ++removals)
        if (isYouTubeId(gone.videoId)) {
            waiting[removed.token] = scope.launch {
                kotlinx.coroutines.delay(UNDO_MS)
                synchronized(this@LibraryStore) {
                    if (waiting.remove(removed.token) == null) return@launch
                    mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.REMOVE, id, remote, videoIds = listOf(gone.videoId), setVideoId = gone.setVideoId) }
                }
            }
        }
        return removed
    }

    /** Puts [removed] back at its place. Called late, the removal already reached YouTube Music, so it's added there again. */
    @Synchronized
    fun undoRemove(removed: Removed) {
        val p = playlist(removed.playlistId) ?: return
        val at = removed.index.coerceIn(0, p.songs.size)
        val songs = p.songs.toMutableList().apply { add(at, removed.song) }
        editPlaylist(removed.playlistId) { it.copy(songs = songs) }
        val held = waiting.remove(removed.token)
        held?.cancel()
        if (held != null || !isYouTubeId(removed.song.videoId)) return
        val id = removed.playlistId
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.ADD, id, remote, videoIds = listOf(removed.song.videoId)) }
        val before = songs.drop(at + 1).firstOrNull { isYouTubeId(it.videoId) }?.videoId ?: return
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.MOVE, id, remote, videoIds = listOf(removed.song.videoId), before = before) }
    }

    @Synchronized
    fun movePlaylistItem(id: String, from: Int, to: Int) {
        val p = playlist(id) ?: return
        if (from !in p.songs.indices || to !in p.songs.indices || from == to) return
        val songs = p.songs.toMutableList().apply { add(to, removeAt(from)) }
        editPlaylist(id) { it.copy(songs = songs) }
        val moved = songs[to].videoId
        if (!isYouTubeId(moved)) return
        // A file on this phone isn't on YouTube Music, so it can't be the one to sit before.
        val before = songs.drop(to + 1).firstOrNull { isYouTubeId(it.videoId) }?.videoId
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.MOVE, id, remote, videoIds = listOf(moved), before = before) }
    }

    @Synchronized
    fun renamePlaylist(id: String, name: String) {
        val old = playlist(id)?.name ?: return
        val new = name.trim().ifEmpty { old }
        if (new == old) return
        editPlaylist(id) { it.copy(name = new) }
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.RENAME, id, remote, name = new) }
    }

    /** Who can see a linked playlist's copy on YouTube Music. */
    @Synchronized
    fun setPlaylistPrivacy(id: String, privacy: PlaylistPrivacy) {
        val p = playlist(id)?.takeIf { it.synced } ?: return
        if (p.privacy == privacy) return
        editPlaylist(id) { it.copy(privacy = privacy) }
        mirror(id) { remote -> PlaylistEdit(PlaylistEdit.Kind.PRIVACY, id, remote, privacy = privacy) }
    }

    /**
     * Deletes the playlist here; with [alsoOnAccount], its copy on YouTube
     * Music too. A copy not made yet is never made.
     */
    @Synchronized
    fun deletePlaylist(id: String, alsoOnAccount: Boolean = false) {
        val p = playlist(id)
        update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
        if (p != null && p.synced && (alsoOnAccount || p.remoteId == null)) {
            PlaylistSync.enqueue(PlaylistEdit(PlaylistEdit.Kind.DELETE, id, p.remoteId))
        }
    }

    fun playlist(id: String): LocalPlaylist? = _playlists.value.firstOrNull { it.id == id }

    /**
     * The copy of Spotify playlist [spotifyId]: made at the end of the list the
     * first time, its name, cover and songs replaced after that.
     */
    fun putSpotifyPlaylist(spotifyId: String, name: String, songs: List<Song>, revision: String?, coverUrl: String?) {
        val refs = songs.distinctBy { it.videoId }.map(SongRef::of)
        update { d ->
            val existing = d.playlists.firstOrNull { it.spotifyId == spotifyId }
            if (existing == null) {
                val p = LocalPlaylist(UUID.randomUUID().toString(), name, refs, spotifyId = spotifyId, spotifyRevision = revision, coverUrl = coverUrl)
                d.copy(playlists = d.playlists + p)
            } else {
                d.copy(playlists = d.playlists.map {
                    if (it.id == existing.id) it.copy(name = name, songs = refs, spotifyRevision = revision, coverUrl = coverUrl) else it
                })
            }
        }
    }

    /** Takes away the copies of Spotify playlists no longer in the Spotify library. */
    fun dropSpotifyPlaylists(keep: Set<String>) = update { d ->
        d.copy(playlists = d.playlists.filter { it.spotifyId == null || it.spotifyId in keep })
    }

    private fun editPlaylist(id: String, t: (LocalPlaylist) -> LocalPlaylist) = update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) t(it) else it })
    }

    /** Queues the edit [make] builds, when playlist [id] is kept in step with YouTube Music. */
    private fun mirror(id: String, make: (remoteId: String?) -> PlaylistEdit) {
        val p = playlist(id)?.takeIf { it.synced } ?: return
        PlaylistSync.enqueue(make(p.remoteId))
    }

    // ---- Written back by [PlaylistSync] --------------------------------------

    /** Records that playlist [id]'s copy on YouTube Music is [remoteId], made in [account]. */
    @Synchronized
    internal fun link(id: String, remoteId: String, account: String?) = editPlaylist(id) { it.copy(remoteId = remoteId, account = account) }

    /**
     * Signed in as [account]: playlists linked in another account go back to
     * living only on this phone (see [PlaylistMerge.forOtherAccount]), and
     * links made before accounts were recorded are taken to be this one's.
     * Returns the ids of the playlists that lost their link.
     */
    @Synchronized
    internal fun keepLinksFor(account: String): Set<String> {
        val dropped = HashSet<String>()
        update { d ->
            d.copy(playlists = d.playlists.map { p ->
                when {
                    !p.synced -> p
                    PlaylistMerge.forOtherAccount(p, account) -> {
                        dropped += p.id
                        p.copy(remoteId = null, privacy = null, account = null, songs = p.songs.map { it.copy(setVideoId = null) })
                    }
                    p.account == null -> p.copy(account = account)
                    else -> p
                }
            })
        }
        return dropped
    }

    /** Takes back a copy that couldn't be made, so "Save to YouTube Music" is offered again. */
    @Synchronized
    internal fun unlink(id: String) = editPlaylist(id) { if (it.remoteId == null) it.copy(privacy = null) else it }

    /** Keeps the set-video-ids YouTube Music gave the entries of playlist [id], by video id. */
    @Synchronized
    internal fun rememberEntries(id: String, setVideoIds: Map<String, String>) {
        if (setVideoIds.isEmpty()) return
        val p = playlist(id) ?: return
        if (p.songs.none { s -> setVideoIds[s.videoId]?.let { it != s.setVideoId } == true }) return
        editPlaylist(id) { pl -> pl.copy(songs = pl.songs.map { s -> setVideoIds[s.videoId]?.let { s.copy(setVideoId = it) } ?: s }) }
    }

    /**
     * Folds what YouTube Music has into linked playlist [id] (see
     * [PlaylistMerge.pull]); not while changes made here are still on their
     * way, since YouTube Music is behind until they land.
     */
    @Synchronized
    internal fun applyRemote(id: String, remote: com.opentune.data.RemotePlaylist) {
        if (PlaylistSync.isWaiting(id)) return
        val p = playlist(id)?.takeIf { it.remoteId != null } ?: return
        val merged = PlaylistMerge.pull(p, remote)
        if (merged != p) editPlaylist(id) { merged }
    }

    fun exportJson(): JsonElement = json.encodeToJsonElement(LibraryDocument.serializer(), doc.value)

    fun importJson(element: JsonElement) = publish(json.decodeFromJsonElement(LibraryDocument.serializer(), element))
}
