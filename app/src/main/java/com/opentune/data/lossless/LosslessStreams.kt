package com.opentune.data.lossless

import com.opentune.data.settings.AppSettings
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Pins each FLAC rendition to a unique cache key; expired URLs never become YouTube bytes. */
object LosslessStreams {
    data class Rendition(val key: String, val track: LosslessSource.Track, val at: Long)
    private val renditions = ConcurrentHashMap<String, Rendition>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private const val TTL = 10 * 60_000L

    fun allowed(): Boolean = AppSettings.playback.value.let {
        it.losslessStreaming && (!it.losslessUnmeteredOnly || !AppSettings.onMeteredNetwork())
    }

    suspend fun find(videoId: String, durationMs: Long): Rendition? {
        if (!allowed() || durationMs <= 0) return null
        val now = now()
        if ((failedUntil[videoId] ?: 0) > now) return null
        renditions[videoId]?.takeIf { now - it.at < TTL }?.let { return it }
        val track = LosslessSource.resolve(videoId, durationMs, AppSettings.playback.value.losslessHiRes)
        if (track == null) {
            if (failedUntil.size > 256) failedUntil.clear()
            failedUntil[videoId] = now() + 60_000
            return null
        }
        // Even two FLACs of the same recording can have different byte offsets.
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), track, now())
        if (renditions.size > 256) renditions.clear()
        renditions[videoId] = rendition
        return rendition
    }

    fun get(videoId: String, key: String): Rendition? = renditions[videoId]?.takeIf { it.key == key }

    fun resolve(videoId: String, key: String): String {
        val rendition = get(videoId, key)
        if (!allowed() || rendition == null || now() - rendition.at >= TTL) {
            throw IOException("Lossless rendition expired; resume the original stream")
        }
        return rendition.track.url
    }

    fun failed(videoId: String) {
        renditions.remove(videoId)
        failedUntil[videoId] = now() + TTL
    }

    private fun now(): Long = System.nanoTime() / 1_000_000
}
