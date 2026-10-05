package com.opentune.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.media.AudioManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.animateColorAsState
import com.opentune.ui.theme.rememberArtworkSeed
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opentune.ui.components.glass
import com.opentune.ui.components.pressable
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.NerdStats
import com.opentune.data.model.ROW_ART_PX
import com.opentune.playback.AudioFormatInfo
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.PlayerBackground
import com.opentune.ui.components.Artwork
import com.opentune.ui.formatTime
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full player's backdrop, in the style chosen in settings. With
 * [fullCover] the artwork itself runs edge to edge across the top and fades
 * into the background below it.
 */
@Composable
fun PlayerBackdrop(
    style: PlayerBackground,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    fullCover: Boolean = false,
) {
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
            style == PlayerBackground.MESH -> MeshGradient(animate, Modifier.matchParentSize())
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
        if (fullCover) {
            AnimatedContent(artworkUrl, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "cover") { url ->
                // The cover fades out into whatever backdrop is below it, with a
                // light scrim at the top so the status line stays readable.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.9f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(0.6f to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    Artwork(url.artworkAt(com.opentune.data.model.PLAYER_ART_PX), Modifier.matchParentSize(), shape = RectangleShape)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.2f to Color.Transparent)))
                }
            }
        }
    }
}

/**
 * Soft blobs of the artwork's colors drifting slowly over a dark base, like
 * Apple Music's animated backgrounds. Still when [animate] is false.
 */
@Composable
private fun MeshGradient(animate: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer)
    val transition = rememberInfiniteTransition(label = "mesh")
    val t by if (animate) {
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "meshT")
    } else {
        remember { mutableFloatStateOf(0.15f) }
    }
    Canvas(modifier) {
        drawRect(scheme.surface)
        val w = size.width
        val h = size.height
        colors.forEachIndexed { i, color ->
            val phase = (t + i * 0.25f) * 2 * PI.toFloat()
            val cx = w * (0.5f + 0.38f * cos(phase + i))
            val cy = h * (0.32f + 0.3f * sin(phase * (if (i % 2 == 0) 1f else -1f) + i * 1.3f))
            val radius = maxOf(w, h) * (0.55f + 0.1f * sin(phase * 2))
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = radius),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        // Keep the lower half calm so the controls stay readable.
        drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 1f to scheme.surface.copy(alpha = 0.85f)))
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
    wavy: Boolean = false,
    playing: Boolean = false,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // The wave travels while music plays and flattens out when it stops.
    // Only animated while it can be seen, so an idle bar costs no frames.
    val phaseState = if (wavy && playing) rememberWavePhase() else null
    val amplitude by animateFloatAsState(if (wavy && playing && dragFraction == null) 1f else 0f, tween(400), label = "amp")
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
            if (amplitude > 0f) {
                // Played part as a sine wave, a stroke as thick as the track.
                val amp = h * 0.9f * amplitude
                val length = h * 7f
                val wavePath = Path().apply {
                    moveTo(0f, size.height / 2)
                    var x = 0f
                    while (x <= size.width * f) {
                        lineTo(x, size.height / 2 + amp * sin(x / length * 2 * PI.toFloat() + (phaseState?.value ?: 0f)))
                        x += 2f
                    }
                }
                drawPath(wavePath, active, style = Stroke(width = h, cap = StrokeCap.Round))
            } else {
                drawRoundRect(active, Offset(0f, y), Size(size.width * f, h), r)
            }
            if (thumbRadius > 0.dp) drawCircle(active, thumbRadius.toPx(), Offset(size.width * f, size.height / 2))
        }
        val elapsedSeconds by remember(durationMs) {
            derivedStateOf { ((dragFraction?.times(durationMs)?.toLong() ?: position()) / 1000) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsedSeconds * 1000), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Time left, as Apple Music shows it; the total until the length is known.
            Text(
                if (durationMs > 0) "-" + formatTime((durationMs - elapsedSeconds * 1000).coerceAtLeast(0)) else formatTime(0),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The wave's travel, 0 to 2π, looping. */
@Composable
private fun rememberWavePhase(): androidx.compose.runtime.State<Float> =
    rememberInfiniteTransition(label = "wave")
        .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "phase")

/**
 * Back, play/pause and forward as large bare glyphs, Apple Music style. Each
 * sinks under the finger; play and pause cross-fade with a little scale.
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Icons.Rounded.FastRewind, "Previous", 64.dp, onClick = onPrevious)
        Box(contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CircularProgressIndicator(Modifier.size(84.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Box(Modifier.size(84.dp).pressable(onTogglePlay, 0.86f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    isPlaying,
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    label = "playIcon",
                ) { playing ->
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "Pause" else "Play",
                        Modifier.size(80.dp),
                    )
                }
            }
        }
        GlyphButton(Icons.Rounded.FastForward, "Next", 64.dp, enabled = hasNext, onClick = onNext)
    }
}

@Composable
private fun GlyphButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Box(Modifier.size(size + 12.dp).pressable({ if (enabled) onClick() }, 0.82f), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(size), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

/**
 * Now playing, above the dock: a card tinted with the cover's colour, with
 * the song's progress as a ring around the play button. Swipe it sideways to
 * skip. [inline] is the round bubble the card becomes while a page scrolls:
 * the cover in a circle, ringed by progress; tap it to open the player.
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
    inline: Boolean = false,
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    // The cover's own colour, faded into the glass, so each song tints the card.
    val seed = rememberArtworkSeed(song.thumbnailUrl)
    val tint by animateColorAsState((seed ?: MaterialTheme.colorScheme.surfaceContainerHigh).copy(alpha = 0.38f), tween(600), label = "miniTint")

    if (inline) {
        Box(
            modifier
                .glass(CircleShape, tint)
                .clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProgressRing(progress, accent, track, stroke = 3.dp, modifier = Modifier.matchParentSize())
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.padding(4.dp).fillMaxSize(), CircleShape)
            if (isBuffering) CircularProgressIndicator(Modifier.matchParentSize(), strokeWidth = 3.dp, color = accent)
        }
        return
    }

    val shape = RoundedCornerShape(22.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = modifier
            .graphicsLayer { translationX = offsetX.value }
            .glass(shape, tint)
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
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(song, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "mini", modifier = Modifier.weight(1f)) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(s.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(44.dp), RoundedCornerShape(12.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Play inside the progress ring.
            Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onTogglePlay), contentAlignment = Alignment.Center) {
                ProgressRing(progress, accent, track, stroke = 2.5.dp, modifier = Modifier.matchParentSize().padding(3.dp))
                if (isBuffering) CircularProgressIndicator(Modifier.matchParentSize().padding(3.dp), strokeWidth = 2.5.dp, color = accent)
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", Modifier.size(24.dp))
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp))
            }
        }
    }
}

/** A circle that fills clockwise from the top as [progress] goes 0 to 1. */
@Composable
private fun ProgressRing(progress: () -> Float, color: Color, track: Color, stroke: Dp, modifier: Modifier = Modifier) {
    // Its own layer: the ring redraws as the song plays, the glass under it needn't.
    Canvas(modifier.graphicsLayer()) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * progress().coerceIn(0f, 1f), useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** "Playing from" and the queue's origin, or just "Now playing" when there isn't one. */
@Composable
fun SongStatus(source: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (source.isNullOrBlank()) {
            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(source, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Codec, bitrate, sample rate and channels of what's actually playing. */
@Composable
fun NerdStatsLine(format: AudioFormatInfo?) {
    val picked by NerdStats.lastPicked.collectAsState()
    val startup by NerdStats.startupMs.collectAsState()
    val gain by NerdStats.loudnessGainDb.collectAsState()
    val engine by NerdStats.engine.collectAsState()
    val kbps = format?.bitrateKbps ?: picked?.second
    val parts = listOfNotNull(
        format?.codec,
        kbps?.let { "$it kbps" },
        format?.sampleRateHz?.let { "%.1f kHz".format(it / 1000f) },
        format?.channels?.let { if (it == 2) "stereo" else if (it == 1) "mono" else "$it ch" },
        gain?.let { "normalized %+.1f dB".format(it) },
        startup?.let { "started in $it ms" },
        engine?.let { "via $it" },
    )
    Text(
        parts.joinToString(" · ").ifEmpty { "Waiting for stream…" },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

/** The phone's media volume, kept in step with the hardware buttons. */
@Composable
fun VolumeBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1 }
    var volume by remember { mutableFloatStateOf(am?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat() ?: 0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(am) {
        while (true) {
            if (!dragging) volume = am?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat() ?: volume
            delay(400)
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
        val fill = MaterialTheme.colorScheme.onSurface
        fun set(x: Float, width: Int) {
            volume = (x / width).coerceIn(0f, 1f) * max
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, volume.roundToInt(), 0)
        }
        Canvas(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(28.dp)
                .pointerInput(max) { detectTapGestures { set(it.x, size.width) } }
                .pointerInput(max) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        set(change.position.x, size.width)
                    }
                },
        ) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            val f = (volume / max).coerceIn(0f, 1f)
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(fill, Offset(0f, y), Size(size.width * f, h), CornerRadius(h / 2))
        }
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** An empty area that still skips tracks on a sideways swipe, for full-screen cover mode. */
@Composable
fun ArtworkSwipeArea(onSwipeNext: () -> Unit, onSwipePrevious: () -> Unit, modifier: Modifier = Modifier) {
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dx = 0f },
                onDragEnd = {
                    when {
                        dx < -threshold -> onSwipeNext()
                        dx > threshold -> onSwipePrevious()
                    }
                },
            ) { change, amount ->
                change.consume()
                dx += amount
            }
        },
    )
}
