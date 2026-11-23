package com.opentune.playback

import com.opentune.data.model.QueueTier

/**
 * Where a queue entry sits on its album, as far as its details say: which album (by its
 * browse id, or failing that its name and artist), its track number when known, and its
 * place in the list it was queued from (an album page queues in the album's order).
 */
data class AlbumSpot(
    val albumId: String? = null,
    val albumName: String? = null,
    val artist: String? = null,
    val trackNumber: Int? = null,
    val listPosition: Int? = null,
    val tier: QueueTier = QueueTier.CONTEXT,
)

/**
 * The gapless-album guard: whether [next] carries straight on from [current] on one album in
 * its own order (a live album, a continuous mix), so the two play as recorded, never blended.
 *
 * [adjacent] is whether [next] is the entry right after [current] in the queue, played in queue
 * order (not shuffled by the player). The album is the same when both have one browse id, or,
 * without ids, the same album name by the same artist. The order is told by track numbers when
 * both have one, else by their places in the list they were queued from; with neither, two
 * neighbouring entries of the list itself (not queued by hand or by autoplay) count as in order.
 */
fun albumRunsOn(current: AlbumSpot, next: AlbumSpot, adjacent: Boolean): Boolean {
    if (!adjacent || !sameAlbum(current, next)) return false
    val a = current.trackNumber
    val b = next.trackNumber
    if (a != null && b != null && a > 0 && b > 0) return b == a + 1
    val p = current.listPosition
    val q = next.listPosition
    if (p != null && q != null) return q == p + 1
    return current.tier == QueueTier.CONTEXT && next.tier == QueueTier.CONTEXT
}

private fun sameAlbum(a: AlbumSpot, b: AlbumSpot): Boolean {
    val x = a.albumId?.takeIf { it.isNotBlank() }
    val y = b.albumId?.takeIf { it.isNotBlank() }
    if (x != null && y != null) return x == y
    val nameA = a.albumName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val nameB = b.albumName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (!nameA.equals(nameB, ignoreCase = true)) return false
    val artistA = a.artist?.trim().orEmpty()
    val artistB = b.artist?.trim().orEmpty()
    return artistA.isNotEmpty() && artistA.equals(artistB, ignoreCase = true)
}
