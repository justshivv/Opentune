package com.opentune.data.tips

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A hidden gesture the app points out once, the first time it's of use. */
enum class Tip {
    /** Swiping a song row: one way plays it next, the other queues it. */
    SWIPE_ROW,
    /** Pressing and holding a Speed dial tile for its menu. */
    HOLD_TILE,
    /** Pressing and holding Search to recognise the song playing nearby. */
    RECOGNIZE,
    /** Swiping the player's cover to skip, double-tapping its sides to jump. */
    PLAYER,
    /** Swiping the mini player sideways to skip, down to stop. */
    MINI_PLAYER,
    /** Downloading an album or playlist played often, from its page's menu. */
    DOWNLOAD,
}

/** When a tip may show, apart from Android so it can be tested. */
object TipRules {
    /** Recognising songs is pointed out only from this launch on, so the first one isn't crowded. */
    const val RECOGNIZE_FROM_LAUNCH = 2
    /** A tip that stayed up this long counts as read, even if it went away without a tap. */
    const val READ_MS = 2_500L
    /** After a tip goes, this long before the next: no tip chasing a finger that's scrolling. */
    const val QUIET_MS = 3_000L

    /** Played from this often, an album or playlist is worth pointing the download out on. */
    const val DOWNLOAD_AFTER_PLAYS = 3

    fun canShow(tip: Tip, enabled: Boolean, seen: Set<Tip>, showing: Tip?, launches: Int, welcoming: Boolean): Boolean =
        enabled && !welcoming && tip !in seen && (showing == null || showing == tip) &&
            (tip != Tip.RECOGNIZE || launches >= RECOGNIZE_FROM_LAUNCH)
}

/**
 * The tips at first use: which have been seen, which one is up. Each shows
 * once, one at a time, never during the welcome. Kept in their own
 * preferences, so "Show tips again" touches nothing else.
 */
object Tips {
    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    private val _seen = MutableStateFlow<Set<Tip>>(emptySet())
    private val _active = MutableStateFlow<Tip?>(null)
    private var launches = 0
    private var shownAt = 0L
    private var quietUntil = Long.MIN_VALUE

    /** Tips on at all (Settings › Show tips). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    /** The tips already seen or done. */
    val seen: StateFlow<Set<Tip>> = _seen.asStateFlow()
    /** The tip up on screen now, if any. */
    val active: StateFlow<Tip?> = _active.asStateFlow()

    /** Says whether the welcome is on screen; set by the app (true keeps every tip back). */
    var welcoming: () -> Boolean = { false }
    /** The clock, replaceable in tests. */
    internal var now: () -> Long = { android.os.SystemClock.uptimeMillis() }

    /** Once per process start: reads what was seen and how often the app was opened. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("tips", Context.MODE_PRIVATE)
        prefs = p
        launches = p.getInt(LAUNCHES, 0)
        _enabled.value = p.getBoolean(ENABLED, true)
        _seen.value = p.getStringSet(SEEN, emptySet()).orEmpty().mapNotNull { n -> Tip.entries.firstOrNull { it.name == n } }.toSet()
    }

    /**
     * The app's screen was opened afresh (not turned or brought back). Counted here rather
     * than per process: with music playing the process outlives the app being closed.
     */
    fun opened() {
        launches++
        prefs?.edit()?.putInt(LAUNCHES, launches)?.apply()
    }

    /** Whether [tip] could still show: on, and not seen yet. Cheap, for deciding whether to watch for it at all. */
    fun wanted(tip: Tip): Boolean = _enabled.value && tip !in _seen.value

    /** Asks to show [tip]; true when it's (now or already) the one up. */
    fun request(tip: Tip): Boolean {
        if (_active.value == tip) return true
        if (now() < quietUntil) return false
        if (!TipRules.canShow(tip, _enabled.value, _seen.value, _active.value, launches, welcoming())) return false
        _active.value = tip
        shownAt = now()
        return true
    }

    /** The tip was closed, or what it points out was done: it never shows again. */
    fun dismiss(tip: Tip) {
        if (tip !in _seen.value) {
            _seen.value = _seen.value + tip
            save()
        }
        if (_active.value == tip) hide()
    }

    private fun hide() {
        _active.value = null
        quietUntil = now() + TipRules.QUIET_MS
    }

    /** The user did what [tip] is about, whether or not it was up. */
    fun done(tip: Tip) = dismiss(tip)

    /**
     * What [tip] points at went away (scrolled off, another page, the player
     * opened over it). Up long enough to have been read, it counts as seen;
     * otherwise it comes back the next time.
     */
    fun withdraw(tip: Tip) {
        if (_active.value != tip) return
        if (now() - shownAt >= TipRules.READ_MS) dismiss(tip) else hide()
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (!on) _active.value = null
        prefs?.edit()?.putBoolean(ENABLED, on)?.apply()
    }

    /** Settings › Show tips again: every tip shows once more. */
    fun reset() {
        _seen.value = emptySet()
        _active.value = null
        quietUntil = Long.MIN_VALUE
        save()
        setEnabled(true)
    }

    private fun save() {
        prefs?.edit()?.putStringSet(SEEN, _seen.value.map { it.name }.toSet())?.apply()
    }

    /** For tests: back to a fresh install's state, with no preferences. */
    internal fun clearForTest(launchCount: Int = 1) {
        prefs = null
        launches = launchCount
        _enabled.value = true
        _seen.value = emptySet()
        _active.value = null
        quietUntil = Long.MIN_VALUE
        welcoming = { false }
    }

    private const val LAUNCHES = "launches"
    private const val ENABLED = "enabled"
    private const val SEEN = "seen"
}
