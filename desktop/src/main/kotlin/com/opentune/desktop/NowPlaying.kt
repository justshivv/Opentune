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
import androidx.compose.ui.unit.dp
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

/**
 * The phone's player: its own dark scheme from the cover over the chosen
 * backdrop, "Playing from" at the top, the cover (smaller while paused), the
 * title with the heart and more buttons, the seek bar, the transport in the
 * chosen button design, and lyrics, volume and the sleep timer at the foot.
 * [docked] is the pane beside the pages in a wide window, where the close
 * button folds it into the strip. A full window wide enough puts the lyrics
 * beside the cover.
 */
@Composable
internal fun NowPlaying(song: Song, docked: Boolean, onClose: () -> Unit) {
    var showLyrics by remember { mutableStateOf(false) }
    val playing by Player.playing.collectAsState()
    val theme by AppSettings.theme.collectAsState()
    PlayerTheme(seed = rememberArtworkSeed(song.thumbnailUrl)) {
        // Its own ink: the player is dark whatever the window around it is.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {}) {
            PlayerBackdrop(theme.playerBackground, song.thumbnailUrl, playing, Modifier.matchParentSize())
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = if (docked) 8.dp else DesktopWindow.topInset)) {
                val beside = !docked && maxWidth >= 900.dp && maxWidth > maxHeight
                if (beside) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).fillMaxHeight().widthIn(max = 560.dp)) {
                            PlayerHeader(song, docked, onClose)
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { BigCover(song, playing) }
                            PlayerFoot(song, showLyrics = true, onLyrics = null)
                        }
                        Spacer(Modifier.width(48.dp))
                        LyricsPane(song, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = if (docked) 20.dp else 28.dp).padding(bottom = 16.dp)
                            .then(if (docked) Modifier else Modifier.widthIn(max = 560.dp)).align(Alignment.TopCenter),
                    ) {
                        PlayerHeader(song, docked, onClose)
                        AnimatedContent(
                            showLyrics,
                            transitionSpec = { (fadeIn(tween(260)) + scaleIn(tween(320, easing = FastOutSlowInEasing), 0.96f)) togetherWith fadeOut(tween(140)) },
                            label = "lyrics or cover",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) { lyrics ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (lyrics) LyricsPane(song, Modifier.fillMaxSize()) else BigCover(song, playing)
                            }
                        }
                        PlayerFoot(song, showLyrics) { showLyrics = !showLyrics }
                    }
                }
            }
        }
        }
    }
}

/**
 * Behind the player, as the phone draws each backdrop: true black; the
 * cover's colour from the top, deepening by the middle; or, for the moving
 * styles, that gradient under a slow, softly lit blur of the cover itself.
 */
@Composable
private fun PlayerBackdrop(style: PlayerBackground, cover: String?, playing: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (style == PlayerBackground.AMOLED) {
        Box(modifier.background(Color.Black))
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
                Artwork(
                    url,
                    Modifier.fillMaxSize().graphicsLayer {
                        val t = drift()
                        val s = 1.35f + 0.08f * kotlin.math.sin(t * 0.21f)
                        scaleX = s; scaleY = s
                        translationX = size.width * 0.05f * kotlin.math.sin(t * 0.13f)
                        translationY = size.height * 0.05f * kotlin.math.cos(t * 0.11f)
                        alpha = 0.55f
                        renderEffect = androidx.compose.ui.graphics.BlurEffect(90f, 90f, androidx.compose.ui.graphics.TileMode.Clamp)
                    },
                    androidx.compose.ui.graphics.RectangleShape,
                )
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

/** The close button, and where the song is playing from, as the phone's player opens. */
@Composable
private fun PlayerHeader(song: Song, docked: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(if (docked) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.ExpandMore, if (docked) "Hide player" else "Close", Modifier.size(30.dp))
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PLAYING FROM", style = MaterialTheme.typography.labelSmall, letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 1.6f,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                song.albumName?.takeIf { it.isNotBlank() } ?: "Up next", style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The cover, as large as fits, easing a little smaller while paused and crossfading between songs. */
@Composable
private fun BigCover(song: Song, playing: Boolean) {
    val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "coverScale")
    BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        AnimatedContent(
            song.videoId,
            transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(420, easing = FastOutSlowInEasing), 0.94f)) togetherWith (fadeOut(tween(200)) + scaleOut(tween(300), 1.03f)) },
            label = "cover",
        ) { _ ->
            val shape = RoundedCornerShape(24.dp)
            Artwork(
                song.thumbnailUrl,
                Modifier.size(side).aspectRatio(1f).graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f)),
                shape,
            )
        }
    }
}

/** Everything under the cover: title row, seek bar, transport and the foot row. */
@Composable
private fun PlayerFoot(song: Song, showLyrics: Boolean, onLyrics: (() -> Unit)?) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val error by Player.error.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val liked by Library.liked.collectAsState()
    val quality by Player.quality.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val progress = rememberProgress(song)
    val isLiked = liked.any { it.videoId == song.videoId }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                song.videoId,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                label = "title",
                modifier = Modifier.weight(1f),
            ) { _ ->
                Column {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        error ?: song.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            HeartButton(isLiked) { Library.setLiked(song, !isLiked) }
            Spacer(Modifier.width(10.dp))
            MoreButton(song)
        }
        SeekBar(progress, Modifier.padding(top = 14.dp))
        Box(Modifier.fillMaxWidth()) {
            Text(Time.format(progress.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterStart))
            QualityLabel(quality, Modifier.align(Alignment.Center))
            Text(
                "-" + Time.format((progress.durationMs - progress.positionMs).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        PlayerControls(
            isPlaying = playing,
            isBuffering = loading,
            hasNext = index < queue.lastIndex,
            onTogglePlay = Player::toggle,
            onNext = Player::next,
            onPrevious = Player::previous,
            modifier = Modifier.padding(top = 8.dp),
            style = ui.controlStyle,
            animate = !ui.reduceAnimation,
            progress = progress::fraction,
        )
        FootRow(showLyrics, onLyrics)
    }
}

/** Lyrics, the volume and the sleep timer, along the foot of the player. */
@Composable
private fun FootRow(showLyrics: Boolean, onLyrics: (() -> Unit)?) {
    val volume by Player.volume.collectAsState()
    val sleep by Player.sleep.collectAsState()
    var timing by remember { mutableStateOf(false) }
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onLyrics != null) {
            IconButton(onClick = onLyrics) {
                Icon(
                    PlayerIcons.Lyrics, if (showLyrics) "Hide lyrics" else "Show lyrics",
                    tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        var level by remember { mutableFloatStateOf(volume.toFloat()) }
        LineBar(level, { level = it; Player.setVolume(it.toDouble()) }, MaterialTheme.colorScheme.onSurface, faint, Modifier.weight(1f).padding(horizontal = 10.dp))
        Box {
            IconButton(onClick = { timing = true }) {
                Icon(Icons.Rounded.Bedtime, "Sleep timer", tint = if (sleep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(timing, onDismissRequest = { timing = false }) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 300.dp)) { SleepChips(sleep) }
            }
        }
    }
    (sleep as? Player.Sleep.At)?.let { at ->
        var left by remember { mutableLongStateOf(0L) }
        LaunchedEffect(at) { while (true) { left = at.epochMs - System.currentTimeMillis(); delay(1_000) } }
        Text("Stopping in ${Time.format(left)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    if (sleep == Player.Sleep.EndOfSong) {
        Text("Stopping after this song", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
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
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(46.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(interactionSource = interactions, indication = FocusRing(CircleShape)) {
                    if (!liked && !still) bursts++
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Remove from Liked" else "Like",
                Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                tint = if (liked) Color(0xFFFF4D6D) else LocalContentColor.current,
            )
        }
        if (burst.value < 1f) LikeBurst(burst.value, bursts, colors, Modifier.requiredSize(150.dp))
    }
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
    Box {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MoreHoriz, "More", Modifier.size(26.dp)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                onClick = { open = false; adding = true },
            )
        }
        AddToPlaylistMenu(song, adding) { adding = false }
    }
}
