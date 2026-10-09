package com.opentune.data.lossless

import com.opentune.data.settings.AppSettings
import com.opentune.data.model.Song
import com.opentune.data.innertube.StreamResolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Pins each external rendition to a unique cache key; expired URLs never become YouTube bytes. */
object ExternalStreams {
    data class Rendition(val key: String, val url: String, val label: String, val lossless: Boolean, val at: Long)
    private val renditions = ConcurrentHashMap<String, Rendition>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private const val TTL = 10 * 60_000L

    fun allowed(lossless: Boolean? = null): Boolean = AppSettings.playback.value.let {
        val enabled = when (lossless) { true -> it.losslessStreaming; false -> it.jioSaavnQuality; null -> it.losslessStreaming || it.jioSaavnQuality }
        enabled && (lossless != false || AppSettings.effectiveAudioQuality.maxKbps >= 320) &&
            (!it.losslessUnmeteredOnly || !AppSettings.onMeteredNetwork())
    }

    suspend fun find(song: Song, durationMs: Long): Rendition? {
        val videoId = song.videoId
        if (!allowed() || durationMs <= 0) return null
        val now = now()
        if ((failedUntil[videoId] ?: 0) > now) return null
        renditions[videoId]?.takeIf {
            now - it.at < TTL && allowed(it.lossless) && (it.lossless || StreamResolver.worthExternalUpgrade(videoId, 320))
        }?.let { return it }
        val track = if (allowed(true)) LosslessSource.resolve(song, durationMs, AppSettings.playback.value.losslessHiRes) else null
        val saavn = if (track == null && allowed(false) && StreamResolver.worthExternalUpgrade(videoId, 320)) JioSaavnSource.resolve(song, durationMs) else null
        if (track == null && saavn == null) {
            if (failedUntil.size > 256) failedUntil.clear()
            failedUntil[videoId] = now() + 60_000
            return null
        }
        // Even two FLACs of the same recording can have different byte offsets.
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), track?.url ?: saavn!!,
            track?.let { "${it.provider} · ${it.bits}-bit FLAC" } ?: "JioSaavn · 320 kbps (lossy)", track != null, now())
        if (renditions.size > 256) renditions.clear()
        renditions[videoId] = rendition
        return rendition
    }

    fun get(videoId: String, key: String): Rendition? = renditions[videoId]?.takeIf { it.key == key }

    fun resolve(videoId: String, key: String): String {
        val rendition = get(videoId, key)
        if (rendition == null || !allowed(rendition.lossless) || now() - rendition.at >= TTL) {
            throw IOException("External rendition expired; resume the original stream")
        }
        return rendition.url
    }

    fun failed(videoId: String) {
        renditions.remove(videoId)
        failedUntil[videoId] = now() + TTL
    }

    private fun now(): Long = System.nanoTime() / 1_000_000
}
