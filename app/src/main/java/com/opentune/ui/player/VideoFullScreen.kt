package com.opentune.ui.player

import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.opentune.data.model.artworkAt
import coil3.request.allowHardware
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.innertube.VideoQuality
import com.opentune.data.model.Song
import com.opentune.ui.LyricsState
import com.opentune.ui.formatTime
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What the full-screen video page asks of the live player. */
class VideoPage(
    /** The picture, letterboxed, or with crop filling the screen. Shown in one place at a time. */
    val picture: @Composable BoxScope.(crop: Boolean) -> Unit,
    /** The picture's shape, width over height, 0 while unknown. */
    val aspect: Float = 0f,
    /** The size playing now as a rung (720 for 720p), 0 while unknown. */
    val playing: Int = 0,
    /** The size chosen for the network in use; [VideoQuality.AUTO] for Auto. */
    val chosen: Int = VideoQuality.AUTO,
    /** Auto first, then the sizes the video can be chosen at; asked when the list opens. */
    val choices: () -> List<Int> = { listOf(VideoQuality.AUTO) },
    val choose: (Int) -> Unit = {},
)

/** How the full-screen page was opened, which decides how it turns the screen and how it closes. */
internal enum class FullScreenEntry {
    /** The button: a wide picture turns the screen on its side, and back after. */
    BUTTON,

    /** Turning the phone: it closes when the phone is turned upright again. */
    ROTATION,
}

/** How long the controls stay up untouched while the video plays. */
internal const val VIDEO_CONTROLS_MS = 3_000L

/** How far a double tap on the left or right third seeks. */
private const val DOUBLE_TAP_SEEK_MS = 10_000L

/**
 * The music video on the whole screen, system bars away. The picture is
 * letterboxed, or with a pinch out (or the Fit button) fills the screen,
 * the overflow cut off. A tap shows the controls, which fade after a few
 * seconds untouched; a double tap on the left or right third seeks ten
 * seconds. A swipe down or back returns to the player, the same picture
 * playing on: the view it's drawn into moves back with its surface, so the
 * stream is never touched.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FullScreenVideo(
    state: PlayerUiState,
    position: () -> Long,
    buffered: () -> Long,
    actions: PlayerActions,
    page: VideoPage,
    liked: Boolean,
    onLike: () -> Unit,
    commentsOpen: Boolean,
    onComments: (Boolean) -> Unit,
    entry: FullScreenEntry,
    onExit: () -> Unit,
    /** Start with the controls showing; the page itself opens with them up. */
    controlsAtFirst: Boolean = true,
) {
    val animate = !state.ui.reduceAnimation && !state.ui.liteMode
    // The loading clock, the big player's own: one turn every 1_100.
    val spin = rememberEchoPhase(state.isBuffering && animate, 1_100)
    val exit by rememberUpdatedState(onExit)
    var lyricsOpen by remember { mutableStateOf(false) }
    // Which side panel the shared fraction is showing; kept while it closes.
    var lastPanel by remember { mutableStateOf(1) }
    LaunchedEffect(commentsOpen, lyricsOpen) {
        if (commentsOpen) lastPanel = 1 else if (lyricsOpen) lastPanel = 2
    }
    BackHandler(enabled = commentsOpen) { onComments(false) }
    BackHandler(enabled = lyricsOpen) { lyricsOpen = false }
    BackHandler { exit() }
    // The comments panel's share of the width: none, or its full share, travelled smoothly.
    val panel by animateFloatAsState(
        if (commentsOpen || lyricsOpen) COMMENTS_PANEL_FRACTION else 0f,
        if (animate) tween(340, easing = FastOutSlowInEasing) else snap(),
        label = "commentsPanel",
    )
    SystemBarHider(true, androidx.core.view.WindowInsetsCompat.Type.systemBars())
    // A wide picture opened by the button turns the screen; one turned to by the phone is already there.
    TurnScreen(entry == FullScreenEntry.BUTTON && (page.aspect == 0f || page.aspect >= 1f))

    var crop by remember { mutableStateOf(state.ui.videoCrop) }
    // The fit/fill choice is kept, so the video opens the way it was left.
    LaunchedEffect(crop) {
        if (state.ui.videoCrop != crop) {
            com.opentune.data.settings.AppSettings.updateUi { it.copy(videoCrop = crop) }
        }
    }
    var ambient by remember { mutableStateOf(state.ui.videoAmbient) }
    // Whether the row's extra controls are folded out; kept here, above the
    // controls, so hiding and showing them does not fold the extras away.
    var moreOpen by remember { mutableStateOf(false) }
    // The ambient choice is kept the same way, between sessions.
    LaunchedEffect(ambient) {
        if (state.ui.videoAmbient != ambient) {
            com.opentune.data.settings.AppSettings.updateUi { it.copy(videoAmbient = ambient) }
        }
    }
    var shown by remember { mutableStateOf(controlsAtFirst) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    fun touched() {
        touchedAt = System.nanoTime()
    }
    LaunchedEffect(shown, touchedAt, state.isPlaying, menuOpen, scrubbing) {
        if (!shown || !state.isPlaying || menuOpen || scrubbing) return@LaunchedEffect
        delay(VIDEO_CONTROLS_MS)
        shown = false
    }

    // A double tap's mark: the side, and how far the taps in a row have gone.
    var seekMark by remember { mutableStateOf<SeekMark?>(null) }
    LaunchedEffect(seekMark) {
        if (seekMark == null) return@LaunchedEffect
        delay(SEEK_MARK_MS)
        seekMark = null
    }

    // The fit button's note: the mode it just switched to, named briefly at the centre.
    var modeNote by remember { mutableStateOf<String?>(null) }
    var modeNoteShown by remember { mutableStateOf(false) }
    LaunchedEffect(modeNoteShown) {
        if (!modeNoteShown) return@LaunchedEffect
        delay(MODE_NOTE_MS)
        modeNoteShown = false
    }
    fun seekBy(forward: Boolean) {
        val last = seekMark?.takeIf { it.forward == forward }
        val to = (position() + if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS)
            .coerceIn(0L, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        actions.seekTo(to)
        seekMark = SeekMark(
            forward,
            (last?.seconds ?: 0) + (DOUBLE_TAP_SEEK_MS / 1000).toInt(),
            System.nanoTime()
        )
    }

    // A swipe down pulls the page with the finger; far enough and it closes.
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    // The pinch's live scale on the picture, between the fit and fill sizes.
    val pinch = remember { Animatable(1f) }
    // The picture's own size, which the pinch's fill ratio is measured against.
    var pictureSize by remember { mutableStateOf(IntSize.Zero) }
    // A pinch home from fill runs on the fit rendering under the fill scale.
    var pinchFromFill by remember { mutableStateOf(false) }
    val closePx = with(LocalDensity.current) { 120.dp.toPx() }
    // The bottom controls cluster's height (padding, the seek
    // bar's touch box, its row): a pull starting on it is the controls' own.
    val uiLook = com.opentune.data.settings.AppSettings.ui.collectAsState().value
    val barBase = uiLook.seekbarThickness.dp.dp
    val controlsStrip = 16.dp + maxOf(48.dp, barBase * 6f) + 44.dp
    val haptics = com.opentune.ui.components.rememberHaptics()

    Row(Modifier
        .fillMaxSize()
        .background(Color.Black)) {
        Box(
            Modifier
                .weight(1f - panel)
                .fillMaxHeight()
                .testTag("video-full-screen")
                .then(if (state.isPlaying) Modifier.keepScreenOn() else Modifier)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { shown = !shown; touched() },
                        onDoubleTap = { at ->
                            val third = at.x / size.width
                            when {
                                third < 1f / 3f -> seekBy(forward = false)
                                third > 2f / 3f -> seekBy(forward = true)
                                else -> {
                                    shown = !shown; touched()
                                }
                            }
                        },
                    )
                }
        ) {
            // The glow fades in and out with its setting; once the fade out
            // lands it leaves the composition entirely, so it costs nothing.
            val glowAlpha by animateFloatAsState(
                targetValue = if (ambient && !crop) 1f else 0f,
                animationSpec = if (animate) tween(if (ambient && !crop) 350 else 250) else snap(),
                label = "ambientGlowAlpha",
            )
            if (glowAlpha > 0.001f) {
                AmbientGlow(
                    state.song.thumbnailUrl,
                    state.isPlaying,
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = glowAlpha },
                )
            }

            // A pull drags the picture alone; the controls stay where they are.
            Box(
                Modifier
                    .matchParentSize()
                    .onSizeChanged { pictureSize = it }
                    .graphicsLayer {
                        if (!animate) return@graphicsLayer
                        translationY = pull.value * 0.6f
                        val p = (pull.value / (closePx * 3f)).coerceIn(0f, 1f)
                        scaleX = pinch.value * (1f - 0.08f * p)
                        scaleY = pinch.value * (1f - 0.08f * p)
                    }
            ) {
                page.picture(this, crop && !pinchFromFill)
            }


            // The pull-to-close and pinch layer. It covers the picture except the
            // bottom controls strip, which is outside its bounds entirely: a press
            // there is never even offered to it, so it cannot steal a seek scrub.
            Box(
                Modifier
                    .matchParentSize()
                    .padding(bottom = controlsStrip),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // One finger down the screen closes the page; two pinch between fit and fill.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // How far the fit picture can grow before it fills the
                                // screen, and the fill picture's way back by the same ratio.
                                val fillZoom = if (page.aspect > 0f && pictureSize.height > 0) {
                                    val container = pictureSize.width.toFloat() / pictureSize.height
                                    maxOf(container / page.aspect, page.aspect / container).coerceAtLeast(1f)
                                } else 1.5f
                                var zoom = 1f
                                var pinching = false
                                var fromFill = false
                                var dragging = false
                                var distance = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        if (!pinching) {
                                            // The pinch begins: coming from fill, the picture
                                            // switches to its fit rendering under the fill
                                            // scale, so the shrink home shows the whole
                                            // frame instead of black bars.
                                            fromFill = crop
                                            if (fromFill) {
                                                pinchFromFill = true
                                                scope.launch { pinch.snapTo(fillZoom) }
                                            }
                                        }
                                        if (!pinching && dragging) {
                                            // The second finger turns the pull into a pinch:
                                            // the pull lets go and the picture settles back.
                                            dragging = false
                                            distance = 0f
                                            scope.launch {
                                                if (animate) pull.animateTo(
                                                    0f,
                                                    tween(220)
                                                ) else pull.snapTo(0f)
                                            }
                                        }
                                        pinching = true
                                        zoom *= event.calculateZoom()
                                        // The picture follows the fingers, no further than
                                        // the other mode's size.
                                        val shown =
                                            if (fromFill) (fillZoom * zoom).coerceIn(1f, fillZoom) else zoom.coerceIn(1f, fillZoom)
                                        scope.launch { pinch.snapTo(shown) }
                                        event.changes.forEach { it.consume() }
                                    } else if (!pinching) {
                                        val change = pressed.first()
                                        val moved = change.position - down.position
                                        if (!dragging && moved.y > viewConfiguration.touchSlop && abs(moved.y) > abs(
                                                moved.x
                                            ) * 1.5f
                                        ) dragging = true
                                        if (dragging) {
                                            distance = moved.y.coerceAtLeast(0f)
                                            scope.launch { pull.snapTo(distance) }
                                            change.consume()
                                        }
                                    }
                                }
                                if (pinching) {
                                    // The picture settles onto the mode the pinch chose, at
                                    // the scale where the two modes meet, then the mode
                                    // takes over at scale one: no jump.
                                    val target = when {
                                        !fromFill && zoom > 1.08f && !crop -> true
                                        fromFill && zoom < 0.92f && crop -> false
                                        else -> null
                                    }
                                    scope.launch {
                                        when {
                                            target == true -> {
                                                if (animate) pinch.animateTo(fillZoom, tween(160)) else pinch.snapTo(fillZoom)
                                                crop = true
                                                touched()
                                                modeNote = "Fill the screen"
                                                modeNoteShown = true
                                                pinch.snapTo(1f)
                                            }
                                            target == false -> {
                                                if (animate) pinch.animateTo(1f, tween(160)) else pinch.snapTo(1f)
                                                crop = false
                                                touched()
                                                modeNote = "Fit the whole picture"
                                                modeNoteShown = true
                                                pinchFromFill = false
                                            }
                                            fromFill -> {
                                                // Not far enough: back to fill the way it came.
                                                if (animate) pinch.animateTo(fillZoom, tween(160)) else pinch.snapTo(fillZoom)
                                                pinchFromFill = false
                                                pinch.snapTo(1f)
                                            }
                                            pinch.value != 1f -> {
                                                if (animate) pinch.animateTo(1f, tween(160)) else pinch.snapTo(1f)
                                            }
                                        }
                                    }
                                }
                                if (dragging) {
                                    if (distance > closePx) exit()
                                    else scope.launch {
                                        if (animate) pull.animateTo(
                                            0f,
                                            tween(220)
                                        ) else pull.snapTo(0f)
                                    }
                                }
                            }
                        }
                )
            }

            seekMark?.let { SeekRipple(it, animate) }

            ControlsVisibility(
                visible = shown,
                animate = animate,
                modifier = Modifier.fillMaxSize(),
            ) {
                VideoControls(
                    state, position, buffered, actions, page,
                    crop = crop,
                    liked = liked,
                    onLike = onLike,
                    commentsOpen = commentsOpen,
                    onComments = { open ->
                        if (open) lyricsOpen = false
                        onComments(open)
                    },
                    lyricsOpen = lyricsOpen,
                    onLyrics = { open ->
                        lyricsOpen = open
                        if (open) onComments(false)
                    },
                    onScrubbing = { scrubbing = it },
                    onCrop = {
                        crop = !crop
                        touched()
                        modeNote = if (crop) "Fill the screen" else "Fit the whole picture"
                        modeNoteShown = true
                    },
                    moreOpen = moreOpen,
                    onMore = { moreOpen = !moreOpen },
                    ambient = ambient,
                    onAmbient = {
                        val next = !ambient
                        ambient = next
                        touched()
                        modeNote = if (next) "Ambient mode on" else "Ambient mode off"
                        modeNoteShown = true
                    },
                    menuOpen = menuOpen,
                    onMenu = { menuOpen = it; touched() },
                    onTouch = ::touched,
                    onSeekBy = ::seekBy,
                    onExit = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.DONE); exit() },
                )
            }
            ControlsVisibility(
                visible = modeNoteShown,
                animate = animate,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 88.dp),
            ) {
                Box(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(modeNote ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.isBuffering) {
                // The buffering arc lives here, not on the play button, so it keeps
                // turning after the controls have faded away.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(76.dp)
                        .drawBehind {
                            drawArc(
                                Color.White.copy(alpha = 0.8f),
                                spin.value * 360f - 90f,
                                100f,
                                false,
                                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                                size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                        .semantics { stateDescription = "Buffering" }
                )
            }
        }
        if (panel > 0.001f) {
            if (lyricsOpen || (!commentsOpen && lastPanel == 2)) {
                LyricsPanel(
                    state,
                    position,
                    onSeek = { actions.seekTo(it) },
                    onClose = { lyricsOpen = false },
                    modifier = Modifier.weight(panel),
                )
            } else {
                CommentsPanel(
                    state.song,
                    onClose = { onComments(false) },
                    modifier = Modifier.weight(panel)
                )
            }
        }
    }
}

/** The share of the width the comments panel takes when it is open. */
private const val COMMENTS_PANEL_FRACTION = 0.45f

/**
 * A panel's header keeps its drags to itself. The page sits inside the big
 * player's sheet, whose own pull down starts from any drag nothing under it
 * claims; a pull on a panel's top edge is the panel's business, never the
 * whole player's close.
 */
private fun Modifier.panelHeaderShield(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/**
 * The comments beside the video: a panel on the right the video makes room
 * for, showing the same content as the comments sheet.
 */
@Composable
private fun CommentsPanel(song: Song, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var fromVideo by remember(song.videoId) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxHeight()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 6.dp)
                .panelHeaderShield(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Comments",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (fromVideo) "From the music video on YouTube" else "From YouTube",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    ImageVector.vectorResource(AppIcons.Close),
                    "Close comments",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        CommentsContent(song, onFromVideo = { fromVideo = it })
    }
}

/**
 * The ambient glow around a fitted video: the song's cover, drawn small and
 * spread over the whole picture area under a dim, so its colours bleed into
 * the black the fitted frame leaves around itself.
 */
@Composable
private fun AmbientGlow(artworkUrl: String?, moving: Boolean, modifier: Modifier = Modifier) {
    // The glow breathes and drifts, slowly, the way the big player's blurred
    // cover does: every frame where the phone keeps up, stepped where it
    // doesn't, and still while the song is paused.
    val smooth = smoothBackdrop()
    val t by produceState(0f, moving, smooth) {
        if (!moving) return@produceState
        val start = withFrameNanos { it } - (value * 1e9f).toLong()
        while (true) {
            if (!smooth) delay(80L)
            value = (withFrameNanos { it } - start) / 1e9f
        }
    }
    Box(modifier.clipToBounds()) {
        androidx.compose.animation.Crossfade(
            artworkUrl,
            animationSpec = tween(900),
            label = "ambientGlow",
        ) { url ->
            if (url != null) {
                val context = LocalContext.current
                val painter = coil3.compose.rememberAsyncImagePainter(
                    remember(url) {
                        coil3.request.ImageRequest.Builder(context)
                            .data(url.artworkAt(120)).size(96).allowHardware(false).build()
                    },
                )
                Image(
                    painter,
                    null,
                    Modifier
                        .fillMaxSize()
                        .blur(36.dp)
                        .graphicsLayer {
                            val s = 1.35f + 0.05f * sin(t * 0.21f)
                            scaleX = s
                            scaleY = s
                            translationX = 14f * sin(t * 0.09f)
                            translationY = 10f * cos(t * 0.07f)
                        },
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
    }
}

/**
 * The lyrics beside the picture, in the comments panel's own shell: the player's
 * lyrics view under a header, so the two panels open, close and sit the same way.
 */
@Composable
private fun LyricsPanel(
    state: PlayerUiState,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 6.dp)
                .panelHeaderShield(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lyrics",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.song.title,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    ImageVector.vectorResource(AppIcons.Close),
                    "Close lyrics",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        LyricsView(
            state.lyrics,
            position,
            onSeek,
            modifier = Modifier.weight(1f),
            playing = { state.isPlaying },
        )
    }
}

/**
 * The controls over the picture, fading in and out. A plain visibility: inside
 * the row the comments panel shares, the name alone would pick a row's own
 * AnimatedVisibility, so the call lives out here where no scope is in reach.
 */
@Composable
private fun ControlsVisibility(visible: Boolean, animate: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = if (animate) fadeIn(tween(180)) else EnterTransition.None,
        exit = if (animate) fadeOut(tween(320)) else ExitTransition.None,
        modifier = modifier,
    ) {
        content()
    }
}

/** The button that opens the video on the whole screen. */
@Composable
internal fun FullScreenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = com.opentune.ui.components.FocusRing(CircleShape),
                onClick = onClick
            )
            .testTag("video-full-screen-button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            ImageVector.vectorResource(AppIcons.Fullscreen),
            "Full screen",
            Modifier.size(24.dp),
            tint = tint
        )
    }
}

/** Where a double tap seeked: which side, how far in all, and when. */
private data class SeekMark(val forward: Boolean, val seconds: Int, val at: Long)

/** How long a double tap's mark stays. */
private const val SEEK_MARK_MS = 650L
private const val MODE_NOTE_MS = 1200L

/** A soft wash over the side tapped, with the arrows and how far it went. */
@Composable
private fun BoxScope.SeekRipple(mark: SeekMark, animate: Boolean) {
    val fade = remember(mark.at) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(mark.at) {
        if (!animate) return@LaunchedEffect
        fade.animateTo(1f, tween(120))
        fade.animateTo(0f, tween((SEEK_MARK_MS - 120).toInt()))
    }
    // Half of a tall oval, its flat side on the screen's edge.
    val shape = androidx.compose.foundation.shape.GenericShape { size, _ ->
        val left = if (mark.forward) 0f else -size.width
        addOval(
            androidx.compose.ui.geometry.Rect(
                left,
                -size.height * 0.15f,
                left + size.width * 2f,
                size.height * 1.15f
            )
        )
    }
    Box(
        Modifier
            .align(if (mark.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.36f)
            .graphicsLayer { alpha = fade.value }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.16f))
            .testTag("seek-mark"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                ImageVector.vectorResource(if (mark.forward) AppIcons.FastForward else AppIcons.FastRewind),
                null,
                Modifier.size(32.dp),
                tint = Color.White,
            )
            Text(
                com.opentune.i18n.Tr.f("{0} s", mark.seconds),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The controls over the picture: the song and the way back on top, the transport in the middle, time and options below. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun VideoControls(
    state: PlayerUiState,
    position: () -> Long,
    buffered: () -> Long,
    actions: PlayerActions,
    page: VideoPage,
    crop: Boolean,
    onCrop: () -> Unit,
    liked: Boolean,
    onLike: () -> Unit,
    commentsOpen: Boolean,
    onComments: (Boolean) -> Unit,
    lyricsOpen: Boolean,
    onLyrics: (Boolean) -> Unit,
    ambient: Boolean,
    onAmbient: () -> Unit,
    moreOpen: Boolean,
    onMore: () -> Unit,
    onScrubbing: (Boolean) -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onTouch: () -> Unit,
    onSeekBy: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val song = state.song
    val animate = !state.ui.reduceAnimation && !state.ui.liteMode
    val haptics = com.opentune.ui.components.rememberHaptics()
    var previousRolls by remember { mutableIntStateOf(0) }
    var nextRolls by remember { mutableIntStateOf(0) }
    val white = Color.White
    Box(
        Modifier
            .fillMaxSize()
            // Shade at the top and bottom so the white controls read over any picture.
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.62f),
                        0.3f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
            }
            .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = white,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        song.artist,
                        color = white.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                QualityButton(page, onClick = { onMenu(true) })
                QualityMenu(page, menuOpen, onDismiss = { onMenu(false) }, onPick = { onMenu(false); page.choose(it) })
            }
            Spacer(Modifier.width(4.dp))
            ControlIcon(ImageVector.vectorResource(AppIcons.FullscreenExit), "Exit full screen", onExit)
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlTap(
                "Rewind 10 seconds",
                {
                    onTouch(); haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); previousRolls++; onSeekBy(
                    false
                )
                }) {
                JumpGlyph(false, 10, previousRolls, white, animate, Modifier.size(34.dp))
            }
            ControlTap(if (state.isPlaying) "Pause" else "Play", {
                onTouch()
                haptics.pattern(if (state.isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY)
                actions.togglePlay()
            }, size = 76) {
                PlayPauseGlyph(state.isPlaying, white, animate, Modifier.size(52.dp))
            }
            ControlTap(
                "Forward 10 seconds",
                {
                    onTouch(); haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); nextRolls++; onSeekBy(
                    true
                )
                }) {
                JumpGlyph(true, 10, nextRolls, white, animate, Modifier.size(34.dp))
            }
        }

        // The seek bar's lyric preview and part marks, read from the same
        // synced lyrics the big player reads.
        val synced =
            (state.lyrics as? LyricsState.Found)?.lyrics as? com.opentune.data.lyrics.Lyrics.Synced
        val lyricsSettings by com.opentune.data.settings.AppSettings.lyrics.collectAsState()
        val offsetMs = com.opentune.data.lyrics.lyricsOffset(
            lyricsSettings.offsets[state.song.videoId],
            (state.lyrics as? LyricsState.Found)?.lyrics
        )
        val marks = remember(synced, offsetMs) {
            synced?.let { com.opentune.data.lyrics.SongParts.marks(it).map { ms -> ms - offsetMs } }
                .orEmpty()
        }
        val lyricAt: ((Long) -> String?)? =
            if (state.ui.lyricScrubPreview && synced != null) {
                { ms ->
                    synced.lines.lastOrNull { it.startMs <= ms + offsetMs }?.text?.takeIf { it.isNotBlank() }
                }
            } else null

        Column(Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)) {
            VideoSeekBar(
                position,
                buffered,
                state.durationMs,
                onSeek = { onTouch(); actions.seekTo(it) },
                playing = state.isPlaying,
                wavy = state.ui.wavySeekbar && !state.ui.reduceAnimation,
                visualizer = state.ui.seekbarWaveform && !state.ui.wavySeekbar,
                lyricAt = lyricAt,
                marks = marks,
                onScrubbing = onScrubbing
            ) {
                // Shifted by the buttons' own inset, so the first glyph's edge lines up
                // with the seek bar's start, the way the time lines up with its end.
                Row(
                    Modifier.offset(x = (-9).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlIcon(
                        ImageVector.vectorResource(if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder),
                        if (liked) "Remove from Liked" else "Like",
                        onClick = {
                            onTouch()
                            haptics.pattern(if (liked) com.opentune.ui.components.Haptics.Pattern.OFF else com.opentune.ui.components.Haptics.Pattern.LIKE)
                            onLike()
                        },
                    )
                    if (state.ui.commentsButton) {
                        Spacer(Modifier.width(4.dp))
                        ControlIcon(
                            ImageVector.vectorResource(AppIcons.Chat),
                            "Comments",
                            onClick = { onTouch(); onComments(!commentsOpen) },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    ControlIcon(
                        PlayerIcons.Lyrics,
                        "Lyrics",
                        onClick = { onTouch(); onLyrics(!lyricsOpen) },
                    )
                    Spacer(Modifier.width(4.dp))
                    ControlIcon(
                        ImageVector.vectorResource(AppIcons.AspectRatio),
                        if (crop) "Fit the whole picture" else "Fill the screen",
                        onClick = onCrop,
                    )
                    // The extras unfold without a clip edge: their slot widens
                    // while they fade and grow in over it, and narrows as they go.
                    val extraW by animateDpAsState(
                        targetValue = if (moreOpen) 48.dp else 0.dp,
                        animationSpec = if (animate) tween(220) else snap(),
                        label = "moreWidth",
                    )
                    if (extraW > 0.5.dp) {
                        Box(Modifier.width(extraW)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier.graphicsLayer {
                                        val f = (extraW / 48.dp).coerceIn(0f, 1f)
                                        alpha = f
                                        scaleX = 0.8f + 0.2f * f
                                        scaleY = 0.8f + 0.2f * f
                                    }
                                ) {
                                    ControlIcon(
                                        ImageVector.vectorResource(AppIcons.Ambient),
                                        "Ambient mode",
                                        onClick = onAmbient,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    ControlIcon(
                        ImageVector.vectorResource(AppIcons.ChevronRight),
                        "More controls",
                        onClick = { onTouch(); onMore() },
                        modifier = Modifier.graphicsLayer { rotationZ = if (moreOpen) 180f else 0f },
                    )
                }
            }
        }
    }
}

/** The big player's own seek bar, shared: it reads the seek settings itself — thickness, thumb, colour, the wavy style and the beat waves — and scrubs the way it does there. Under it, a row: what the caller puts at its left, and the time at its right — elapsed, then the length. */
@Composable
private fun VideoSeekBar(
    position: () -> Long,
    buffered: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    playing: Boolean,
    wavy: Boolean,
    visualizer: Boolean,
    lyricAt: ((Long) -> String?)?,
    marks: List<Long>,
    onScrubbing: (Boolean) -> Unit,
    belowLeft: @Composable () -> Unit
) {
    // The shared bar centres its track in a tall touch box; the row under it
    // rides up over the box's empty lower half, so it sits by the track.
    val look = com.opentune.data.settings.AppSettings.ui.collectAsState().value
    val base = look.seekbarThickness.dp.dp
    val emptyBelow = (maxOf(48.dp, base * 6f) - base) / 2f
    val shownMs by remember(durationMs) {
        derivedStateOf {
            position().coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) / 1000 * 1000
        }
    }
    Column {
        SeekBar(
            position = position,
            buffered = buffered,
            durationMs = durationMs,
            onSeek = onSeek,
            wavy = wavy,
            playing = playing,
            lyricAt = lyricAt,
            marks = marks,
            visualizer = visualizer,
            showTimes = false,
            onScrubbing = onScrubbing,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .offset(y = -(emptyBelow - 8.dp))
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            belowLeft()
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(shownMs) + " / " + formatTime(durationMs),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(
                    lineHeightStyle = LineHeightStyle(
                        LineHeightStyle.Alignment.Center,
                        LineHeightStyle.Trim.Both
                    )
                )
            )
        }
    }
}

/**
 * The badge for a size: the mark for its tier, all one family — 8K at the
 * top, then 4K, 2K, HD, and SD for the small sizes.
 */
private fun qualityBadge(rung: Int): Int = when {
    rung >= 4320 -> AppIcons.Badge8K
    rung >= 2160 -> AppIcons.Badge4K
    rung >= 1440 -> AppIcons.Badge2K
    rung >= 720 -> AppIcons.BadgeHD
    else -> AppIcons.BadgeSD
}

/**
 * The quality button: the badge of the size being watched — the one chosen,
 * or, on Auto, the one playing now. Before any size is known, the plain
 * HQ mark.
 */
@Composable
private fun QualityButton(page: VideoPage, onClick: () -> Unit) {
    val rung = if (page.chosen == VideoQuality.AUTO) page.playing else page.chosen
    Box(Modifier.testTag("video-quality")) {
        ControlIcon(
            ImageVector.vectorResource(if (rung > 0) qualityBadge(rung) else AppIcons.HighQuality),
            "Video quality",
            onClick = onClick,
        )
    }
}

/**
 * The sizes to choose from: Auto, which says the size it is playing at, then
 * each size the video comes in that this phone can decode. The one chosen is ticked.
 */
@Composable
private fun QualityMenu(
    page: VideoPage,
    open: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val choices = remember(open) { if (open) page.choices() else emptyList() }
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        if (page.playing > 0) {
            Text(
                com.opentune.i18n.Tr.f("Playing at {0}", VideoQuality.label(page.playing)),
                fontSize = 12.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        choices.forEach { rung ->
            val label =
                if (rung == VideoQuality.AUTO && page.playing > 0 && page.chosen == VideoQuality.AUTO) {
                    com.opentune.i18n.Tr.t("Auto") + " · " + VideoQuality.label(page.playing)
                } else VideoQuality.label(rung)
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onPick(rung) },
                trailingIcon = if (rung == page.chosen) {
                    { Icon(ImageVector.vectorResource(AppIcons.Check), null, Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun ControlIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = com.opentune.ui.components.FocusRing(CircleShape),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(26.dp), tint = tint)
    }
}

@Composable
private fun ControlTap(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Int = 60,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = com.opentune.ui.components.FocusRing(CircleShape),
                enabled = enabled,
                onClick = onClick
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Turns the screen on its side while [on] holds, and gives back the
 * orientation it had after. With auto-rotate on, either side follows the
 * phone; with rotation locked, the screen still goes sideways (that's what
 * the button asks for) but stays on the one side.
 */
@Composable
private fun TurnScreen(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on) {
        var ctx: android.content.Context? = context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx =
            ctx.baseContext
        val activity = ctx as? android.app.Activity
        if (!on || activity == null) return@DisposableEffect onDispose {}
        val before = activity.requestedOrientation
        val autoRotate = runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            ) == 1
        }.getOrDefault(false)
        activity.requestedOrientation =
            if (autoRotate) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        onDispose { activity.requestedOrientation = before }
    }
}
