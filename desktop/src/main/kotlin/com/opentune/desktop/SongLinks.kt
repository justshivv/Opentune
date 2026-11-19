package com.opentune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.opentune.data.AlbumMatch
import com.opentune.data.isYouTubeId
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A song's artist and album pages, found as the phone finds them (MusicRepository's
 * artistIdFor and albumIdFor): the song's own ids when it has them, else what YouTube Music's
 * watch data links it to, else a search for the same song or artist. Most songs the desktop
 * plays from history, the queue or a video have no ids of their own, so their names led
 * nowhere when clicked.
 */
internal object SongLinks {
    private val artists = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val albums = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun firstArtist(song: Song) = song.artist.split(", ", " & ").first().trim()

    suspend fun artistId(song: Song, name: String = firstArtist(song)): String? {
        val wanted = name.trim()
        if (wanted.equals(firstArtist(song), ignoreCase = true)) song.artistId?.takeIf { it.startsWith("UC") }?.let { return it }
        val key = "${song.videoId}|${wanted.lowercase()}"
        artists[key]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            if (isYouTubeId(song.videoId)) safely {
                InnertubeParser.watchArtistLinks(Innertube.next(song.videoId), song.videoId)[wanted.lowercase()]
            }?.let { return@withContext it }
            repeat(2) {
                safely {
                    val rows = InnertubeParser.parseSearchPage(Innertube.search(wanted, SearchFilter.ARTISTS.params)).rows
                        .mapNotNull { (it as? SearchResult.Browse)?.item?.takeIf { b -> b.type == BrowseType.ARTIST } }
                    (rows.firstOrNull { it.title.equals(wanted, ignoreCase = true) } ?: rows.firstOrNull())?.browseId
                }?.let { return@withContext it }
            }
            null
        }
        if (found != null) artists[key] = found
        return found
    }

    suspend fun albumId(song: Song): String? {
        song.albumId?.let { return it }
        albums[song.videoId]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            if (isYouTubeId(song.videoId)) safely {
                InnertubeParser.parseWatchQueue(Innertube.next(song.videoId), allVersions = true).firstOrNull { it.videoId == song.videoId }?.albumId
            }?.let { return@withContext it }
            val first = firstArtist(song)
            safely {
                val tracks = InnertubeParser.parseSearchPage(Innertube.search("${AlbumMatch.bareTitle(song.title)} $first", SearchFilter.SONGS.params)).rows
                    .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                // Only the same recording, or the same title by one of the same artists.
                (tracks.firstOrNull { it.videoId == song.videoId }
                    ?: tracks.firstOrNull { it.albumId != null && AlbumMatch.sameTitle(it.title, song.title) && AlbumMatch.sharesArtist(it.artist, song.artist) })?.albumId
            }?.let { return@withContext it }
            // `Song (From "Film")`: the film's soundtrack, by a name in common.
            AlbumMatch.fromTitle(song.title)?.let { from ->
                safely {
                    InnertubeParser.parseSearchPage(Innertube.search("$from $first", SearchFilter.ALBUMS.params)).rows
                        .mapNotNull { (it as? SearchResult.Browse)?.item?.takeIf { i -> i.type == BrowseType.ALBUM } }
                        .firstOrNull { AlbumMatch.sameTitle(it.title, from) && AlbumMatch.sharesArtist(it.subtitle, song.artist) }?.browseId
                }
            }
        }
        if (found != null) albums[song.videoId] = found
        return found
    }

    private inline fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Opens [song]'s artist page ([name] among several), finding it first if the song doesn't say. */
@Composable
internal fun rememberOpenArtist(): (Song, String?) -> Unit {
    val open = LocalOpen.current
    val scope = rememberCoroutineScope()
    return { song, name ->
        val who = name ?: song.artist.split(", ", " & ").first().trim()
        scope.launch {
            val id = if (name == null) SongLinks.artistId(song) else SongLinks.artistId(song, name)
            if (id != null) open(ShelfItem(who, "Artist", null, null, id)) else Notice.show("Couldn't find $who's page")
        }
    }
}

/** Opens [song]'s album, finding it first if the song doesn't say. */
@Composable
internal fun rememberOpenAlbum(): (Song) -> Unit {
    val open = LocalOpen.current
    val scope = rememberCoroutineScope()
    return { song ->
        scope.launch {
            val id = SongLinks.albumId(song)
            if (id != null) open(ShelfItem(song.albumName ?: song.title, "Album • ${song.artist}", song.thumbnailUrl, null, id))
            else Notice.show("Couldn't find the album for \"${song.title}\"")
        }
    }
}
