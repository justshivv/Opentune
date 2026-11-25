package com.opentune.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.core.content.ContextCompat
import com.opentune.data.model.BrowseType
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.livingArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Which of Home's big cards may move their cover, apart from Compose so it
 * can be tested. The movement is the player's own moving cover art
 * ([livingArt]): the cover already on the card, drifting and zooming inside
 * its frame. It never fetches anything of its own.
 */
internal object MovingCoverRules {
    /** A card has to be at least this much on screen to move. */
    const val MIN_VISIBLE = 0.6f
    /** No more than this many move at once, so Home doesn't swim. */
    const val MAX_MOVING = 2

    /**
     * Where one card is: its share on screen, its place, the shelf it's in,
     * and whether its cover has loaded, so is in the image cache.
     */
    data class Spot(val key: Any, val shelf: Any, val visible: Float, val top: Float, val left: Float, val cached: Boolean = true)

    /**
     * Songs and albums have moving art, as in the player. Artists, playlists
     * and other pages keep a still cover.
     */
    fun hasMovingArt(isSong: Boolean, type: BrowseType?): Boolean = isSong || type == BrowseType.ALBUM

    /**
     * Whether anything on Home may move: the switch is on, animation isn't
     * reduced, battery saver is off, and the full player isn't [covered] over the page.
     */
    fun allowed(enabled: Boolean, reduceAnimation: Boolean, powerSave: Boolean, covered: Boolean = false): Boolean =
        enabled && !reduceAnimation && !powerSave && !covered

    /**
     * The cards that move: none while [scrolling] or not [allowed]; on
     * [metered] data, only those whose cover is already cached; otherwise
     * the most visible of those at least [MIN_VISIBLE] on screen, top to
     * bottom and start to end on a tie, one per shelf so the two are apart.
     */
    fun pick(
        spots: Collection<Spot>,
        allowed: Boolean,
        scrolling: Boolean,
        metered: Boolean = false,
        rtl: Boolean = false,
        max: Int = MAX_MOVING,
    ): Set<Any> {
        if (!allowed || scrolling || max <= 0) return emptySet()
        val shelves = HashSet<Any>()
        val picked = LinkedHashSet<Any>()
        spots.asSequence()
            .filter { it.visible >= MIN_VISIBLE && (it.cached || !metered) }
            .sortedWith(compareByDescending<Spot> { it.visible }.thenBy { it.top }.thenBy { if (rtl) -it.left else it.left })
            .forEach { s ->
                if (picked.size < max && shelves.add(s.shelf)) picked += s.key
            }
        return picked
    }

    /** The share of a [width] × [height] card that its on-screen bounds cover. */
    fun visibleShare(width: Int, height: Int, shownWidth: Float, shownHeight: Float): Float {
        val area = width.toFloat() * height
        if (area <= 0f) return 0f
        return (shownWidth.coerceAtLeast(0f) * shownHeight.coerceAtLeast(0f) / area).coerceIn(0f, 1f)
    }
}

/**
 * Home's moving covers: the cards report where they are and when their
 * cover has loaded, and this picks which may move. Any scroll on Home, down
 * the page or along a shelf, holds them all still until it settles.
 */
@Stable
class MovingCovers internal constructor(private val rtl: Boolean, private val scope: CoroutineScope) {
    private val spots = mutableStateMapOf<Any, MovingCoverRules.Spot>()
    private val loaded = mutableStateMapOf<Any, Boolean>()
    /** The switch is on and animation isn't reduced: covers can move at all. */
    var enabled by mutableStateOf(false)
    /** [enabled], and nothing for now says to keep still (battery saver, the player over the page). */
    var allowed by mutableStateOf(false)
    /** On mobile data or another metered network. */
    var metered by mutableStateOf(false)
    /** The page's own scroll, from its list state. */
    var pageScrolling by mutableStateOf(false)
    /** A drag or fling anywhere inside the page, shelves included, seen through nested scrolling. */
    private var nestedScrolling by mutableStateOf(false)

    val moving: Set<Any> by derivedStateOf {
        // Read nothing else while still, so a scroll's every frame of new
        // positions doesn't recompute this.
        if (!allowed || pageScrolling || nestedScrolling) emptySet()
        else MovingCoverRules.pick(
            spots.values.map { it.copy(cached = loaded[it.key] == true) },
            allowed = true, scrolling = false, metered = metered, rtl = rtl,
        )
    }

    internal fun report(spot: MovingCoverRules.Spot) {
        if (spots[spot.key] != spot) spots[spot.key] = spot
    }

    internal fun artLoaded(key: Any, done: Boolean) {
        if (loaded[key] != done) loaded[key] = done
    }

    internal fun forget(key: Any) {
        spots.remove(key)
        loaded.remove(key)
    }

    private var quiet: Job? = null

    val scrollWatch = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available == Offset.Zero) return Offset.Zero
            if (!nestedScrolling) nestedScrolling = true
            // Drags and flings report every frame. A fling stopped by a tap
            // never reports its end, so a short quiet spell counts as one.
            quiet?.cancel()
            quiet = scope.launch {
                delay(QUIET_MS)
                nestedScrolling = false
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            quiet?.cancel()
            nestedScrolling = false
            return Velocity.Zero
        }
    }

    private companion object {
        const val QUIET_MS = 250L
    }
}

/**
 * The page's [MovingCovers], kept in step with the settings, battery saver,
 * the network and the player over the page.
 */
@Composable
internal fun rememberMovingCovers(rtl: Boolean, covered: Boolean): MovingCovers {
    val ui by AppSettings.ui.collectAsState()
    val powerSave = rememberPowerSaveMode()
    val metered = rememberMeteredNetwork()
    val scope = rememberCoroutineScope()
    val covers = remember(rtl, scope) { MovingCovers(rtl, scope) }
    // The system's "Remove animations" holds them still too, as Reduce animation does.
    val still = ui.reduceAnimation || com.opentune.ui.components.systemAnimationsRemoved()
    covers.enabled = MovingCoverRules.allowed(ui.homeMovingCovers, still, powerSave = false)
    covers.allowed = MovingCoverRules.allowed(ui.homeMovingCovers, still, powerSave, covered)
    covers.metered = metered
    return covers
}

/**
 * What one Home card needs to move its cover: [spot] goes on the card to
 * report where it is, [art] on its image, and [onArtLoaded] hears whether
 * the cover has loaded.
 */
@Immutable
internal class CoverMotion(val spot: Modifier, val art: Modifier, val onArtLoaded: ((Boolean) -> Unit)?) {
    companion object {
        val Still = CoverMotion(Modifier, Modifier, null)
    }
}

/** The [CoverMotion] for the card [key] in [shelf]; still for cards without moving art. */
@Composable
internal fun rememberCoverMotion(covers: MovingCovers, key: Any, shelf: Any, hasMovingArt: Boolean): CoverMotion {
    if (!hasMovingArt) return CoverMotion.Still
    // A card scrolled away is forgotten, and its frame loop (in livingArt) ends with it.
    DisposableEffect(covers, key) { onDispose { covers.forget(key) } }
    val spot = remember(covers, key, shelf) {
        Modifier.onGloballyPositioned { c ->
            // Clipped by the page and the shelf, so a card half off either reads as half shown.
            val shown = c.boundsInWindow()
            val share = MovingCoverRules.visibleShare(c.size.width, c.size.height, shown.width, shown.height)
            covers.report(MovingCoverRules.Spot(key, shelf, share, shown.top, shown.left))
        }
    }
    val onArtLoaded = remember(covers, key) { { done: Boolean -> covers.artLoaded(key, done) } }
    // Eases in and out of the still cover over a second and a half (see
    // livingArt), inside its frame, so nothing around it moves. Battery saver
    // or a scroll only take the card out of [MovingCovers.moving], so it
    // eases back to still rather than jumping.
    val art = Modifier.livingArt(playing = key in covers.moving, enabled = covers.enabled)
    return CoverMotion(spot, art, onArtLoaded)
}

/** Whether battery saver is on, following it as it's turned on and off. */
@Composable
internal fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var saving by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                saving = power?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // It may have changed between the first read and registering.
        saving = power?.isPowerSaveMode == true
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return saving
}

/** Whether the network in use is metered (mobile data, a metered hotspot), following it as it changes. */
@Composable
internal fun rememberMeteredNetwork(): Boolean {
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var metered by remember(connectivity) { mutableStateOf(AppSettings.onMeteredNetwork()) }
    DisposableEffect(connectivity) {
        val cm = connectivity ?: return@DisposableEffect onDispose {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }

            override fun onLost(network: Network) {
                metered = AppSettings.onMeteredNetwork()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return metered
}
