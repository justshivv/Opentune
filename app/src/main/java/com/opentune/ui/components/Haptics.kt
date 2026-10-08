package com.opentune.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import com.opentune.data.settings.AppSettings

/**
 * The app's taps and buzzes, at the strength chosen in Settings.
 *
 * Where the phone can play haptic primitives (Android 11+ on most recent
 * phones) each tap is a full click with a low thump after it, scaled to the
 * setting. Otherwise a pulse at a matching amplitude, and on phones without
 * amplitude control, the system's own feedback. Nothing plays at zero, or
 * when touch feedback is off in the phone's settings.
 */
class Haptics internal constructor(private val view: View, private val strength: () -> Float) {
    private val vibrator: Vibrator? by lazy { vibratorOf(view.context) }

    /** A light tick: picking a tab, moving through a list. */
    fun tick() = play(Kind.TICK)

    /** A firmer click: a long press, a confirmed action. */
    fun press() = play(Kind.PRESS)

    internal enum class Kind { TICK, PRESS }

    /**
     * Feels made for one control, so each switch can be told apart by touch:
     * a riffle for shuffle, a loop closing for repeat, a swell for autoplay,
     * a fading fall for turning something off, a rising run when a job is done.
     */
    enum class Pattern { SHUFFLE, REPEAT_ALL, REPEAT_ONE, AUTOPLAY, OFF, DONE, PLAY, PAUSE, NEXT, PREVIOUS, LIKE }

    /** Whether a pattern is one of the transport's, which are meant to be felt deep and strong. */
    private val Pattern.deep get() = this == Pattern.PLAY || this == Pattern.PAUSE || this == Pattern.NEXT || this == Pattern.PREVIOUS

    fun pattern(p: Pattern) {
        val s = strength().coerceIn(0f, 1f)
        if (s <= 0f || !systemFeedbackOn(view.context)) return
        val v = vibrator ?: return play(Kind.TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            patternComposition(v, p, s)?.let { v.vibrate(it); return }
        }
        if (v.hasAmplitudeControl()) {
            val (timings, levels) = waveform(p, s)
            v.vibrate(VibrationEffect.createWaveform(timings, levels, -1))
        } else {
            play(if (p == Pattern.OFF) Kind.TICK else Kind.PRESS)
        }
    }

    /** The patterns from the phone's own primitives, when it has them all. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun patternComposition(v: Vibrator, p: Pattern, s: Float): VibrationEffect? {
        val steps: List<Triple<Int, Float, Int>> = when (p) {
            // Three quick ticks, each a little stronger: cards riffling.
            Pattern.SHUFFLE -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 38), Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 38))
            // A swell that lands on a click: a loop closing.
            Pattern.REPEAT_ALL -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.55f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 10))
            // A click and a soft second tap: once more, just this one.
            Pattern.REPEAT_ONE -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.8f, 70))
            // Up and back down without a break, like the ∞ it stands for.
            Pattern.AUTOPLAY -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.6f, 20), Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 30))
            // Falling away.
            Pattern.OFF -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f, 0))
            // A rising run of taps when something long has finished.
            Pattern.DONE -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 60), Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 0.9f, 60))
            // Play: a swell into a deep thump, then a small aftershock, like a heart starting.
            Pattern.PLAY -> listOf(
                Triple(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.7f, 0),
                Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 0),
                Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.9f, 55),
            )
            // Like: a heartbeat, lub-dub.
            Pattern.LIKE -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 0.85f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 110))
            // Pause: a deep thump that dies away.
            Pattern.PAUSE -> listOf(Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 0.95f, 0), Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.8f, 15))
            // Next: a rush forward that lands on a hard kick.
            Pattern.NEXT -> listOf(
                Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.85f, 0),
                Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0),
                Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.8f, 40),
            )
            // Previous: the kick first, then pulled back.
            Pattern.PREVIOUS -> listOf(
                Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0),
                Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.85f, 10),
                Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f, 45),
            )
        }
        if (!v.areAllPrimitivesSupported(*steps.map { it.first }.distinct().toIntArray())) return null
        val c = VibrationEffect.startComposition()
        // The transport stays strong even at a light setting; the rest follow the setting fully.
        val floor = if (p.deep) 0.7f else 0.45f
        steps.forEach { (prim, scale, delay) -> c.addPrimitive(prim, (scale * (floor + (1f - floor) * s)).coerceIn(0f, 1f), delay) }
        return c.compose()
    }

    internal fun play(kind: Kind) {
        val s = strength().coerceIn(0f, 1f)
        if (s <= 0f || !systemFeedbackOn(view.context)) return
        val v = vibrator
        if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(v, kind, s)?.let { v.vibrate(it); return }
        }
        when {
            v != null && v.hasAmplitudeControl() ->
                v.vibrate(VibrationEffect.createOneShot(pulseMs(kind), amplitude(kind, s)))
            else -> view.performHapticFeedback(
                if (kind == Kind.PRESS || s >= 0.5f) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
    }

    /**
     * A full click with a low thump under it, so a tap lands with weight
     * instead of a thin buzz. The thump (Android 12+) is left out where the
     * phone can't play it.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun composition(v: Vibrator, kind: Kind, s: Float): VibrationEffect? {
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        if (!v.areAllPrimitivesSupported(click)) return null
        val low = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (kind == Kind.PRESS) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        } else null
        val c = VibrationEffect.startComposition().addPrimitive(click, primitiveScale(kind, s))
        if (low != null && v.areAllPrimitivesSupported(low)) c.addPrimitive(low, lowScale(kind, s))
        return c.compose()
    }

    companion object {
        /** Default strength: noticeable without being loud. */
        const val DEFAULT = 0.6f

        internal fun primitiveScale(kind: Kind, s: Float) = (if (kind == Kind.TICK) 0.45f + 0.55f * s else 0.7f + 0.3f * s).coerceAtMost(1f)
        internal fun lowScale(kind: Kind, s: Float) = (if (kind == Kind.TICK) 0.3f + 0.7f * s else 0.55f + 0.45f * s).coerceAtMost(1f)
        internal fun amplitude(kind: Kind, s: Float) = ((if (kind == Kind.TICK) 110 + 145 * s else 160 + 95 * s).toInt()).coerceIn(1, 255)
        internal fun pulseMs(kind: Kind) = if (kind == Kind.TICK) 18L else 40L

        /** The same patterns as on/off pulses, for phones without primitives: gaps, then pulses at levels. */
        internal fun waveform(p: Pattern, s: Float): Pair<LongArray, IntArray> {
            fun a(f: Float) = (f * (110 + 145 * s)).toInt().coerceIn(1, 255)
            return when (p) {
                Pattern.SHUFFLE -> longArrayOf(0, 12, 28, 12, 28, 18) to intArrayOf(0, a(0.5f), 0, a(0.75f), 0, a(1f))
                Pattern.REPEAT_ALL -> longArrayOf(0, 40, 30, 16) to intArrayOf(0, a(0.35f), a(0.6f), a(1f))
                Pattern.REPEAT_ONE -> longArrayOf(0, 18, 60, 12) to intArrayOf(0, a(1f), 0, a(0.6f))
                Pattern.AUTOPLAY -> longArrayOf(0, 25, 25, 25, 25) to intArrayOf(0, a(0.4f), a(0.8f), a(0.5f), a(0.25f))
                Pattern.OFF -> longArrayOf(0, 20, 20) to intArrayOf(0, a(0.6f), a(0.25f))
                Pattern.DONE -> longArrayOf(0, 12, 50, 12, 50, 30) to intArrayOf(0, a(0.5f), 0, a(0.7f), 0, a(1f))
                Pattern.LIKE -> longArrayOf(0, 28, 90, 34) to intArrayOf(0, a(0.8f), 0, 255)
                Pattern.PLAY -> longArrayOf(0, 30, 45, 40, 14) to intArrayOf(0, a(0.4f), 255, 0, a(0.8f))
                Pattern.PAUSE -> longArrayOf(0, 45, 25, 25) to intArrayOf(0, 255, a(0.6f), a(0.25f))
                Pattern.NEXT -> longArrayOf(0, 20, 30, 35, 14) to intArrayOf(0, a(0.6f), 255, 0, a(0.7f))
                Pattern.PREVIOUS -> longArrayOf(0, 30, 20, 20, 35, 12) to intArrayOf(0, 255, a(0.6f), a(0.3f), 0, a(0.6f))
            }
        }

        /** "Off", "Light", "Medium", "Strong" for a strength. */
        fun label(s: Float) = when {
            s <= 0f -> "Off"
            s < 0.4f -> "Light"
            s < 0.75f -> "Medium"
            else -> "Strong"
        }

        private fun vibratorOf(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

        private fun systemFeedbackOn(context: Context): Boolean =
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }.getOrDefault(true)
    }
}

/** Haptics for this screen, following the strength setting as it changes. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val ui by AppSettings.ui.collectAsState()
    val strength by rememberUpdatedState(ui.hapticStrength)
    return remember(view) { Haptics(view) { strength } }
}
