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

/**
 * How a page spreads out in the room the chrome leaves it. Phones, upright
 * or on their side, always get [PHONE]: only a large screen (see
 * [LARGE_SCREEN_MIN_DP]) with room to spare lays its pages out otherwise.
 */
enum class PageSpread {
    /** The phone's page, as it always was. */
    PHONE,
    /** Upright tablets and unfolded foldables: the phone's page, its headers in a row and its rows wider. */
    ROOMY,
    /** A tablet on its side: a list page's header beside its songs rather than over them. */
    SIDE_BY_SIDE,
}

/** The app's chrome for one window: see [appLayout]. */
data class AppLayout(
    val width: WidthClass,
    val nav: NavChrome,
    val nowPlaying: NowPlaying,
    /** The widest the pages get, centred in the room left; null lets them fill it. */
    val contentMaxWidthDp: Int?,
    /** How the pages lay themselves out in the room they get. */
    val spread: PageSpread = PageSpread.PHONE,
) {
    /** The player lives beside the page here, so the full-screen one stays shut. */
    val sidePlayer: Boolean get() = nowPlaying == NowPlaying.PANE || nowPlaying == NowPlaying.STRIP
}

/** Below this height the docked player has no room for its cover over the controls: phones on their side. */
const val SIDE_PLAYER_MIN_HEIGHT_DP = 560

/**
 * A window whose shorter side is at least this is a large screen (a tablet,
 * an unfolded foldable) whichever way it is held. A phone on its side is
 * wide but never this tall, so its pages stay a phone's.
 */
const val LARGE_SCREEN_MIN_DP = 600

/** What the rail, and the docked player or its strip, take from the window's width, insets aside (SideChrome's gutters). */
private const val RAIL_ROOM_DP = 96
private const val PANE_ROOM_DP = 404
private const val STRIP_ROOM_DP = 96

/** Narrower than this a page keeps the phone's layout even on a large screen (upright, beside the docked player). */
private const val SPREAD_MIN_PAGE_DP = 520

/** At least this wide, a page on a tablet held sideways puts a list's header beside its songs. */
private const val SIDE_BY_SIDE_MIN_PAGE_DP = 640

/**
 * How a page spreads out on a window [widthDp] by [heightDp] when it is
 * given [pageWidthDp] of it: see [PageSpread].
 */
fun pageSpread(widthDp: Int, heightDp: Int, pageWidthDp: Int): PageSpread = when {
    minOf(widthDp, heightDp) < LARGE_SCREEN_MIN_DP || pageWidthDp < SPREAD_MIN_PAGE_DP -> PageSpread.PHONE
    widthDp > heightDp && pageWidthDp >= SIDE_BY_SIDE_MIN_PAGE_DP -> PageSpread.SIDE_BY_SIDE
    else -> PageSpread.ROOMY
}

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
): AppLayout {
    val width = WidthClass.of(widthDp)
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
    val maxWidth = if (width == WidthClass.MEDIUM) 720 else 840
    val beside = when (nowPlaying) {
        NowPlaying.PANE -> PANE_ROOM_DP
        NowPlaying.STRIP -> STRIP_ROOM_DP
        else -> 0
    }
    val pageWidth = minOf(widthDp - RAIL_ROOM_DP - beside, maxWidth)
    return AppLayout(width, NavChrome.RAIL, nowPlaying, maxWidth, pageSpread(widthDp, heightDp, pageWidth))
}

/**
 * How many columns a grid of tiles gets in [availableDp] of width: two on
 * a phone, as it always was, and as many as fit at least [minCellDp] each
 * on anything wider.
 */
fun gridColumns(availableDp: Float, minCellDp: Float = 200f, spacingDp: Float = 10f): Int =
    ((availableDp + spacingDp) / (minCellDp + spacingDp)).toInt().coerceAtLeast(2)
