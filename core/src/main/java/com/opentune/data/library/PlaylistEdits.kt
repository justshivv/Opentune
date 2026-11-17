package com.opentune.data.library

import com.opentune.data.RemotePlaylist
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.PlaylistPrivacy
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.UserPlaylist
import java.io.IOException
import kotlinx.serialization.Serializable

/**
 * One change to a playlist kept in step with YouTube Music, waiting to be
 * made there. The change is already made on the phone; this is the half
 * that needs the network, kept until it goes through.
 */
@Serializable
data class PlaylistEdit(
    val kind: Kind,
    /** The app's own id for the playlist. */
    val playlist: String,
    /**
     * The YouTube Music id (no `VL`) when it was known at the time; null
     * for [Kind.CREATE], and read off the playlist when it runs otherwise.
     * Kept on the edit so a playlist deleted from the phone but kept on
     * YouTube Music still gets the changes made before.
     */
    val remoteId: String? = null,
    /** ADD: the songs, in order. REMOVE and MOVE: the one song. */
    val videoIds: List<String> = emptyList(),
    /** REMOVE: the entry's set-video-id, when it was known. */
    val setVideoId: String? = null,
    /** MOVE: the song that now follows the moved one; null when it went last. */
    val before: String? = null,
    /** RENAME: the new name. */
    val name: String? = null,
    /** PRIVACY: who can see it now. */
    val privacy: PlaylistPrivacy? = null,
    /** Order in the queue; given by [SyncQueue.add]. */
    val seq: Long = 0,
    /** Times YouTube Music turned it down; dropped after [SyncQueue.MAX_REFUSALS]. */
    val refusals: Int = 0,
) {
    enum class Kind { CREATE, ADD, REMOVE, MOVE, RENAME, PRIVACY, DELETE }
}

/**
 * The rules for the queue of [PlaylistEdit]s: what a new edit replaces or
 * cancels, and what a refusal does. Kept apart from the sending so they can
 * be checked without a network.
 */
object SyncQueue {
    /** A change YouTube Music refuses this many times isn't going to go through. */
    const val MAX_REFUSALS = 3

    /**
     * [queue] with [edit] added. [busy] is the edit being sent right now,
     * which nothing may fold into: it is already on its way.
     *
     *  - While the playlist is still waiting to be made on YouTube Music,
     *    other edits are left out: the create sends the playlist as it is
     *    when it runs, and catches up on what changed while it was sent.
     *  - Deleting drops whatever else was waiting for that playlist, and a
     *    playlist that was never made needs no delete at all.
     *  - A rename or privacy change replaces the one still waiting.
     *  - Removing a song whose add hasn't gone yet takes it out of that add
     *    instead, so it never reaches YouTube Music.
     */
    fun add(queue: List<PlaylistEdit>, edit: PlaylistEdit, busy: Long? = null): List<PlaylistEdit> {
        val next = edit.copy(seq = (queue.maxOfOrNull { it.seq } ?: 0) + 1)
        val creating = queue.any { it.playlist == edit.playlist && it.kind == PlaylistEdit.Kind.CREATE }
        return when (edit.kind) {
            PlaylistEdit.Kind.CREATE -> if (creating) queue else queue + next
            PlaylistEdit.Kind.DELETE -> {
                val rest = queue.filterNot { it.playlist == edit.playlist }
                if (creating) rest else rest + next
            }
            else -> when {
                creating -> queue
                edit.kind == PlaylistEdit.Kind.RENAME || edit.kind == PlaylistEdit.Kind.PRIVACY ->
                    queue.filterNot { it.playlist == edit.playlist && it.kind == edit.kind && it.seq != busy } + next
                edit.kind == PlaylistEdit.Kind.REMOVE -> cancelAdd(queue, edit, busy) ?: (queue + next)
                edit.kind == PlaylistEdit.Kind.ADD && edit.videoIds.isEmpty() -> queue
                else -> queue + next
            }
        }
    }

    /** [queue] with [remove]'s song taken out of an add still waiting, or null when there is none. */
    private fun cancelAdd(queue: List<PlaylistEdit>, remove: PlaylistEdit, busy: Long?): List<PlaylistEdit>? {
        val videoId = remove.videoIds.singleOrNull() ?: return null
        val add = queue.lastOrNull {
            it.playlist == remove.playlist && it.kind == PlaylistEdit.Kind.ADD && it.seq != busy && videoId in it.videoIds
        } ?: return null
        val left = add.videoIds - videoId
        return if (left.isEmpty()) queue.filterNot { it.seq == add.seq }
        else queue.map { if (it.seq == add.seq) it.copy(videoIds = left) else it }
    }

    /** [queue] after the edit [seq] went through. */
    fun done(queue: List<PlaylistEdit>, seq: Long): List<PlaylistEdit> = queue.filterNot { it.seq == seq }

    /**
     * [queue] after YouTube Music turned down the edit [seq]: tried again
     * later, or dropped. A create is dropped at once: the one way it is known
     * to fail after answering is making the playlist and not saying its id,
     * and trying again would make another.
     */
    fun refused(queue: List<PlaylistEdit>, seq: Long): List<PlaylistEdit> = queue.mapNotNull {
        when {
            it.seq != seq -> it
            it.kind == PlaylistEdit.Kind.CREATE || it.refusals + 1 >= MAX_REFUSALS -> null
            else -> it.copy(refusals = it.refusals + 1)
        }
    }

    /**
     * Whether [error] means "not now" rather than "no": no connection, a
     * server hiccup, or no session to send with. Those wait for the next
     * try without counting against the edit.
     */
    fun isTransient(error: Throwable): Boolean =
        error is IOException ||
                error is Innertube.NotSignedInException ||
                error is io.ktor.client.plugins.ServerResponseException
}

/** Folding what YouTube Music has into a linked playlist. */
object PlaylistMerge {
    /**
     * Whether [p]'s link to YouTube Music belongs to another account than
     * [account], the one signed in now. Accounts are told apart by email,
     * case aside; a link with no account recorded is never taken for another's.
     */
    fun forOtherAccount(p: LocalPlaylist, account: String): Boolean =
        p.synced && p.account != null && !p.account.equals(account, ignoreCase = true)

    /**
     * [local] brought up to date with [remote], for changes made on YouTube
     * Music itself (another app, the website):
     *
     *  - songs there that aren't here are added at the end, in its order;
     *  - songs here that were on YouTube Music (they have a set-video-id) and
     *    no longer are have been removed there, so they go here too;
     *  - songs here it never had (not sent yet, or a file on this phone)
     *    stay;
     *  - the name, and who can see it, follow YouTube Music.
     *
     * The order here is kept: moves made elsewhere aren't followed.
     *
     * An empty answer for a playlist that has songs on YouTube Music is not
     * believed. It is far more likely a page that didn't load properly than
     * every song removed, and believing it would empty the playlist here.
     */
    fun pull(local: LocalPlaylist, remote: RemotePlaylist): LocalPlaylist {
        val there = LinkedHashMap<String, String?>()
        val fresh = LinkedHashMap<String, Song>()
        remote.songs.forEach {
            there.putIfAbsent(it.videoId, it.setVideoId)
            fresh.putIfAbsent(it.videoId, it)
        }
        val wasThere = local.songs.any { it.setVideoId != null }
        if (there.isEmpty() && wasThere) return local
        val kept = local.songs.mapNotNull { s ->
            when {
                // A kept song's credit widens with the fresh read, under the
                // same rule as Liked: only by a credit naming it and more.
                s.videoId in there -> s.copy(
                    setVideoId = there[s.videoId] ?: s.setVideoId,
                    artist = fresh[s.videoId]?.let {
                        com.opentune.data.model.fullerArtist(s.artist, it.artist)
                    } ?: s.artist,
                )
                s.setVideoId != null -> null
                else -> s
            }
        }
        val have = kept.mapTo(HashSet()) { it.videoId }
        val added = remote.songs.filter { have.add(it.videoId) }.map { SongRef.of(it).copy(setVideoId = it.setVideoId) }
        return local.copy(
            name = remote.title.ifBlank { local.name },
            songs = kept + added,
            privacy = remote.privacy ?: local.privacy,
        )
    }
}

/** Which of the account's playlists to show, given the ones here linked to them. */
object PlaylistLinks {
    /** [account] without the playlists already shown as one of [local] that is linked to them. */
    fun unlinked(account: List<ShelfItem>, local: List<LocalPlaylist>): List<ShelfItem> {
        val linked = local.mapNotNullTo(HashSet()) { p -> p.remoteId?.let { "VL$it" } }
        return account.filterNot { it.browseId in linked }
    }

    /**
     * The account's playlists a song can be added to: its own, not Liked
     * Music or a mix, and not one already listed as a linked playlist here.
     * [owned] is what each playlist's page said about who made it; one whose
     * page hasn't been read yet is offered, since YouTube Music says so
     * plainly if it isn't the account's to change.
     */
    fun pickable(account: List<ShelfItem>, owned: Map<String, Boolean>, local: List<LocalPlaylist>): List<UserPlaylist> =
        InnertubeParser.parseUserPlaylists(unlinked(account, local)).filter { owned[it.browseId] != false }
}
