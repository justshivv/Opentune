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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.opentune.data.lyrics.sungUntilMs
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.playback.ShuffleMode
import com.opentune.playback.next
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
 * Songs smart shuffle mixed in carry a small sparkle.
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
    smartShuffle: Boolean = false,
    /** Apple Music's words for what comes after the queue. */
    appleWords: Boolean = false,
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
    val television = com.opentune.ui.tv.LocalIsTv.current
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
                    Text(if (appleWords && autoplay && !shuffle && !smartShuffle) "AutoPlay" else "Up next",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            shuffle -> "Shuffled · turn shuffle off to reorder"
                            smartShuffle -> "Smart shuffle · similar songs are marked with a sparkle"
                            autoplay && appleWords -> "Similar music will keep playing"
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
                        if (!canDrag || television) Modifier else Modifier.pointerInput(index) {
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
                Column {
                    QueueRow(song, isCurrent = false, isPlaying = false, handle = canDrag && !com.opentune.ui.tv.LocalIsTv.current, onClick = { onPlayIndex(index) }, onRemove = { onRemoveIndex(index) })
                    if (com.opentune.ui.tv.LocalIsTv.current && canDrag) Row {
                        androidx.compose.material3.TextButton(onClick = { onMove(index, order[pos - 1]) }, enabled = pos > 0) { Text("Move up") }
                        androidx.compose.material3.TextButton(onClick = { onMove(index, order[pos + 1]) }, enabled = pos < order.lastIndex) { Text("Move down") }
                    }
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.smartShuffle) {
                    Icon(Icons.Rounded.AutoAwesome, "Added by smart shuffle", Modifier.padding(end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                // Songs queued by hand stay next whatever is started; autoplay's come after everything.
                when (song.queueTier) {
                    com.opentune.data.model.QueueTier.USER ->
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Queued by you", Modifier.padding(end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    com.opentune.data.model.QueueTier.AUTOPLAY ->
                        Icon(Icons.Rounded.AllInclusive, "Added by autoplay", Modifier.padding(end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.opentune.data.model.QueueTier.CONTEXT -> Unit
                }
                Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
fun LyricPreview(
    lyrics: LyricsState, position: () -> Long, onOpen: () -> Unit, modifier: Modifier = Modifier,
    /** Apple Music's look: plain grey words while they're found, no pill. */
    plain: Boolean = false,
) {
    // The position function changes with the lyrics offset; read the latest.
    val pos by rememberUpdatedState(position)
    // While the lyrics are being found, say so where the line will be.
    if (lyrics is LyricsState.Loading) {
        if (plain) Text("Finding the words", modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else SettingUpBadge(modifier)
        return
    }
    val synced = (lyrics as? LyricsState.Found)?.lyrics as? Lyrics.Synced ?: return
    val line by remember(synced) {
        derivedStateOf {
            val p = pos()
            synced.lines.lastOrNull { it.startMs <= p && it.text.isNotBlank() } ?: synced.lines.firstOrNull { it.text.isNotBlank() }
        }
    }
    val current = line ?: return
    // Like the lyrics page: the words light as they're sung (the whole line at
    // once where only lines are timed), and the line dims once it's been sung
    // through an instrumental break before the next.
    val next = remember(synced, current) { synced.lines.getOrNull(synced.lines.indexOf(current) + 1) }
    val resting by remember(current, next) {
        derivedStateOf {
            val p = pos()
            val sungTo = current.sungUntilMs()
            p < current.startMs || (next != null && next.startMs - sungTo >= 5_000 && p > sungTo + 600)
        }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (resting) 0.5f else 1f, label = "previewRest")
    Row(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Each new line flips in: up from below while the last one goes up and out.
        androidx.compose.animation.AnimatedContent(
            current,
            transitionSpec = {
                (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 420f)) { it } + fadeIn(tween(240))) togetherWith
                    (androidx.compose.animation.slideOutVertically(tween(260)) { -it } + fadeOut(tween(200))) using
                    androidx.compose.animation.SizeTransform(clip = true)
            },
            label = "previewLine",
            modifier = Modifier.weight(1f, fill = false),
        ) { l ->
            SungLine(l, pos, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), Modifier.graphicsLayer { this.alpha = alpha })
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open lyrics", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round glass button that lights up while its mode is on. */
@Composable
fun GlassToggle(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val bg by animateColorAsState(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, BUTTON_SETTLE, label = "toggle")
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale = pressScale(press, selected)
    Box(
        Modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape).background(bg).clickable(press, androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(size * 0.5f))
    }
}

/** How a colour or size settles after a tap: quick, with no bounce past the mark. */
private val BUTTON_SETTLE = spring<Color>(dampingRatio = 1f, stiffness = 500f)

/**
 * A button's size under the finger: it gives a little while pressed and
 * springs back with a small overshoot, and a button that turns on gets a
 * brief swell, so taps feel answered rather than only recoloured.
 */
@Composable
private fun pressScale(press: androidx.compose.foundation.interaction.InteractionSource, selected: Boolean): androidx.compose.runtime.State<Float> {
    val pressed by press.collectIsPressedAsState()
    val swell = remember { androidx.compose.animation.core.Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) { first = false; return@LaunchedEffect }
        if (selected) {
            swell.animateTo(1.08f, spring(dampingRatio = 1f, stiffness = 900f))
            swell.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
        }
    }
    val target = if (pressed) 0.88f else 1f
    val pressedScale = animateFloatAsState(target, spring(dampingRatio = if (pressed) 1f else 0.5f, stiffness = if (pressed) 1_200f else 450f), label = "press")
    return remember { derivedStateOf { pressedScale.value * swell.value } }
}

/**
 * One half (or third) of a pill of buttons: the whole segment is the button,
 * so a press, and the lit look when [selected], fill the segment, the pill's
 * own rounding shaping its outer end, rather than a circle round the icon.
 */
@Composable
fun PillButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, width: androidx.compose.ui.unit.Dp = 76.dp, enabled: Boolean = true) {
    val bg by animateColorAsState(if (selected) Color.White.copy(alpha = 0.20f) else Color.Transparent, BUTTON_SETTLE, label = "pillButton")
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale = pressScale(press, selected)
    Box(
        Modifier.fillMaxHeight().width(width).background(bg)
            .clickable(press, androidx.compose.material3.ripple(), enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The segment's fill stays put in the pill; the icon is what gives under the finger.
        Icon(icon, label, Modifier.size(26.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value })
    }
}

/**
 * One pill that morphs between two sets of buttons, [first] and [second]
 * (shown when [second] is true). Its fill stretches or narrows to the new
 * set's width and takes its colour, the buttons are clipped to it as it
 * goes, and the old set fades out as the new one fades in. All of that is
 * read from one value each frame, so the parts can't drift apart, and a tap
 * back mid-way turns it round from where it is, at the speed it had. Only
 * the set that's coming in takes taps: each gets whether it's enabled.
 */
@Composable
fun MorphPill(
    second: Boolean,
    animate: Boolean,
    firstFill: Color,
    secondFill: Color,
    modifier: Modifier = Modifier,
    first: @Composable (enabled: Boolean) -> Unit,
    secondContent: @Composable (enabled: Boolean) -> Unit,
) {
    val morph = remember { androidx.compose.animation.core.Animatable(if (second) 1f else 0f) }
    LaunchedEffect(second, animate) {
        val target = if (second) 1f else 0f
        if (!animate) morph.snapTo(target) else morph.animateTo(target, spring(dampingRatio = 1f, stiffness = 340f))
    }
    var firstWidth by remember { mutableFloatStateOf(0f) }
    var secondWidth by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.height(52.dp).drawWithContent {
            if (firstWidth <= 0f || secondWidth <= 0f) return@drawWithContent
            val p = morph.value
            val w = androidx.compose.ui.util.lerp(firstWidth, secondWidth, p)
            val left = (size.width - w) / 2f
            val path = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(left, 0f, left + w, size.height, androidx.compose.ui.geometry.CornerRadius(size.height / 2f)))
            }
            drawPath(path, androidx.compose.ui.graphics.lerp(firstFill, secondFill, p))
            clipPath(path) { this@drawWithContent.drawContent() }
        },
        contentAlignment = Alignment.Center,
    ) {
        // The coming-in set is on top, so it's the one under the finger.
        Box(
            Modifier.zIndex(if (second) 0f else 1f)
                .onSizeChanged { firstWidth = it.width.toFloat() }
                .graphicsLayer { alpha = morphShown(1f - morph.value) },
        ) { first(!second) }
        Box(
            Modifier.zIndex(if (second) 1f else 0f)
                .onSizeChanged { secondWidth = it.width.toFloat() }
                .graphicsLayer { alpha = morphShown(morph.value) },
        ) { secondContent(second) }
    }
}

/**
 * How visible a set is at [p] of the way to it: nothing until 42%, then an
 * ease in to fully there by 95%. The old set is all but gone by the middle
 * when the new one starts, so their icons never sit on top of each other.
 */
internal fun morphShown(p: Float): Float = ((p - 0.42f) / 0.53f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/**
 * Shuffle, repeat and autoplay in one glass pill, split by hairlines. Each
 * has a feel of its own under the finger (see [Haptics.Pattern]) and a small
 * motion of its own as it changes.
 */
@Composable
fun ModesPill(
    shuffle: ShuffleMode,
    smartShuffleAvailable: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    /** False where the caller draws the pill's fill itself (a fill that morphs between pills). */
    filled: Boolean = true,
    /** False while it's fading out of a morphing pill, so taps go to what's coming in. */
    enabled: Boolean = true,
) {
    val autoplay = AppSettings.playback.collectAsState().value.autoplay
    val haptics = com.opentune.ui.components.rememberHaptics()
    val context = LocalContext.current
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.height(52.dp).clip(shape).then(if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Like repeat beside it, shuffle has a third state for a queue from a
        // playlist or album: off, shuffle, smart shuffle, then off again.
        PillSegment(
            Icons.Rounded.Shuffle,
            when (shuffle) {
                ShuffleMode.OFF -> "Shuffle off"
                ShuffleMode.ON -> "Shuffle on"
                ShuffleMode.SMART -> "Smart shuffle"
            },
            shuffle != ShuffleMode.OFF,
            SegmentMotion.WIGGLE,
            badge = if (shuffle == ShuffleMode.SMART) Icons.Rounded.AutoAwesome else null,
            enabled = enabled,
        ) {
            val next = shuffle.next(smartShuffleAvailable)
            haptics.pattern(if (next == ShuffleMode.OFF) Haptics.Pattern.OFF else Haptics.Pattern.SHUFFLE)
            // The new mode in a word, so the third state is clear.
            when {
                next == ShuffleMode.SMART -> "Smart shuffle: similar songs mixed in"
                // Once a run is enough to learn there's a third state.
                next == ShuffleMode.ON && smartShuffleAvailable && !smartHintShown -> { smartHintShown = true; "Shuffle on. Tap again for smart shuffle" }
                shuffle == ShuffleMode.SMART -> "Shuffle off. Added songs removed"
                else -> null
            }?.let { com.opentune.data.Pills.info(it, context) }
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
            enabled = enabled,
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
        PillSegment(Icons.Rounded.AllInclusive, "AutoPlay", autoplay, SegmentMotion.PULSE, enabled = enabled) {
            haptics.pattern(if (autoplay) Haptics.Pattern.OFF else Haptics.Pattern.AUTOPLAY)
            AppSettings.setAutoplay(!autoplay)
        }
    }
}

private enum class SegmentMotion { WIGGLE, SPIN, PULSE }

/** Whether the shuffle button has said, this run, that a second tap gives smart shuffle. */
private var smartHintShown = false

@Composable
private fun PillSegment(icon: ImageVector, label: String, active: Boolean, motion: SegmentMotion, badge: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
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
        Modifier.fillMaxHeight().width(60.dp).background(bg).clickable(enabled = enabled) { taps++; onClick() },
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
        // A small mark on the icon's corner for a mode's extra state.
        if (badge != null) {
            Icon(badge, null, tint = fg, modifier = Modifier.align(Alignment.Center).padding(start = 22.dp, bottom = 20.dp).size(13.dp))
        }
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

/** Which kind of output the sound is going to, for the player's output button and the pipeline page. */
enum class OutputRoute { PHONE, HEADPHONES, BLUETOOTH, USB, CAST }

/** True for anything you'd put over your ears: the button shows a headphone icon for these. */
val OutputRoute.isHeadset: Boolean get() = this == OutputRoute.HEADPHONES || this == OutputRoute.BLUETOOTH || this == OutputRoute.USB

/** The live output route, following device changes and casting. */
@Composable
fun rememberOutputRoute(): OutputRoute {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    var route by remember { mutableStateOf(outputRoute(am)) }
    DisposableEffect(am) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { route = outputRoute(am) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { route = outputRoute(am) }
        }
        am?.registerAudioDeviceCallback(cb, null)
        onDispose { am?.unregisterAudioDeviceCallback(cb) }
    }
    val cast by com.opentune.cast.Cast.session.collectAsState()
    return if (cast != null) OutputRoute.CAST else route
}

private fun outputRoute(am: AudioManager?): OutputRoute {
    val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
    fun has(vararg types: Int) = devices.any { it.type in types }
    val bt = buildList {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
    }.toIntArray()
    return when {
        has(*bt) -> OutputRoute.BLUETOOTH
        has(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE) -> OutputRoute.USB
        has(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET) -> OutputRoute.HEADPHONES
        else -> OutputRoute.PHONE
    }
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

