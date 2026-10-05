package com.opentune.data

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.MoodGenreSection
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An album or playlist page, ready to show. */
data class Collection(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
)

/**
 * The screens' one way into Innertube: every call runs off the main thread
 * and returns parsed models.
 */
object MusicRepository {

    suspend fun home(): List<HomeShelf> = io {
        InnertubeParser.parseHome(Innertube.browse("FEmusic_home")).filter { it.items.isNotEmpty() }
    }

    suspend fun moodsAndGenres(): List<MoodGenreSection> = io {
        InnertubeParser.parseMoodAndGenres(Innertube.browse("FEmusic_moods_and_genres"))
    }

    /** A mood or genre page is laid out like Home: carousels of playlists. */
    suspend fun shelves(browseId: String, params: String?): List<HomeShelf> = io {
        val response = Innertube.browse(browseId, params)
        InnertubeParser.parseHome(response).ifEmpty { InnertubeParser.parseHomeContinuation(response) }
            .filter { it.items.isNotEmpty() }
    }

    suspend fun search(query: String, filter: SearchFilter): List<SearchResult> = io {
        val response = Innertube.search(query, filter.params)
        InnertubeParser.parseSearchPage(response, includeVideos = filter == SearchFilter.VIDEOS).rows
    }

    suspend fun suggestions(input: String): List<String> = io {
        InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input))
    }

    suspend fun collection(browseId: String): Collection = io {
        val response = Innertube.browse(browseId)
        val header = InnertubeParser.parseBrowseHeader(response)
        val songs = InnertubeParser.parsePlaylistShelf(response)?.songs?.takeIf { it.isNotEmpty() }
            ?: InnertubeParser.collectSongsDeep(response)
        Collection(
            browseId = browseId,
            title = header?.title.orEmpty(),
            subtitle = header?.subtitle.orEmpty(),
            // Album rows carry no art of their own; the cover is the header's.
            thumbnailUrl = header?.thumbnailUrl,
            songs = songs.map { song -> if (song.thumbnailUrl == null) song.copy(thumbnailUrl = header?.thumbnailUrl) else song },
        )
    }

    suspend fun artist(browseId: String): ArtistPage = io {
        InnertubeParser.parseArtistPage(Innertube.browse(browseId))
    }

    /** What a browse id opens, read off its prefix. */
    fun typeOf(browseId: String): BrowseType = when {
        browseId.startsWith("UC") -> BrowseType.ARTIST
        browseId.startsWith("MPREb") -> BrowseType.ALBUM
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") ||
            browseId.startsWith("OLAK") || browseId.startsWith("MPSP") -> BrowseType.PLAYLIST
        else -> BrowseType.OTHER
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
