package com.opentune.lyrics

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.opentune.data.lyrics.lyricsOffset
import com.opentune.data.lyrics.momentAt
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Somewhere outside the player that shows the sung line: a widget, the floating lyrics. */
internal interface LyricsOutlet {
    /** The least time between two lines sent to it. */
    val minIntervalMs: Long

    /** Whether it wants lines now: its setting on, and whatever else it needs. */
    fun wanted(context: Context, playing: Boolean): Boolean

    /** Shows [now]; null shows no lyrics. */
    fun show(context: Context, now: LyricsNow?)
}

/**
 * Lets a changed line through at most once per [minIntervalMs]; a line that changes
 * again sooner waits, and the latest one goes when the time is up. The same line twice
 * is never sent again.
 */
internal class LineThrottle(private val minIntervalMs: Long) {
    sealed interface Decision {
        data object Push : Decision
        data object Same : Decision
        data class Wait(val ms: Long) : Decision
    }

    private var sent = false
    private var sentKey: String? = null
    private var sentAtMs = 0L

    fun offer(key: String?, nowMs: Long): Decision {
        if (sent && key == sentKey) return Decision.Same
        val since = nowMs - sentAtMs
        if (sent && since < minIntervalMs) return Decision.Wait(minIntervalMs - since)
        sent = true
        sentKey = key
        sentAtMs = nowMs
        return Decision.Push
    }

    /** Forget what was sent, so the next line goes even if it's the same one. */
    fun reset() {
        sent = false
        sentKey = null
    }
}

/**
 * Follows the playing song's lyrics for the widgets and the floating lyrics. It wakes when
 * the sung line changes, not on every frame, and only while one of them is on. Paused, or
 * with the screen off, it sleeps until playback or the screen comes back. The lyrics are
 * the ones the player already has ([NowPlayingLyrics]): nothing here looks any up.
 */
object LyricsOutside {
    private var follower: LyricsFollower? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called once from the app's start, with every outlet there is. */
    internal fun install(context: Application, vararg outlet: LyricsOutlet) {
        val f = LyricsFollower(context, outlet.toList())
        follower = f
        scope.launch {
            combine(
                AppSettings.lyrics.map { Triple(it.onWidgets, it.floating, it.offsets) }.distinctUntilChanged(),
                NowPlayingLyrics.loaded,
                // A mix starting or ending changes the song heard.
                com.opentune.playback.MixState.showing,
            ) { _, _, _ -> }.collect { f.poke() }
        }
    }

    /** The player service's player changed state; null when it has gone. */
    fun onPlayer(player: Player?) {
        follower?.onPlayer(player)
    }

    /** Something an outlet depends on changed (the app came to the front, say): look again. */
    fun poke() {
        follower?.poke()
    }
}

/** [LyricsOutside]'s workings, for one set of [outlets]. */
internal class LyricsFollower(private val context: Context, private val outlets: List<LyricsOutlet>) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private val throttles = outlets.associateWith { LineThrottle(it.minIntervalMs) }
    private val showing = HashSet<LyricsOutlet>()
    private val tick = Runnable { tick() }
    private var screenWatched = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                )
            ) tick()
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tick()
    }

    fun onPlayer(player: Player?) = onMain {
        if (player !== this.player) {
            this.player?.removeListener(listener)
            player?.addListener(listener)
            this.player = player
        }
        tick()
    }

    fun poke() = onMain { tick() }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun tick() {
        handler.removeCallbacks(tick)
        val p = player
        val playing = p?.isPlaying == true
        val wanted = outlets.filter { p != null && it.wanted(context, playing) }
        for (o in outlets) if (o !in wanted && showing.remove(o)) {
            throttles.getValue(o).reset()
            o.show(context, null)
        }
        watchScreen(wanted.isNotEmpty())
        if (wanted.isEmpty() || p == null) return

        // Through an Automix or crossfade, the song heard is the one coming in, on the second
        // player, from wherever the mix started it: the main player is still on the song going
        // out, or (once that ends) on the new one from its start, silent until it takes over.
        // Read from the main player, the new song's lines ran behind by the whole mix so far.
        val mixing = com.opentune.playback.MixState.showing.value
        val videoId = mixing?.item?.mediaId ?: p.currentMediaItem?.mediaId
        val synced = NowPlayingLyrics.syncedFor(videoId)?.takeIf { it.lines.isNotEmpty() }
        // When the line changes next, in real time.
        var lineChangeInMs = Long.MAX_VALUE
        val now = if (videoId == null || synced == null) null else {
            val heardMs = mixing?.positionMs?.invoke()
                ?: com.opentune.playback.PlaybackRequests.soundingPosition?.invoke()
                ?: p.currentPosition
            val positionMs = heardMs + lyricsOffset(AppSettings.lyrics.value.offsets[videoId], synced)
            val moment = synced.lines.momentAt(positionMs)
            if (moment.untilMs != Long.MAX_VALUE) {
                val speed = p.playbackParameters.speed.takeIf { it > 0f } ?: 1f
                lineChangeInMs = ((moment.untilMs - positionMs) / speed).toLong() + PAST_EDGE_MS
                // A mix bends the incoming song's tempo, which the main player's speed doesn't say.
                if (mixing != null) lineChangeInMs = minOf(lineChangeInMs, MIX_WAKE_MS)
            }
            LyricsNow(videoId, moment.current, moment.next)
        }
        val clock = SystemClock.elapsedRealtime()
        // Woken for the next line, or, for an outlet held back, when it may have the latest one.
        var wakeInMs = Long.MAX_VALUE
        for (o in wanted) {
            val need = when (val d = throttles.getValue(o).offer(now?.key, clock)) {
                LineThrottle.Decision.Push -> { showing += o; o.show(context, now); lineChangeInMs }
                LineThrottle.Decision.Same -> { showing += o; lineChangeInMs }
                is LineThrottle.Decision.Wait -> d.ms
            }
            wakeInMs = minOf(wakeInMs, need)
        }
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        if (playing && screenOn && wakeInMs != Long.MAX_VALUE) handler.postDelayed(tick, wakeInMs.coerceAtLeast(MIN_WAKE_MS))
    }

    /** Hears the screen going off and on while anything is shown, to sleep and wake with it. */
    private fun watchScreen(on: Boolean) {
        if (on == screenWatched) return
        screenWatched = on
        if (on) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            ContextCompat.registerReceiver(context, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            runCatching { context.unregisterReceiver(screen) }
        }
    }

    private companion object {
        /** Wake a little after a line's start, so the position read then is past it. */
        const val PAST_EDGE_MS = 30L
        const val MIN_WAKE_MS = 50L
        /** Through a mix, look again at least this often. */
        const val MIX_WAKE_MS = 500L
    }
}
