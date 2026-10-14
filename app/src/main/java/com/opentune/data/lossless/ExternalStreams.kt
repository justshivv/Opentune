package com.opentune.data.lossless

import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.model.Song
import com.opentune.data.innertube.StreamResolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Pins each external rendition to a unique cache key; expired URLs never become YouTube bytes. */
object ExternalStreams {
    data class Rendition(val key: String, val url: String, val label: String, val lossless: Boolean, val at: Long,
        val provider: String = label, val bits: Int? = null, val sampleRate: Int? = null, val kbps: Int? = null,
        val maximumSearch: Boolean = false, val sessionRevision: Long = 0)
    private val renditions = ConcurrentHashMap<String, Rendition>()
    private val pinned = ConcurrentHashMap<String, Pair<String, Rendition>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val reports = ConcurrentHashMap<String, String>()
    private const val TTL = 10 * 60_000L

    fun selectionDetail(videoId: String): String? = reports[videoId]

    private fun report(videoId: String, detail: String) {
        if (reports.size > 256) reports.clear()
        reports[videoId] = detail
    }

    fun allowed(lossless: Boolean? = null): Boolean = AppSettings.playback.value.let {
        val maximum = AppSettings.effectiveAudioQuality == AudioQuality.MAX
        val enabled = maximum || when (lossless) { true -> it.losslessStreaming; false -> it.jioSaavnQuality; null -> it.losslessStreaming || it.jioSaavnQuality }
        enabled && (lossless != false || AppSettings.effectiveAudioQuality.maxKbps >= 320) &&
            (!it.losslessUnmeteredOnly || !AppSettings.onMeteredNetwork())
    }

    suspend fun find(song: Song, durationMs: Long, beforePlayback: Boolean = false, force: Boolean = false, losslessOnly: Boolean = false): Rendition? {
        val videoId = song.videoId
        if (!allowed()) {
            report(videoId, if (AppSettings.playback.value.losslessUnmeteredOnly && AppSettings.onMeteredNetwork())
                "External sources skipped: Unmetered networks only is on. Turn it off in Settings to try FLAC and JioSaavn on mobile data."
                else "External sources disabled for the current quality settings.")
            return null
        }
        val now = now()
        if (force) failedUntil.remove(videoId)
        if ((failedUntil[videoId] ?: 0) > now) return null
        renditions[videoId]?.takeIf {
            !force && (!losslessOnly || it.lossless) && now - it.at < TTL && allowed(it.lossless) &&
                (it.lossless || it.sessionRevision == SpotiflacSession.revision.value) && (!beforePlayback || it.maximumSearch) &&
                (it.lossless || beforePlayback || StreamResolver.worthExternalUpgrade(videoId, 320))
        }?.let { return it }
        val duration = CatalogMetadata.duration(song, durationMs)
        if (duration <= 0) {
            report(videoId, "External sources skipped: this track's duration could not be verified.")
            return null
        }
        val detail = java.util.concurrent.CopyOnWriteArrayList<String>()
        val candidates = PlaybackCandidates.resolve(if (beforePlayback) 8_000 else 20_000,
            lossless = {
                if (allowed(true)) LosslessSource.resolve(song, duration,
                    beforePlayback || AppSettings.playback.value.losslessHiRes, bestAvailable = beforePlayback,
                    report = { detail.addIfAbsent(it) }, timeoutMs = if (beforePlayback) 7_500 else 19_500) else null
            }, fallback = {
                if (!losslessOnly && allowed(false) && (beforePlayback || StreamResolver.worthExternalUpgrade(videoId, 320)))
                    JioSaavnSource.resolve(song, duration, report = { detail.addIfAbsent(it) }) else null
            })
        val track = candidates.first
        val saavn = if (track == null) candidates.second else null
        if (allowed(true)) detail += if (track == null) "FLAC: no verified matching stream within the playback wait limit" else "FLAC: verified ${track.bits}-bit, ${track.sampleRate} Hz"
        report(videoId, detail.joinToString("\n").ifEmpty { "No external source eligible for this quality setting." })
        if (track == null && saavn == null) {
            if (failedUntil.size > 256) failedUntil.clear()
            failedUntil[videoId] = now() + 60_000
            return null
        }
        // Even two FLACs of the same recording can have different byte offsets.
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), track?.url ?: saavn!!,
            track?.let { "${it.provider} · ${it.bits}-bit FLAC · ${it.sampleRate} Hz" } ?: "JioSaavn · 320 kbps (lossy)", track != null, now(),
            provider = track?.provider ?: "JioSaavn", bits = track?.bits, sampleRate = track?.sampleRate,
            kbps = if (track == null) 320 else null, maximumSearch = beforePlayback, sessionRevision = SpotiflacSession.revision.value)
        remember(videoId, rendition)
        return rendition
    }

    internal fun remember(videoId: String, rendition: Rendition) {
        if (renditions.size > 256) renditions.clear()
        pinned.entries.removeIf { now() - it.value.second.at >= TTL }
        pinned[rendition.key] = videoId to rendition
        renditions[videoId] = rendition
    }

    fun get(videoId: String, key: String): Rendition? = pinned[key]?.takeIf { it.first == videoId }?.second

    fun resolve(videoId: String, key: String): String {
        val rendition = get(videoId, key)
        if (rendition == null || !allowed(rendition.lossless) || now() - rendition.at >= TTL) {
            throw IOException("External rendition expired; resume the original stream")
        }
        return rendition.url
    }

    /** Verification/renewal should take effect immediately, including after a cached miss. */
    fun retryLookups() {
        failedUntil.clear()
        reports.clear()
        // Keep currently playing rendition keys valid until the player swaps sources.
    }

    fun failed(videoId: String) {
        renditions.remove(videoId)
        pinned.entries.removeIf { it.value.first == videoId }
        failedUntil[videoId] = now() + TTL
        report(videoId, "The selected external stream failed during playback; using YouTube. External retry paused for 10 minutes.")
    }

    private fun now(): Long = System.nanoTime() / 1_000_000
}


