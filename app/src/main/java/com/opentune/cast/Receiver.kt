package com.opentune.cast

import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow

/** Something that plays what's cast to it: a Chromecast or a DLNA renderer. */
interface Receiver {
    val name: String

    /** What it's doing, as it last said. */
    val status: StateFlow<RemoteStatus>

    /** Loads [media] and starts it at [positionMs], playing or paused. */
    suspend fun load(media: CastMedia, positionMs: Long, play: Boolean)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)

    /** Its own volume, 0 to 1. */
    suspend fun setVolume(level: Float)

    /** Stops it playing and lets it go. */
    fun close()
}

/** A song as it's handed to a receiver: where to fetch it from and what to show. */
data class CastMedia(
    val id: String,
    val url: String,
    val contentType: String,
    val title: String,
    val artist: String,
    val album: String?,
    val imageUrl: String?,
    val durationMs: Long,
    /** A radio station, with no end. */
    val live: Boolean = false,
)

enum class RemoteState { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, FINISHED, ERROR, GONE }

/**
 * A receiver's report: its [state], and [positionMs] as of [at] (the phone's
 * elapsedRealtime), so the position now can be worked out between reports.
 */
data class RemoteStatus(
    val state: RemoteState = RemoteState.IDLE,
    val positionMs: Long = 0,
    val at: Long = 0,
    val volume: Float? = null,
    /** Which song the report is about, when the receiver says. */
    val mediaId: String? = null,
) {
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long =
        if (state == RemoteState.PLAYING) positionMs + (now - at).coerceAtLeast(0) else positionMs
}
