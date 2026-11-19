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
            // Over the sidebar's head too, so the window can still be moved with the player open.
            if (!docked) Box(Modifier.fillMaxWidth().height(36.dp).windowDrag())
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = if (docked) 8.dp else DesktopWindow.topInset)) {
                val beside = !docked && maxWidth >= 900.dp && maxWidth > maxHeight
                if (beside) {
                    // As the web's full player lays it out: the cover's column a third of the width, the
                    // lyrics beside it, both centred. The cover takes what height the controls leave it.
                    val art = minOf(maxWidth * 0.32f, 720.dp).coerceAtLeast(300.dp)
                    val gap = (maxWidth * 0.07f).coerceIn(48.dp, 144.dp)
                    val lyricsWidth = maxWidth * 0.38f
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = (maxWidth * 0.09f).coerceIn(24.dp, 144.dp)).padding(top = 56.dp, bottom = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Column(Modifier.width(art).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true)) { BigCover(song, playing) }
                            PlayerFoot(song, showLyrics = true, onLyrics = null)
                        }
                        Spacer(Modifier.width(gap))
                        LyricsPane(song, Modifier.weight(1f, fill = false).widthIn(max = lyricsWidth).fillMaxWidth().fillMaxHeight())
                    }
                    PlayerHeader(song, docked, onClose, Modifier.align(Alignment.TopCenter))
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
internal fun SoftCover(url: String?, strength: Float = 0.6f, drift: () -> Float) {
    val context = LocalPlatformContext.current
    val painter = coil3.compose.rememberAsyncImagePainter(
        remember(url) { ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).size(96).build() },
    )
    val state by painter.state.collectAsState()
    val loaded = state is coil3.compose.AsyncImagePainter.State.Success
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val t = drift()
                val s = 1.35f + 0.08f * kotlin.math.sin(t * 0.21f)
                scaleX = s; scaleY = s
                translationX = size.width * 0.05f * kotlin.math.sin(t * 0.13f)
                translationY = size.height * 0.05f * kotlin.math.cos(t * 0.11f)
                alpha = strength
            }
            .drawWithCache {
                val small = androidx.compose.ui.graphics.ImageBitmap(SOFT_PX, SOFT_PX)
                if (loaded) {
                    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                        this, layoutDirection, androidx.compose.ui.graphics.Canvas(small), Size(SOFT_PX.toFloat(), SOFT_PX.toFloat()),
                    ) { with(painter) { draw(size) } }
                    soften(small)
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
 * The close button in the corner, a 32dp circle that a soft fill comes up
 * behind under the pointer, and where the song is playing from over the
 * middle, small and spaced out, as the web's full player has them.
 */
@Composable
private fun PlayerHeader(song: Song, docked: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(64.dp)) {
        HoverIcon(
            if (docked) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.Close, if (docked) "Hide player" else "Close",
            size = 32.dp, iconSize = 20.dp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp), onClick = onClose,
        )
        Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PLAYING FROM", fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                song.albumName?.takeIf { it.isNotBlank() } ?: "OpenTune", fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * The cover, as large as fits, with the web's rounding and its glow: a small,
 * blurred and saturated copy just behind and below it. It eases to 92% while
 * paused over 450ms, and a new song's comes in with a fade from 94%.
 */
@Composable
private fun BigCover(song: Song, playing: Boolean) {
    val ui by AppSettings.ui.collectAsState()
    val scale by animateFloatAsState(
        if (playing) 1f else 0.92f, if (ui.reduceAnimation) androidx.compose.animation.core.snap() else tween(450, easing = Motion.Settle), label = "coverScale",
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        AnimatedContent(
            song.videoId,
            transitionSpec = {
                if (ui.reduceAnimation) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                else (fadeIn(tween(450, easing = Motion.Emphasized)) + scaleIn(tween(450, easing = Motion.Emphasized), 0.94f)) togetherWith fadeOut(tween(200))
            },
            label = "cover",
        ) { _ ->
            val shape = RoundedCornerShape(28.dp)
            Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                // The glow, left out with blur reduced.
                if (!ui.reduceBlur) Artwork(
                    song.thumbnailUrl,
                    Modifier.matchParentSize().graphicsLayer { translationY = 8.dp.toPx(); scaleX = 0.96f; scaleY = 0.96f; alpha = 0.4f }
                        .blur(20.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                    shape,
                )
                Artwork(
                    song.thumbnailUrl,
                    Modifier.matchParentSize().aspectRatio(1f).shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.25f)),
                    shape,
                )
            }
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
        // The names a little apart from the cover, as the web's are, and a new song's come in with a short rise.
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
                    Text(song.title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        error ?: song.artist,
                        fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            HeartButton(isLiked) { Library.setLiked(song, !isLiked) }
            Spacer(Modifier.width(8.dp))
            MoreButton(song)
        }
        SeekBar(progress, Modifier.padding(top = 8.dp))
        Box(Modifier.fillMaxWidth()) {
            val times = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontFeatureSettings = "tnum")
            Text(Time.format(progress.positionMs), style = times, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterStart))
            QualityLabel(quality, Modifier.align(Alignment.Center))
            Text(
                "-" + Time.format((progress.durationMs - progress.positionMs).coerceAtLeast(0)), style = times,
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
    // The web's toggles: grey when off; on, the full ink on a soft fill of it with a dot under,
    // rather than the accent, which would clash with the cover's colours behind.
    val on = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onLyrics != null) {
            HoverIcon(PlayerIcons.Lyrics, if (showLyrics) "Hide lyrics" else "Show lyrics", lit = showLyrics, litColour = on, size = 38.dp, iconSize = 21.dp, onClick = onLyrics)
        }
        val shuffled by Player.shuffled.collectAsState()
        val repeat by Player.repeat.collectAsState()
        HoverIcon(Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", lit = shuffled, litColour = on, size = 38.dp, iconSize = 21.dp, onClick = Player::toggleShuffle)
        HoverIcon(
            if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
            lit = repeat != Player.Repeat.OFF, litColour = on, size = 38.dp, iconSize = 21.dp, onClick = Player::cycleRepeat,
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        var level by remember { mutableFloatStateOf(volume.toFloat()) }
        LaunchedEffect(volume) { level = volume.toFloat() }
        LineBar(
            level, { level = it; Player.setVolume(it.toDouble()) }, MaterialTheme.colorScheme.onSurface, faint,
            Modifier.weight(1f).padding(horizontal = 10.dp).volumeWheel(),
        )
        Box {
            HoverIcon(Icons.Rounded.Bedtime, "Sleep timer", lit = sleep != null, litColour = on, size = 38.dp, iconSize = 21.dp) { timing = true }
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
