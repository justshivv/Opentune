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
 * phones) they're scaled to the setting, which feels crisp at any level.
 * Otherwise a short pulse at a matching amplitude, and on phones without
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
            val primitive = if (kind == Kind.TICK) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            if (v.areAllPrimitivesSupported(primitive)) {
                v.vibrate(VibrationEffect.startComposition().addPrimitive(primitive, primitiveScale(kind, s)).compose())
                return
            }
        }
        when {
            v != null && v.hasAmplitudeControl() ->
                v.vibrate(VibrationEffect.createOneShot(pulseMs(kind), amplitude(kind, s)))
            else -> view.performHapticFeedback(
                when {
                    kind == Kind.PRESS -> HapticFeedbackConstants.LONG_PRESS
                    s < 0.5f -> HapticFeedbackConstants.CLOCK_TICK
                    else -> HapticFeedbackConstants.KEYBOARD_TAP
                },
            )
        }
    }

    companion object {
        /** Default strength: noticeable without being loud. */
        const val DEFAULT = 0.6f

        internal fun primitiveScale(kind: Kind, s: Float) = if (kind == Kind.TICK) s else (0.35f + 0.65f * s)
        internal fun amplitude(kind: Kind, s: Float) = ((if (kind == Kind.TICK) 30 + 170 * s else 70 + 185 * s).toInt()).coerceIn(1, 255)
        internal fun pulseMs(kind: Kind) = if (kind == Kind.TICK) 10L else 22L

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
