package com.opentune.ui

/**
 * Material's window size classes by width: phones held upright are compact,
 * small tablets and unfolded phones held upright are medium, and tablets
 * on their side are expanded.
 */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun of(widthDp: Int): WidthClass = when {
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** Where the tabs are: the floating dock at the bottom, or a rail down the side. */
enum class NavChrome { DOCK, RAIL }

/** Where the song that's playing shows while the full-screen player is shut. */
enum class NowPlaying {
    /** Nothing is loaded. */
    NONE,
    /** The card above the dock, as on a phone. */
    DOCK,
    /** A bar along the bottom of the page. */
    BAR,
    /** The player itself, docked on the right. */
    PANE,
    /** The docked player folded into a slim strip, to open again. */
    STRIP,
}

/** The app's chrome for one window: see [appLayout]. */
data class AppLayout(
    val width: WidthClass,
    val nav: NavChrome,
    val nowPlaying: NowPlaying,
    /** The widest the pages get, centred in the room left; null lets them fill it. */
    val contentMaxWidthDp: Int?,
) {
    /** The player lives beside the page here, so the full-screen one stays shut. */
    val sidePlayer: Boolean get() = nowPlaying == NowPlaying.PANE || nowPlaying == NowPlaying.STRIP
}

/** Below this height the docked player has no room for its cover over the controls: phones on their side. */
const val SIDE_PLAYER_MIN_HEIGHT_DP = 560

/**
 * The chrome for a window [widthDp] by [heightDp], with or without a song
 * loaded ([hasSong]). Compact windows keep the phone layout. Wider ones get
 * the rail, pages in a readable column and the mini player along the
 * bottom; expanded ones, tall enough, dock the player on the right instead
 * while a song is loaded, or its strip when [paneCollapsed]. [wideLayout]
 * and [sidePlayer] are the settings that turn each part off.
 */
fun appLayout(
    widthDp: Int,
    heightDp: Int,
    hasSong: Boolean,
    paneCollapsed: Boolean = false,
    wideLayout: Boolean = true,
    sidePlayer: Boolean = true,
    television: Boolean = false,
): AppLayout {
    val width = WidthClass.of(widthDp)
    if (television) return AppLayout(width, NavChrome.RAIL, if (hasSong) NowPlaying.BAR else NowPlaying.NONE, null)
    if (width == WidthClass.COMPACT || !wideLayout) {
        return AppLayout(width, NavChrome.DOCK, if (hasSong) NowPlaying.DOCK else NowPlaying.NONE, null)
    }
    val docked = width == WidthClass.EXPANDED && sidePlayer && heightDp >= SIDE_PLAYER_MIN_HEIGHT_DP
    val nowPlaying = when {
        !hasSong -> NowPlaying.NONE
        docked && paneCollapsed -> NowPlaying.STRIP
        docked -> NowPlaying.PANE
        else -> NowPlaying.BAR
    }
    return AppLayout(width, NavChrome.RAIL, nowPlaying, if (width == WidthClass.MEDIUM) 720 else 840)
}

/**
 * How many columns a grid of tiles gets in [availableDp] of width: two on
 * a phone, as it always was, and as many as fit at least [minCellDp] each
 * on anything wider.
 */
fun gridColumns(availableDp: Float, minCellDp: Float = 200f, spacingDp: Float = 10f): Int =
    ((availableDp + spacingDp) / (minCellDp + spacingDp)).toInt().coerceAtLeast(2)
