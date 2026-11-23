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
        val moods = async { attempt { MusicRepository.moodsAndGenres() } }
        val releases = async { attempt { MusicRepository.shelves("FEmusic_new_releases", null) } }
        val charts = async { attempt { MusicRepository.chartShelves() } }
        val mine = attempt { MusicRepository.personalArtists() }.getOrDefault(emptyList())
        assemble(moods.await(), releases.await(), charts.await(), mine)
    }

    /**
     * The board from its three sources, each of which may have failed: a part that failed is left
     * out and the rest is shown (YouTube's moods page answers 404 in some places). Only when every
     * part failed is the first failure thrown, so the page offers Retry instead of saying there's
     * nothing to explore.
     */
    fun assemble(
        moods: Result<List<com.opentune.data.model.MoodGenreSection>>,
        releases: Result<List<com.opentune.data.model.HomeShelf>>,
        charts: Result<List<com.opentune.data.model.HomeShelf>>,
        mine: List<Artist> = emptyList(),
    ): Canvas {
        if (moods.isFailure && releases.isFailure && charts.isFailure) throw moods.exceptionOrNull()!!
        val chartShelves = charts.getOrDefault(emptyList())
        val artists = mine.ifEmpty { artistsOf(chartShelves) }.distinctBy { it.browseId }.take(24)

        val moodTiles = moods.getOrDefault(emptyList()).flatMap { section ->
            val kind = if (section.title.contains("genre", ignoreCase = true)) Kind.GENRE else Kind.MOOD
            section.items.map { Tile.Mood(it, kind) }
        }
        val newTiles = releases.getOrDefault(emptyList()).flatMap { shelf ->
            shelf.items.filter { it.browseId != null }.map { Tile.Item(it, shelf.title, Kind.NEW) }
        }
        val chartTiles = chartShelves.flatMap { shelf ->
            shelf.items.filter { it.browseId?.startsWith("UC") == false }.map { Tile.Item(it, shelf.title, Kind.CHART) }
        }
        return Canvas(artists, weave(listOf(moodTiles, newTiles, chartTiles)).distinctBy { it.key })
    }

    /** [block]'s result, or its failure; a cancellation still cancels. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** The artists on a page's shelves (the charts' "Top artists"), each once, in order. */
    fun artistsOf(shelves: List<com.opentune.data.model.HomeShelf>): List<Artist> =
        shelves.flatMap { it.items }.filter { it.browseId?.startsWith("UC") == true }
            .map { Artist(it.browseId!!, it.title, it.thumbnailUrl) }
            .distinctBy { it.browseId }

    /**
     * Takes from each list in turn, so the board mixes them instead of
     * showing every mood before the first album.
     */
    fun <T> weave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        val iterators = lists.map { it.iterator() }
        while (iterators.any { it.hasNext() }) iterators.forEach { if (it.hasNext()) out += it.next() }
        return out
    }
}
