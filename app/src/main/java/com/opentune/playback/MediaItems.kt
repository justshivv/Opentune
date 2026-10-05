package com.opentune.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

fun trackUri(videoId: String): Uri =
    Uri.Builder().scheme(TRACK_SCHEME).authority(TRACK_HOST).appendPath(videoId).build()

/** The video id a [trackUri] stands for, or null for any other URI. */
fun videoIdOf(uri: Uri): String? =
    if (uri.scheme == TRACK_SCHEME && uri.authority == TRACK_HOST) uri.lastPathSegment else null

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(trackUri(videoId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setExtras(Bundle().apply { putString(EXTRA_DURATION_TEXT, durationText) })
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
    )
