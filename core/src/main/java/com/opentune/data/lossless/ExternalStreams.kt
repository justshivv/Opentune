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
        val maximumSearch: Boolean = false, val sessionRevision: Long = 0,
        val addonId: String? = null, val mimeType: String? = null,
        /** Picked by hand in Search: played whatever the quality settings say. */
        val picked: Boolean = false)
    private val renditions = ConcurrentHashMap<String, Rendition>()
    private val pinned = ConcurrentHashMap<String, Pair<String, Rendition>>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val reports = ConcurrentHashMap<String, String>()
    private const val TTL = 5 * 60_000L

    fun selectionDetail(videoId: String): String? = reports[videoId]

    /** Instant startup lookup only; a miss must never start network work. */
    fun cached(videoId: String): Rendition? {
        val now = now()
        if ((failedUntil[videoId] ?: 0) > now) return null
        return renditions[videoId]?.takeIf {
            now - it.at < TTL && (it.picked || allowed(it.lossless)) &&
                (it.addonId == null || (it.sessionRevision == com.opentune.data.addons.AddonStore.revision.value && addonAllowed(it)))
        }
    }

    /** JioSaavn has its own fast path; never wait for a FLAC search before it. */
    suspend fun findJioSaavn(song: Song, durationMs: Long): Rendition? {
        if (!allowed(false) || (failedUntil[song.videoId] ?: 0) > now()) return null
        cached(song.videoId)?.takeIf { it.mimeType == null }?.let { return it }
        val duration = CatalogMetadata.duration(song, durationMs)
        if (duration <= 0) return null
        val url = JioSaavnSource.resolve(song, duration, report = { report(song.videoId, it) }) ?: return null
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), url, "JioSaavn · 320 kbps (lossy)", false, now(),
            provider = "JioSaavn", kbps = 320, sessionRevision = com.opentune.data.addons.AddonStore.revision.value)
        // A simultaneous FLAC result remains the preferred cached rendition.
        cached(song.videoId)?.takeIf { it.lossless && it.mimeType == null }?.let { return it }
        remember(song.videoId, rendition)
        return rendition
    }

    private fun report(videoId: String, detail: String) {
        if (reports.size > 256) reports.clear()
        reports[videoId] = detail
    }

    /**
     * The picked stream was found but playback couldn't switch to it: said so
     * under what was picked, so the pipeline doesn't read as if it's playing.
     */
    fun swapFailed(videoId: String) {
        val picked = reports[videoId] ?: return
        if ("Couldn't switch" in picked) return
        report(videoId, "$picked\nCouldn't switch to it: the stream didn't load in time. YouTube keeps playing; it's tried again next song.")
    }

    fun allowed(lossless: Boolean? = null): Boolean = AppSettings.playback.value.let {
        val maximum = AppSettings.effectiveAudioQuality == AudioQuality.MAX
        val enabled = when (lossless) { true -> maximum || it.losslessStreaming; false -> it.jioSaavnQuality; null -> maximum || it.losslessStreaming || it.jioSaavnQuality }
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
            !force && (!losslessOnly || it.lossless) && now - it.at < TTL && (it.picked || allowed(it.lossless)) &&
                (it.addonId == null || (it.sessionRevision == com.opentune.data.addons.AddonStore.revision.value && addonAllowed(it))) && (!beforePlayback || it.maximumSearch) &&
                (it.lossless || beforePlayback || StreamResolver.worthExternalUpgrade(videoId, 320))
        }?.let { return it }
        val duration = CatalogMetadata.duration(song, durationMs)
        if (duration <= 0) {
            report(videoId, "External sources skipped: this track's duration could not be verified.")
            return null
        }
        val detail = java.util.concurrent.CopyOnWriteArrayList<String>()
        val track = if (allowed(true)) LosslessSource.resolve(song, duration,
            beforePlayback || AppSettings.playback.value.losslessHiRes, bestAvailable = beforePlayback, report = { detail.addIfAbsent(it) }) else null
        if (allowed(true)) detail += if (track == null) "FLAC: no verified matching stream available" else "Add-on audio selected; playback diagnostics show the decoded format"
        val saavn = if (!losslessOnly && track == null && allowed(false) && (beforePlayback || StreamResolver.worthExternalUpgrade(videoId, 320)))
            JioSaavnSource.resolve(song, duration, report = { detail += it }) else null
        report(videoId, detail.joinToString("\n").ifEmpty { "No external source eligible for this quality setting." })
        if (track == null && saavn == null) {
            if (failedUntil.size > 256) failedUntil.clear()
            failedUntil[videoId] = now() + 60_000
            return null
        }
        // Even two FLACs of the same recording can have different byte offsets.
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), track?.url ?: saavn!!,
            track?.let { "${it.provider} · lossless requested" } ?: "JioSaavn · 320 kbps (lossy)", track != null, now(),
            provider = track?.provider ?: "JioSaavn", bits = track?.bits, sampleRate = track?.sampleRate,
            kbps = if (track == null) 320 else null, maximumSearch = beforePlayback, sessionRevision = com.opentune.data.addons.AddonStore.revision.value, addonId = track?.addonId, mimeType = track?.mimeType)
        remember(videoId, rendition)
        return rendition
    }

    /**
     * A stream picked by hand from an add-on's search results, to start
     * [videoId] on: the listener chose this recording, so it isn't searched
     * for or compared again. Startup takes it at once when external sources
     * are allowed (see [cached]).
     */
    fun offer(videoId: String, stream: com.opentune.data.addons.AddonStream): Rendition {
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), stream.url,
            "${stream.provider} · ${if (stream.lossless) "lossless requested" else stream.codec}", stream.lossless, now(),
            provider = stream.provider, bits = stream.bits, sampleRate = stream.sampleRate, kbps = stream.kbps,
            maximumSearch = true, sessionRevision = com.opentune.data.addons.AddonStore.revision.value,
            addonId = stream.addonId, mimeType = stream.mimeType, picked = true)
        failedUntil.remove(videoId)
        remember(videoId, rendition)
        report(videoId, "${stream.provider}: picked in Search")
        return rendition
    }

    internal fun remember(videoId: String, rendition: Rendition) {
        if (renditions.size > 256) renditions.clear()
        pinned.entries.removeIf { now() - it.value.second.at >= 2 * 60 * 60_000L }
        pinned[rendition.key] = videoId to rendition
        renditions[videoId] = rendition
    }

    fun get(videoId: String, key: String): Rendition? = pinned[key]?.takeIf { it.first == videoId }?.second

    fun resolve(videoId: String, key: String): String {
        val rendition = get(videoId, key)
        if (rendition == null || !addonAllowed(rendition) || !(rendition.picked || allowed(rendition.lossless))) {
            throw IOException("External rendition expired; resume the original stream")
        }
        return rendition.url
    }

    /** Verification/renewal should take effect immediately, including after a cached miss. */
    fun retryLookups() {
        failedUntil.clear()
        com.opentune.data.addons.AddonResolver.retry()
        reports.clear()
        // Keep currently playing rendition keys valid until the player swaps sources.
    }

    fun failed(videoId: String, key: String? = null) {
        // A lookup may have finished while an older rendition was playing.
        // Invalidate the stream that actually failed, not the new candidate.
        val failed = if (key == null) renditions.remove(videoId) else get(videoId, key)
        if (key != null && renditions[videoId]?.key == key) renditions.remove(videoId)
        val addon = failed?.addonId
        if (addon != null) com.opentune.data.addons.AddonResolver.failed(addon, videoId)
        pinned.entries.removeIf { it.value.first == videoId && (key == null || it.key == key) }
        if (addon == null && renditions[videoId] == null) failedUntil[videoId] = now() + TTL
        report(videoId, if (addon != null) "The selected add-on failed during playback; using fallback audio and trying the next add-on." else "The selected external stream failed during playback; using YouTube temporarily.")
    }

    private fun addonAllowed(rendition: Rendition): Boolean = rendition.addonId?.let { key ->
        com.opentune.data.addons.AddonStore.addons.value.firstOrNull { it.key == key }?.let(com.opentune.data.addons.AddonStore::usable) == true
    } ?: true

    private fun now(): Long = System.nanoTime() / 1_000_000
}


