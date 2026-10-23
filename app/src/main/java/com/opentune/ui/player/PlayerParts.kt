package com.opentune.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.livingArt

import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.media.AudioManager
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.abs
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
        when (style) {
            PlayerBackground.ECHO_GLOW -> EchoGlowBackground(animate && playing, Modifier.matchParentSize())
            PlayerBackground.MESH -> MeshGradient(artworkUrl, animate, Modifier.matchParentSize())
            PlayerBackground.GRADIENT -> {
                // Two tones: the cover's own colour at the top, deepening to
                // the dark surface by the middle, where the controls sit.
                val top = scheme.primaryContainer
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to top,
                            0.3f to lerp(top, scheme.surface, 0.35f),
                            0.62f to lerp(top, scheme.surface, 0.85f),
                            1f to scheme.surface,
                        ),
                    ),
                )
                // Under the controls, a faint glow in the same colour.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
                    AmbientLights(
                        listOf(scheme.primary, scheme.primaryContainer, scheme.inversePrimary),
                        playing = playing,
                        animate = animate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(maxHeight * 0.5f)
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = 0.35f },
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
 * Wide, soft patches of the cover's own colours, several of them, laid over
 * the whole player and drifting slowly into each other, like Apple Music's
 * animated backgrounds. Darker toward the bottom so the controls read.
 * Still when [animate] is false.
 */
@Composable
private fun MeshGradient(artworkUrl: String?, animate: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val palette = com.opentune.ui.theme.rememberArtworkPalette(artworkUrl)
    // The cover's colours, or the song's scheme until they've been read. Each
    // is kept between dark and mid so none of them glares behind white text.
    val wanted = remember(palette, scheme.primary, scheme.tertiary) {
        val cover = palette.ifEmpty { listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer) }
            .map { Hct.fromInt(it.toArgb()) }
        // A cover of one hue (or greys and one hue) gets the scheme's second
        // accent too, so the patches don't all melt into one colour.
        val hues = cover.filter { it.chroma >= 12 }.map { it.hue }
        val oneHue = hues.all { h -> hues.all { abs(h - it).let { d -> minOf(d, 360 - d) } < 40 } }
        val extra = listOfNotNull(scheme.tertiary.takeIf { oneHue }, scheme.primaryContainer.takeIf { cover.size < 3 })
        // Dark greys stay dark, as shadow between the colours rather than haze.
        val all = (cover + extra.map { Hct.fromInt(it.toArgb()) }).map {
            val grey = it.chroma < 12
            Color(Hct.from(it.hue, if (grey) it.chroma else it.chroma * 1.15, it.tone.coerceIn(if (grey) 14.0 else 30.0, if (grey) 50.0 else 60.0)).toInt())
        }
        // The patches, then the deepest shade of the cover's main colour to lay them on.
        List(MESH_BLOBS) { all[it % all.size] } + Color(cover.first().withTone(12.0).toInt())
    }
    // Each colour eases into the next song's rather than jumping.
    val colors = wanted.mapIndexed { i, c -> animateColorAsState(c, tween(1_200), label = "mesh$i").value }
    // One drift takes 40 s, so a dozen steps a second can't be told from
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
        drawRect(colors[MESH_BLOBS])
        val w = size.width
        val h = size.height
        val phase = t * 2 * PI.toFloat()
        for (i in 0 until MESH_BLOBS) {
            val p = MESH_PATHS[i]
            // Whole turns per drift, so the loop closes without a jump.
            val cx = w * (p[0] + 0.22f * cos(phase * p[2] + p[4]))
            val cy = h * (p[1] + 0.12f * sin(phase * p[3] + p[4]))
            val radius = maxOf(w, h) * (0.36f + 0.04f * sin(phase + i))
            val c = colors[i]
            drawCircle(
                // Bright well out, then easing to nothing, so no patch shows a rim.
                Brush.radialGradient(
                    *com.opentune.ui.components.glowStops(c, 1f, shape = 2.5f),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        // Darker toward the bottom so the title and controls stay readable.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.06f),
                0.5f to Color.Black.copy(alpha = 0.2f),
                1f to Color.Black.copy(alpha = 0.5f),
            ),
        )
    }
}

private const val MESH_BLOBS = 5
private const val MESH_CYCLE_NS = 40_000_000_000L
private const val MESH_STEP_MS = 80L

/** Each patch's resting place (x, y as fractions), its turns per drift across and down, and where it starts. */
private val MESH_PATHS = arrayOf(
    floatArrayOf(0.15f, 0.1f, 1f, 1f, 0f),
    floatArrayOf(0.9f, 0.36f, -1f, 2f, 1.7f),
    floatArrayOf(0.1f, 0.62f, 2f, -1f, 3.4f),
    floatArrayOf(0.78f, 0.74f, 1f, -2f, 4.6f),
    floatArrayOf(0.4f, 0.98f, -2f, 1f, 2.2f),
)

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
    // The opening cover morph lands on this cover; tell it where and how big.
    com.opentune.ui.components.PlayerMorph.playerCoverScale = scale
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.opentune.ui.components.PlayerMorph.playerCover = null } }
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
                // As wide as the pane, unless that's taller than the room it has: then as
                // tall as the room. Forcing the full width made a square that spilled over
                // "Playing from" and the title on screens where the controls take more height.
                Modifier
                    .aspectRatio(1f)
                    .then(motion)
                    .then(with(com.opentune.ui.components.PlayerMorph) { Modifier.playerLanding() })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX.value
                        rotationZ = offsetX.value / 90f
                        shadowElevation = 28.dp.toPx()
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                        clip = true
                        // Hidden under the morphing cover while it grows in or shrinks away.
                        alpha = if (com.opentune.ui.components.PlayerMorph.coverAway) 0f else 1f
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
        val peak = (0.7f + 0.25f * s).coerceAtMost(1f) * strength
        // A straight fade to transparent black showed its edge as a line across the player.
        val stops = com.opentune.ui.components.glowStops(a, peak, b)
        drawCircle(Brush.radialGradient(*stops, center = c, radius = r), r, c)
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
    /** The lyric line sung at a moment, shown above the finger while the bar is dragged. */
    lyricAt: ((Long) -> String?)? = null,
    /** Where the song's parts (verse, chorus) begin, in ms, marked as small gaps in the track. */
    marks: List<Long> = emptyList(),
    quality: (@Composable () -> Unit)? = null,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var scrubStartY by remember { mutableStateOf(0f) }
    var fineScrubbing by remember { mutableStateOf(false) }
    // The wave travels while music plays and flattens out when it stops.
    // Only animated while it can be seen, so an idle bar costs no frames.
    val phaseState = if (wavy && playing) rememberWavePhase() else null
    val amplitude by animateFloatAsState(if (wavy && playing && dragFraction == null) 1f else 0f, tween(400), label = "amp")
    val look = AppSettings.ui.collectAsState().value
    val base = look.seekbarThickness.dp.dp
    val trackHeight by animateDpAsState(if (dragFraction != null) base * 2 else base, label = "track")
    // The dot rides the track; the line stands across it; "While dragging" only shows while held.
    val thumbShown = when (look.seekbarThumb) {
        com.opentune.data.settings.SeekbarThumb.DOT, com.opentune.data.settings.SeekbarThumb.LINE -> dragFraction == null
        com.opentune.data.settings.SeekbarThumb.TOUCH -> dragFraction != null
        com.opentune.data.settings.SeekbarThumb.NONE -> false
    }
    val thumbRadius by animateDpAsState(if (thumbShown) maxOf(7.dp, base * 0.9f + 3.dp) else 0.dp, label = "thumb")
    val lineThumb = look.seekbarThumb == com.opentune.data.settings.SeekbarThumb.LINE
    val active = when (look.seekbarColor) {
        com.opentune.data.settings.SeekbarColor.PLAIN -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.primary
    }
    val gradientEnd = MaterialTheme.colorScheme.tertiary
    val gradient = look.seekbarColor == com.opentune.data.settings.SeekbarColor.GRADIENT
    val shownMarks = if (look.seekbarMarks) marks else emptyList()
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val markDot = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    val seekHaptics = com.opentune.ui.components.rememberHaptics()
    fun fraction(): Float = dragFraction
        ?: if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // Until the song's length is known there's nothing to seek: a band of light runs along the
    // track instead, so it reads as loading rather than stuck at the start.
    val loading = durationMs <= 0
    val sweep = if (loading) rememberInfiniteTransition(label = "seekLoading")
        .animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "sweep") else null
    val sheen = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    // A remote or keyboard focuses the bar and moves through the song with left and right.
    var focused by remember { mutableStateOf(false) }
    val focusRing = MaterialTheme.colorScheme.primary

    Column(modifier) {
        Box {
        if (lyricAt != null) ScrubLyric(dragFraction, durationMs, lyricAt)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    val step = seekStepMs(e.key, durationMs) ?: return@onKeyEvent false
                    if (e.type == KeyEventType.KeyDown) onSeek((position() + step).coerceIn(0L, durationMs))
                    true
                }
                .focusable()
                // Redrawn every frame while playing; its own layer keeps that from
                // re-recording the rest of the player.
                .graphicsLayer()
                .semantics {
                    contentDescription = "Playback position. Drag away from the bar for fine scrubbing."
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                    setProgress { value ->
                        if (durationMs <= 0) false else {
                            onSeek((value.coerceIn(0f, 1f) * durationMs).toLong()); true
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) onSeek((offset.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubStartY = it.y; dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                            dragFraction = null
                            fineScrubbing = false
                        },
                        onDragCancel = { dragFraction = null; fineScrubbing = false },
                    ) { change, delta ->
                        change.consume()
                        fineScrubbing = kotlin.math.abs(change.position.y - scrubStartY) > 48.dp.toPx()
                        val speed = if (fineScrubbing) 0.2f else 1f
                        val f = ((dragFraction ?: 0f) + delta / size.width * speed).coerceIn(0f, 1f)
                        // A soft tick each time the drag crosses a tenth of the song, and a firmer one at either end.
                        val before = dragFraction
                        if (before != null && (before * 10).toInt() != (f * 10).toInt()) seekHaptics.tick()
                        if (before != null && before > 0f && before < 1f && (f == 0f || f == 1f)) seekHaptics.press()
                        dragFraction = f
                    }
                },
        ) {
            val h = trackHeight.toPx() * (if (focused) 2f else 1f)
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            val f = fraction()
            if (focused) drawRoundRect(focusRing, Offset(-3.dp.toPx(), y - 3.dp.toPx()), Size(size.width + 6.dp.toPx(), h + 6.dp.toPx()), CornerRadius(h / 2 + 3.dp.toPx()), style = Stroke(2.dp.toPx()))
            val b = if (durationMs > 0) (buffered().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            drawRoundRect(inactive, Offset(0f, y), Size(size.width, h), r)
            if (sweep != null) {
                val at = sweep.value * size.width
                val band = size.width * 0.25f
                drawRoundRect(
                    Brush.horizontalGradient(0f to sheen.copy(alpha = 0f), 0.5f to sheen, 1f to sheen.copy(alpha = 0f), startX = at - band, endX = at + band),
                    Offset(0f, y), Size(size.width, h), r,
                )
                return@Canvas
            }
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
                if (gradient) drawPath(wavePath, Brush.horizontalGradient(listOf(active, gradientEnd), endX = maxOf(size.width * f, 1f)), style = Stroke(width = h, cap = StrokeCap.Round))
                else drawPath(wavePath, active, style = Stroke(width = h, cap = StrokeCap.Round))
            } else if (gradient) {
                drawRoundRect(Brush.horizontalGradient(listOf(active, gradientEnd), endX = maxOf(size.width * f, 1f)), Offset(0f, y), Size(size.width * f, h), r)
            } else {
                drawRoundRect(active, Offset(0f, y), Size(size.width * f, h), r)
            }
            // A small dot where each part of the song still to come begins. Ones already
            // played aren't drawn: cut into the played line (the wave above all) they read as breaks.
            if (durationMs > 0) {
                val played = size.width * f
                val clear = maxOf(thumbRadius.toPx(), h) + 2.dp.toPx()
                shownMarks.forEach { ms ->
                    val x = size.width * (ms.toFloat() / durationMs)
                    if (x > played + clear && x < size.width - h) drawCircle(markDot, h * 0.32f, Offset(x, size.height / 2))
                }
            }
            if (thumbRadius > 0.dp) {
                val at = Offset(size.width * f, size.height / 2)
                val thumbColor = if (gradient) gradientEnd else active
                if (lineThumb) {
                    // A short upright bar across the track, rounded at the ends.
                    val w = 3.dp.toPx()
                    val tall = thumbRadius.toPx() * 2.6f
                    drawRoundRect(thumbColor, Offset(at.x - w / 2f, at.y - tall / 2f), Size(w, tall), CornerRadius(w / 2f))
                } else {
                    drawCircle(thumbColor, thumbRadius.toPx(), at)
                }
            }
        }
        }
        if (fineScrubbing) Text("Fine scrubbing · ⅕ speed", style = MaterialTheme.typography.labelSmall)
        val elapsedSeconds by remember(durationMs) {
            derivedStateOf { ((dragFraction?.times(durationMs)?.toLong() ?: position()) / 1000) }
        }
        if (look.seekbarTime != com.opentune.data.settings.SeekbarTime.HIDDEN) {
            val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = LocalDensity.current
            // Reserve identical, stable side columns using the whole track's length.
            // Seconds changing width must never push the quality label sideways.
            val timeWidth = remember(durationMs, timeStyle, density) {
                val sample = if (durationMs > 0) "-" + formatTime(durationMs) else "--:--"
                with(density) { (measurer.measure(sample, timeStyle).size.width + 8.dp.roundToPx()).toDp() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "--:--" else formatTime(elapsedSeconds * 1000), modifier = Modifier.width(timeWidth),
                    style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                // Raised a little, closer to the bar it describes than the times beside it.
                quality?.let { Box(Modifier.weight(1f).offset(y = (-14).dp), contentAlignment = Alignment.Center) { it() } }
                Text(
                    when {
                        durationMs <= 0 -> "--:--"
                        look.seekbarTime == com.opentune.data.settings.SeekbarTime.TOTAL -> formatTime(durationMs)
                        else -> "-" + formatTime((durationMs - elapsedSeconds * 1000).coerceAtLeast(0))
                    },
                    modifier = Modifier.width(timeWidth),
                    style = timeStyle,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (look.seekbarTime == com.opentune.data.settings.SeekbarTime.HIDDEN) quality?.let { Box(Modifier.fillMaxWidth().offset(y = (-6).dp), contentAlignment = Alignment.Center) { it() } }
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
    /**
     * A still copy drawn inside the shrinking player card (see PlayerSheet):
     * the same look, with no gestures, a solid face instead of glass, and
     * not registered as where the mini player is.
     */
    replica: Boolean = false,
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    // The cover's own colour, faded into the glass, so each song tints the card.
    val seed = rememberArtworkSeed(song.thumbnailUrl)
    val colourFromCover = com.opentune.data.settings.AppSettings.ui.collectAsState().value.miniPlayerTint
    val tint by animateColorAsState(((if (colourFromCover) seed else null) ?: MaterialTheme.colorScheme.surfaceContainerHigh).copy(alpha = 0.38f), tween(600), label = "miniTint")

    val solid = MaterialTheme.colorScheme.surfaceContainerHigh
    val replicaFace = androidx.compose.ui.graphics.lerp(solid, tint.copy(alpha = 1f), tint.alpha)
    val landing: Modifier = if (replica) Modifier else with(com.opentune.ui.components.PlayerMorph) { Modifier.miniLanding().hiddenWhilePulled() }
    if (inline) {
        Box(
            modifier
                .then(
                    if (replica) Modifier.clip(CircleShape).background(replicaFace)
                    else with(com.opentune.ui.components.PlayerMorph) { Modifier.miniBarLanding(round = true).miniUnderCard() }
                        .glass(CircleShape, tint)
                        .pullToOpen(round = true, onOpen = onClick)
                        .clickable(onClick = onClick),
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProgressRing(progress, accent, track, stroke = 3.dp, modifier = Modifier.matchParentSize())
            Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.padding(4.dp).fillMaxSize().then(landing), CircleShape)
            if (isBuffering) CircularProgressIndicator(Modifier.matchParentSize(), strokeWidth = 3.dp, color = accent)
        }
        return
    }

    val shape = RoundedCornerShape(22.dp)
    if (replica) {
        Box(modifier.clip(shape).background(replicaFace)) { MiniRow(song, isPlaying, isBuffering, hasNext, progress, accent, track, {}, {}, landing) }
        return
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = modifier
            .then(with(com.opentune.ui.components.PlayerMorph) { Modifier.miniBarLanding(round = false).miniUnderCard() })
            .graphicsLayer { translationX = offsetX.value }
            .glass(shape, tint)
            .pullToOpen(round = false, onOpen = onClick)
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
        MiniRow(song, isPlaying, isBuffering, hasNext, progress, accent, track, onTogglePlay, onNext, landing)
    }
}

/** The mini player's row: cover, title and artist, play in its progress ring, next. */
@Composable
private fun MiniRow(
    song: Song, isPlaying: Boolean, isBuffering: Boolean, hasNext: Boolean, progress: () -> Float,
    accent: Color, track: Color, onTogglePlay: () -> Unit, onNext: () -> Unit, landing: Modifier,
) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(song, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "mini", modifier = Modifier.weight(1f)) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(s.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(44.dp).then(landing), RoundedCornerShape(12.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        com.opentune.ui.components.SubtitleLine(s.artist, explicit = s.isExplicit == true, style = MaterialTheme.typography.bodySmall)
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

/**
 * Swiping the mini player up opens the player, the cover growing out of it
 * with the finger as it goes (see [com.opentune.ui.components.PlayerMorph.pullOpen]).
 * Far enough and it opens, the flight finishing from there; short of that it
 * settles back. With motion reduced it just opens past the line.
 */
@Composable
private fun Modifier.pullToOpen(round: Boolean, onOpen: () -> Unit): Modifier {
    val reduce = com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceAnimation
    val scope = rememberCoroutineScope()
    val back = remember { Animatable(0f) }
    val density = LocalDensity.current
    val screenH = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val openPx = with(density) { 90.dp.toPx() }
    val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
    val morph = com.opentune.ui.components.PlayerMorph
    // Read and written on the gesture itself, never from a launched step: a
    // step landing after the finger lifts would pull the cover back up once
    // the player has started opening, and leave it hanging there.
    var pulled by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    fun settle(from: Float) = scope.launch {
        back.snapTo(from)
        back.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f)) { if (!reduce) morph.pullOpen(value, round, screenH) }
        morph.endPull()
    }
    // Taken away mid-pull (the player opened over it, the dock folded): let go of the cover.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { morph.endPull() } }
    return pointerInput(round, reduce) {
        detectVerticalDragGestures(
            onDragStart = { pulled = 0f; scope.launch { back.stop() } },
            onDragEnd = {
                val far = pulled
                pulled = 0f
                if (far >= openPx || morph.pulling >= 0.25f) open() else settle(far)
            },
            onDragCancel = { settle(pulled).also { pulled = 0f } },
        ) { change, dy ->
            change.consume()
            pulled = (pulled - dy).coerceAtLeast(0f)
            if (!reduce) morph.pullOpen(pulled, round, screenH)
        }
    }
}

/** The mini player's cover steps aside while the flying copy of it is pulled up. */
private fun Modifier.hiddenWhilePulled(): Modifier = graphicsLayer {
    // When the whole player is pulled up instead, the mini player keeps its cover under the card.
    alpha = if (com.opentune.ui.components.PlayerMorph.pulling > 0f && !com.opentune.ui.components.PlayerMorph.sheetMorph) 0f else 1f
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

/** One line naming where the song is playing from, as a plain label, or "Now playing" when there isn't one. */
@Composable
fun SongStatus(source: String?) {
    Text(
        if (source.isNullOrBlank()) "Now playing" else "Playing from $source",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

/** Codec, bitrate, sample rate and channels of what's actually playing. */
@Composable
fun NerdStatsLine(format: AudioFormatInfo?) {
    val picked by NerdStats.lastPicked.collectAsState()
    val startup by NerdStats.startupMs.collectAsState()
    val gain by NerdStats.loudnessGainDb.collectAsState()
    val engine by NerdStats.engine.collectAsState()
    val lossless by NerdStats.externalSource.collectAsState()
    val kbps = format?.bitrateKbps ?: picked?.second.takeIf { lossless == null }
    val parts = listOfNotNull(
        format?.codec,
        kbps?.let { "$it kbps" },
        format?.sampleRateHz?.let { "%.1f kHz".format(it / 1000f) },
        format?.channels?.let { if (it == 2) "stereo" else if (it == 1) "mono" else "$it ch" },
        gain?.let { "normalized %+.1f dB".format(it) },
        startup?.let { "started in $it ms" },
        (lossless ?: engine)?.let { "via $it" },
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

/** How far left or right on a remote moves the song: 10 seconds, or a fiftieth of a long one. */
internal fun seekStepMs(key: Key, durationMs: Long): Long? {
    if (durationMs <= 0) return null
    val step = maxOf(10_000L, durationMs / 50)
    return when (key) {
        Key.DirectionRight, Key.MediaFastForward -> step
        Key.DirectionLeft, Key.MediaRewind -> -step
        else -> null
    }
}
