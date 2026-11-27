package com.opentune.data.download

/**
 * A device's own rules for downloads, over the app's settings. The watch sets these: regular
 * quality only, Wi-Fi it has asked for (Wear OS turns Wi-Fi off while Bluetooth to the phone is
 * up), and room left on its small storage. The phone sets nothing and goes by its settings.
 */
object DownloadRules {
    @Volatile var device: DeviceDownloads? = null
}

class DeviceDownloads(
    /** The best stream to take; lossless and JioSaavn are never tried. */
    val maxKbps: Int,
    /** Whether a network downloads may use is up now; false holds them until it is. */
    val networkReady: () -> Boolean,
    /** Downloads stop with less free space than this. */
    val keepFreeBytes: Long,
    /** Said when downloads stop for want of space. */
    val noSpaceMessage: String,
)

/** What the worker does before fetching a song under [DownloadRules]. */
internal object DeviceDownloadCheck {
    enum class Verdict { GO, WAIT, NO_SPACE }

    fun verdict(rules: DeviceDownloads?, freeBytes: Long): Verdict = when {
        rules == null -> Verdict.GO
        !rules.networkReady() -> Verdict.WAIT
        freeBytes < rules.keepFreeBytes -> Verdict.NO_SPACE
        else -> Verdict.GO
    }
}
