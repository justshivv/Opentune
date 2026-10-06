package com.opentune.data.spotify

import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube Music (and YouTube) playlists by link, read in full: the first
 * page, then every continuation page until the playlist ends.
 */
object YouTubePlaylists {
    /** At 100 songs a page, enough for 10,000 songs. */
    private const val MAX_PAGES = 100

    data class Playlist(val id: String, val name: String, val subtitle: String, val thumbnailUrl: String?, val songs: List<Song>)

    private val LIST = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")
    private val BROWSE = Regex("""music\.youtube\.com/browse/VL([A-Za-z0-9_-]{10,})""")
    private val HOSTS = Regex("""https?://(?:music\.|www\.|m\.)?youtube\.com/|https?://youtu\.be/""")

    /** The playlist id a YouTube Music or YouTube link names, if any. */
    fun parse(text: String): String? {
        if (!HOSTS.containsMatchIn(text)) return null
        return (LIST.find(text) ?: BROWSE.find(text))?.groupValues?.get(1)
    }

    /** The playlist's name and every song in it; [onPage] hears the count as pages arrive. */
    suspend fun load(id: String, onPage: (Int) -> Unit = {}): Playlist = withContext(Dispatchers.IO) {
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        val first = Innertube.browse(browseId)
        val header = InnertubeParser.parseBrowseHeader(first)
        val page = InnertubeParser.parsePlaylistShelf(first)
        val songs = (page?.songs?.takeIf { it.isNotEmpty() } ?: InnertubeParser.collectSongsDeep(first)).toMutableList()
        onPage(songs.size)
        var token = page?.continuation
        var pages = 1
        while (token != null && pages < MAX_PAGES) {
            val more = InnertubeParser.parsePlaylistContinuation(Innertube.browseContinuation(token))
            if (more.songs.isEmpty()) break
            songs += more.songs
            onPage(songs.size)
            token = more.continuation
            pages++
        }
        if (songs.isEmpty()) throw java.io.IOException("This playlist is empty or private")
        Playlist(
            id = id,
            name = header?.title?.takeIf { it.isNotBlank() } ?: "YouTube Music playlist",
            subtitle = header?.subtitle.orEmpty(),
            thumbnailUrl = header?.thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl,
            songs = songs.distinctBy { it.videoId },
        )
    }
}
