package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.foundation.layout.offset
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PlayerBackground
import com.opentune.data.settings.SeekbarColor
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.components.FocusRing
import com.opentune.ui.components.glass
import com.opentune.ui.player.PlayerControls
import com.opentune.ui.player.PlayerIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the song is, and how long it is. */
internal class Progress(val positionMs: Long, val durationMs: Long) {
    fun fraction(): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Where the song is, read four times a second. */
@Composable
internal fun rememberProgress(song: Song): Progress {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            duration = Player.durationMs()
            delay(250)
        }
    }
    return Progress(position, duration)
}

/** A cover filling [modifier]'s box, cropped square, with the phone's grey while it loads. */
@Composable
internal fun Artwork(url: String?, modifier: Modifier, shape: Shape) {
    val context = LocalPlatformContext.current
    AsyncImage(
        model = remember(url) { ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).crossfade(260).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** What the full player shows beside its cover (or, in a narrow window, in its place). */
internal enum class PlayerPanel { QUEUE, LYRICS }

/** The panel the full player showed last, so it opens again as it was left. */
private var playerLastPanel: PlayerPanel? = PlayerPanel.QUEUE

/**
 * The full player, as the reference lays it out: the cover blurred over the
 * whole window behind; in a wide window the cover in the left half with the
 * names, seek bar and transport under it and the control row at its foot
 * (lyrics, a joined pill of toggles, the queue, and the output device under
 * the pill), and the queue or the lyrics in the right half. A narrow window
 * stacks it: the cover (or the queue or lyrics in its place) over the rest.
 * [docked] is the narrow pane beside the pages. [onOpenTogether] opens the
 * Listen together page; [startPanel] and [lyricsOverride] are for pictures.
 */
@Composable
internal fun NowPlaying(
    song: Song,
    docked: Boolean,
    onClose: () -> Unit,
    onOpenTogether: (() -> Unit)? = null,
    startPanel: PlayerPanel? = playerLastPanel,
    lyricsOverride: LyricsState? = null,
) {
    var panel by remember { mutableStateOf(startPanel) }
    val show: (PlayerPanel?) -> Unit = { panel = it; playerLastPanel = it }
    // L and Q switch the player's own panel while it's open.
    LaunchedEffect(Unit) {
        Shortcuts.commands.collect { c ->
            when (c) {
                Shortcuts.Command.LYRICS -> show(if (panel == PlayerPanel.LYRICS) null else PlayerPanel.LYRICS)
                Shortcuts.Command.QUEUE -> show(if (panel == PlayerPanel.QUEUE) null else PlayerPanel.QUEUE)
                else -> Unit
            }
        }
    }
    val playing by Player.playing.collectAsState()
    val theme by AppSettings.theme.collectAsState()
    val reveal = LocalPlayerReveal.current
    PlayerTheme(seed = rememberArtworkSeed(song.thumbnailUrl)) {
        // Its own ink: white over the dark backdrop, whatever the window around it is, and greys
        // that are a softer white rather than the cover's tint.
        val base = MaterialTheme.colorScheme
        val inks = remember(base) { base.copy(onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = 0.68f)) }
        MaterialTheme(colorScheme = inks, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {}) {
            PlayerBackdrop(theme.playerBackground, song.thumbnailUrl, playing, Modifier.matchParentSize())
            // Over the sidebar's head too, so the window can still be moved with the player open.
            if (!docked) Box(Modifier.fillMaxWidth().height(36.dp).windowDrag())
            BoxWithConstraints(
                Modifier.fillMaxSize().graphicsLayer {
                    // A beat behind the sheet as it rises: a little lower, and fading in.
                    val r = reveal()
                    alpha = ((r - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    translationY = (1f - r) * 48.dp.toPx()
                },
            ) {
                val wide = !docked && maxWidth >= 900.dp && maxWidth >= maxHeight * 0.9f
                if (wide) PlayerWide(song, playing, panel, show, onOpenTogether, lyricsOverride, maxWidth)
                else PlayerNarrow(song, playing, panel, show, onOpenTogether, lyricsOverride, docked)
                HoverIcon(
                    if (docked) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.ExpandMore, if (docked) "Hide player" else "Close player",
                    size = 40.dp, iconSize = 26.dp,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = DesktopWindow.topInset + 10.dp),
                    onClick = onClose,
                )
            }
        }
        }
        }
    }
}

/** The cover's half and the panel's half, the cover's sliding to the middle when no panel is chosen. */
@Composable
private fun PlayerWide(
    song: Song,
    playing: Boolean,
    panel: PlayerPanel?,
    onPanel: (PlayerPanel?) -> Unit,
    onOpenTogether: (() -> Unit)?,
    lyricsOverride: LyricsState?,
    width: Dp,
) {
    val ui by AppSettings.ui.collectAsState()
    val half = width / 2
    val top = DesktopWindow.topInset + 48.dp
    val bottom = 28.dp
    val split by animateFloatAsState(
        if (panel != null) 1f else 0f, if (ui.reduceAnimation) androidx.compose.animation.core.snap() else tween(420, easing = Motion.Emphasized), label = "player split",
    )
    Box(Modifier.width(half).fillMaxHeight().graphicsLayer { translationX = (1f - split) * (half / 2).toPx() }) {
        PlayerCoverColumn(song, playing, panel, onPanel, onOpenTogether, Modifier.fillMaxSize().padding(top = top, bottom = bottom, start = 40.dp, end = 40.dp))
    }
    val edge = (width * 0.05f).coerceIn(32.dp, 96.dp)
    Box(
        Modifier.offset(x = half + 40.dp).width((half - 40.dp - edge).coerceIn(200.dp, 760.dp)).fillMaxHeight()
            .padding(top = top - 12.dp, bottom = bottom - 8.dp),
    ) { PlayerPanelSwap(song, panel, lyricsOverride, Modifier.fillMaxSize()) }
}

/** A narrow window: the cover, or the panel chosen in its place, over the names and controls. */
@Composable
private fun PlayerNarrow(
    song: Song,
    playing: Boolean,
    panel: PlayerPanel?,
    onPanel: (PlayerPanel?) -> Unit,
    onOpenTogether: (() -> Unit)?,
    lyricsOverride: LyricsState?,
    docked: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(top = DesktopWindow.topInset + 52.dp, bottom = 16.dp).padding(horizontal = if (docked) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PlayerPanelSwap(song, panel, lyricsOverride, Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerCover(song, playing, minOf(maxWidth, maxHeight - 16.dp).coerceIn(120.dp, 560.dp))
                }
            }
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            // The queue names the song itself, so it gets the room the title would take.
            if (panel != PlayerPanel.QUEUE) PlayerTitleRow(song) else Spacer(Modifier.height(8.dp))
            PlayerSeekRow(song)
            PlayerTransport(song)
        }
        Spacer(Modifier.height(10.dp))
        PlayerControlRow(panel, onPanel, onOpenTogether)
    }
}

/**
 * The queue or the lyrics, the one coming in fading in with a small rise
 * over 220ms as the other fades away; [none] is what shows with neither.
 */
@Composable
private fun PlayerPanelSwap(
    song: Song,
    panel: PlayerPanel?,
    lyricsOverride: LyricsState?,
    modifier: Modifier,
    none: @Composable () -> Unit = {},
) {
    val ui by AppSettings.ui.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val lift = with(LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedContent(
        panel,
        transitionSpec = {
            if (ui.reduceAnimation) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
            else (fadeIn(tween(220, easing = Motion.Panel)) + androidx.compose.animation.slideInVertically(tween(220, easing = Motion.Panel)) { lift }) togetherWith
                (fadeOut(tween(150, easing = Motion.Exit)) + androidx.compose.animation.slideOutVertically(tween(150, easing = Motion.Exit)) { -lift / 2 })
        },
        label = "player panel",
        modifier = modifier,
    ) { p ->
        when (p) {
            PlayerPanel.QUEUE -> PlayerQueuePanel(queue, index, Modifier.fillMaxSize())
            PlayerPanel.LYRICS -> PlayerLyricsPanel(song, Modifier.fillMaxSize(), lyricsOverride)
            null -> Box(Modifier.fillMaxSize()) { none() }
        }
    }
}

/** The cover's half: the cover as large as the height allows, the names, seek bar and transport, and the control row at the foot. */
@Composable
private fun PlayerCoverColumn(
    song: Song,
    playing: Boolean,
    panel: PlayerPanel?,
    onPanel: (PlayerPanel?) -> Unit,
    onOpenTogether: (() -> Unit)?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        // What the names, seek bar, transport and control row take under the cover.
        val below = 300.dp
        val side = minOf(maxWidth, maxHeight - below).coerceIn(160.dp, 640.dp)
        val column = side.coerceIn(minOf(420.dp, maxWidth), maxWidth)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            PlayerCover(song, playing, side)
            Column(Modifier.width(column)) {
                PlayerTitleRow(song)
                PlayerSeekRow(song)
                PlayerTransport(song)
            }
            Spacer(Modifier.weight(0.6f))
            Spacer(Modifier.height(8.dp))
            PlayerControlRow(panel, onPanel, onOpenTogether)
        }
    }
}

/**
 * Behind the player. Cover blur (the default), as the reference has it: the
 * cover scaled to fill the window, heavily blurred and a little darkened,
 * cross-fading to the next song's. The others as the phone draws them: true
 * black; the cover's colour from the top, deepening by the middle; or that
 * gradient under a slow, softly lit blur of the cover.
 */
@Composable
private fun PlayerBackdrop(style: PlayerBackground, cover: String?, playing: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (style == PlayerBackground.AMOLED) {
        Box(modifier.background(Color.Black))
        return
    }
    if (style == PlayerBackground.BLUR) {
        val drift = rememberDrift(playing)
        Box(modifier.background(lerp(Color.Black, scheme.primaryContainer, 0.35f))) {
            AnimatedContent(cover, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "backdrop", modifier = Modifier.matchParentSize()) { url ->
                SoftCover(url, strength = 1f, px = 64, radius = 2, zoom = 1.18f, drift = drift)
            }
            // Darkened enough for white text, a little more toward the foot where the controls are.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.34f), 0.6f to Color.Black.copy(alpha = 0.4f), 1f to Color.Black.copy(alpha = 0.52f)),
                ),
            )
        }
        return
    }
    Box(modifier.background(scheme.surface)) {
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
        if (style != PlayerBackground.GRADIENT) {
            // The cover itself, huge and soft, drifting while the music plays.
            val drift = rememberDrift(playing)
            AnimatedContent(cover, transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) }, label = "backdrop", modifier = Modifier.matchParentSize()) { url ->
                SoftCover(url, drift = drift)
            }
            // Darker toward the bottom so the title and controls stay readable.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.1f), 0.5f to Color.Black.copy(alpha = 0.3f), 1f to Color.Black.copy(alpha = 0.62f)),
                ),
            )
        }
    }
}

/**
 * The cover as a soft wash of its colours: a small copy, softened on the
 * CPU and stretched smoothly over the player, drifting with [drift]. It looks
 * like a heavy blur of the cover but costs almost nothing a frame, where a
 * real blur of a window-sized image every step made the player stutter.
 */
@Composable
internal fun SoftCover(
    url: String?,
    strength: Float = 0.6f,
    px: Int = SOFT_PX,
    radius: Int = 5,
    zoom: Float = 1.35f,
    drift: () -> Float,
) {
    val context = LocalPlatformContext.current
    val painter = coil3.compose.rememberAsyncImagePainter(
        remember(url) { ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).size(PLAYER_SOFT_REQUEST).build() },
    )
    val state by painter.state.collectAsState()
    val loaded = state is coil3.compose.AsyncImagePainter.State.Success
    // Fades in once there, rather than popping in, if it wasn't in memory already.
    val shown by animateFloatAsState(if (loaded) 1f else 0f, tween(420), label = "soft cover")
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val t = drift()
                val s = zoom + 0.06f * zoom * kotlin.math.sin(t * 0.21f)
                scaleX = s; scaleY = s
                translationX = size.width * 0.05f * kotlin.math.sin(t * 0.13f)
                translationY = size.height * 0.05f * kotlin.math.cos(t * 0.11f)
                alpha = strength * shown
            }
            .drawWithCache {
                val small = androidx.compose.ui.graphics.ImageBitmap(px, px)
                if (loaded) {
                    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                        this, layoutDirection, androidx.compose.ui.graphics.Canvas(small), Size(px.toFloat(), px.toFloat()),
                    ) { with(painter) { draw(size) } }
                    soften(small, radius = radius)
                }
                val side = maxOf(size.width, size.height)
                onDrawBehind {
                    if (!loaded) return@onDrawBehind
                    drawImage(
                        small,
                        dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - side) / 2).toInt(), ((size.height - side) / 2).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    )
                }
            },
    )
}

/** How many pixels across the cover is drawn before it's stretched over the player. */
private const val SOFT_PX = 32

/** The size the soft cover is asked for at, shared with the full player's preload. */
internal const val PLAYER_SOFT_REQUEST = 96

/**
 * A box blur, three passes along rows then columns (close to a Gaussian),
 * run in place over [image]'s pixels, so even a cover split hard down the
 * middle comes out as one gradual fade.
 */
internal fun soften(image: androidx.compose.ui.graphics.ImageBitmap, radius: Int = 5, passes: Int = 3) {
    val bitmap = image.asSkiaBitmap()
    val side = bitmap.width
    if (bitmap.height != side) return
    val bytes = bitmap.readPixels() ?: return
    val tmp = ByteArray(bytes.size)
    fun pass(src: ByteArray, dst: ByteArray, step: Int, line: Int) {
        val n = radius * 2 + 1
        for (l in 0 until side) for (i in 0 until side) {
            for (c in 0 until 4) {
                var sum = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, side - 1)
                    sum += src[(l * line + j * step) * 4 + c].toInt() and 0xFF
                }
                dst[(l * line + i * step) * 4 + c] = (sum / n).toByte()
            }
        }
    }
    repeat(passes) {
        pass(bytes, tmp, step = 1, line = side)
        pass(tmp, bytes, step = side, line = 1)
    }
    bitmap.installPixels(bitmap.imageInfo, bytes, bitmap.rowBytes)
}

/** Seconds of drift that run while [playing] and hold still while paused. */
@Composable
private fun rememberDrift(playing: Boolean): () -> Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { now ->
            // A dozen steps a second is plenty for something this slow.
            if (now - last >= 80_000_000L) {
                t += (now - last) / 1e9f
                last = now
            }
        }
    }
    return { t }
}

/**
 * The cover at [side], rounded 8dp over a soft shadow. It eases to 95% while
 * paused, and a new song's comes in with a fade from 94%.
 */
@Composable
private fun PlayerCover(song: Song, playing: Boolean, side: Dp) {
    val ui by AppSettings.ui.collectAsState()
    val scale by animateFloatAsState(
        if (playing) 1f else 0.95f, if (ui.reduceAnimation) androidx.compose.animation.core.snap() else tween(450, easing = Motion.Settle), label = "coverScale",
    )
    AnimatedContent(
        song.videoId,
        transitionSpec = {
            if (ui.reduceAnimation) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
            else (fadeIn(tween(450, easing = Motion.Emphasized)) + scaleIn(tween(450, easing = Motion.Emphasized), 0.94f)) togetherWith fadeOut(tween(200))
        },
        label = "cover",
    ) { _ ->
        val shape = RoundedCornerShape(8.dp)
        Artwork(
            song.thumbnailUrl,
            Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.55f)),
            shape,
        )
    }
}

/** The title and artist under the cover, with the heart and More; a new song's come in with a short rise. */
@Composable
private fun PlayerTitleRow(song: Song) {
    val error by Player.error.collectAsState()
    val liked by Library.liked.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val rise = with(LocalDensity.current) { 6.dp.toPx() }.toInt()
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            song.videoId,
            transitionSpec = {
                if (ui.reduceAnimation) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                else (fadeIn(tween(280, easing = Motion.Emphasized)) + androidx.compose.animation.slideInVertically(tween(280, easing = Motion.Emphasized)) { rise }) togetherWith fadeOut(tween(120))
            },
            label = "title",
            modifier = Modifier.weight(1f),
        ) { _ ->
            Column {
                Text(
                    song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
                )
                Text(
                    error ?: song.artist,
                    fontSize = 16.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (error != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        HeartButton(isLiked) { Library.setLiked(song, !isLiked) }
        Spacer(Modifier.width(8.dp))
        MoreButton(song)
    }
}

/** The seek bar, with where the song is, how it's playing and what's left under it. */
@Composable
private fun PlayerSeekRow(song: Song) {
    val quality by Player.quality.collectAsState()
    val progress = rememberProgress(song)
    SeekBar(progress, Modifier.padding(top = 6.dp))
    Box(Modifier.fillMaxWidth()) {
        val times = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontFeatureSettings = "tnum")
        val grey = Color.White.copy(alpha = 0.6f)
        Text(Time.format(progress.positionMs), style = times, color = grey, modifier = Modifier.align(Alignment.CenterStart))
        QualityLabel(quality, Modifier.align(Alignment.Center))
        Text(
            "-" + Time.format((progress.durationMs - progress.positionMs).coerceAtLeast(0)), style = times,
            color = grey, modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** The sleep timer at the left, previous, play and next in the chosen design, and the volume at the right. */
@Composable
private fun PlayerTransport(song: Song) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val progress = rememberProgress(song)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { PlayerSleepButton() }
        PlayerControls(
            isPlaying = playing,
            isBuffering = loading,
            hasNext = index < queue.lastIndex,
            onTogglePlay = Player::toggle,
            onNext = Player::next,
            onPrevious = Player::previous,
            modifier = Modifier.width(236.dp),
            style = ui.controlStyle,
            animate = !ui.reduceAnimation,
            progress = progress::fraction,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { PlayerVolume() }
    }
}

/** The sleep timer: a moon, lit while it runs with the time left beside it, and its choices in a menu. */
@Composable
private fun PlayerSleepButton() {
    val sleep by Player.sleep.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            HoverIcon(Icons.Rounded.Bedtime, "Sleep timer", lit = sleep != null, litColour = Color.White, size = 40.dp, iconSize = 21.dp) { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 300.dp)) { SleepChips(sleep) }
            }
        }
        val left = when (val s = sleep) {
            is Player.Sleep.At -> {
                var ms by remember { mutableLongStateOf(0L) }
                LaunchedEffect(s) { while (true) { ms = s.epochMs - System.currentTimeMillis(); delay(1_000) } }
                Time.format(ms.coerceAtLeast(0))
            }
            Player.Sleep.EndOfSong -> "End of song"
            null -> null
        }
        if (left != null) Text(
            left, color = Color.White.copy(alpha = 0.7f), maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** The volume: a speaker that mutes, and a short line to drag or scroll. */
@Composable
private fun PlayerVolume() {
    val volume by Player.volume.collectAsState()
    var level by remember { mutableFloatStateOf(volume.toFloat()) }
    LaunchedEffect(volume) { level = volume.toFloat() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.volumeWheel()) {
        HoverIcon(
            when {
                level <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeOff
                level < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            },
            if (level <= 0.001f) "Unmute" else "Mute", size = 36.dp, iconSize = 20.dp,
        ) { Player.toggleMute() }
        LineBar(
            level, { level = it; Player.setVolume(it.toDouble()) }, Color.White, Color.White.copy(alpha = 0.2f),
            Modifier.width(84.dp).padding(start = 4.dp),
        )
    }
}

/**
 * The row at the foot of the cover: lyrics in a 56dp circle, a pill of
 * joined toggles (shuffle, repeat and AutoPlay with the queue; the output
 * device and Listen together with the lyrics) with the device playing under
 * it, and the queue in a circle. A circle is filled while its panel shows.
 */
@Composable
private fun PlayerControlRow(panel: PlayerPanel?, onPanel: (PlayerPanel?) -> Unit, onOpenTogether: (() -> Unit)?) {
    val device by AudioDevices.selected.collectAsState()
    val lyrics = panel == PlayerPanel.LYRICS
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PlayerRoundToggle(PlayerIcons.Lyrics, if (lyrics) "Hide lyrics" else "Show lyrics", on = lyrics) {
            onPanel(if (lyrics) null else PlayerPanel.LYRICS)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                lyrics,
                transitionSpec = { (fadeIn(tween(220, easing = Motion.Panel)) togetherWith fadeOut(tween(120))).using(androidx.compose.animation.SizeTransform(clip = false)) },
                contentAlignment = Alignment.Center,
                label = "player pill",
            ) { l ->
                PlayerPill {
                    if (l) {
                        PlayerDevicesButton()
                        PlayerPillDivider()
                        PlayerTogetherButton(onOpenTogether)
                    } else {
                        val shuffled by Player.shuffled.collectAsState()
                        val repeat by Player.repeat.collectAsState()
                        val playback by AppSettings.playback.collectAsState()
                        PlayerPillButton(Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", shuffled, Player::toggleShuffle)
                        PlayerPillDivider()
                        PlayerPillButton(
                            if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
                            repeat != Player.Repeat.OFF, Player::cycleRepeat,
                        )
                        PlayerPillDivider()
                        PlayerPillButton(Icons.Rounded.AllInclusive, if (playback.autoplay) "AutoPlay on" else "AutoPlay", playback.autoplay) {
                            AppSettings.setAutoplay(!playback.autoplay)
                        }
                    }
                }
            }
            Text(
                device.ifBlank { "System default" }, color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 240.dp),
            )
        }
        val queue = panel == PlayerPanel.QUEUE
        PlayerRoundToggle(Icons.AutoMirrored.Rounded.QueueMusic, if (queue) "Hide queue" else "Show queue", on = queue) {
            onPanel(if (queue) null else PlayerPanel.QUEUE)
        }
    }
}

/** A 56dp round button, filled a soft white while [on]. */
@Composable
private fun PlayerRoundToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(
        Color.White.copy(alpha = when { on && hovered -> 0.2f; on -> 0.15f; hovered -> 0.08f; else -> 0f }), tween(180), label = "round toggle",
    )
    Box(
        Modifier.size(56.dp).pressGive(hover).clip(CircleShape).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(CircleShape), role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White.copy(alpha = if (on || hovered) 1f else 0.78f), modifier = Modifier.size(24.dp)) }
}

/** Two or three buttons joined in one 56dp pill on a soft white, thin lines between them. */
@Composable
private fun PlayerPill(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.12f)),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun PlayerPillDivider() {
    Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.16f)))
}

/** A button in the pill: white on a small soft disc when [active], grey when not. */
@Composable
private fun PlayerPillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val wash by animateColorAsState(Color.White.copy(alpha = if (hovered) 0.08f else 0f), tween(150), label = "pill hover")
    val disc by animateFloatAsState(if (active) 1f else 0f, tween(200, easing = Motion.Settle), label = "pill on")
    Box(
        Modifier.size(64.dp, 56.dp).background(wash).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(RoundedCornerShape(28.dp)), role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).graphicsLayer { alpha = disc; scaleX = 0.7f + 0.3f * disc; scaleY = 0.7f + 0.3f * disc }.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
        Icon(icon, label, tint = Color.White.copy(alpha = if (active) 1f else if (hovered) 0.85f else 0.58f), modifier = Modifier.size(22.dp))
    }
}

/** Where the sound goes: headphones, with the system's default and this computer's outputs in a menu read afresh each time. */
@Composable
private fun PlayerDevicesButton() {
    val selected by AudioDevices.selected.collectAsState()
    var open by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<AudioDevices.Device>()) }
    Box {
        PlayerPillButton(Icons.Rounded.Headphones, "Output device", selected.isNotBlank()) { devices = AudioDevices.available(); open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp, max = 360.dp)) {
            Text(
                "Play on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val choices = listOf(AudioDevices.Device("", "")) + devices.filter { it.name.isNotBlank() }
            choices.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(d.name.ifBlank { "System default" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            d.description.takeIf { it.isNotBlank() && it != d.name }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    leadingIcon = { if (d.name == selected) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; AudioDevices.select(d.name) },
                )
            }
        }
    }
}

/**
 * Listen together: a person, lit while in a room. Its menu starts a room or
 * goes to the page to join one; in a room, it says how it's going, copies the
 * invite and leaves.
 */
@Composable
private fun PlayerTogetherButton(onOpenTogether: (() -> Unit)?) {
    val room by com.opentune.data.together.Together.room.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        PlayerPillButton(if (room != null) Icons.Rounded.Groups else Icons.Rounded.PersonAdd, "Listen together", room != null) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            val r = room
            val grey = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Listen together", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
            if (r == null) {
                Text(
                    "Friends hear what you play, in step.", style = MaterialTheme.typography.bodySmall, color = grey,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                )
                val name = com.opentune.data.together.Together.savedName.ifBlank { com.opentune.data.account.AccountStore.account.value?.name.orEmpty() }
                if (name.isNotBlank()) DropdownMenuItem(
                    text = { Text("Start a room") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) },
                    onClick = { open = false; com.opentune.data.together.Together.create(name); Notice.show("Room started") },
                )
                if (onOpenTogether != null) DropdownMenuItem(
                    text = { Text(if (name.isBlank()) "Start or join a room" else "Join a friend's room") }, leadingIcon = { Icon(Icons.Rounded.Groups, null) },
                    onClick = { open = false; onOpenTogether() },
                )
            } else {
                val people = if (r.members.size == 1) "1 person" else "${r.members.size} people"
                Text(
                    "${if (r.hosting) "Hosting" else "Listening with ${r.hostName ?: "the host"}"} · $people · ${r.code.pretty}",
                    style = MaterialTheme.typography.bodySmall, color = grey,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                )
                DropdownMenuItem(
                    text = { Text("Copy invite link") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { open = false; copyText(r.code.link); Notice.show("Invite link copied") },
                )
                if (onOpenTogether != null) DropdownMenuItem(
                    text = { Text("Open the room") }, leadingIcon = { Icon(Icons.Rounded.Groups, null) },
                    onClick = { open = false; onOpenTogether() },
                )
                DropdownMenuItem(
                    text = { Text(if (r.hosting) "End the room" else "Leave") }, leadingIcon = { Icon(Icons.Rounded.Close, null) },
                    onClick = { open = false; com.opentune.data.together.Together.leave() },
                )
            }
        }
    }
}

/**
 * The phone's seek bar: a rounded track that thickens while it's dragged,
 * the played part in the accent (or white, or a gradient, as chosen), a dot
 * riding it, and a band of light running along it until the length is known.
 */
@Composable
private fun SeekBar(progress: Progress, modifier: Modifier = Modifier) {
    val look by AppSettings.ui.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val base = look.seekbarThickness.dp.dp
    val thickness by animateDpAsState(if (dragging != null) base * 2 else base, label = "track")
    val thumb by animateDpAsState(if (dragging != null) maxOf(9.dp, base + 5.dp) else maxOf(7.dp, base * 0.9f + 3.dp), label = "thumb")
    val scheme = MaterialTheme.colorScheme
    val active = if (look.seekbarColor == SeekbarColor.PLAIN) scheme.onSurface else scheme.primary
    val end = if (look.seekbarColor == SeekbarColor.GRADIENT) scheme.tertiary else active
    val inactive = scheme.onSurface.copy(alpha = 0.18f)
    val loading = progress.durationMs <= 0
    val sweep = if (loading) androidx.compose.animation.core.rememberInfiniteTransition(label = "seekLoading")
        .animateFloat(-0.3f, 1.3f, androidx.compose.animation.core.infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "sweep") else null
    val sheen = scheme.onSurface.copy(alpha = 0.55f)
    val shown by animateFloatAsState(progress.fraction(), tween(260, easing = LinearEasing), label = "seekAlong")
    Canvas(
        modifier.fillMaxWidth().height(maxOf(36.dp, base * 6f))
            .pointerInput(progress.durationMs) {
                detectTapGestures { at -> if (progress.durationMs > 0) Player.seekTo(((at.x / size.width).coerceIn(0f, 1f) * progress.durationMs).toLong()) }
            }
            .pointerInput(progress.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { if (progress.durationMs > 0) Player.seekTo((it * progress.durationMs).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, delta ->
                    change.consume()
                    dragging = ((dragging ?: 0f) + delta / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val h = thickness.toPx()
        val y = size.height / 2
        val f = dragging ?: shown
        drawLine(inactive, Offset(h / 2, y), Offset(size.width - h / 2, y), strokeWidth = h, cap = StrokeCap.Round)
        if (sweep != null) {
            val x = size.width * sweep.value
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, sheen, Color.Transparent), startX = x - size.width * 0.2f, endX = x + size.width * 0.2f),
                Offset(h / 2, y), Offset(size.width - h / 2, y), strokeWidth = h, cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val x = (size.width * f).coerceAtLeast(h / 2)
        drawLine(Brush.horizontalGradient(listOf(active, end), endX = size.width), Offset(h / 2, y), Offset(x, y), strokeWidth = h, cap = StrokeCap.Round)
        drawCircle(lerp(active, end, f), thumb.toPx(), Offset(x, y))
    }
}

/** A thin line to drag or click along, the phone's volume bar: it thickens while held. */
@Composable
private fun LineBar(value: Float, onChange: (Float) -> Unit, active: Color, track: Color, modifier: Modifier = Modifier) {
    var held by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (held) 8.dp else 5.dp, label = "lineBar")
    Canvas(
        modifier.height(32.dp)
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { held = true; onChange((it.x / size.width).coerceIn(0f, 1f)) }, onDragEnd = { held = false }, onDragCancel = { held = false }) { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val h = thickness.toPx()
        val y = size.height / 2
        drawLine(track, Offset(h / 2, y), Offset(size.width - h / 2, y), strokeWidth = h, cap = StrokeCap.Round)
        drawLine(active, Offset(h / 2, y), Offset((size.width * value).coerceAtLeast(h / 2), y), strokeWidth = h, cap = StrokeCap.Round)
    }
}

/** The heart in a round button; liking pops it and throws a small burst of hearts, as on the phone. */
@Composable
private fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still) 0.94f else 1f, tween(if (still) 0 else 140), label = "heartPress")
    val burst = remember { Animatable(1f) }
    var bursts by remember { mutableIntStateOf(0) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(bursts) {
        if (bursts == 0) return@LaunchedEffect
        launch { pop.snapTo(0.7f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
        burst.snapTo(0f)
        burst.animateTo(1f, tween(720, easing = LinearEasing))
    }
    val colors = listOf(Color(0xFFFF4D6D), Color(0xFFFF8FA3), Color(0xFFFFC2D1), MaterialTheme.colorScheme.primary)
    Box(Modifier.size(ROUND_BUTTON), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(ROUND_BUTTON).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(roundFill(interactions))
                .hoverable(interactions)
                .clickable(interactionSource = interactions, indication = FocusRing(CircleShape)) {
                    if (!liked && !still) bursts++
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Remove from Liked" else "Like",
                Modifier.size(20.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                tint = if (liked) Color(0xFFFF4D6D) else LocalContentColor.current,
            )
        }
        if (burst.value < 1f) LikeBurst(burst.value, bursts, colors, Modifier.requiredSize(150.dp))
    }
}

/** The heart's and More's size beside the title: the web's soft round buttons, a little larger for a pointer. */
private val ROUND_BUTTON = 38.dp

/** The web's on-dark fill for those buttons: a faint white, brighter under the pointer. */
@Composable
private fun roundFill(source: MutableInteractionSource): Color {
    val hovered by source.collectIsHoveredAsState()
    return animateColorAsState(Color.White.copy(alpha = if (hovered) 0.18f else 0.10f), tween(150), label = "round fill").value
}

/** Small hearts and sparks flying out from the middle at [t] (0 to 1), a little different each [seed]. */
@Composable
private fun LikeBurst(t: Float, seed: Int, colors: List<Color>, modifier: Modifier) {
    Canvas(modifier) {
        val random = java.util.Random(seed.toLong())
        val centre = Offset(size.width / 2, size.height / 2)
        val ease = 1f - (1f - t) * (1f - t)
        repeat(10) { i ->
            val angle = (i / 10f) * 2 * Math.PI.toFloat() + random.nextFloat() * 0.5f
            val reach = size.minDimension * (0.28f + random.nextFloat() * 0.16f) * ease
            val at = centre + Offset(kotlin.math.cos(angle) * reach, kotlin.math.sin(angle) * reach)
            val r = (if (i % 2 == 0) 5.dp.toPx() else 3.dp.toPx()) * (if (t < 0.4f) t / 0.4f else 1f - (t - 0.4f) / 0.6f).coerceIn(0f, 1f)
            drawCircle(colors[i % colors.size].copy(alpha = (1f - t).coerceIn(0f, 1f)), r, at)
        }
    }
}

/** More, in a round button: add the song to a playlist. */
@Composable
private fun MoreButton(song: Song) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier.size(ROUND_BUTTON).pressGive(press, to = 0.94f).clip(CircleShape).background(roundFill(press)).hoverable(press)
                .clickable(interactionSource = press, indication = FocusRing(CircleShape)) { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MoreHoriz, "More", Modifier.size(22.dp)) }
        // The same actions as a song's right-click menu, as on the web player.
        val actions = songActions(song) { adding = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, leadingIcon = { Icon(a.icon, null) }, onClick = { open = false; a.run() })
            }
        }
        AddToPlaylistMenu(song, adding) { adding = false }
    }
}
