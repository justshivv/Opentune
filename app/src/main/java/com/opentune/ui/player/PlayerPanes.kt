package com.opentune.ui.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.Haptics
import com.opentune.playback.SleepTimer
import com.opentune.ui.LyricsState
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.NowPlayingBars
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.glass
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val QUEUE_ROW = 64.dp

/**
 * The queue inside the player: now playing, then what's next. Rows drag by
 * their handle (or a long press) to reorder; the move is sent once, on drop.
 * With shuffle on, rows don't drag: the play order isn't the list order.
 */
@Composable
fun QueuePane(
    queue: List<Song>,
    currentIndex: Int,
    upNext: List<Int>,
    isPlaying: Boolean,
    shuffle: Boolean,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoplay = AppSettings.playback.collectAsState().value.autoplay
    val haptics = com.opentune.ui.components.rememberHaptics()
    val rowPx = with(LocalDensity.current) { QUEUE_ROW.toPx() }
    // A local copy of the upcoming order, so a drag can move rows before the player hears of it.
    val order = remember { mutableStateListOf<Int>() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragStartPos by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Drag gestures outlive the list they started with (a row keeps its key
    // when the song ends and the queue moves on), so they read the latest.
    val currentUpNext by rememberUpdatedState(upNext)
    val currentOnMove by rememberUpdatedState(onMove)
    LaunchedEffect(upNext) {
        if (dragging == null) {
            order.clear()
            order.addAll(upNext)
        }
    }
    val list = rememberLazyListState()
    val canDrag = !shuffle

    LazyColumn(modifier, state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
        queue.getOrNull(currentIndex)?.let { current ->
            item(key = "now-label") { PaneLabel("Now playing") }
            item(key = "now") {
                QueueRow(current, isCurrent = true, isPlaying = isPlaying, handle = false, onClick = {}, onRemove = null)
            }
        }
        item(key = "autoplay") {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AllInclusive, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Up next", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            shuffle -> "Shuffled · turn shuffle off to reorder"
                            autoplay -> "Similar music keeps playing after these"
                            else -> "Playback stops after these"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(order, key = { _, index -> "q$index:${queue.getOrNull(index)?.videoId}" }) { pos, index ->
            val song = queue.getOrNull(index) ?: return@itemsIndexed
            val isDragged = dragging == index
            val lift by animateFloatAsState(if (isDragged) 1.03f else 1f, label = "lift")
            Box(
                Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragged) dragOffset else 0f
                        scaleX = lift
                        scaleY = lift
                    }
                    .then(if (isDragged) Modifier else Modifier.animateItem())
                    .then(
                        if (!canDrag) Modifier else Modifier.pointerInput(index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.press()
                                    dragging = index
                                    dragStartPos = order.indexOf(index)
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    val from = dragStartPos
                                    val to = order.indexOf(index)
                                    if (from >= 0 && to >= 0 && from != to) currentOnMove(currentUpNext[from], currentUpNext[to])
                                    dragging = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    order.clear(); order.addAll(currentUpNext)
                                    dragging = null
                                    dragOffset = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val at = order.indexOf(index)
                                val steps = (dragOffset / rowPx).roundToInt()
                                val target = (at + steps).coerceIn(0, order.lastIndex)
                                if (target != at) {
                                    order.add(target, order.removeAt(at))
                                    dragOffset -= (target - at) * rowPx
                                    haptics.tick()
                                }
                            }
                        },
                    ),
            ) {
                QueueRow(song, isCurrent = false, isPlaying = false, handle = canDrag, onClick = { onPlayIndex(index) }, onRemove = { onRemoveIndex(index) })
            }
            if (pos == order.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PaneLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
}

@Composable
private fun QueueRow(song: Song, isCurrent: Boolean, isPlaying: Boolean, handle: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(QUEUE_ROW).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle) Icon(Icons.Rounded.DragHandle, "Drag to reorder", Modifier.padding(end = 10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(50.dp)) {
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.fillMaxHeight().width(50.dp), RoundedCornerShape(8.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isCurrent) NowPlayingBars(isPlaying, MaterialTheme.colorScheme.onSurface, Modifier.padding(horizontal = 12.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove from queue", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The line being sung right now, under the title; tap to open the lyrics. */
@Composable
fun LyricPreview(lyrics: LyricsState, position: () -> Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    // The position function changes with the lyrics offset; read the latest.
    val pos by rememberUpdatedState(position)
    val synced = (lyrics as? LyricsState.Found)?.lyrics as? Lyrics.Synced ?: return
    val line by remember(synced) {
        derivedStateOf {
            val p = pos()
            synced.lines.lastOrNull { it.startMs <= p && it.text.isNotBlank() }?.text ?: synced.lines.firstOrNull { it.text.isNotBlank() }?.text
        }
    }
    val text = line ?: return
    Row(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open lyrics", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round glass button that lights up while its mode is on. */
@Composable
fun GlassToggle(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val bg by animateColorAsState(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, label = "toggle")
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(size * 0.5f))
    }
}

/**
 * Shuffle, repeat and autoplay in one glass pill, split by hairlines. Each
 * has a feel of its own under the finger (see [Haptics.Pattern]) and a small
 * motion of its own as it changes.
 */
@Composable
fun ModesPill(shuffle: Boolean, repeatMode: Int, onShuffle: () -> Unit, onRepeat: () -> Unit) {
    val autoplay = AppSettings.playback.collectAsState().value.autoplay
    val haptics = com.opentune.ui.components.rememberHaptics()
    val shape = RoundedCornerShape(30.dp)
    Row(Modifier.height(52.dp).glass(shape, Color.White.copy(alpha = 0.10f)), verticalAlignment = Alignment.CenterVertically) {
        PillSegment(Icons.Rounded.Shuffle, "Shuffle", shuffle, SegmentMotion.WIGGLE) {
            haptics.pattern(if (shuffle) Haptics.Pattern.OFF else Haptics.Pattern.SHUFFLE)
            onShuffle()
        }
        VerticalDivider(Modifier.height(30.dp), color = Color.White.copy(alpha = 0.18f))
        PillSegment(
            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat one"
                Player.REPEAT_MODE_ALL -> "Repeat all"
                else -> "Repeat off"
            },
            repeatMode != Player.REPEAT_MODE_OFF,
            SegmentMotion.SPIN,
        ) {
            // Off, then all, then one, then off again.
            haptics.pattern(
                when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> Haptics.Pattern.REPEAT_ALL
                    Player.REPEAT_MODE_ALL -> Haptics.Pattern.REPEAT_ONE
                    else -> Haptics.Pattern.OFF
                },
            )
            onRepeat()
        }
        VerticalDivider(Modifier.height(30.dp), color = Color.White.copy(alpha = 0.18f))
        PillSegment(Icons.Rounded.AllInclusive, "AutoPlay", autoplay, SegmentMotion.PULSE) {
            haptics.pattern(if (autoplay) Haptics.Pattern.OFF else Haptics.Pattern.AUTOPLAY)
            AppSettings.setAutoplay(!autoplay)
        }
    }
}

private enum class SegmentMotion { WIGGLE, SPIN, PULSE }

@Composable
private fun PillSegment(icon: ImageVector, label: String, active: Boolean, motion: SegmentMotion, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Color.White.copy(alpha = 0.20f) else Color.Transparent, label = "seg")
    val fg by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "segFg")
    val reduce = AppSettings.ui.collectAsState().value.reduceAnimation
    // Each change plays the segment's motion once: 0 at rest, through to 1 and back.
    val kick = remember { androidx.compose.animation.core.Animatable(0f) }
    var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        if (taps == 0 || reduce) return@LaunchedEffect
        kick.snapTo(0f)
        kick.animateTo(1f, androidx.compose.animation.core.tween(if (motion == SegmentMotion.SPIN) 520 else 420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        kick.snapTo(0f)
    }
    Box(
        Modifier.fillMaxHeight().width(60.dp).background(bg).clickable { taps++; onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label, tint = fg,
            modifier = Modifier.size(24.dp).graphicsLayer {
                val k = kick.value
                val wave = kotlin.math.sin(Math.PI.toFloat() * k)
                when (motion) {
                    // A quick side-to-side shake, the arrows crossing.
                    SegmentMotion.WIGGLE -> { rotationZ = kotlin.math.sin(k * 3f * 2f * Math.PI.toFloat()) * 14f * (1f - k); translationX = wave * 2.dp.toPx() }
                    // Once round, like the loop it is.
                    SegmentMotion.SPIN -> { rotationZ = 360f * k; val sc = 1f - 0.15f * wave; scaleX = sc; scaleY = sc }
                    // A heartbeat swell.
                    SegmentMotion.PULSE -> { val sc = 1f + 0.28f * wave; scaleX = sc; scaleY = sc }
                }
            },
        )
    }
}

/** Where the sound is going: a Bluetooth or USB device's name, headphones, or this phone's name. */
@Composable
fun rememberOutputDeviceName(): String {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    var name by remember { mutableStateOf(outputName(context, am)) }
    DisposableEffect(am) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { name = outputName(context, am) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { name = outputName(context, am) }
        }
        am?.registerAudioDeviceCallback(cb, null)
        onDispose { am?.unregisterAudioDeviceCallback(cb) }
    }
    val cast by com.opentune.cast.Cast.session.collectAsState()
    return cast?.let { if (it.receiver == null) "Connecting to ${it.target.name}…" else "Playing on ${it.target.name}" } ?: name
}

private fun outputName(context: Context, am: AudioManager?): String {
    val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
    fun named(vararg types: Int) = devices.firstOrNull { it.type in types }
    val bt = buildList {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
    }.toIntArray()
    named(*bt)?.let { return it.productName?.toString()?.takeIf(String::isNotBlank) ?: "Bluetooth" }
    named(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.let { return it.productName?.toString()?.takeIf(String::isNotBlank) ?: "USB audio" }
    named(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let { return "Headphones" }
    return runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "This phone"
}

/** What the sleep timer shows: off, minutes left, or "end of song". */
@Composable
fun sleepTimerLabel(): String? {
    val state by SleepTimer.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is SleepTimer.State.At) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return when (val s = state) {
        SleepTimer.State.Off -> null
        SleepTimer.State.EndOfTrack -> "Stops at end of song"
        is SleepTimer.State.At -> {
            val left = ((s.endsAtMs - now) / 1000).coerceAtLeast(0)
            "Stops in %d:%02d".format(left / 60, left % 60)
        }
    }
}

