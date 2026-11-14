package com.opentune.data.library

import com.opentune.data.model.LikeStatus
import com.opentune.data.model.Song

internal data class LikedMergeResult(val songs: List<SongRef>, val agreed: Set<String>)

/** A first sign-in is a union; subsequent syncs reconcile against the last successful agreement. */
internal suspend fun mergeLikedSongs(
    local: List<SongRef>,
    remote: List<Song>,
    agreed: Set<String>?,
    disliked: Set<String>,
    rate: suspend (String, LikeStatus) -> Boolean,
): LikedMergeResult {
    val localIds = local.mapTo(HashSet()) { it.videoId }.filterNotTo(HashSet()) { it.startsWith("local:") }
    val remoteIds = remote.mapTo(HashSet()) { it.videoId }.filterNotTo(HashSet()) { it.startsWith("local:") }
    val baseline = agreed.orEmpty()
    val addedHere = localIds - remoteIds - baseline - disliked
    val removedHere = if (agreed == null) emptySet() else (baseline intersect remoteIds) - localIds - disliked
    val removedThere = if (agreed == null) emptySet() else (baseline intersect localIds) - remoteIds
    val addedThere = remoteIds - localIds - baseline - disliked
    val pushedLikes = addedHere.filterTo(HashSet()) { rate(it, LikeStatus.LIKE) }
    val pushedUnlikes = removedHere.filterTo(HashSet()) { rate(it, LikeStatus.INDIFFERENT) }
    val songs = (remote.filter { it.videoId in addedThere }.map { SongRef.of(it) } +
            local.filterNot { it.videoId in removedThere }).distinctBy { it.videoId }
    // Give every song that has no like time one, so the library's order
    // can be read off the times and no merge can scramble it again. The
    // account's list is itself in like order, so its songs take times a
    // step apart under this moment, in that order; local songs the
    // account doesn't have keep their own order just above it — they
    // were liked here. A song already stamped keeps its time: stamping
    // it again on a later sync would move a song that didn't move.
    val now = System.currentTimeMillis()
    val remoteIndex = HashMap<String, Int>()
    remote.forEachIndexed { i, s -> remoteIndex.putIfAbsent(s.videoId, i) }
    val localOnly = songs.filter { it.likedAt == 0L && it.videoId !in remoteIndex }
    val stamped = songs.map { ref ->
        when {
            ref.likedAt != 0L -> ref
            ref.videoId in remoteIndex -> ref.copy(likedAt = now - remoteIndex.getValue(ref.videoId))
            else -> ref.copy(likedAt = now + (localOnly.size - localOnly.indexOf(ref)))
        }
    }
    // A failed write stays outside the agreement, and will be retried on the next sync.
    val shared = stamped.mapTo(HashSet()) { it.videoId }.intersect(remoteIds + pushedLikes - pushedUnlikes)
    // Remember unsuccessful unlikes too, so the next run can still remove them remotely.
    return LikedMergeResult(stamped, shared + (removedHere - pushedUnlikes))
}

/** Preserve heart taps made while the remote list or writes were in flight. */
internal fun keepLikedEdits(start: List<SongRef>, current: List<SongRef>, merged: List<SongRef>): List<SongRef> {
    val before = start.mapTo(HashSet()) { it.videoId }
    val now = current.mapTo(HashSet()) { it.videoId }
    val removed = before - now
    return (current.filter { it.videoId !in before } + merged.filterNot { it.videoId in removed }).distinctBy { it.videoId }
}
