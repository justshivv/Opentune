package com.opentune.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import com.opentune.R
import com.opentune.data.settings.AppSettings
import com.opentune.ui.Links
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating lyrics: the sung line in a small pill over other apps, while music plays and the
 * app is in the background. It needs "Display over other apps", asked for when the setting
 * is turned on. There is no service of its own: the player service keeps the app running
 * while music plays, and [LyricsOutside] tells the pill when the line changes.
 *
 * Tap it to open the player. Drag it anywhere, and it stays there next time; drag it (or
 * long-press and drag) onto the close mark at the bottom to put it away until the app is
 * next opened.
 */
object FloatingLyrics : LyricsOutlet {
    override val minIntervalMs = 250L

    private val handler = Handler(Looper.getMainLooper())
    private var started = 0
    private var dismissed = false
    private var window: WindowManager? = null
    private var pill: FloatingLyricsPill? = null
    private var params: WindowManager.LayoutParams? = null
    private var target: View? = null
    private val removeLater = Runnable { remove() }

    /** Follows whether any of the app's screens is showing: the pill keeps out of the app's way. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                // Opening the app brings back a pill that was put away.
                dismissed = false
                LyricsOutside.poke()
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                LyricsOutside.poke()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Whether the app may draw over others ("Display over other apps"). */
    fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** The system page where the person allows "Display over other apps" for this app. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))

    override fun wanted(context: Context, playing: Boolean): Boolean =
        AppSettings.lyrics.value.floating && playing && started == 0 && !dismissed && allowed(context)

    override fun show(context: Context, now: LyricsNow?) {
        if (now == null || (now.current == null && now.next == null)) {
            // Paused, or between songs while the next one's lyrics come in: wait a moment, so a
            // skip doesn't flash it away and back. Over the app, or turned off, it goes at once.
            if (started > 0 || dismissed || !AppSettings.lyrics.value.floating) remove()
            else if (pill != null) handler.postDelayed(removeLater, HIDE_AFTER_MS)
            return
        }
        handler.removeCallbacks(removeLater)
        val pill = pill ?: add(context.applicationContext) ?: return
        pill.setLines(now, animate = moving())
    }

    /** Lines slide and the close mark fades, unless Reduce animation or Lite mode is on. */
    private fun moving(): Boolean = AppSettings.ui.value.let { !it.reduceAnimation && !it.liteMode }

    private fun add(context: Context): FloatingLyricsPill? {
        if (!allowed(context)) return null
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val metrics = context.resources.displayMetrics
        val pill = FloatingLyricsPill(context, maxWidthPx = (metrics.widthPixels * 0.72f).toInt())
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Where it was left; kept on screen if that was on a wider or taller one (turned sideways, say).
        val edge = (48 * metrics.density).toInt()
        val lp = overlayParams(Gravity.TOP or Gravity.START, touchable = true).apply {
            x = saved.getInt("x", (16 * metrics.density).toInt()).coerceIn(0, (metrics.widthPixels - edge).coerceAtLeast(0))
            y = saved.getInt("y", (metrics.heightPixels * 0.18f).toInt()).coerceIn(0, (metrics.heightPixels - edge).coerceAtLeast(0))
        }
        pill.setOnTouchListener(Dragger(context, wm, lp))
        // A tap (and TalkBack's double-tap) opens the player.
        pill.setOnClickListener { openPlayer(context) }
        ViewCompat.addAccessibilityAction(pill, com.opentune.i18n.Tr.t("Close floating lyrics")) { _, _ -> dismiss(); true }
        if (runCatching { wm.addView(pill, lp) }.isFailure) return null
        window = wm
        params = lp
        this.pill = pill
        return pill
    }

    private fun overlayParams(gravity: Int, touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun remove() {
        handler.removeCallbacks(removeLater)
        hideTarget()
        pill?.let { p -> runCatching { window?.removeView(p) } }
        pill = null
        params = null
    }

    /** Put away until the app is next opened. */
    private fun dismiss() {
        dismissed = true
        remove()
        LyricsOutside.poke()
    }

    private fun openPlayer(context: Context) {
        val intent = com.opentune.data.AppHooks.openApp(context).setAction(Links.ACTION_OPEN_PLAYER)
        runCatching { context.startActivity(intent) }
    }

    /** The close mark at the bottom, shown while the pill is held. */
    private fun showTarget(context: Context): View? {
        target?.let { return it }
        val wm = window ?: return null
        val density = context.resources.displayMetrics.density
        val size = (56 * density).toInt()
        val view = FrameLayout(context).apply {
            val mark = ImageView(context).apply {
                setImageResource(R.drawable.symbol_close_rounded)
                setColorFilter(0xFFFFFFFF.toInt())
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xE6202228.toInt())
                    setStroke((1.5f * density).toInt(), 0x40FFFFFF)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(mark, FrameLayout.LayoutParams(size, size))
        }
        val lp = overlayParams(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, touchable = false).apply { y = (TARGET_BOTTOM_DP * density).toInt() }
        if (runCatching { wm.addView(view, lp) }.isFailure) return null
        if (moving()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).start()
        }
        target = view
        return view
    }

    private fun hideTarget() {
        target?.let { t -> runCatching { window?.removeView(t) } }
        target = null
    }

    /** Whether the pill's middle is over the close mark. */
    private fun overTarget(context: Context, pill: View, lp: WindowManager.LayoutParams): Boolean {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val targetX = metrics.widthPixels / 2f
        val targetY = metrics.heightPixels - (TARGET_BOTTOM_DP + 28) * density
        val x = lp.x + pill.width / 2f
        val y = lp.y + pill.height / 2f
        return hypot(x - targetX, y - targetY) < CLOSE_RADIUS_DP * density
    }

    /** Taps, drags and long-presses on the pill. */
    private class Dragger(
        private val context: Context,
        private val wm: WindowManager,
        private val lp: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var held = false
        private var dragging = false
        private var over = false
        private var view: View? = null
        private val longPress = Runnable {
            held = true
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showTarget(context)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view = v
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    held = false
                    dragging = false
                    over = false
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        v.removeCallbacks(longPress)
                        showTarget(context)
                    }
                    if (dragging || held) {
                        val metrics = context.resources.displayMetrics
                        lp.x = (startX + dx).toInt().coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, lp) }
                        val nowOver = overTarget(context, v, lp)
                        if (nowOver != over) {
                            over = nowOver
                            if (over) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            val scale = if (over) 1.25f else 1f
                            target?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(if (moving()) 120 else 0)?.start()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    when {
                        (dragging || held) && over -> dismiss()
                        dragging || held -> {
                            hideTarget()
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("x", lp.x); putInt("y", lp.y) }
                        }
                        else -> v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    hideTarget()
                }
            }
            return true
        }
    }

    private const val PREFS = "floating_lyrics"
    private const val HIDE_AFTER_MS = 1_500L
    private const val TARGET_BOTTOM_DP = 56
    private const val CLOSE_RADIUS_DP = 72
}
