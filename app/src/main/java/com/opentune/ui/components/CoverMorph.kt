package com.opentune.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.PageTransition
import com.opentune.data.settings.PerformanceProfile
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.opentune.ui.CoverClocks

/** The shared-transition scope round the pages, for a page to grow out of the cover tapped. Null outside the app's pages. */
val LocalSharedPages = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The page this is on, as it comes and goes. Null outside the app's pages. */
val LocalPageScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Grow from cover: an album or playlist page grows out of the cover tapped
 * to open it, and shrinks back into it on the way back; the page behind sinks
 * back a little meanwhile (PageMotion.recede). Only a page opened by a tap on
 * a cover grows; one opened any other way (a link, a search for its name), or
 * with the cover's place unknown, comes in the usual way, as does every page
 * with Reduce animation, Lite mode or Battery saver on.
 *
 * A tap leaves its cover's place here ([tapped]); the page opening next claims
 * it if it's that cover's page ([arrived]). Main thread only.
 */
object CoverMorph {
    /** A cover just tapped: its page id, the card, its place on screen and corner radius, and when. */
    internal class Tap(val id: String, val card: Long, val bounds: Rect, val cornerPx: Float, val at: Long)

    /** A page that grew out of a cover: the card, the page that card is on, and the cover's width and corner. */
    internal class Grown(val card: Long, val source: String?, val coverWidth: Float, val cornerPx: Float) {
        /** The page's own width, once it's laid out, for the corner to round off as it grows. */
        var pageWidth = 0f
        /** Taken off the back stack: forgotten once it has also left the screen. */
        var closing = false
        /** Off the screen: closed, or under another page. */
        var away = false
    }

    /** A tap this long ago no longer opens its page out of the cover. */
    internal const val TAP_FRESH_MS = 1_000L

    private var pending: Tap? = null

    /** By the back stack entry of the page that grew. */
    internal val grown = mutableStateMapOf<String, Grown>()

    /** Whether pages may grow out of covers with these settings. */
    fun allowed(ui: InterfaceSettings): Boolean =
        ui.pageTransition == PageTransition.GROW && !ui.reduceAnimation && !ui.liteMode &&
            ui.performanceProfile != PerformanceProfile.BATTERY_SAVER

    /** Whether the page opening for [id] grows out of [tap], [now]; otherwise it comes in the usual way. */
    internal fun grows(tap: Tap?, id: String?, now: Long, allowed: Boolean): Boolean =
        allowed && tap != null && id != null && tap.id == id && now - tap.at in 0..TAP_FRESH_MS &&
            tap.bounds.width > 0f && tap.bounds.height > 0f

    /** The cover of [card], for page [id], was tapped where [bounds] is (unknown: null). */
    internal fun tapped(id: String, card: Long, bounds: Rect?, cornerPx: Float, now: Long = SystemClock.uptimeMillis()) {
        pending = bounds?.let { Tap(id, card, it, cornerPx, now) }
    }

    /**
     * The pages moved: [entry] is on top now, showing page [id] (null for a page
     * that never grows), over [below]. True if it grows out of the cover just tapped.
     */
    fun arrived(entry: String?, id: String?, below: String?, allowed: Boolean, now: Long = SystemClock.uptimeMillis()): Boolean {
        val tap = pending
        pending = null
        if (entry == null || !grows(tap, id, now, allowed)) return false
        grown[entry] = Grown(tap!!.card, below, tap.bounds.width, tap.cornerPx)
        return true
    }

    /** Whether the page of back stack [entry] grows out of a cover. */
    fun opens(entry: String): Boolean = entry in grown

    /** Whether going back from [from] to [to] shrinks [from] into its cover on [to]. */
    fun closesInto(from: String, to: String): Boolean = grown[from]?.source == to

    /** Everything forgotten: the setting was turned off, or motion reduced. */
    fun clear() {
        pending = null
        grown.clear()
    }

    /**
     * The back stack is [stack] now. A page taken off it is forgotten once it
     * has left the screen too, whichever of the two comes first.
     */
    fun stackIs(stack: Collection<String>) {
        grown.forEach { (key, g) -> g.closing = key !in stack }
        grown.filterValues { it.closing && it.away }.keys.forEach(grown::remove)
    }

    /** The page of [entry] is on the screen ([shown]) or has left it. */
    internal fun shown(entry: String, shown: Boolean) {
        val g = grown[entry] ?: return
        g.away = !shown
        if (g.away && g.closing) grown.remove(entry)
    }

    /** The key [card]'s cover shares with the page that grew out of it, if one is open. */
    internal fun keyFor(card: Long): String? = grown.entries.lastOrNull { it.value.card == card }?.key?.let(::keyOf)

    private fun keyOf(entry: String) = "cover:$entry"

    // Both sides animate their own bounds and must agree: growing is opening.
    internal val bounds = BoundsTransform { initial, target ->
        if (target.width >= initial.width) CoverClocks.open(Rect.VisibilityThreshold) else CoverClocks.close()
    }

    /**
     * Rounds both sides alike: the cover's corner while the shape is the
     * cover's size, square once it's the page's, worked out from the size
     * itself so a back swipe that scrubs one scrubs the other.
     */
    internal class Clip(private val entry: String) : SharedTransitionScope.OverlayClip {
        private val path = Path()

        override fun getClipPath(state: SharedTransitionScope.SharedContentState, bounds: Rect, layoutDirection: LayoutDirection, density: Density): Path {
            val g = grown[entry]
            val radius = if (g == null) 0f else lerp(g.cornerPx, 0f, progress(g.coverWidth, g.pageWidth, bounds.width))
            path.rewind()
            path.addRoundRect(RoundRect(bounds, CornerRadius(radius)))
            return path
        }
    }

    /** How far from the cover's width ([from]) to the page's ([to]) [width] is, 0 to 1. */
    internal fun progress(from: Float, to: Float, width: Float): Float =
        if (to > from + 1f) ((width - from) / (to - from)).coerceIn(0f, 1f) else 1f

    // The cover gives way to the page under it on the way out and comes back
    // before the page has quite shrunk away on the way back.
    internal val coverIn = fadeIn(tween(180, delayMillis = 200))
    internal val coverOut = fadeOut(tween(150, delayMillis = 50))
    // The page stays solid for most of the shrink, so it doesn't ghost over the page behind.
    internal val pageIn = fadeIn(tween(120))
    internal val pageOut = fadeOut(tween(180, delayMillis = 220))
}

/**
 * Follows [nav] as pages open and close, for [CoverMorph.arrived]: a page of
 * [routes] (its id the `id` argument) grows out of the cover tapped to open it
 * while [allowed]. Settled as the page opens, before its transition is picked.
 */
@Composable
fun FollowCoverPages(nav: NavHostController, allowed: Boolean, routes: Set<String>) {
    val allowedNow by rememberUpdatedState(allowed)
    LaunchedEffect(allowed) { if (!allowed) CoverMorph.clear() }
    DisposableEffect(nav) {
        val moved = NavController.OnDestinationChangedListener { _, destination, arguments ->
            val id = if (destination.route in routes) arguments?.getString("id") else null
            CoverMorph.arrived(nav.currentBackStackEntry?.id, id, nav.previousBackStackEntry?.id, allowedNow)
        }
        nav.addOnDestinationChangedListener(moved)
        onDispose { nav.removeOnDestinationChangedListener(moved) }
    }
    // The whole stack is only public as this restricted, long-stable flow; it's
    // brought up to date after the listener above has heard of a move.
    LaunchedEffect(nav) { nav.backStackFlow().collect { stack -> CoverMorph.stackIs(stack.map { it.id }) } }
}

@android.annotation.SuppressLint("RestrictedApi")
private fun NavController.backStackFlow() = currentBackStack

/** A cover that can open its page out of itself: call [tap] as it's tapped, and put [coverMorphCard] on the cover. */
@Stable
class CoverMorphCard internal constructor(internal val id: String, internal val card: Long, private val shape: Shape, private val density: Density) {
    internal var coords: LayoutCoordinates? = null

    /** Remembers where the cover is, for the page it opens to grow out of. */
    fun tap() {
        val at = coords?.takeIf { it.isAttached }
        val corner = at?.let { (shape as? CornerBasedShape)?.topStart?.toPx(it.size.toSize(), density) } ?: 0f
        CoverMorph.tapped(id, card, at?.boundsInWindow(), corner)
    }
}

/**
 * The cover of an album or playlist card that opens page [id] (that page's
 * route id), drawn in [shape]; null for a card that opens no such page.
 */
@Composable
fun rememberCoverMorph(id: String?, shape: Shape): CoverMorphCard? {
    // Kept with the card's saved state, so the card is the same one when its
    // page comes back from under the one it opened.
    val card = rememberSaveable { kotlin.random.Random.nextLong() }
    val density = LocalDensity.current
    return remember(id, card, shape, density) { id?.let { CoverMorphCard(it, card, shape, density) } }
}

/** On the cover of [card]: the page it opened grows out of here, and shrinks back here. */
@Composable
fun Modifier.coverMorphCard(card: CoverMorphCard?): Modifier {
    if (card == null) return this
    val placed = onPlaced { card.coords = it }
    val shared = LocalSharedPages.current ?: return placed
    val page = LocalPageScope.current ?: return placed
    // Only the card whose page is open looks again when one opens or closes.
    val key by remember(card.card) { derivedStateOf { CoverMorph.keyFor(card.card) } }
    val k = key ?: return placed
    val clip = remember(k) { CoverMorph.Clip(k.removePrefix("cover:")) }
    return with(shared) {
        placed.sharedBounds(
            rememberSharedContentState(k),
            page,
            enter = CoverMorph.coverIn,
            exit = CoverMorph.coverOut,
            boundsTransform = CoverMorph.bounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            clipInOverlayDuringTransition = clip,
        )
    }
}

/** On the page of back stack [entry]: grows out of the cover it was opened from, if it was. */
@Composable
fun Modifier.coverMorphPage(entry: String): Modifier {
    DisposableEffect(entry) {
        CoverMorph.shown(entry, true)
        onDispose { CoverMorph.shown(entry, false) }
    }
    val shared = LocalSharedPages.current ?: return this
    val page = LocalPageScope.current ?: return this
    val g = CoverMorph.grown[entry] ?: return this
    val clip = remember(entry) { CoverMorph.Clip(entry) }
    return with(shared) {
        onSizeChanged { g.pageWidth = it.width.toFloat() }.sharedBounds(
            rememberSharedContentState("cover:$entry"),
            page,
            enter = CoverMorph.pageIn,
            exit = CoverMorph.pageOut,
            boundsTransform = CoverMorph.bounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            zIndexInOverlay = 1f,
            clipInOverlayDuringTransition = clip,
        )
    }
}
