package com.opentune.ui.player

import com.opentune.ui.components.livingArt

import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.media.AudioManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.ContentTransform
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
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.drawscope.clipRect
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
    /** Where the player's cover area ends, from the top; the full cover runs a little past it. */
    fullCoverBottom: Dp? = null,
    /** Let the full cover drift and zoom while playing (see livingArt). */
    movingCover: Boolean = false,
    playing: Boolean = false,
    /** How the full-screen cover changes to the next song's, and where that song is in the queue. */
    change: com.opentune.data.settings.CoverChange = com.opentune.data.settings.CoverChange.FADE,
    index: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(scheme.surface)) {
        val blur = style == PlayerBackground.BLUR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            style == PlayerBackground.BLUR && !blur -> {
                // No blur effect before Android 12: a tiny copy of the cover,
                // stretched to fill, smears into the same soft colour field.
                AnimatedContent(artworkUrl, transitionSpec = { fadeIn(tween(800)) togetherWith fadeOut(tween(800)) }, label = "backdrop") { url ->
                    Artwork(url.artworkAt(24), Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }, shape = RectangleShape)
                }
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
            }
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
            else -> {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to scheme.primaryContainer,
                            0.55f to scheme.surfaceContainer,
                            1f to scheme.surface,
                        ),
                    ),
                )
                // Under the controls, the song's colours glow and drift into each other.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
                    AmbientLights(
                        listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.inversePrimary, scheme.primaryContainer),
                        playing = playing,
                        animate = animate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(maxHeight * 0.6f)
                            .align(Alignment.BottomCenter),
                    )
                }
            }
        }
        if (fullCover) {
            CoverSwap(CoverShown(artworkUrl, index), change) { url, motion ->
                // The cover runs from the top down to just behind the title and
                // fades out there into whatever backdrop is below it, with a light
                // scrim at the top so the status line stays readable. It's never
                // shorter than it is wide; taller, it's cropped at the sides.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val height = maxOf(maxWidth / 0.9f, (fullCoverBottom ?: 0.dp) + FULL_COVER_OVERLAP)
                val solid = (1f - FULL_COVER_FADE / height).coerceIn(0.4f, 0.8f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .then(motion)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(solid to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    Artwork(
                        url.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                        Modifier.matchParentSize(),
                        shape = RectangleShape,
                        imageModifier = Modifier.livingArt(playing, movingCover),
                    )
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.2f to Color.Transparent)))
                }
                }
            }
        }
    }
}

/** How far a full-screen cover runs past its area, under the title, and how long its fade is. */
private val FULL_COVER_OVERLAP = 44.dp
private val FULL_COVER_FADE = 210.dp

/**
 * Soft blobs of the artwork's colors drifting slowly over a dark base, like
 * Apple Music's animated backgrounds. Still when [animate] is false.
 */
@Composable
private fun MeshGradient(animate: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer)
    // One drift takes 24 s, so a dozen steps a second can't be told from
    // every frame. It matters: the mesh is what the player's Liquid Glass
    // samples, and each step makes every glass control blur and bend again.
    val t by produceState(0.15f, animate) {
        if (!animate) return@produceState
        val start = withFrameNanos { it } - (0.15f * MESH_CYCLE_NS).toLong()
        while (true) {
            delay(MESH_STEP_MS)
            value = ((withFrameNanos { it } - start) % MESH_CYCLE_NS).toFloat() / MESH_CYCLE_NS
        }
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

private const val MESH_CYCLE_NS = 24_000_000_000L
private const val MESH_STEP_MS = 80L

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
    moving: Boolean = false,
    change: com.opentune.data.settings.CoverChange = com.opentune.data.settings.CoverChange.FADE,
    /** The song's place in the queue, so Carousel knows which way to slide. */
    index: Int = 0,
    /** Double-tapping the cover's left or right side jumps by this many milliseconds back or on. */
    onSeekBy: ((Long) -> Unit)? = null,
    /** A soft light in the cover's colour under it, swelling with the bass. */
    glow: Boolean = false,
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
        if (glow) CoverGlow(isPlaying, scale, Modifier.matchParentSize())
        CoverSwap(CoverShown(song.thumbnailUrl, index), change) { url, motion ->
            Artwork(
                url.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(motion)
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
                imageModifier = Modifier.livingArt(isPlaying, moving),
            )
        }
        if (onSeekBy != null) SeekTaps(onSeekBy, scale, Modifier.matchParentSize())
    }
}

/**
 * Double-tap the cover's left side to go back ten seconds, its right side
 * to go on: a ripple spreads from the finger over that half and a label
 * says how far, adding up while the taps keep coming.
 */
@Composable
private fun SeekTaps(onSeekBy: (Long) -> Unit, scale: Float, modifier: Modifier) {
    val haptics = com.opentune.ui.components.rememberHaptics()
    val ripple = remember { Animatable(1f) }
    var side by remember { mutableStateOf(0) }
    var at by remember { mutableStateOf(Offset.Zero) }
    var total by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { o ->
                val s = if (o.x < size.width / 2f) -1 else 1
                val now = android.os.SystemClock.uptimeMillis()
                total = if (s == side && now - lastTap < 1_200) total + 10 else 10
                side = s
                at = o
                lastTap = now
                haptics.tick()
                onSeekBy(s * 10_000L)
                scope.launch {
                    ripple.snapTo(0f)
                    ripple.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                }
            })
        },
    ) {
        val p = ripple.value
        if (p < 1f && side != 0) {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
            Canvas(Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale; clip = true; this.shape = shape }) {
                // Only the half that was tapped.
                val left = if (side < 0) 0f else size.width / 2f
                clipRect(left = left, right = left + size.width / 2f) {
                    drawRect(Color.Black.copy(alpha = 0.18f * (1f - p)))
                    drawCircle(Color.White.copy(alpha = 0.28f * (1f - p)), size.width * 0.75f * p, at)
                }
            }
            val label = if (side < 0) "« $total s" else "$total s »"
            Text(
                label,
                color = Color.White.copy(alpha = (1f - p * p).coerceIn(0f, 1f)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 36.dp)
                    .graphicsLayer { translationX = side * 10.dp.toPx() * p },
            )
        }
    }
}

/** A blurred light in the cover's colours under it, drifting a little lower and swelling with the bass. */
@Composable
private fun CoverGlow(playing: Boolean, scale: Float, modifier: Modifier) {
    val (a, b) = com.opentune.ui.theme.songAccents()
    val swell = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.opentune.playback.AudioLevels.hold()
        onDispose { com.opentune.playback.AudioLevels.release() }
    }
    LaunchedEffect(playing) {
        var peak = 0.05f
        var last = 0L
        while (playing) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val level = com.opentune.playback.AudioLevels.at(System.nanoTime())?.let { it.bass * 0.8f + it.level * 0.2f } ?: 0f
                peak = maxOf(level, peak * (1f - dt * 0.3f)).coerceAtLeast(0.02f)
                val target = (level / peak).coerceIn(0f, 1f)
                swell.floatValue += (target - swell.floatValue) * (dt * if (target > swell.floatValue) 6f else 2f).coerceAtMost(1f)
            }
        }
        swell.floatValue = 0f
    }
    val strength by animateFloatAsState(if (playing) 1f else 0.45f, tween(600), label = "glow")
    Canvas(modifier) {
        val s = swell.floatValue
        val w = size.width * scale
        val c = Offset(center.x, center.y + w * 0.12f)
        val r = w * (0.7f + 0.07f * s)
        drawCircle(
            Brush.radialGradient(
                0f to a.copy(alpha = (0.7f + 0.25f * s).coerceAtMost(1f) * strength),
                0.5f to b.copy(alpha = (0.35f + 0.15f * s) * strength),
                1f to Color.Transparent,
                center = c,
                radius = r,
            ),
            r,
            c,
        )
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
    /** The song's waveform; drawn as bars in place of the track when given. */
    waveform: com.opentune.playback.Waveforms.Wave? = null,
    /** Moves on as [waveform] fills in, so the bars are redrawn. */
    waveVersion: () -> Int = { 0 },
    /** The lyric line sung at a moment, shown above the finger while the bar is dragged. */
    lyricAt: ((Long) -> String?)? = null,
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

    val seekHaptics = com.opentune.ui.components.rememberHaptics()
    fun fraction(): Float = dragFraction
        ?: if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(modifier) {
        Box {
        if (lyricAt != null) ScrubLyric(dragFraction, durationMs, lyricAt)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                // Redrawn every frame while playing; its own layer keeps that from
                // re-recording the rest of the player.
                .graphicsLayer()
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
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        // A soft tick each time the drag crosses a tenth of the song, and a firmer one at either end.
                        val before = dragFraction
                        if (before != null && (before * 10).toInt() != (f * 10).toInt()) seekHaptics.tick()
                        if (before != null && before > 0f && before < 1f && (f == 0f || f == 1f)) seekHaptics.press()
                        dragFraction = f
                    }
                },
        ) {
            val h = trackHeight.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            val f = fraction()
            val b = if (durationMs > 0) (buffered().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            if (waveform != null) {
                waveVersion()
                // Bars from the middle line, scaled so the loudest is full height; steps not
                // heard yet are dots. They grow taller while the bar is being dragged.
                val bins = waveform.bins
                val top = bins.maxOrNull()?.takeIf { it > 0f } ?: 1f
                val n = bins.size
                val step = size.width / n
                val bar = step * 0.62f
                val full = size.height * (if (dragFraction != null) 1f else 0.82f)
                for (i in 0 until n) {
                    val x = i * step + (step - bar) / 2f
                    val v = bins[i]
                    val played = (i + 0.5f) / n <= f
                    val color = when {
                        played -> active
                        (i + 0.5f) / n <= b -> bufferedColor
                        else -> inactive
                    }
                    val bh = if (v == com.opentune.playback.Waveforms.UNKNOWN) bar else maxOf(bar, full * (0.12f + 0.88f * (v / top)))
                    drawRoundRect(color, Offset(x, (size.height - bh) / 2f), Size(bar, bh), CornerRadius(bar / 2f))
                }
                return@Canvas
            }
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
            with(com.opentune.ui.components.FlyingCover) { modifier.card() }
                .glass(CircleShape, tint)
                .clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProgressRing(progress, accent, track, stroke = 3.dp, modifier = Modifier.matchParentSize())
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), with(com.opentune.ui.components.FlyingCover) { Modifier.padding(4.dp).fillMaxSize().landing() }, CircleShape)
            if (isBuffering) CircularProgressIndicator(Modifier.matchParentSize(), strokeWidth = 3.dp, color = accent)
        }
        return
    }

    val shape = RoundedCornerShape(22.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = with(com.opentune.ui.components.FlyingCover) { modifier.card() }
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
                    Artwork(s.thumbnailUrl.artworkAt(ROW_ART_PX), with(com.opentune.ui.components.FlyingCover) { Modifier.size(44.dp).landing() }, RoundedCornerShape(12.dp))
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
fun ArtworkSwipeArea(
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
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
        content = content,
    )
}

/**
 * The cover as a record: round, with grooves and a spindle hole, turning
 * at 33 rpm while the song plays and resting where it stopped on pause.
 */
@Composable
fun VinylPane(
    song: Song,
    isPlaying: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, animate) {
        if (!isPlaying || !animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) angle = (angle + (now - last) / 1_000_000_000f * VINYL_DEGREES_PER_SECOND) % 360f
                last = now
            }
        }
    }
    val groove = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    ArtworkSwipeArea(onSwipeNext = onSwipeNext, onSwipePrevious = onSwipePrevious, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(1f)
                .align(Alignment.Center)
                .graphicsLayer {
                    rotationZ = angle
                    shadowElevation = 30.dp.toPx()
                    shape = CircleShape
                    clip = true
                }
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center,
        ) {
            Artwork(
                song.thumbnailUrl.artworkAt(com.opentune.data.model.PLAYER_ART_PX),
                Modifier.fillMaxSize(0.62f).clip(CircleShape),
                CircleShape,
            )
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                // Grooves between the label and the rim.
                var ring = r * 0.66f
                while (ring < r * 0.98f) {
                    drawCircle(groove, radius = ring, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f))
                    ring += r * 0.035f
                }
                drawCircle(Color(0xFF111111), radius = r * 0.045f)
                drawCircle(Color.White.copy(alpha = 0.25f), radius = r * 0.045f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
            }
        }
    }
}

private const val VINYL_DEGREES_PER_SECOND = 360f * 33.3f / 60f

/**
 * The waveform for [videoId], kept filling in from the app's own audio
 * while it plays, and saved when the song changes or the player goes.
 */
@Composable
fun rememberWaveform(videoId: String, position: () -> Long, durationMs: Long, playing: Boolean): Pair<com.opentune.playback.Waveforms.Wave, androidx.compose.runtime.State<Int>> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wave = remember(videoId) { com.opentune.playback.Waveforms.forSong(context, videoId) }
    val version = remember(videoId) { androidx.compose.runtime.mutableIntStateOf(wave.version) }
    androidx.compose.runtime.DisposableEffect(wave) {
        com.opentune.playback.AudioLevels.hold()
        onDispose {
            com.opentune.playback.AudioLevels.release()
            com.opentune.playback.Waveforms.save(wave)
        }
    }
    LaunchedEffect(wave, playing, durationMs) {
        var lastShown = -1
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            if (playing && !wave.complete) {
                com.opentune.playback.AudioLevels.at(System.nanoTime())?.let { com.opentune.playback.Waveforms.observe(wave, position(), durationMs, it.level) }
            }
            // A decoded waveform arrives all at once; a heard one a step at a time.
            if (wave.version != lastShown) {
                lastShown = wave.version
                version.intValue = wave.version
            }
            if (!playing && wave.complete) break
            kotlinx.coroutines.delay(60)
        }
    }
    return wave to version
}

/**
 * While the seek bar is dragged, the line sung at that point in a bubble
 * above the finger. It follows the finger, stays inside the bar's width
 * and changes as the finger crosses into another line.
 */
@Composable
private fun BoxScope.ScrubLyric(dragFraction: Float?, durationMs: Long, lyricAt: (Long) -> String?) {
    var last by remember { mutableStateOf<String?>(null) }
    var lastFraction by remember { mutableStateOf(0f) }
    val line = dragFraction?.let { lyricAt((it * durationMs).toLong()) }
    if (dragFraction != null) {
        lastFraction = dragFraction
        if (line != null) last = line
    }
    val shown by animateFloatAsState(if (dragFraction != null && last != null) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "scrubLyric")
    if (shown <= 0.01f || last == null) return
    val text = last!!
    Box(
        Modifier
            .layout { measurable, constraints ->
                val maxW = (constraints.maxWidth * 0.8f).toInt()
                val p = measurable.measure(androidx.compose.ui.unit.Constraints(maxWidth = maxW))
                layout(constraints.maxWidth, 0) {
                    val center = (constraints.maxWidth * lastFraction).toInt()
                    val x = (center - p.width / 2).coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                    p.place(x, -p.height - 6.dp.roundToPx())
                }
            }
            .graphicsLayer {
                alpha = shown
                val s = 0.85f + 0.15f * shown
                scaleX = s
                scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                translationY = (1f - shown) * 8.dp.toPx()
            }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        androidx.compose.animation.AnimatedContent(
            text,
            transitionSpec = { (fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 3 }) togetherWith fadeOut(tween(120)) },
            label = "scrubLine",
        ) { t ->
            Text(t, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
