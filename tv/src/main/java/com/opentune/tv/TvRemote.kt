package com.opentune.tv

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.opentune.data.remote.PairedPhone
import com.opentune.data.remote.PairedPhones
import com.opentune.data.remote.RemoteHost
import com.opentune.data.remote.RemoteInvite
import com.opentune.data.remote.RemoteKey
import com.opentune.data.remote.RemoteMessage
import com.opentune.data.remote.TvAdvertiser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cursor a phone's trackpad moves on the TV's screen. It is drawn by OpenTune over its own
 * pages (see [TvCursor]), and what it clicks is OpenTune's own: this is not a pointer for the
 * whole TV, only for this app.
 */
class TvPointer {
    var x by mutableFloatStateOf(0f); private set
    var y by mutableFloatStateOf(0f); private set
    var visible by mutableStateOf(false); private set
    /** When the cursor last moved or clicked, for fading it out. */
    var lastActive by mutableLongStateOf(0L); private set
    private var placed = false

    fun moveBy(dx: Float, dy: Float, width: Int, height: Int) {
        if (!placed) { x = width / 2f; y = height / 2f; placed = true }
        x = (x + dx).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        y = (y + dy).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        wake()
    }

    fun show(width: Int, height: Int) {
        if (!placed) { x = width / 2f; y = height / 2f; placed = true }
        wake()
    }

    fun hide() { visible = false }
    fun wake() { visible = true; lastActive = SystemClock.uptimeMillis() }
}

/** The arrow, drawn at the pointer; it fades after a few seconds still and comes back when it moves. */
@Composable
fun TvCursor(pointer: TvPointer) {
    // Remembered: a new state object on every recomposition was one the timer below never wrote.
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(pointer.lastActive) {
        while (true) { now = SystemClock.uptimeMillis(); delay(250); if (now - pointer.lastActive > CURSOR_FADE_START_MS + 600) break }
    }
    val faded = pointer.visible && now - pointer.lastActive > CURSOR_FADE_START_MS
    val alpha by animateFloatAsState(if (pointer.visible && !faded) 1f else 0f, tween(450), label = "cursorAlpha")
    if (alpha <= 0.01f) return
    Canvas(Modifier.fillMaxSize().alpha(alpha)) {
        val s = 30.dp.toPx()
        val at = Offset(pointer.x, pointer.y)
        val arrow = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x, at.y + s * 0.82f)
            lineTo(at.x + s * 0.22f, at.y + s * 0.62f)
            lineTo(at.x + s * 0.40f, at.y + s)
            lineTo(at.x + s * 0.56f, at.y + s * 0.92f)
            lineTo(at.x + s * 0.38f, at.y + s * 0.56f)
            lineTo(at.x + s * 0.66f, at.y + s * 0.56f)
            close()
        }
        drawPath(arrow, Color.White)
        drawPath(arrow, Color.Black, style = Stroke(width = 2.dp.toPx()))
    }
}

private const val CURSOR_FADE_START_MS = 4_000L

/**
 * What a paired phone's messages do on the TV. It turns the phone's trackpad into mouse events
 * sent into OpenTune's own window (hover, click, drag and scroll), its buttons into the keys a
 * remote sends, and its media buttons into the player. Everything runs on the main thread.
 */
class TvRemoteInput(
    private val activity: Activity,
    private val pointer: TvPointer,
    private val nav: TvNav,
) {
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var downTime = 0L
    private var held = false

    private val width get() = activity.window.decorView.width
    private val height get() = activity.window.decorView.height
    private val density get() = activity.resources.displayMetrics.density

    fun handle(message: RemoteMessage) {
        when (message) {
            is RemoteMessage.Move -> move(message.dx, message.dy)
            is RemoteMessage.Click -> { click(); if (message.double) click() }
            is RemoteMessage.Press -> press(message.down)
            is RemoteMessage.Scroll -> scroll(message.dx, message.dy)
            is RemoteMessage.Key -> key(message.key, message.repeat)
            is RemoteMessage.Text -> text(message.text)
            is RemoteMessage.Cursor -> if (message.shown) pointer.show(width, height) else { release(); pointer.hide() }
            else -> Unit
        }
    }

    /** What is playing, for the phone to show. */
    fun now(): RemoteMessage.Now {
        val song = nav.player.currentSong.value
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return RemoteMessage.Now(
            title = song?.title, artist = song?.artist, artworkUrl = song?.thumbnailUrl,
            playing = nav.player.isPlaying.value,
            positionMs = nav.player.position.value, durationMs = nav.player.durationMs.value,
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max,
        )
    }

    // ---- The cursor ----

    private fun move(dx: Float, dy: Float) {
        pointer.moveBy(dx * density * POINTER_GAIN, dy * density * POINTER_GAIN, width, height)
        if (held) send(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, touch = true)
        else send(MotionEvent.ACTION_HOVER_MOVE, 0, touch = false)
    }

    private fun click() {
        pointer.wake()
        send(MotionEvent.ACTION_HOVER_MOVE, 0, touch = false)
        press(true)
        press(false)
    }

    private fun press(down: Boolean) {
        pointer.wake()
        if (down == held) return
        held = down
        if (down) downTime = SystemClock.uptimeMillis()
        send(if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, if (down) MotionEvent.BUTTON_PRIMARY else 0, touch = true)
        if (!down) send(MotionEvent.ACTION_HOVER_MOVE, 0, touch = false)
    }

    /** Lets go if the phone went away or switched mode with the button down. */
    fun release() { if (held) press(false) }

    private fun scroll(dx: Float, dy: Float) {
        pointer.wake()
        // A real mouse is always hovering before its wheel turns; the page under it learns where the pointer is from that.
        send(MotionEvent.ACTION_HOVER_MOVE, 0, touch = false)
        val e = event(MotionEvent.ACTION_SCROLL, 0) { c ->
            // A wheel notch is a fixed run of the trackpad. Android's wheel counts forward (up) as
            // positive, so fingers moving down is a positive dy and the page follows them.
            c.setAxisValue(MotionEvent.AXIS_VSCROLL, dy / SCROLL_PER_NOTCH)
            c.setAxisValue(MotionEvent.AXIS_HSCROLL, -dx / SCROLL_PER_NOTCH)
        }
        activity.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    private fun send(action: Int, buttons: Int, touch: Boolean) {
        val e = event(action, buttons) { }
        if (touch) activity.dispatchTouchEvent(e) else activity.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    private fun event(action: Int, buttons: Int, axes: (MotionEvent.PointerCoords) -> Unit): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            x = pointer.x; y = pointer.y; pressure = if (buttons != 0) 1f else 0f; size = 1f
            axes(this)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            if (downTime == 0L) now else downTime, now, action, 1, arrayOf(props), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    // ---- The buttons ----

    private fun key(key: RemoteKey, repeat: Boolean) {
        when (key) {
            RemoteKey.UP -> dpad(KeyEvent.KEYCODE_DPAD_UP, repeat)
            RemoteKey.DOWN -> dpad(KeyEvent.KEYCODE_DPAD_DOWN, repeat)
            RemoteKey.LEFT -> dpad(KeyEvent.KEYCODE_DPAD_LEFT, repeat)
            RemoteKey.RIGHT -> dpad(KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            RemoteKey.OK -> dpad(KeyEvent.KEYCODE_DPAD_CENTER, repeat)
            RemoteKey.BACK -> back()
            RemoteKey.HOME -> { pointer.hide(); nav.goTab(Dest.Home) }
            RemoteKey.PLAY_PAUSE -> nav.player.togglePlayPause()
            RemoteKey.NEXT -> nav.player.skipNext()
            RemoteKey.PREVIOUS -> nav.player.skipPrevious()
            RemoteKey.FORWARD -> nav.player.seekBy(SEEK_STEP_MS)
            RemoteKey.REWIND -> nav.player.seekBy(-SEEK_STEP_MS)
            RemoteKey.VOLUME_UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            RemoteKey.VOLUME_DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            RemoteKey.MUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
    }

    /** The key as if pressed on the TV's own remote, which also puts the focus ring back and the cursor away. */
    private fun dpad(code: Int, repeat: Boolean) {
        pointer.hide()
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, if (repeat) 1 else 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    // Back goes up a page; from Home it would leave the app, which a phone's button shouldn't do.
    private fun back() {
        val dispatcher = (activity as? androidx.activity.OnBackPressedDispatcherOwner)?.onBackPressedDispatcher ?: return
        if (dispatcher.hasEnabledCallbacks()) dispatcher.onBackPressed()
    }

    private fun text(typed: String) {
        val query = typed.trim().take(200)
        if (query.isEmpty()) return
        pointer.hide()
        nav.goTab(Dest.Search)
        nav.pendingSearch = query
    }

    private companion object {
        /** TV dp the cursor moves for each dp a finger moves on the phone, before the phone's own speed setting. */
        const val POINTER_GAIN = 3f
        const val SCROLL_PER_NOTCH = 70f
        const val SEEK_STEP_MS = 10_000L
    }
}

/**
 * The TV side of the phone remote, kept by the activity: it listens for paired phones, tells
 * them it is there, and hands what they send to whichever [TvRemoteInput] is on screen.
 */
class TvRemote(private val context: Context, val phones: PairedPhones) {
    val pointer = TvPointer()
    private val main = Handler(Looper.getMainLooper())
    private val advertiser = TvAdvertiser(context)
    private var host: RemoteHost? = null
    @Volatile private var input: TvRemoteInput? = null
    private val _controllers = MutableStateFlow<List<String>>(emptyList())
    /** Names of the phones connected now. */
    val controllers: StateFlow<List<String>> = _controllers.asStateFlow()

    /** What this TV is called to a phone: what the owner named it in the TV's settings, else its model. */
    val name: String = (Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL)
        .let { "OpenTune on $it" }.take(60)

    fun start() {
        if (host != null) return
        host = RemoteHost(
            phones, { name },
            onMessage = { m -> main.post { deliver(m) } },
            onControllers = { _controllers.value = it; if (it.isEmpty()) main.post { input?.release() } },
        ).also { advertiser.start(name, it.port, phones.tvId) }
    }

    fun stop() {
        advertiser.stop()
        host?.close()
        host = null
        _controllers.value = emptyList()
    }

    /** Hands a phone's message to the page on screen; on the main thread. */
    fun deliver(message: RemoteMessage) { input?.handle(message) }

    fun attach(i: TvRemoteInput) { input = i }
    fun detach(i: TvRemoteInput) { if (input === i) input = null }

    /** A QR code's worth of invitation for a new phone. */
    fun invite(): RemoteInvite = host?.createInvite() ?: error("The remote isn't running.")
    fun cancelInvite() { host?.cancelInvite() }

    fun push(now: RemoteMessage.Now) { host?.push(now) }

    /** Takes a phone's access away and cuts it off. */
    fun forget(phone: PairedPhone) {
        phones.remove(phone.id)
        host?.disconnect(phone.id)
    }
}
