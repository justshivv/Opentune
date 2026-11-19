package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FocusRing
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The queue beside the full player's cover, as the reference lays it out:
 * a "Queue" heading, the song playing under "Now playing" with a moving
 * wave and a cross that skips it out of the queue, AutoPlay (similar music
 * after the last song), then every song still to come in a 70dp row with no
 * card: a handle to drag it up or down, its cover and names, and a cross to
 * take it out. A row fills faintly under the pointer and plays on a click;
 * the list fades out softly at its top and foot.
 */
@Composable
internal fun PlayerQueuePanel(queue: List<Song>, index: Int, modifier: Modifier = Modifier) {
    val playback by AppSettings.playback.collectAsState()
    val playing by Player.playing.collectAsState()
    val current = queue.getOrNull(index)
    val upcoming = remember(queue, index) { (index + 1..queue.lastIndex).toList() }
    // Keys that stay with a song as it moves, and tell apart one song queued twice.
    val keys = remember(queue) {
        val seen = HashMap<String, Int>()
        queue.map { s -> val n = seen.merge(s.videoId, 1, Int::plus)!!; "${s.videoId}#$n" }
    }
    val rowPx = with(LocalDensity.current) { PLAYER_QUEUE_ROW.toPx() }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragBy by remember { mutableFloatStateOf(0f) }
    // Where the dropped row was left against where it lands, eased away after the drop.
    var settleKey by remember { mutableStateOf<String?>(null) }
    val settle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val target = if (dragFrom < 0) -1 else (dragFrom + (dragBy / rowPx).roundToInt()).coerceIn(index + 1, queue.lastIndex)
    LazyColumn(
        modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(PLAYER_QUEUE_FADE, blendMode = BlendMode.DstIn)
            },
        contentPadding = PaddingValues(top = 12.dp, bottom = 72.dp),
    ) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", color = Color.White, style = playerQueueType(26, 32, FontWeight.Bold), modifier = Modifier.weight(1f))
                if (upcoming.isNotEmpty()) PlayerTextButton("Clear") { Player.clearUpcoming(); Notice.show("Queue cleared") }
            }
        }
        if (current != null) {
            item(key = "now label") { PlayerQueueLabel("Now playing") }
            item(key = "now") {
                PlayerQueueRow(
                    current, onClick = null, handle = false,
                    trailing = {
                        PlayerWave(playing, Modifier.padding(end = 6.dp))
                        if (index < queue.lastIndex) PlayerRowCross("Skip ${current.title}") {
                            val at = Player.index.value
                            Player.next()
                            if (Player.index.value != at) Player.removeAt(at)
                        }
                    },
                )
            }
        }
        item(key = "autoplay") {
            PlayerAutoPlayRow(playback.autoplay) { AppSettings.setAutoplay(!playback.autoplay) }
        }
        if (upcoming.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (playback.autoplay) "Nothing queued after this song. AutoPlay will keep the music going." else "Nothing queued after this song.",
                    color = Color.White.copy(alpha = 0.55f), style = playerQueueType(15, 22),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp), textAlign = TextAlign.Start,
                )
            }
        } else {
            item(key = "next label") { PlayerQueueLabel("Next up") }
            items(upcoming, key = { keys[it] }) { i ->
                val lifted = dragFrom == i
                val shift = when {
                    dragFrom < 0 || lifted -> 0f
                    i in (dragFrom + 1)..target -> -rowPx
                    i in target until dragFrom -> rowPx
                    else -> 0f
                }
                val shown by animateFloatAsState(shift, if (dragFrom < 0) snap() else tween(160, easing = Motion.Settle), label = "queue make room")
                val key = keys[i]
                PlayerQueueRow(
                    queue[i],
                    onClick = { Player.playAt(i) },
                    handle = true,
                    lifted = lifted,
                    queueIndex = i,
                    modifier = Modifier.zIndex(if (lifted) 1f else 0f).graphicsLayer {
                        translationY = when {
                            lifted -> dragBy
                            settleKey == key -> settle.value
                            else -> shown
                        }
                        if (lifted) { shadowElevation = 18f; shape = RoundedCornerShape(8.dp); clip = true }
                    },
                    handleModifier = Modifier.pointerInput(i, queue.size) {
                        detectDragGestures(
                            onDragStart = { dragFrom = i; dragBy = 0f },
                            onDragEnd = {
                                val to = (i + (dragBy / rowPx).roundToInt()).coerceIn(index + 1, queue.lastIndex)
                                val left = dragBy - (to - i) * rowPx
                                dragFrom = -1; dragBy = 0f
                                if (to != i) Player.move(i, to)
                                settleKey = key
                                scope.launch { settle.snapTo(left); settle.animateTo(0f, tween(180, easing = Motion.Settle)); settleKey = null }
                            },
                            onDragCancel = { dragFrom = -1; dragBy = 0f },
                        ) { change, amount -> change.consume(); dragBy += amount.y }
                    },
                    trailing = { PlayerRowCross("Remove ${queue[i].title}") { Player.removeAt(i) } },
                )
            }
        }
    }
}

/** Each row's height in the full player's queue. */
private val PLAYER_QUEUE_ROW = 70.dp

/** Soft at the top and foot, so rows fade into the edges rather than being cut there. */
private val PLAYER_QUEUE_FADE = Brush.verticalGradient(0f to Color.Transparent, 0.04f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent)

@Composable
private fun playerQueueType(size: Int, line: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

@Composable
private fun PlayerQueueLabel(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.55f), style = playerQueueType(20, 26, FontWeight.Medium),
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** A row of the queue: a drag handle where the song can move, the cover, the names, and what's at its end. */
@Composable
private fun PlayerQueueRow(
    song: Song,
    onClick: (() -> Unit)?,
    handle: Boolean,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
    queueIndex: Int? = null,
    handleModifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val fill by animateColorAsState(
        Color.White.copy(alpha = if (lifted) 0.14f else if (hovered && onClick != null) 0.06f else 0f),
        if (hovered) snap() else tween(160), label = "queue row fill",
    )
    val row: @Composable () -> Unit = {
        Row(
            modifier.fillMaxWidth().height(PLAYER_QUEUE_ROW).clip(shape).background(fill).hoverable(hover)
                .then(
                    if (onClick != null) Modifier.clickable(interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClickLabel = "Play", onClick = onClick)
                    else Modifier,
                )
                .padding(start = if (handle) 0.dp else 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (handle) {
                Box(
                    handleModifier.size(40.dp, PLAYER_QUEUE_ROW).pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.DragHandle, "Drag to move", tint = Color.White.copy(alpha = if (hovered || lifted) 0.8f else 0.45f), modifier = Modifier.size(22.dp)) }
            }
            Artwork(song.thumbnailUrl, Modifier.size(56.dp), RoundedCornerShape(4.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(song.title, color = Color.White, style = playerQueueType(18, 24, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Color.White.copy(alpha = 0.6f), style = playerQueueType(16, 22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
    if (queueIndex != null) SongContextMenu(song, queueIndex = queueIndex) { row() } else row()
}

/** AutoPlay: similar music once the queue runs out, with its switch. */
@Composable
private fun PlayerAutoPlayRow(on: Boolean, onToggle: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val fill by animateColorAsState(Color.White.copy(alpha = if (hovered) 0.06f else 0f), tween(160), label = "autoplay fill")
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(PLAYER_QUEUE_ROW).clip(shape).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(shape), role = Role.Switch, onClick = onToggle)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AllInclusive, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("AutoPlay", color = Color.White, style = playerQueueType(18, 24, FontWeight.Medium), maxLines = 1)
            Text("Similar music, selected to play next", color = Color.White.copy(alpha = 0.6f), style = playerQueueType(16, 22), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(
            on, { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Color.White, checkedBorderColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f), uncheckedTrackColor = Color.White.copy(alpha = 0.12f), uncheckedBorderColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

/** A cross at a row's end, grey, whiter on a soft circle under the pointer. */
@Composable
private fun PlayerRowCross(label: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(Color.White.copy(alpha = if (hovered) 0.12f else 0f), tween(140), label = "cross fill")
    Box(
        Modifier.size(40.dp).pressGive(hover).clip(CircleShape).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(CircleShape), role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Close, label, tint = Color.White.copy(alpha = if (hovered) 1f else 0.6f), modifier = Modifier.size(20.dp)) }
}

/** Grey text that acts, brighter under the pointer (Clear). */
@Composable
internal fun PlayerTextButton(text: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Text(
        text, color = Color.White.copy(alpha = if (hovered) 1f else 0.6f), style = playerQueueType(15, 20, FontWeight.Medium),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(RoundedCornerShape(6.dp)), role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Four white bars rising and falling while the song plays, still and low while it's paused. */
@Composable
internal fun PlayerWave(playing: Boolean, modifier: Modifier = Modifier) {
    val t = if (playing) rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "wave t") else null
    val low by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "wave still")
    Canvas(modifier.size(20.dp, 18.dp)) {
        val bars = 4
        val w = 2.6.dp.toPx()
        val gap = (size.width - bars * w) / (bars - 1)
        val phase = t?.value ?: 0f
        for (i in 0 until bars) {
            val wave = 0.5f + 0.5f * kotlin.math.sin((phase + i * 0.27f) * 2 * Math.PI.toFloat() * (1f + i * 0.15f))
            val h = size.height * (0.4f + 0.6f * wave * low)
            val x = i * (w + gap) + w / 2
            drawLine(Color.White, Offset(x, size.height - w / 2), Offset(x, size.height - h + w / 2), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}
