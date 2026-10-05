package com.opentune.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.BrowseType
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto (and any other media browser) can browse and play:
 *
 *  - Home: YouTube Music's shelves, each shown as a titled group;
 *  - Recent: this device's listening history;
 *  - Library: Liked, Downloads, playlists made in the app and the
 *    account's YouTube playlists;
 *  - search, typed or spoken.
 *
 * Albums, playlists and artists open as their own pages. A song carries the
 * list it was shown in (`<parent>|<videoId>`), so tapping it queues that list
 * from there, as tapping a song in the app does; [resolve] turns those back
 * into the plain queue items the rest of the app uses.
 */
@UnstableApi
class CarLibrary(private val context: Context) {
    /** The songs last listed under each node, for queueing a tapped song's list. */
    private val listed = ConcurrentHashMap<String, List<Song>>()

    /** Every item handed out, so a browser can look one up again by id. */
    private val known = ConcurrentHashMap<String, MediaItem>()

    private var homeCache: Pair<Long, List<HomeShelf>>? = null

    fun root(): MediaItem = folder(ROOT, "OpenTune", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)

    /** Extras for the root: search is supported, folders show as a grid and songs as a list. */
    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_SEARCH_SUPPORTED, true)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    fun item(mediaId: String): MediaItem? = known[mediaId] ?: if (mediaId == ROOT) root() else null

    suspend fun children(parentId: String, browser: MediaSession.ControllerInfo?): List<MediaItem> {
        val items = when {
            parentId == ROOT -> listOf(
                folder(HOME, "Home", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
                folder(RECENT, "Recent"),
                folder(LIBRARY, "Library", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            )
            parentId == HOME -> homeItems()
            parentId == RECENT -> songs(RECENT, History.recents(History.records.value, MAX_SONGS))
            parentId == LIBRARY -> libraryItems()
            parentId == LIKED -> songs(LIKED, LibraryStore.liked.value.map { it.toSong() })
            parentId == DOWNLOADS -> songs(DOWNLOADS, Downloads.doneNow().map { it.song.toSong() })
            parentId.startsWith(PLAYLIST) -> {
                val playlist = LibraryStore.playlists.value.firstOrNull { it.id == parentId.removePrefix(PLAYLIST) }
                songs(parentId, playlist?.songs.orEmpty().map { it.toSong() })
            }
            parentId.startsWith(COLLECTION) ->
                songs(parentId, MusicRepository.collection(parentId.removePrefix(COLLECTION)).songs)
            parentId.startsWith(ARTIST) -> artistItems(parentId)
            else -> emptyList()
        }
        grantArtwork(items, browser)
        return items
    }

    suspend fun search(query: String, browser: MediaSession.ControllerInfo): List<MediaItem> {
        val results = MusicRepository.search(query, SearchFilter.ALL)
        val tracks = results.mapNotNull(::songOf)
        listed[SEARCH] = tracks
        val items = results.mapNotNull { result ->
            when (result) {
                is SearchResult.TopTrack -> song(SEARCH, result.song)
                is SearchResult.Track -> song(SEARCH, result.song)
                is SearchResult.Browse -> browseFolder(result.item.browseId, result.item.title, result.item.subtitle, result.item.thumbnailUrl, null)
            }
        }.take(MAX_SONGS)
        grantArtwork(items, browser)
        return items
    }

    /**
     * Items a browser asked to play, as queue items. A single tapped song
     * brings its list with it; a spoken request ("play … on OpenTune")
     * plays the best match, and autoplay carries on after it.
     */
    suspend fun resolve(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val single = items.singleOrNull()
        if (single != null) {
            val query = single.requestMetadata.searchQuery
            if (single.mediaId.isEmpty() && query != null) return voice(query)
            val (parent, videoId) = split(single.mediaId) ?: (null to single.mediaId)
            if (parent != null) {
                val songs = listed[parent] ?: run {
                    runCatching { children(parent, null) }
                    listed[parent]
                }
                val at = songs?.indexOfFirst { it.videoId == videoId } ?: -1
                if (songs != null && at >= 0) {
                    return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, at, startPositionMs)
                }
            }
        }
        return MediaSession.MediaItemsWithStartPosition(items.map(::toQueueItem), startIndex, startPositionMs)
    }

    /** A queue item for anything a controller sends: the app's own, or a browsed song. */
    fun toQueueItem(item: MediaItem): MediaItem {
        val videoId = split(item.mediaId)?.second ?: item.mediaId
        if (videoId == item.mediaId) return item.buildUpon().setUri(trackUri(videoId)).build()
        val known = listed.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.videoId == videoId } }
        val song = known ?: Song(
            videoId = videoId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            thumbnailUrl = null,
        )
        return song.toMediaItem()
    }

    private suspend fun voice(query: String): MediaSession.MediaItemsWithStartPosition {
        val songs = if (query.isBlank()) {
            // "Play music": what was played lately, or the liked songs.
            History.recents(History.records.value, MAX_SONGS).ifEmpty { LibraryStore.liked.value.map { it.toSong() } }.shuffled()
        } else {
            // The mixed search leads with YouTube's best match; songs only if it has none.
            MusicRepository.search(query, SearchFilter.ALL).firstNotNullOfOrNull(::songOf)?.let(::listOf)
                ?: MusicRepository.search(query, SearchFilter.SONGS).mapNotNull(::songOf).take(1)
        }
        Log.d(TAG, "voice request \"$query\": ${songs.size} song(s)")
        return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, 0L)
    }

    private suspend fun homeItems(): List<MediaItem> {
        val now = System.currentTimeMillis()
        val shelves = homeCache?.takeIf { now - it.first < HOME_FRESH_MS }?.second
            ?: (MusicRepository.home() + MusicRepository.homeMore.value).also { homeCache = now to it }
        return shelves.take(MAX_SHELVES).flatMapIndexed { index, shelf ->
            val parent = "$HOME_SHELF$index"
            val shelfSongs = shelf.items.mapNotNull { it.toSongOrNull() }
            listed[parent] = shelfSongs
            shelf.items.take(PER_SHELF).mapNotNull { item ->
                val built = if (item.browseId != null) {
                    browseFolder(item.browseId, item.title, item.subtitle, item.thumbnailUrl, shelf.title)
                } else {
                    item.toSongOrNull()?.let { song(parent, it, group = shelf.title) }
                }
                built
            }
        }
    }

    private suspend fun libraryItems(): List<MediaItem> = buildList {
        val liked = LibraryStore.liked.value
        add(folder(LIKED, "Liked songs", subtitle = "${liked.size} songs", artwork = liked.firstOrNull()?.thumbnailUrl))
        val downloads = Downloads.doneNow()
        add(folder(DOWNLOADS, "Downloads", subtitle = "${downloads.size} songs", artwork = downloads.firstOrNull()?.song?.thumbnailUrl))
        LibraryStore.playlists.value.forEach { p ->
            add(folder("$PLAYLIST${p.id}", p.name, subtitle = "${p.songs.size} songs", artwork = p.songs.firstOrNull()?.thumbnailUrl, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
        }
        if (AccountStore.signedIn.value) {
            runCatching { MusicRepository.libraryPlaylists() }.getOrDefault(emptyList()).forEach { p ->
                p.browseId?.let { add(browseFolder(it, p.title, p.subtitle, p.thumbnailUrl, null)) }
            }
        }
    }

    private suspend fun artistItems(parentId: String): List<MediaItem> {
        val page = MusicRepository.artist(parentId.removePrefix(ARTIST))
        val top = songs(parentId, page.songs.take(MAX_SONGS), group = "Top songs")
        val more = page.sections.flatMap { section ->
            section.items.take(PER_SHELF).mapNotNull { item ->
                item.browseId?.let { browseFolder(it, item.title, item.subtitle, item.thumbnailUrl, section.title) }
            }
        }
        return top + more
    }

    private fun songs(parent: String, songs: List<Song>, group: String? = null): List<MediaItem> {
        listed[parent] = songs
        return songs.take(MAX_SONGS).map { song(parent, it, group) }
    }

    private fun song(parent: String, song: Song, group: String? = null): MediaItem {
        val id = "$parent$SEP${song.videoId}"
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(CarArtwork.uriFor(context, song.videoId, song.thumbnailUrl))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(groupExtras(group))
                    .build(),
            )
            .build()
            .also { known[id] = it }
    }

    /** An album, playlist or artist: a page of its own. */
    private fun browseFolder(browseId: String, title: String, subtitle: String, art: String?, group: String?): MediaItem =
        when (MusicRepository.typeOf(browseId)) {
            BrowseType.ARTIST -> folder("$ARTIST$browseId", title, subtitle, art, MediaMetadata.MEDIA_TYPE_ARTIST, group)
            BrowseType.ALBUM -> folder("$COLLECTION$browseId", title, subtitle, art, MediaMetadata.MEDIA_TYPE_ALBUM, group)
            else -> folder("$COLLECTION$browseId", title, subtitle, art, MediaMetadata.MEDIA_TYPE_PLAYLIST, group)
        }

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        artwork: String? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        group: String? = null,
        browsableStyle: Int? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle?.takeIf { it.isNotBlank() })
                .setArtworkUri(CarArtwork.uriFor(context, null, artwork))
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(type)
                .setExtras(
                    groupExtras(group).apply {
                        browsableStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
                    },
                )
                .build(),
        )
        .build()
        .also { known[id] = it }

    private fun groupExtras(group: String?) = Bundle().apply {
        group?.takeIf { it.isNotBlank() }?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
    }

    /** Lets the browser that asked read the artwork this app serves it. */
    private fun grantArtwork(items: List<MediaItem>, browser: MediaSession.ControllerInfo?) {
        val pkg = browser?.packageName?.takeIf { it.isNotBlank() && it != context.packageName } ?: return
        items.mapNotNull { it.mediaMetadata.artworkUri }.filter { it.scheme == "content" }.forEach { uri ->
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun ShelfItem.toSongOrNull(): Song? = videoId?.takeIf { browseId == null }?.let {
        Song(videoId = it, title = title, artist = subtitle.substringBefore(" • "), thumbnailUrl = thumbnailUrl)
    }

    private fun songOf(result: SearchResult): Song? = when (result) {
        is SearchResult.TopTrack -> result.song
        is SearchResult.Track -> result.song
        is SearchResult.Browse -> null
    }

    private fun split(mediaId: String): Pair<String, String>? {
        val at = mediaId.lastIndexOf(SEP)
        return if (at <= 0) null else mediaId.substring(0, at) to mediaId.substring(at + 1)
    }

    companion object {
        private const val TAG = "CarLibrary"
        const val ROOT = "root"
        const val HOME = "home"
        const val RECENT = "recent"
        const val LIBRARY = "library"
        const val LIKED = "liked"
        const val DOWNLOADS = "downloads"
        const val SEARCH = "search"
        private const val HOME_SHELF = "home:"
        private const val PLAYLIST = "playlist:"
        private const val COLLECTION = "collection:"
        private const val ARTIST = "artist:"
        private const val SEP = '|'
        private const val MAX_SONGS = 100
        private const val MAX_SHELVES = 12
        private const val PER_SHELF = 8
        private const val HOME_FRESH_MS = 10 * 60 * 1000L
        /** Android Auto shows its search box when the root says so. */
        private const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}

/**
 * Artwork URIs Android Auto can load. It won't fetch web addresses for the
 * items it browses, so covers go through [ArtworkProvider] as content URIs:
 * a download's own cover file when there is one, the web image otherwise.
 */
object CarArtwork {
    fun uriFor(context: Context, videoId: String?, url: String?): Uri? {
        val authority = "${context.packageName}.artwork"
        if (videoId != null && Downloads.artFor(videoId) != null) {
            return Uri.Builder().scheme("content").authority(authority).appendPath("download").appendPath(videoId).build()
        }
        val web = url?.artworkAt(CARD_ART_PX) ?: return null
        return Uri.Builder().scheme("content").authority(authority).appendPath("web").appendQueryParameter("u", web).build()
    }
}
