package com.opentune.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.PlayerBackground
import com.opentune.ui.components.Artwork
import com.opentune.ui.formatTime
import kotlinx.coroutines.launch

/** The full player's backdrop, in the style chosen in settings. */
@Composable
fun PlayerBackdrop(style: PlayerBackground, artworkUrl: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(scheme.surface)) {
        val blur = style == PlayerBackground.BLUR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            blur -> {
                AnimatedContent(artworkUrl, transitionSpec = { fadeIn(tween(800)) togetherWith fadeOut(tween(800)) }, label = "backdrop") { url ->
                    Artwork(
                        url,
                        Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }.blur(90.dp),
                        shape = RectangleShape,
                    )
                }
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            style == PlayerBackground.PLAIN -> Unit
            else -> Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to scheme.primaryContainer,
                        0.55f to scheme.surfaceContainer,
                        1f to scheme.surface,
                    ),
                ),
            )
        }
    }
}

/**
 * Large cover art. It eases smaller while paused, crossfades on a track
 * change, and can be swiped sideways to skip.
 */
@Composable
fun ArtworkPane(
    song: Song,
    isPlaying: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        if (isPlaying) 1f else 0.86f,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "artScale",
    )
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }

    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dx = offsetX.value
                        when {
                            dx < -threshold -> onSwipeNext()
                            dx > threshold -> onSwipePrevious()
                        }
                        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.7f)) }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + amount) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = song.thumbnailUrl,
            transitionSpec = {
                (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.94f)) togetherWith fadeOut(tween(300))
            },
            label = "artwork",
        ) { url ->
            Artwork(
                url.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX.value
                        rotationZ = offsetX.value / 90f
                        shadowElevation = 28.dp.toPx()
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                        clip = true
                    },
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
    }
}

/**
 * A slim seek bar that thickens while dragged. Position is read in the draw
 * phase, so the bar moves every frame without recomposing anything.
 */
@Composable
fun SeekBar(
    position: () -> Long,
    buffered: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val trackHeight by animateDpAsState(if (dragFraction != null) 10.dp else 5.dp, label = "track")
    val thumbRadius by animateDpAsState(if (dragFraction != null) 0.dp else 7.dp, label = "thumb")
    val active = MaterialTheme.colorScheme.onSurface
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    fun fraction(): Float = dragFraction
        ?: if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) onSeek((offset.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val h = trackHeight.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            val f = fraction()
            val b = if (durationMs > 0) (buffered().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            drawRoundRect(inactive, Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(bufferedColor, Offset(0f, y), Size(size.width * maxOf(b, f), h), r)
            drawRoundRect(active, Offset(0f, y), Size(size.width * f, h), r)
            if (thumbRadius > 0.dp) drawCircle(active, thumbRadius.toPx(), Offset(size.width * f, size.height / 2))
        }
        val elapsedSeconds by remember(durationMs) {
            derivedStateOf { ((dragFraction?.times(durationMs)?.toLong() ?: position()) / 1000) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsedSeconds * 1000), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeButton(Icons.Rounded.Shuffle, "Shuffle", active = shuffle, onClick = onShuffle)
        IconButton(onClick = onPrevious, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(40.dp))
        }
        PlayPauseButton(isPlaying, isBuffering, onTogglePlay)
        IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(40.dp))
        }
        ModeButton(
            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat one"
                Player.REPEAT_MODE_ALL -> "Repeat all"
                else -> "Repeat off"
            },
            active = repeatMode != Player.REPEAT_MODE_OFF,
            onClick = onRepeat,
        )
    }
}

/** A toggle shown in the accent color with a dot underneath when on. */
@Composable
private fun ModeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint)
            if (active) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(top = 30.dp).size(4.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 76.dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f), label = "press")
    Box(contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            interactionSource = interaction,
            modifier = Modifier.size(size).scale(scale),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    isPlaying,
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    label = "playIcon",
                ) { playing ->
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "Pause" else "Play",
                        Modifier.size(size * 0.5f),
                    )
                }
            }
        }
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(size + 10.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The floating bar above the tabs: art, title, play and next, with a thin
 * progress line along its bottom edge. Swipe sideways to skip.
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    progress: () -> Float,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .height(66.dp)
            .graphicsLayer { translationX = offsetX.value }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            offsetX.value < -threshold -> onNext()
                            offsetX.value > threshold -> onPrevious()
                        }
                        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.7f)) }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + amount * 0.6f) }
                }
            },
    ) {
        Box {
            Row(
                Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(song, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "mini", modifier = Modifier.weight(1f)) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(s.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(46.dp), MaterialTheme.shapes.small)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (isBuffering) CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 2.dp)
                    IconButton(onClick = onTogglePlay) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(30.dp))
                    }
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(28.dp))
                }
            }
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).padding(horizontal = 14.dp)) {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height))
                drawRoundRect(fill, size = Size(size.width * progress().coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height))
            }
        }
    }
}
