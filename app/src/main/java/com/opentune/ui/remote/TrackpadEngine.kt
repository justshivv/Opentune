package com.opentune.ui.remote

import com.opentune.data.remote.RemoteKey
import com.opentune.data.remote.RemoteMessage
import kotlin.math.hypot
import kotlin.math.min

/** How the trackpad behaves; read afresh for each gesture, so a change in settings applies at once. */
internal data class TrackpadConfig(
    /** 0.5 to 2.5: how far the cursor goes for a finger's move. */
    val speed: Float = 1f,
    /** Two fingers drag the page with them; off, the other way. */
    val naturalScroll: Boolean = true,
    val tapToClick: Boolean = true,
)

/**
 * What a finger or two or three on the trackpad mean, turned into messages for the TV:
 *
 *  - one finger moves the cursor (faster when it moves faster); a quick tap clicks
 *  - holding one finger still, then moving it, drags: the button goes down until it lifts
 *  - two fingers scroll; a quick tap with two goes Back
 *  - a quick tap with three goes Home
 *
 * It knows nothing of the screen: it is told how many fingers are down and how far they moved
 * (in dp), so it can be tried with made-up touches. [feel] is told when to buzz the phone.
 */
internal class TrackpadEngine(
    private val send: (RemoteMessage) -> Unit,
    private val config: () -> TrackpadConfig = { TrackpadConfig() },
    private val feel: (Feel) -> Unit = {},
) {
    enum class Feel { TAP, GRAB, RELEASE, BACK, HOME }

    private var active = false
    private var startMs = 0L
    private var maxFingers = 0
    private var travel = 0f
    private var slopCrossed = false
    private var dragging = false
    private var pendingX = 0f
    private var pendingY = 0f

    /** Fingers [count] now down, which together moved ([dx], [dy]) dp in the last [dtMs]. Called for every touch event. */
    fun onEvent(now: Long, count: Int, dx: Float, dy: Float, dtMs: Long) {
        if (count <= 0) return
        if (!active) {
            active = true; startMs = now; maxFingers = 0; travel = 0f
            slopCrossed = false; dragging = false; pendingX = 0f; pendingY = 0f
        }
        maxFingers = maxOf(maxFingers, count)
        val dist = hypot(dx, dy)
        travel += dist
        when {
            // One finger only for the whole gesture; if a second ever touched, a lifted finger doesn't move the cursor.
            maxFingers == 1 -> {
                pendingX += dx; pendingY += dy
                if (!slopCrossed && travel > TOUCH_SLOP_DP) slopCrossed = true
                if (slopCrossed && (pendingX != 0f || pendingY != 0f)) {
                    val speed = if (dtMs > 0) dist / dtMs * 1000f else 0f
                    val gain = config().speed * (BASE_GAIN + min(speed / ACCEL_DP_PER_S, MAX_ACCEL))
                    send(RemoteMessage.Move(pendingX * gain, pendingY * gain))
                    pendingX = 0f; pendingY = 0f
                }
            }
            maxFingers == 2 && count == 2 -> {
                pendingX += dx; pendingY += dy
                if (travel > TOUCH_SLOP_DP * 2) {
                    slopCrossed = true
                    val sign = if (config().naturalScroll) 1f else -1f
                    send(RemoteMessage.Scroll(pendingX * sign * SCROLL_GAIN, pendingY * sign * SCROLL_GAIN))
                    pendingX = 0f; pendingY = 0f
                }
            }
        }
    }

    /** Called while a finger rests, a few times a second: a finger held still for a moment starts a drag. */
    fun onTick(now: Long) {
        if (active && !dragging && maxFingers == 1 && !slopCrossed && now - startMs >= LONG_PRESS_MS) {
            dragging = true
            send(RemoteMessage.Press(true))
            feel(Feel.GRAB)
        }
    }

    /** How long until [onTick] could matter, so a caller can wait exactly that long; null when nothing is pending. */
    fun nextTickIn(now: Long): Long? =
        if (active && !dragging && maxFingers == 1 && !slopCrossed) (LONG_PRESS_MS - (now - startMs)).coerceAtLeast(1L) else null

    /** Every finger is up. */
    fun onRelease(now: Long) {
        if (!active) return
        active = false
        val quick = now - startMs
        when {
            dragging -> { send(RemoteMessage.Press(false)); feel(Feel.RELEASE) }
            maxFingers == 1 && !slopCrossed && quick < TAP_MS -> if (config().tapToClick) { send(RemoteMessage.Click()); feel(Feel.TAP) }
            maxFingers == 2 && travel <= TOUCH_SLOP_DP * 2 && quick < MULTI_TAP_MS -> { send(RemoteMessage.Key(RemoteKey.BACK)); feel(Feel.BACK) }
            maxFingers == 3 && travel <= TOUCH_SLOP_DP * 3 && quick < MULTI_TAP_MS -> { send(RemoteMessage.Key(RemoteKey.HOME)); feel(Feel.HOME) }
        }
    }

    companion object {
        /** A finger that moves less than this is still, so a tap can wobble. */
        const val TOUCH_SLOP_DP = 5f
        const val TAP_MS = 260L
        const val MULTI_TAP_MS = 320L
        const val LONG_PRESS_MS = 450L
        const val BASE_GAIN = 0.8f
        const val ACCEL_DP_PER_S = 450f
        const val MAX_ACCEL = 2.2f
        const val SCROLL_GAIN = 1.0f
    }
}
