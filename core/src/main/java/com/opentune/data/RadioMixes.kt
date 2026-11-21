package com.opentune.data

import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem

/**
 * The Radio page's order for YouTube Music's "Mixed for you" (see
 * [MusicRepository.mixedForYou]): the account's big mixes as tiles at the top, its numbered
 * "My Mix" cards in a row of their own, then a row for each mood with its Supermix first and
 * its numbered mixes after. The cards are known by their titles, so whatever YouTube sends in
 * another shape or language keeps the heading it came under, in [Page.others].
 */
object RadioMixes {
    /** The tiles, in this order, as YouTube Music titles them in English. */
    val TILES = listOf("My Supermix", "Discover Mix", "New Release Mix", "Replay Mix", "Archive Mix")

    private val MY_MIX = Regex("""^My Mix\s*(\d+)$""", RegexOption.IGNORE_CASE)
    private val MOOD_MIX = Regex("""^(.+?)\s+(Supermix|Mix\s*(\d+))$""", RegexOption.IGNORE_CASE)

    data class Row(val title: String, val items: List<ShelfItem>)

    data class Page(
        val tiles: List<ShelfItem> = emptyList(),
        val yours: List<ShelfItem> = emptyList(),
        val moods: List<Row> = emptyList(),
        val others: List<Row> = emptyList(),
    ) {
        val isEmpty: Boolean get() = tiles.isEmpty() && yours.isEmpty() && moods.isEmpty() && others.isEmpty()
    }

    fun arrange(shelves: List<HomeShelf>): Page {
        val seen = HashSet<String>()
        val tiles = HashMap<String, ShelfItem>()
        val yours = mutableListOf<Pair<Int, ShelfItem>>()
        val moods = LinkedHashMap<String, MutableList<Pair<Int, ShelfItem>>>()
        val moodNames = HashMap<String, String>()
        val others = mutableListOf<Row>()
        for (shelf in shelves) {
            val left = mutableListOf<ShelfItem>()
            for (item in shelf.items) {
                val id = item.browseId ?: item.videoId ?: continue
                if (!seen.add(id)) continue
                val title = item.title.trim()
                val tile = TILES.firstOrNull { it.equals(title, ignoreCase = true) }
                val my = MY_MIX.find(title)
                val mood = MOOD_MIX.find(title)?.takeUnless { it.groupValues[1].equals("My", ignoreCase = true) }
                when {
                    tile != null -> if (tile !in tiles) tiles[tile] = item
                    my != null -> yours += my.groupValues[1].toInt() to item
                    mood != null -> {
                        val name = mood.groupValues[1].trim()
                        val key = name.lowercase()
                        moodNames.getOrPut(key) { name }
                        // The Supermix leads its row, the numbered ones follow in order.
                        val rank = mood.groupValues[3].toIntOrNull() ?: 0
                        moods.getOrPut(key) { mutableListOf() } += rank to item
                    }
                    else -> left += item
                }
            }
            if (left.isNotEmpty()) others += Row(shelf.title, left)
        }
        return Page(
            tiles = TILES.mapNotNull(tiles::get),
            yours = yours.sortedBy { it.first }.map { it.second },
            moods = moods.map { (key, items) -> Row(moodNames.getValue(key), items.sortedBy { it.first }.map { it.second }) },
            others = others,
        )
    }
}
