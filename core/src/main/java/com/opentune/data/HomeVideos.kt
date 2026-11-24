package com.opentune.data

import com.opentune.data.model.ArtistPage
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem

/**
 * Home's own rows of music videos and live performances, made from artist pages: each page has
 * a row of the artist's music videos and, for many, one of live performances (the first and
 * second rows of wide cards, whatever their titles say in the listener's language). YouTube's
 * own Home only sometimes has such rows, and signed out never, so Home builds them itself from
 * the artists played most, followed, or popular where the listener is.
 */
object HomeVideos {
    /** The most cards a row holds. */
    const val MAX = 20

    /** At least this many cards, or the row isn't worth showing. */
    private const val MIN = 3

    /**
     * The two rows from [pages], in the order the artists matter: the artists take turns, so
     * the first cards are each one's best known video rather than one artist's whole row.
     * A video in both rows, or in two artists' rows, shows once.
     */
    fun shelves(pages: List<ArtistPage>, videosTitle: String, liveTitle: String): List<HomeShelf> {
        val seen = HashSet<String>()
        val videos = takeTurns(pages.map { it.videoShelves.getOrNull(0)?.items.orEmpty() }, seen)
        val live = takeTurns(pages.map { it.videoShelves.getOrNull(1)?.items.orEmpty() }, seen)
        return listOfNotNull(
            videos.takeIf { it.size >= MIN }?.let { HomeShelf(videosTitle, it, videos = true) },
            live.takeIf { it.size >= MIN }?.let { HomeShelf(liveTitle, it, videos = true) },
        )
    }

    private fun takeTurns(rows: List<List<ShelfItem>>, seen: MutableSet<String>): List<ShelfItem> {
        val out = ArrayList<ShelfItem>()
        val longest = rows.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (row in rows) {
                val card = row.getOrNull(i) ?: continue
                val id = card.videoId ?: continue
                if (seen.add(id)) out += card
                if (out.size >= MAX) return out
            }
        }
        return out
    }
}
