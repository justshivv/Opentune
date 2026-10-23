package com.opentune.data.lossless

import com.opentune.data.addons.AddonResolver
import com.opentune.data.model.Song
import kotlin.math.abs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Lossless validation and ordered, user-configured HTTP sources. */
object LosslessSource {
    data class Track(val url: String, val provider: String, val bits: Int?, val sampleRate: Int?,
        val addonId: String, val mimeType: String? = null)
    internal data class FlacInfo(val bits: Int, val sampleRate: Int, val durationMs: Long)
    /** [late] gets a better match from an add-on that answered after the one returned (see [AddonResolver.resolve]). */
    suspend fun resolve(song: Song, durationMs: Long, preferHiRes: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {}, late: (Track) -> Unit = {}): Track? =
        AddonResolver.resolve(song, durationMs, bestAvailable = preferHiRes || bestAvailable, report = report,
            late = { late(it.toTrack()) })?.toTrack()

    /** Every verified match, best first; see [AddonResolver.resolveAll]. */
    suspend fun resolveAll(song: Song, durationMs: Long, preferHiRes: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {}, late: (Track) -> Unit = {},
        skip: Set<String> = emptySet(), settled: (addon: String, erred: Boolean) -> Unit = { _, _ -> }): List<Track> =
        AddonResolver.resolveAll(song, durationMs, bestAvailable = preferHiRes || bestAvailable, report = report,
            late = { late(it.toTrack()) }, skip = skip, settled = settled).map { it.toTrack() }

    private fun com.opentune.data.addons.AddonStream.toTrack() = Track(url, provider, bits, sampleRate, addonId, mimeType)
    internal fun flacInfo(bytes: ByteArray): FlacInfo? {
        if (bytes.size < 42 || String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        if (bytes[4].toInt() and 0x7f != 0 || bytes[5] != 0.toByte() || bytes[6] != 0.toByte() || bytes[7] != 34.toByte()) return null
        var packed = 0L
        for (i in 18..25) packed = (packed shl 8) or (bytes[i].toLong() and 0xff)
        val rate = (packed ushr 44).toInt()
        val bits = ((packed ushr 36) and 31).toInt() + 1
        val samples = packed and 0xfffffffffL
        // Lossless voice-rate audio is not a music quality upgrade.
        if (rate !in 44_100..384_000 || bits !in 16..32 || samples == 0L) return null
        return FlacInfo(bits, rate, samples * 1000 / rate)
    }

    internal fun durationMatches(expectedMs: Long, actualMs: Long): Boolean =
        expectedMs > 0 && abs(expectedMs - actualMs) <= 3_000

}

internal fun httpsUrl(value: String): String? = value.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
