package com.opentune.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.opentune.data.download.Downloads
import com.opentune.data.innertube.UpgradedTracks
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song

/**
 * Queue entries point at `opentune://track/<videoId>` rather than at a stream
 * URL. [PlaybackService] swaps that for a real URL only when ExoPlayer opens
 * the item, so queueing twenty-five tracks costs nothing up front and no
 * entry sits in the queue holding a URL that has gone stale by its turn.
 */
private const val TRACK_SCHEME = "opentune"
private const val TRACK_HOST = "track"
private const val EXTRA_DURATION_TEXT = "durationText"
private const val EXTRA_ALBUM = "album"

/**
 * Local files play straight from MediaStore and downloads from their file;
 * everything else resolves on open.
 */
fun trackUri(videoId: String): Uri = when {
    LocalMusic.isLocal(videoId) -> LocalMusic.contentUri(videoId)
    else -> Downloads.fileFor(videoId)?.let(Uri::fromFile)
        ?: streamUri(videoId, upgraded = UpgradedTracks.contains(videoId))
}

/** A YouTube track's queue URI; [upgraded] asks for the better stream found mid-play. */
fun streamUri(videoId: String, upgraded: Boolean): Uri =
    Uri.Builder().scheme(TRACK_SCHEME).authority(TRACK_HOST).appendPath(videoId)
        .apply { if (upgraded) appendQueryParameter(QUALITY_PARAM, QUALITY_UPGRADED) }
        .build()

/** The video id a [trackUri] stands for, or null for any other URI. */
fun videoIdOf(uri: Uri): String? =
    if (uri.scheme == TRACK_SCHEME && uri.authority == TRACK_HOST) uri.lastPathSegment else null

fun isUpgradedUri(uri: Uri): Boolean = uri.getQueryParameter(QUALITY_PARAM) == QUALITY_UPGRADED

/** The song cache key: the video id, kept apart for an upgraded copy. */
fun cacheKeyOf(uri: Uri): String? = videoIdOf(uri)?.let { if (isUpgradedUri(uri)) "$it:hq" else it }

private const val QUALITY_PARAM = "q"
private const val QUALITY_UPGRADED = "hq"

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(trackUri(videoId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                // A downloaded cover keeps the notification and lock screen right offline.
                .setArtworkUri(Downloads.artFor(videoId)?.let(Uri::fromFile) ?: thumbnailUrl?.let(Uri::parse))
                .setAlbumTitle(albumName)
                .setExtras(
                    Bundle().apply {
                        putString(EXTRA_DURATION_TEXT, durationText)
                        putString(EXTRA_ALBUM, albumName)
                    },
                )
                .build(),
        )
        .build()

fun MediaItem.toSong(): Song =
    Song(
        videoId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        thumbnailUrl = mediaMetadata.artworkUri?.toString(),
        durationText = mediaMetadata.extras?.getString(EXTRA_DURATION_TEXT),
        albumName = mediaMetadata.extras?.getString(EXTRA_ALBUM) ?: mediaMetadata.albumTitle?.toString(),
    )

/**
 * Queue indices in the order they will play from here: the current item
 * first, then what follows it with shuffle applied and repeat ignored.
 */
fun Player.upcomingPlayOrder(): List<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    val out = mutableListOf(currentMediaItemIndex)
    var index = currentMediaItemIndex
    while (true) {
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
        if (index == C.INDEX_UNSET || out.size > timeline.windowCount) break
        out += index
    }
    return out
}
