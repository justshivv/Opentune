package com.opentune.ui.components

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps Liquid Glass smooth on phones whose graphics can't draw all of it
 * every frame (a Redmi Note 8's Adreno 610, say), without changing how it
 * looks on the ones that can.
 *
 * While glass is on screen, the time each frame took is watched. When frames
 * keep running late, glass turns [lite] on this phone: the lens no longer
 * splits light into a colour fringe (three samples of the backdrop instead
 * of one, for a fringe a pixel or two wide) and the inner shade under the rim
 * is left out (another blurred pass). The frost, the lens's bend, the lit rim,
 * the tint and the shadow stay. It's remembered for this phone, and can be
 * turned back in Settings.
 */
object GlassPerformance {
    /** Glass on this phone leaves out its two costliest touches. */
    var lite by mutableStateOf(false)
        private set

    /** Whether [lite] was set by hand in Settings, after which frame times no longer change it. */
    private var chosen = false
    private var prefs: android.content.SharedPreferences? = null

    /** Something drawing Liquid Glass is on screen; only then do slow frames count against it. */
    @Volatile var glassShowing = 0

    fun init(context: Context) {
        val p = context.getSharedPreferences("glass", Context.MODE_PRIVATE)
        prefs = p
        lite = p.getBoolean("lite", false)
        chosen = p.getBoolean("chosen", false)
    }

    /** From Settings: [on] is kept whatever the frame times say. */
    fun choose(on: Boolean) {
        lite = on
        chosen = true
        prefs?.edit()?.putBoolean("lite", on)?.putBoolean("chosen", true)?.apply()
    }

    private fun becomeLite() {
        if (lite || chosen) return
        lite = true
        prefs?.edit()?.putBoolean("lite", true)?.apply()
    }

    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** Starts watching [activity]'s frames; stop with [unwatch]. */
    fun watch(activity: Activity) {
        if (lite || chosen || listener != null) return
        val t = thread ?: HandlerThread("glass-frames").also { it.start(); thread = it }
        val handler = Handler(t.looper)
        @Suppress("DEPRECATION")
        val hz = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.refreshRate else activity.windowManager.defaultDisplay.refreshRate
        }.getOrNull()?.takeIf { it > 1f } ?: 60f
        // A frame more than half again its budget was a dropped one.
        val lateNs = (1_000_000_000L / hz * 1.5f).toLong()
        val window = FrameWindow(SAMPLE, SLOW_SHARE)
        var badWindows = 0
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (glassShowing <= 0) return@OnFrameMetricsAvailableListener
            // The first frame after a pause or a new screen is always slow; it says nothing.
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val verdict = window.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) > lateNs) ?: return@OnFrameMetricsAvailableListener
            badWindows = if (verdict) badWindows + 1 else 0
            if (badWindows >= WINDOWS) activity.runOnUiThread {
                becomeLite()
                unwatch(activity)
            }
        }
        listener = l
        runCatching { activity.window.addOnFrameMetricsAvailableListener(l, handler) }
    }

    fun unwatch(activity: Activity) {
        val l = listener ?: return
        listener = null
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(l) }
    }

    /** Frames in runs of [size]: each full run says whether more than [share] of it was late. */
    internal class FrameWindow(private val size: Int, private val share: Float) {
        private var count = 0
        private var late = 0

        /** Null until a run is full; then whether that run was a slow one. */
        fun add(wasLate: Boolean): Boolean? {
            count++
            if (wasLate) late++
            if (count < size) return null
            val slow = late > size * share
            count = 0
            late = 0
            return slow
        }
    }

    /** Frames looked at together. */
    private const val SAMPLE = 90
    /** More than this share of a run late, and the run was slow. */
    private const val SLOW_SHARE = 0.25f
    /** Slow runs in a row before glass turns lite: a moment's stutter (a page opening) isn't enough. */
    private const val WINDOWS = 3
}
