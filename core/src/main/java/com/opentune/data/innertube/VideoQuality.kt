package com.opentune.data.innertube

import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PlaybackSettings
import kotlin.math.abs

/** One video-only format a music video is offered in: what the quality list is built from. */
data class VideoOffer(val mimeType: String, val width: Int, val height: Int, val kbps: Int = 0)

/**
 * Music-video quality as the listener picks it: Auto (the picture the
 * connection can feed, chosen by [StreamResolver]) or a size such as 720p,
 * which plays the best picture at or under it. Sizes are YouTube's rungs,
 * named by the picture's short side the way YouTube names them, so a 1920 by
 * 800 film and an upright 1080 by 1920 clip both read 1080p.
 */
object VideoQuality {
    /** No size chosen: the picture follows the connection. */
    const val AUTO = 0

    /** YouTube's picture sizes, smallest first. */
    val RUNGS = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    /** What Settings offers before any video is known: Auto, then the sizes tallest first. */
    val CHOICES = listOf(AUTO) + RUNGS.filter { it <= 2160 }.reversed()

    /** The long side of each rung's 16:9 picture. */
    private val LONG = mapOf(144 to 256, 240 to 426, 360 to 640, 480 to 854, 720 to 1280, 1080 to 1920, 1440 to 2560, 2160 to 3840, 4320 to 7680)

    /**
     * The rung a [width] by [height] picture is named by, or 0 when neither is
     * known. Both sides are matched to the nearest rung and the larger answer
     * kept: the short side names a 4:3 picture (1440 by 1080 is 1080p), the
     * long side a wide one (1920 by 800 is 1080p too).
     */
    fun rungOf(width: Int, height: Int): Int {
        val short = listOf(width, height).filter { it > 0 }.minOrNull() ?: return 0
        val long = maxOf(width, height)
        val byShort = RUNGS.minBy { abs(it - short) }
        val byLong = if (width > 0 && height > 0) LONG.entries.minBy { abs(it.value - long) }.key else 0
        return maxOf(byShort, byLong)
    }

    /** "Auto", or the rung as "720p". */
    fun label(rung: Int): String = if (rung == AUTO) "Auto" else "${rung}p"

    /**
     * The quality list for a video offered as [offers]: Auto first, then each
     * size this phone can decode, tallest first, once each. When the phone
     * seems to decode none of them, its codec list is what's wrong, and every
     * size is offered, as [StreamResolver]'s own ranking does.
     */
    fun choices(offers: List<VideoOffer>, canPlay: (String, Int, Int) -> Boolean = VideoDecoders::canPlay): List<Int> {
        val sized = offers.filter { rungOf(it.width, it.height) > 0 }
        val playable = sized.filter { canPlay(it.mimeType, it.width, it.height) }.ifEmpty { sized }
        return listOf(AUTO) + playable.map { rungOf(it.width, it.height) }.distinct().sortedDescending()
    }

    /** The size chosen for the network in use: the mobile-data one on a metered network, else the Wi-Fi one. */
    fun chosen(settings: PlaybackSettings, metered: Boolean): Int =
        (if (metered) settings.mobileVideoQuality else settings.wifiVideoQuality).coerceAtLeast(AUTO)

    /** [settings] with [rung] chosen for the network in use, the other network's choice kept. */
    fun withChoice(settings: PlaybackSettings, metered: Boolean, rung: Int): PlaybackSettings =
        if (metered) settings.copy(mobileVideoQuality = rung) else settings.copy(wifiVideoQuality = rung)

    /** The size chosen for the network in use now. */
    fun now(): Int = chosen(AppSettings.playback.value, AppSettings.onMeteredNetwork())

    /** Chooses [rung] for the network in use now. */
    fun chooseNow(rung: Int) {
        val metered = AppSettings.onMeteredNetwork()
        AppSettings.updatePlayback { withChoice(it, metered, rung) }
    }

    /**
     * [items] in the order to try them for a chosen size [cap]: the tallest at
     * or under it first (the heavier first on a tie, a better encode of the
     * same size), the rest under it after; then anything over it, smallest
     * first, since a picture a size too big still beats none.
     */
    fun <T> rankForCap(items: List<T>, cap: Int, rung: (T) -> Int, kbps: (T) -> Int): List<T> {
        val (within, over) = items.partition { rung(it) <= cap }
        return within.sortedWith(compareByDescending<T> { rung(it) }.thenByDescending { kbps(it) }) +
                over.sortedWith(compareBy<T> { rung(it) }.thenBy { kbps(it) })
    }

    /**
     * The tallest literal picture height under the chosen [cap] among [offers],
     * for an extractor that filters by height alone: an upright clip's 720p is
     * 1280 tall. The cap itself when nothing is known.
     */
    fun heightCap(offers: List<VideoOffer>, cap: Int): Int =
        offers.filter { it.height > 0 && rungOf(it.width, it.height) <= cap }.maxOfOrNull { it.height } ?: cap
}
