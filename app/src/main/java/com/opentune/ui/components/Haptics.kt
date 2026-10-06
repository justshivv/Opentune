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
