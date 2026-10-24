package com.opentune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** What a sideways swipe on a song row asks for. */
enum class SwipeAction { PLAY_NEXT, ADD_TO_QUEUE, REMOVE }

/**
 * The arithmetic of swiping a song row, apart from Compose so it can be
 * tested. Offsets are in pixels, positive to the right, as drawn.
 */
internal object SwipeQueue {
    /** How far the row has to go, as a share of its width, before letting go acts. */
    const val THRESHOLD = 0.35f
    /** The row never goes further than this share of its width, however far the finger does. */
    const val MAX_TRAVEL = 0.6f
    /** How much of the finger's travel the row takes up to the threshold: a little resistance. */
    const val FOLLOW = 0.85f
    /** Once armed, the row has to come back under this share of the threshold to disarm, so a finger resting on the line doesn't buzz twice. */
    const val DISARM = 0.9f
    /** A flick short of the threshold still acts once the row has gone this share of its width... */
    const val FLING_MIN_TRAVEL = 0.12f
    /** ...and was moving at least this fast, in dp a second. */
    const val FLING_DP_PER_S = 900f

    /**
     * Toward the end of the line (right in English) is Play next, toward its
     * start is Add to queue; right-to-left layouts mirror it.
     */
    fun actionFor(offset: Float, rtl: Boolean): SwipeAction? {
        val forward = if (rtl) -offset else offset
        return when {
            forward > 0f -> SwipeAction.PLAY_NEXT
            forward < 0f -> SwipeAction.ADD_TO_QUEUE
            else -> null
        }
    }

    /** [drag] with the directions that have nothing to do held at zero. */
    fun allowed(drag: Float, rtl: Boolean, canPlayNext: Boolean, canAddToQueue: Boolean): Float =
        when (actionFor(drag, rtl)) {
            SwipeAction.PLAY_NEXT -> if (canPlayNext) drag else 0f
            SwipeAction.ADD_TO_QUEUE -> if (canAddToQueue) drag else 0f
            // Never a side of its own: [actionFor] names only the two sides.
            SwipeAction.REMOVE, null -> 0f
        }

    /**
     * Where the row is drawn for a finger [drag] pixels from where it went
     * down: a steady share of the travel up to the threshold, then a rubber
     * band that gives less and less and never passes [MAX_TRAVEL].
     */
    fun resisted(drag: Float, width: Float): Float {
        if (width <= 0f || drag == 0f) return 0f
        val threshold = width * THRESHOLD
        val followed = abs(drag) * FOLLOW
        val shown = if (followed <= threshold) {
            followed
        } else {
            val room = width * (MAX_TRAVEL - THRESHOLD)
            threshold + room * (1f - 1f / ((followed - threshold) / room + 1f))
        }
        return sign(drag) * shown
    }

    /** 0 at rest to 1 at the threshold; the icon grows with it. */
    fun progress(offset: Float, width: Float): Float =
        if (width <= 0f) 0f else (abs(offset) / (width * THRESHOLD)).coerceIn(0f, 1f)

    /** Whether letting go now would act; [wasArmed] adds a little give so the line doesn't flicker. */
    fun armed(offset: Float, width: Float, wasArmed: Boolean): Boolean {
        if (width <= 0f) return false
        val reach = abs(offset) / (width * THRESHOLD)
        return reach >= if (wasArmed) DISARM else 1f
    }

    /**
     * What letting go does. Past the threshold it acts unless flicked back
     * toward the middle; short of it, a fast flick onward acts too.
     * [velocity] is in pixels a second, [flingVelocity] the speed that counts as a flick.
     */
    fun onRelease(offset: Float, velocity: Float, width: Float, flingVelocity: Float, rtl: Boolean, armed: Boolean): SwipeAction? {
        val action = actionFor(offset, rtl) ?: return null
        val fast = abs(velocity) >= flingVelocity
        val onward = sign(velocity) == sign(offset)
        val acts = if (armed) !(fast && !onward) else fast && onward && abs(offset) >= width * FLING_MIN_TRAVEL
        return action.takeIf { acts }
    }
}

/**
 * Short confirmations of a swipe, shown by the app shell in its snackbar
 * like other notices. A newer one replaces the one showing.
 */
object QueueNotices {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun post(action: SwipeAction, title: String) {
        _messages.tryEmit(
            when (action) {
                SwipeAction.PLAY_NEXT -> "\"$title\" plays next"
                SwipeAction.ADD_TO_QUEUE -> "Added \"$title\" to the queue"
                SwipeAction.REMOVE -> "Removed \"$title\" from the playlist"
            },
        )
    }
}

/**
 * A song row that can be swiped: toward the end of the line to play it next,
 * toward the start to add it to the queue. The row follows the finger with a
 * little resistance over a rounded colour that names the action; at the
 * threshold there's one tick and the icon pops, and letting go there acts
 * and springs the row back. Only sideways drags are taken, after the touch
 * slop, so the list still scrolls. With both actions null it's just [content].
 *
 * [content] is handed a modifier for the row's own clickable node: it carries
 * the two actions for TalkBack, which only offers the actions of the node it
 * focuses.
 *
 * Not for rows inside something that scrolls sideways (a pager, a carousel):
 * the row would take its drags.
 */
@Composable
internal fun SwipeToQueueRow(
    title: String,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * In one of your own playlists: the row then swipes toward the end of the
     * line to add to the queue and toward the start to take the song out of
     * the playlist, in place of play next and add to queue.
     */
    onRemove: (() -> Unit)? = null,
    content: @Composable (actions: Modifier) -> Unit,
) {
    if (onRemove != null) {
        SwipeRow(title, onAddToQueue, onRemove, SwipeAction.ADD_TO_QUEUE, SwipeAction.REMOVE, modifier, content)
        return
    }
    SwipeRow(title, onPlayNext, onAddToQueue, SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_QUEUE, modifier, content)
}

/** The swipe itself: [onForward] toward the end of the line, as [forward]; [onBackward] toward its start, as [backward]. */
@Composable
private fun SwipeRow(
    title: String,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    forward: SwipeAction,
    backward: SwipeAction,
    modifier: Modifier,
    content: @Composable (actions: Modifier) -> Unit,
) {
    // The arithmetic names the two sides PLAY_NEXT and ADD_TO_QUEUE; [shown] says what each does here.
    fun shown(side: SwipeAction) = if (side == SwipeAction.PLAY_NEXT) forward else backward
    if (onPlayNext == null && onAddToQueue == null) {
        Box(modifier) { content(Modifier) }
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val flingVelocity = with(density) { SwipeQueue.FLING_DP_PER_S.dp.toPx() }
    val ui by AppSettings.ui.collectAsState()
    val calm by rememberUpdatedState(ui.reduceAnimation)
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val playNext by rememberUpdatedState(onPlayNext)
    val addToQueue by rememberUpdatedState(onAddToQueue)
    val name by rememberUpdatedState(title)

    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    var settling by remember { mutableStateOf<Job?>(null) }

    fun run(side: SwipeAction) {
        val act = (if (side == SwipeAction.PLAY_NEXT) playNext else addToQueue) ?: return
        act()
        QueueNotices.post(shown(side), name)
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .pointerInput(rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var drag = 0f
                    // Waits for the touch slop and claims the drag only when it's
                    // mostly sideways; otherwise the change is left for the list,
                    // which takes it and so cancels this gesture.
                    val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        val moved = change.position - down.position
                        if (abs(moved.x) > abs(moved.y) * 1.5f) {
                            change.consume()
                            drag = over.x
                        }
                    } ?: return@awaitEachGesture
                    // A finger landing on a row still springing back takes hold of it where it is.
                    settling?.cancel()
                    drag += if (offset == 0f) 0f else offset / SwipeQueue.FOLLOW
                    val canNext = playNext != null
                    val canQueue = addToQueue != null
                    val tracker = VelocityTracker()
                    tracker.addPosition(start.uptimeMillis, start.position)

                    fun follow() {
                        drag = SwipeQueue.allowed(drag, rtl, canNext, canQueue)
                        offset = SwipeQueue.resisted(drag, width.toFloat())
                        val now = SwipeQueue.armed(offset, width.toFloat(), armed)
                        if (now && !armed) {
                            haptics.tick()
                            if (!calm) scope.launch {
                                pop.snapTo(1.3f)
                                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
                            }
                        }
                        armed = now
                    }
                    follow()
                    val lifted = horizontalDrag(start.id) { change ->
                        drag += change.positionChange().x
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        follow()
                    }
                    val velocity = tracker.calculateVelocity().x
                    // Only a finger lifted acts; a gesture taken away (the page closing) just springs back.
                    val action = if (!lifted) null
                    else SwipeQueue.onRelease(offset, velocity, width.toFloat(), flingVelocity, rtl, armed)
                    if (action != null) {
                        // A flick that never reached the line gets its tick as it acts.
                        if (!armed) haptics.tick()
                        run(action)
                    }
                    armed = false
                    val from = offset
                    settling = scope.launch {
                        // Held on its own side of the middle: an overshoot would
                        // flash the other action's colour on the way back.
                        animate(
                            from, 0f,
                            animationSpec = if (calm) tween(140) else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        ) { value, _ -> offset = if (from > 0f) value.coerceAtLeast(0f) else value.coerceAtMost(0f) }
                    }
                }
            },
    ) {
        val action = SwipeQueue.actionFor(offset, rtl)
        if (action != null) {
            SwipeReveal(shown(action), SwipeQueue.progress(offset, width.toFloat()), towardRight = offset > 0f, armed = armed, calm = calm, pop = pop.value)
        }
        val moving = offset != 0f
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset }
                // Lifted off the list, the row is a card over the colour.
                .then(if (moving) Modifier.clip(RowShape).background(MaterialTheme.colorScheme.background) else Modifier),
        ) {
            // The menu has the same two actions; this puts them in TalkBack's actions too.
            content(
                Modifier.semantics {
                    customActions = listOfNotNull(
                        onPlayNext?.let { CustomAccessibilityAction(labelOf(forward)) { run(SwipeAction.PLAY_NEXT); true } },
                        onAddToQueue?.let { CustomAccessibilityAction(labelOf(backward)) { run(SwipeAction.ADD_TO_QUEUE); true } },
                    )
                },
            )
        }
    }
}

private val RowShape = RoundedCornerShape(18.dp)

private fun labelOf(action: SwipeAction) = when (action) {
    SwipeAction.PLAY_NEXT -> "Play next"
    SwipeAction.ADD_TO_QUEUE -> "Add to queue"
    SwipeAction.REMOVE -> "Remove"
}

/** The colour behind a swiped row, with the action's icon and name on the side the row has uncovered. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeReveal(
    action: SwipeAction,
    progress: Float,
    towardRight: Boolean,
    armed: Boolean,
    calm: Boolean,
    pop: Float,
) {
    val colors = MaterialTheme.colorScheme
    val (container, onContainer) = when (action) {
        SwipeAction.PLAY_NEXT -> colors.primaryContainer to colors.onPrimaryContainer
        SwipeAction.ADD_TO_QUEUE -> colors.tertiaryContainer to colors.onTertiaryContainer
        SwipeAction.REMOVE -> colors.errorContainer to colors.onErrorContainer
    }
    val icon = when (action) {
        SwipeAction.PLAY_NEXT -> Icons.AutoMirrored.Rounded.PlaylistPlay
        SwipeAction.ADD_TO_QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
        SwipeAction.REMOVE -> Icons.Rounded.Delete
    }
    val label = labelOf(action)
    // The icon grows toward the threshold and pops on reaching it; held still with less motion.
    val scale = if (calm) 1f else (0.6f + 0.4f * progress) * pop
    Box(
        Modifier
            .matchParentSize()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .graphicsLayer { alpha = (progress * 2f).coerceAtMost(1f) }
            .clip(RowShape)
            .background(container),
        contentAlignment = if (towardRight) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight,
    ) {
        // Laid out by the physical side, so the icon always sits at the uncovered edge.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val glyph = @Composable {
                    Icon(icon, null, tint = onContainer, modifier = Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                }
                val text = @Composable {
                    Text(
                        label,
                        color = onContainer,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (armed) FontWeight.Bold else FontWeight.Medium,
                    )
                }
                if (towardRight) { glyph(); text() } else { text(); glyph() }
            }
        }
    }
}
