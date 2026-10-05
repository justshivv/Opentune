package com.opentune.data

import com.opentune.data.history.History
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Explore as one canvas: artists to jump to, then moods, genres, new
 * albums and chart playlists mixed together in a two-column board.
 */
object ExploreFeed {
    enum class Kind(val label: String) { MOOD("Moods"), GENRE("Genres"), NEW("New releases"), CHART("Charts") }

    sealed interface Tile {
        val kind: Kind
        val key: String

        data class Mood(val mood: MoodGenre, override val kind: Kind) : Tile {
            override val key get() = "m:${mood.browseId}:${mood.params}"
        }

        data class Item(val item: ShelfItem, val from: String, override val kind: Kind) : Tile {
            override val key get() = "i:${item.browseId ?: item.videoId}"
        }
    }

    /** An artist pill: a page to open, a name and maybe a photo. */
    data class Artist(val browseId: String, val name: String, val thumbnailUrl: String?)

    data class Canvas(val artists: List<Artist>, val tiles: List<Tile>)

    suspend fun load(): Canvas = coroutineScope {
        val moods = async { runCatching { MusicRepository.moodsAndGenres() }.getOrDefault(emptyList()) }
        val releases = async { runCatching { MusicRepository.shelves("FEmusic_new_releases", null) }.getOrDefault(emptyList()) }
        val charts = async { runCatching { MusicRepository.shelves("FEmusic_charts", null) }.getOrDefault(emptyList()) }

        val chartShelves = charts.await()
        val chartArtists = chartShelves.flatMap { it.items }.filter { it.browseId?.startsWith("UC") == true }
            .map { Artist(it.browseId!!, it.title, it.thumbnailUrl) }
        val mine = run {
            val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            History.replay(History.records.value, since).topArtists.take(8).mapNotNull { e ->
                val id = e.song?.artistId?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
                Artist(id, e.title, chartArtists.firstOrNull { it.browseId == id }?.thumbnailUrl)
            }
        }
        val artists = (mine + chartArtists).distinctBy { it.browseId }.take(24)

        val moodTiles = moods.await().flatMap { section ->
            val kind = if (section.title.contains("genre", ignoreCase = true)) Kind.GENRE else Kind.MOOD
            section.items.map { Tile.Mood(it, kind) }
        }
        val newTiles = releases.await().flatMap { shelf ->
            shelf.items.filter { it.browseId != null }.map { Tile.Item(it, shelf.title, Kind.NEW) }
        }
        val chartTiles = chartShelves.flatMap { shelf ->
            shelf.items.filter { it.browseId != null && !it.browseId.startsWith("UC") }.map { Tile.Item(it, shelf.title, Kind.CHART) }
        }
        Canvas(artists, weave(listOf(moodTiles, newTiles, chartTiles)).distinctBy { it.key })
    }

    /**
     * Takes from each list in turn, so the board mixes them instead of
     * showing every mood before the first album.
     */
    internal fun <T> weave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        val iterators = lists.map { it.iterator() }
        while (iterators.any { it.hasNext() }) iterators.forEach { if (it.hasNext()) out += it.next() }
        return out
    }
}
