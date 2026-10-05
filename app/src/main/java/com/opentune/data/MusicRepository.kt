package com.opentune.data

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.MoodGenreSection
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    private var homeFile: File? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Remembers where Home is kept, and returns the copy saved last time, so
     * the app can open on it while the fresh one loads.
     */
    fun init(context: Context): List<HomeShelf>? {
        val f = File(context.filesDir, "home.json")
        homeFile = f
        return runCatching { json.decodeFromString(ListSerializer(HomeShelf.serializer()), f.readText()) }.getOrNull()
    }

    suspend fun home(): List<HomeShelf> = io {
        InnertubeParser.parseHome(Innertube.browse("FEmusic_home")).filter { it.items.isNotEmpty() }
            .also { shelves ->
                homeFile?.let { f ->
                    runCatching {
                        val tmp = File(f.parentFile, "home.json.tmp")
                        tmp.writeText(json.encodeToString(ListSerializer(HomeShelf.serializer()), shelves))
                        tmp.renameTo(f)
                    }
                }
            }
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

    /** The signed-in account's playlists on YouTube Music, without the "New playlist" tile. */
    suspend fun libraryPlaylists(): List<ShelfItem> = io {
        InnertubeParser.parseLibraryItems(Innertube.browse("FEmusic_liked_playlists")).filter { it.browseId != null }
    }

    /** The song and the radio YouTube Music queues after it. */
    suspend fun watchQueue(videoId: String): List<Song> = io {
        InnertubeParser.parseWatchQueue(Innertube.next(videoId))
    }

    /**
     * The artist page for [song]: its own id when the row carried one, else
     * the one YouTube Music's watch data credits, else the top artist result
     * for the name.
     */
    suspend fun artistIdFor(song: Song): String? = song.artistId ?: io {
        runCatching { InnertubeParser.parseWatchQueue(Innertube.next(song.videoId)).firstOrNull { it.videoId == song.videoId }?.artistId }
            .getOrNull()
            ?: runCatching {
                val name = song.artist.split(", ", " & ").first().trim()
                InnertubeParser.parseSearchPage(Innertube.search(name, SearchFilter.ARTISTS.params)).rows
                    .firstNotNullOfOrNull { (it as? SearchResult.Browse)?.item?.takeIf { b -> b.type == BrowseType.ARTIST }?.browseId }
            }.getOrNull()
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
