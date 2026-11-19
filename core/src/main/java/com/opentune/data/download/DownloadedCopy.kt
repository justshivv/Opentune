package com.opentune.data.download

import com.opentune.data.settings.PlaybackSettings
import java.io.File

/** "Download again in lossless" found no add-on with a lossless file to hand over. */
internal class NoLosslessCopy : Exception("No lossless copy to download")

/** "Stream downloaded songs": when a downloaded song streams. Offline it always plays its file. */
enum class DownloadedPlayback(val label: String, val summary: String) {
    FILE("Never", "Always plays the saved file, uses no data"),
    WIFI("On Wi-Fi only", "Streams on Wi-Fi, the saved file on mobile data"),
    ONLINE("Whenever online", "Streams with any internet, the saved file offline"),
}

/** The choice, kept as the two switches settings already had so earlier choices carry over. */
val PlaybackSettings.downloadedPlayback: DownloadedPlayback
    get() = when {
        !streamDownloadsOnline -> DownloadedPlayback.FILE
        !streamDownloadsOnMobile -> DownloadedPlayback.WIFI
        else -> DownloadedPlayback.ONLINE
    }

fun PlaybackSettings.withDownloadedPlayback(choice: DownloadedPlayback): PlaybackSettings =
    copy(streamDownloadsOnline = choice != DownloadedPlayback.FILE, streamDownloadsOnMobile = choice == DownloadedPlayback.ONLINE)

/** What a downloaded song is: its format, quality and where it came from. */
object DownloadedCopy {
    private val LOSSLESS = setOf("flac", "alac", "wav", "pcm")

    /** The file's codec: recorded for newer downloads, read off the file's extension for older ones. */
    fun codec(entry: DownloadEntry): String? = entry.codec ?: when (entry.path?.let { File(it).extension.lowercase() }) {
        "flac" -> "flac"
        "wav" -> "wav"
        "webm", "opus", "ogg" -> "opus"
        "mp3" -> "mp3"
        // ALAC and AAC share the M4A box; only an add-on's lossless file records a bit depth.
        "m4a", "mp4" -> if (entry.bits != null) "alac" else "aac"
        else -> null
    }

    fun lossless(entry: DownloadEntry): Boolean = codec(entry) in LOSSLESS

    /** How a downloaded file is labelled on the Downloads page, best first. */
    enum class Tier(val label: String) {
        DOLBY_ATMOS("Dolby Atmos"),
        DOLBY("Dolby"),
        HI_RES("Hi-Res Lossless"),
        LOSSLESS("Lossless"),
        HIGH("High Quality"),
        GOOD("Good Quality"),
    }

    /**
     * The file's quality tier, named as the player's quality label names a stream: Dolby by its
     * kind; lossless as Hi-Res past CD (more than 16-bit or 48 kHz); a lossy file as High Quality
     * from 256 kbps (JioSaavn's 320 kbps AAC) and Good Quality below (YouTube's own streams).
     * [durationMs] lets a lossy file's rate be measured from its size; 0 when not known.
     */
    fun tier(entry: DownloadEntry, durationMs: Long): Tier {
        val codec = codec(entry)?.lowercase().orEmpty()
        return when {
            codec in DOLBY_ATMOS -> Tier.DOLBY_ATMOS
            codec in DOLBY -> Tier.DOLBY
            lossless(entry) && ((entry.bits ?: 0) > 16 || (entry.sampleRate ?: 0) > 48_000) -> Tier.HI_RES
            lossless(entry) -> Tier.LOSSLESS
            entry.source?.startsWith("JioSaavn") == true -> Tier.HIGH
            (fileKbps(entry, durationMs) ?: 0) >= 256 -> Tier.HIGH
            else -> Tier.GOOD
        }
    }

    private val DOLBY_ATMOS = setOf("ec+3", "eac3-joc", "ac-4", "ac4")
    private val DOLBY = setOf("ec-3", "eac3", "ac-3", "ac3")

    /** "FLAC · 24-bit / 96 kHz · Lossless Bridge", "AAC · JioSaavn · 320 kbps", "Opus · YouTube". */
    fun describe(entry: DownloadEntry): String {
        val codec = codec(entry)?.let { CODEC_LABELS[it] ?: it.uppercase() }
        val depth = listOfNotNull(entry.bits?.let { "$it-bit" }, entry.sampleRate?.let(::khz)).joinToString(" / ")
        val source = entry.source ?: "YouTube"
        return listOfNotNull(codec, depth.ifEmpty { null }, source).joinToString(" · ")
    }

    private fun khz(rate: Int): String {
        val k = rate / 1000.0
        return if (k % 1.0 == 0.0) "${k.toInt()} kHz" else "${"%.1f".format(java.util.Locale.ROOT, k)} kHz"
    }

    private val CODEC_LABELS = mapOf("flac" to "FLAC", "alac" to "ALAC", "wav" to "WAV", "pcm" to "WAV",
        "opus" to "Opus", "aac" to "AAC", "mp3" to "MP3")

    /** CD quality's data rate: 16-bit stereo at 44.1 kHz. */
    const val CD_KBPS = 1411
    /** The most YouTube's own streams carry without Premium (Opus). */
    const val YOUTUBE_KBPS = 160

    /** A lossless file's data rate from its depth and rate; CD quality when they weren't recorded. */
    fun losslessKbps(bits: Int?, sampleRate: Int?): Int =
        if (bits == null || sampleRate == null) CD_KBPS else (bits.toLong() * sampleRate * 2 / 1000).toInt()

    /**
     * The saved file's quality as a data rate: a lossless file's from its depth and rate,
     * a lossy one's measured from its size and the song's length. Null when that can't be told.
     */
    fun fileKbps(entry: DownloadEntry, durationMs: Long): Int? = when {
        lossless(entry) -> losslessKbps(entry.bits, entry.sampleRate)
        entry.bytes > 0 && durationMs > 0 -> (entry.bytes * 8 / durationMs).toInt()
        else -> null
    }

    /**
     * A stream is better only when clearly so. A lossy file's rate is measured while a stream's
     * is nominal: YouTube's "160 kbps" Opus averages about 130 once saved, so it takes 30% more.
     */
    fun streamBeatsFile(fileKbps: Int?, bestStreamKbps: Int): Boolean =
        fileKbps == null || bestStreamKbps > fileKbps * 13 / 10

    /**
     * Whether a downloaded song plays its file rather than streaming. Offline the file
     * always plays; online, as [choice] says, and with [onlyIfBetter] the file also plays
     * whenever no stream can beat it.
     */
    fun playsFile(choice: DownloadedPlayback, online: Boolean, metered: Boolean, onlyIfBetter: Boolean, streamBetter: Boolean): Boolean =
        !online || (onlyIfBetter && !streamBetter) || when (choice) {
            DownloadedPlayback.FILE -> true
            DownloadedPlayback.WIFI -> metered
            DownloadedPlayback.ONLINE -> false
        }

    /** How long a FLAC search that found nothing is believed: catalogues grow, so not for ever. */
    const val MISS_KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** The add-ons in use, so a miss stops holding once one is added, removed or switched. */
    fun addonSet(): String = com.opentune.data.addons.AddonStore.addons.value
        .filter(com.opentune.data.addons.AddonStore::usable).map { it.key }.sorted().joinToString(",")

    /** Whether [miss] still says there's no FLAC: recent, and with the same add-ons on as now. */
    fun missHolds(miss: LosslessMiss?, nowMs: Long, addonsNow: String): Boolean =
        miss != null && miss.addons == addonsNow && nowMs - miss.at in 0 until MISS_KEEP_MS

    /** "1:38" or "1:02:03" in milliseconds, or 0. */
    fun durationMs(text: String?): Long =
        text?.split(':')?.mapNotNull { it.trim().toLongOrNull() }?.takeIf { it.isNotEmpty() }
            ?.fold(0L) { acc, part -> acc * 60 + part }?.times(1000) ?: 0L

    /** An enabled add-on lets its files be downloaded, so a lossless copy might be had. */
    fun addonCanDownload(): Boolean = com.opentune.data.addons.AddonStore.addons.value.any {
        it.manifest.downloads && com.opentune.data.addons.AddonStore.usable(it) && !com.opentune.data.addons.AddonResolver.paused(it.key)
    }

    /** "Download again in lossless" is offered for a finished copy that isn't lossless, when an add-on can supply one. */
    fun canRedownloadLossless(entry: DownloadEntry, addonCanDownload: Boolean): Boolean =
        addonCanDownload && entry.state == DownloadState.DONE && entry.upgrade == null && !lossless(entry)
}
