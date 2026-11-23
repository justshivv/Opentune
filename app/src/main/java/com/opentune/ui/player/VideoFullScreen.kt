package com.opentune.ui.player

import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentune.data.innertube.VideoQuality
import com.opentune.ui.formatTime
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    entry: FullScreenEntry,
    onExit: () -> Unit,
    /** Start with the controls showing; the page itself opens with them up. */
    controlsAtFirst: Boolean = true,
) {
    val animate = !state.ui.reduceAnimation && !state.ui.liteMode
    val exit by rememberUpdatedState(onExit)
    BackHandler { exit() }
    SystemBarHider(true, androidx.core.view.WindowInsetsCompat.Type.systemBars())
    // A wide picture opened by the button turns the screen; one turned to by the phone is already there.
    TurnScreen(entry == FullScreenEntry.BUTTON && (page.aspect == 0f || page.aspect >= 1f))

    var crop by rememberSaveable { mutableStateOf(false) }
    var shown by remember { mutableStateOf(controlsAtFirst) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var menuOpen by remember { mutableStateOf(false) }
    fun touched() { touchedAt = System.nanoTime() }
    LaunchedEffect(shown, touchedAt, state.isPlaying, menuOpen) {
        if (!shown || !state.isPlaying || menuOpen) return@LaunchedEffect
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
    fun seekBy(forward: Boolean) {
        val last = seekMark?.takeIf { it.forward == forward }
        val to = (position() + if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS)
            .coerceIn(0L, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        actions.seekTo(to)
        seekMark = SeekMark(forward, (last?.seconds ?: 0) + (DOUBLE_TAP_SEEK_MS / 1000).toInt(), System.nanoTime())
    }

    // A swipe down pulls the page with the finger; far enough and it closes.
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val closePx = with(LocalDensity.current) { 120.dp.toPx() }
    val haptics = com.opentune.ui.components.rememberHaptics()

    Box(
        Modifier
            .fillMaxSize()
            .testTag("video-full-screen")
            // Every touch ends here: none reaches the player or the sheet underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }
            .then(if (state.isPlaying) Modifier.keepScreenOn() else Modifier)
            .graphicsLayer {
                if (!animate) return@graphicsLayer
                translationY = pull.value * 0.6f
                val p = (pull.value / (closePx * 3f)).coerceIn(0f, 1f)
                scaleX = 1f - 0.08f * p
                scaleY = 1f - 0.08f * p
            }
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { shown = !shown; touched() },
                    onDoubleTap = { at ->
                        val third = at.x / size.width
                        when {
                            third < 1f / 3f -> seekBy(forward = false)
                            third > 2f / 3f -> seekBy(forward = true)
                            else -> { shown = !shown; touched() }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // One finger down the screen closes the page; two pinch between fit and fill.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var pinching = false
                    var dragging = false
                    var distance = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinching = true
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                        } else if (!pinching) {
                            val change = pressed.first()
                            val moved = change.position - down.position
                            if (!dragging && moved.y > viewConfiguration.touchSlop && abs(moved.y) > abs(moved.x) * 1.5f) dragging = true
                            if (dragging) {
                                distance = moved.y.coerceAtLeast(0f)
                                scope.launch { pull.snapTo(distance) }
                                change.consume()
                            }
                        }
                    }
                    if (pinching) {
                        if (zoom > 1.08f && !crop) { crop = true; touched() }
                        else if (zoom < 0.92f && crop) { crop = false; touched() }
                    }
                    if (dragging) {
                        if (distance > closePx) exit()
                        else scope.launch { if (animate) pull.animateTo(0f, tween(220)) else pull.snapTo(0f) }
                    }
                }
            },
    ) {
        page.picture(this, crop)

        seekMark?.let { SeekRipple(it, animate) }

        AnimatedVisibility(
            visible = shown,
            enter = if (animate) fadeIn(tween(180)) else EnterTransition.None,
            exit = if (animate) fadeOut(tween(320)) else ExitTransition.None,
            modifier = Modifier.fillMaxSize(),
        ) {
            VideoControls(
                state, position, buffered, actions, page,
                crop = crop,
                onCrop = { crop = !crop; touched() },
                menuOpen = menuOpen,
                onMenu = { menuOpen = it; touched() },
                onTouch = ::touched,
                onExit = { haptics.pattern(com.opentune.ui.components.Haptics.Pattern.DONE); exit() },
            )
        }
    }
}

/** The button that opens the video on the whole screen. */
@Composable
internal fun FullScreenButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface) {
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(CircleShape), onClick = onClick)
            .testTag("video-full-screen-button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ImageVector.vectorResource(AppIcons.Fullscreen), "Full screen", Modifier.size(24.dp), tint = tint)
    }
}

/** Where a double tap seeked: which side, how far in all, and when. */
private data class SeekMark(val forward: Boolean, val seconds: Int, val at: Long)

/** How long a double tap's mark stays. */
private const val SEEK_MARK_MS = 650L

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
        addOval(androidx.compose.ui.geometry.Rect(left, -size.height * 0.15f, left + size.width * 2f, size.height * 1.15f))
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
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onTouch: () -> Unit,
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
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.62f), 0.3f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)))
            }
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            ControlIcon(ImageVector.vectorResource(AppIcons.ArrowBack), "Back", onExit)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = white, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.artist.isNotBlank()) {
                    Text(song.artist, color = white.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                QualityButton(page, onClick = { onMenu(true) })
                QualityMenu(page, menuOpen, onDismiss = { onMenu(false) }, onPick = { onMenu(false); page.choose(it) })
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlTap("Previous", { onTouch(); haptics.pattern(com.opentune.ui.components.Haptics.Pattern.PREVIOUS); previousRolls++; actions.previous() }) {
                SkipGlyph(false, previousRolls, white, animate, Modifier.size(34.dp))
            }
            ControlTap(if (state.isPlaying) "Pause" else "Play", {
                onTouch()
                haptics.pattern(if (state.isPlaying) com.opentune.ui.components.Haptics.Pattern.PAUSE else com.opentune.ui.components.Haptics.Pattern.PLAY)
                actions.togglePlay()
            }, size = 76) {
                PlayPauseGlyph(state.isPlaying, white, animate, Modifier.size(52.dp))
            }
            ControlTap("Next", { onTouch(); haptics.pattern(com.opentune.ui.components.Haptics.Pattern.NEXT); nextRolls++; actions.next() }, enabled = state.hasNext) {
                SkipGlyph(true, nextRolls, white.copy(alpha = if (state.hasNext) 1f else 0.35f), animate, Modifier.size(34.dp))
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            VideoSeekBar(position, buffered, state.durationMs, onSeek = { onTouch(); actions.seekTo(it) }, onTouch = onTouch)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FitButton(crop, onCrop)
                Spacer(Modifier.width(4.dp))
                ControlIcon(ImageVector.vectorResource(AppIcons.FullscreenExit), "Exit full screen", onExit)
            }
        }
    }
}

/** Elapsed time, the bar with what's buffered, and the length; tap or drag to seek. */
@Composable
private fun VideoSeekBar(position: () -> Long, buffered: () -> Long, durationMs: Long, onSeek: (Long) -> Unit, onTouch: () -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val now by rememberUpdatedState(position)
    val ahead by rememberUpdatedState(buffered)
    fun fraction(): Float = drag ?: if (durationMs > 0) (now().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs by remember(durationMs) { derivedStateOf { (fraction() * durationMs).toLong() / 1000 * 1000 } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(shownMs), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier
                .weight(1f)
                .height(32.dp)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "Seek" }
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) onSeek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> onTouch(); drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ -> onTouch(); drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .drawBehind {
                    val h = (if (drag != null) 6 else 4).dp.toPx()
                    val top = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, h), r)
                    val buf = if (durationMs > 0) (ahead().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(0f, top), Size(size.width * buf, h), r)
                    val f = fraction()
                    drawRoundRect(Color.White, Offset(0f, top), Size(size.width * f, h), r)
                    drawCircle(Color.White, (if (drag != null) 8 else 6).dp.toPx(), Offset(size.width * f, size.height / 2f))
                },
        )
        Text(formatTime(durationMs), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** The quality button: what's chosen ("Auto", "720p"). */
@Composable
private fun QualityButton(page: VideoPage, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = com.opentune.i18n.Tr.t("Video quality"), onClick = onClick)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .testTag("video-quality"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ImageVector.vectorResource(AppIcons.HighQuality), null, Modifier.size(20.dp), tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(VideoQuality.label(page.chosen), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The sizes to choose from: Auto, which says the size it is playing at, then
 * each size the video comes in that this phone can decode. The one chosen is ticked.
 */
@Composable
private fun QualityMenu(page: VideoPage, open: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
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
            val label = if (rung == VideoQuality.AUTO && page.playing > 0 && page.chosen == VideoQuality.AUTO) {
                com.opentune.i18n.Tr.t("Auto") + " · " + VideoQuality.label(page.playing)
            } else VideoQuality.label(rung)
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onPick(rung) },
                trailingIcon = if (rung == page.chosen) { { Icon(ImageVector.vectorResource(AppIcons.Check), null, Modifier.size(18.dp)) } } else null,
            )
        }
    }
}

/** Fit or Fill: the whole picture with bars, or the screen filled and the picture's edges cut off. */
@Composable
private fun FitButton(crop: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = com.opentune.i18n.Tr.t(if (crop) "Fit the whole picture" else "Fill the screen"), onClick = onClick)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(if (crop) "Fill" else "Fit", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(CircleShape), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(26.dp), tint = Color.White)
    }
}

@Composable
private fun ControlTap(label: String, onClick: () -> Unit, enabled: Boolean = true, size: Int = 60, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = com.opentune.ui.components.FocusRing(CircleShape), enabled = enabled, onClick = onClick)
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
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        val activity = ctx as? android.app.Activity
        if (!on || activity == null) return@DisposableEffect onDispose {}
        val before = activity.requestedOrientation
        val autoRotate = runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        }.getOrDefault(false)
        activity.requestedOrientation =
            if (autoRotate) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        onDispose { activity.requestedOrientation = before }
    }
}
