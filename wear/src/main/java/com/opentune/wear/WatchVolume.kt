package com.opentune.wear

import android.content.Intent
import android.media.AudioManager
import android.provider.Settings

/** The crown as a volume knob, and where to pair headphones. */
object WatchVolume {
    /** How far the crown turns for one volume step, in the pixels it reports. */
    private const val PIXELS_PER_STEP = 60f

    /**
     * How far the crown turns, added up, for one volume step. On a Xiaomi Watch 5 a small
     * turn reports 50 to 60 pixels and a long one 300 to 700: at 15 a small turn went from
     * full to silent.
     */
    const val CROWN_PIXELS_PER_STEP = 40f

    /** A volume step for one rotary event: up turning forward, down turning back, none for a nudge. */
    fun step(pixels: Float): Int? = when {
        pixels >= PIXELS_PER_STEP / 4 -> AudioManager.ADJUST_RAISE
        pixels <= -PIXELS_PER_STEP / 4 -> AudioManager.ADJUST_LOWER
        else -> null
    }

    /**
     * The crown's turns added up into volume steps. Crowns differ in how much
     * one event reports: some send one large event per click, others (Xiaomi's)
     * a stream of small ones that alone never make a step. A turn back the
     * other way starts the count again.
     */
    class Crown(private val perStep: Float = CROWN_PIXELS_PER_STEP) {
        private var turned = 0f

        fun step(pixels: Float): Int? {
            if (pixels == 0f) return null
            if (turned != 0f && (turned > 0) != (pixels > 0)) turned = 0f
            turned += pixels
            return when {
                turned >= perStep -> { turned -= perStep; AudioManager.ADJUST_RAISE }
                turned <= -perStep -> { turned += perStep; AudioManager.ADJUST_LOWER }
                else -> null
            }
        }
    }

    /**
     * Wear OS's Bluetooth picker, showing only headphones and closing once
     * one connects. Watches play music only through Bluetooth audio.
     */
    fun headphonesIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        .putExtra("EXTRA_CONNECTION_ONLY", true)
        .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
        .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
}
