package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic

/** The column beside the page: the song's lyrics, or what plays next. */
enum class SidePanel(val label: String) { NOW("Now playing"), LYRICS("Lyrics"), QUEUE("Up next") }



/**
 * The thin line along the foot of the song in the top bar, as the web
 * player's: 2dp, thickening to 4dp under the pointer over 150ms; click or drag
 * along it to move through the song. [hover] tells the bar when the pointer
 * is on it, and [onScrub] where a drag has got to (null once let go), so the
 * times can show over its ends.
 */
@Composable
internal fun SeekLine(
    progress: Progress,
    modifier: Modifier,
    hover: MutableInteractionSource = remember { MutableInteractionSource() },
    onScrub: (Float?) -> Unit = {},
) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val hovered by hover.collectIsHoveredAsState()
    val thick by androidx.compose.animation.core.animateFloatAsState(
        if (hovered || dragging != null) 1f else 0.5f, tween(150, easing = Motion.Emphasized), label = "seek line",
    )
    val track = quaternaryFill()
    val played = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val shown = dragging ?: progress.fraction()
    fun seekTo(f: Float) { if (progress.durationMs > 0) Player.seekTo((f.coerceIn(0f, 1f) * progress.durationMs).toLong()) }
    fun scrub(f: Float?) { dragging = f; onScrub(f) }
    Box(
        // A taller place to take hold of than the line it carries.
        modifier.height(14.dp).hoverable(hover)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(progress.durationMs) { detectTapGestures { seekTo(it.x / width) } }
            .pointerInput(progress.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { scrub((it.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging?.let(::seekTo); scrub(null) },
                    onDragCancel = { scrub(null) },
                ) { change, _ -> scrub((change.position.x / width).coerceIn(0f, 1f)) }
            }
            .drawBehind {
                // Drawn 4dp and scaled about its middle, 5dp up from the foot, as the web draws it.
                val full = 4.dp.toPx()
                val h = full * thick
                val y = size.height - 5.dp.toPx() - full / 2 - h / 2
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(played, Offset(0f, y), Size(size.width * shown.coerceIn(0f, 1f), h), CornerRadius(h / 2))
            },
    )
}

/** The speaker for the top bar: mutes on click, the wheel over it turns the music up or down. */
@Composable
internal fun VolumeSpeaker(modifier: Modifier = Modifier, size: Dp = 32.dp, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
    val volume by Player.volume.collectAsState()
    var before by remember { mutableStateOf(0.8) }
    val icon = when {
        volume <= 0.0 -> Icons.AutoMirrored.Rounded.VolumeOff
        volume < 0.5 -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    Box(modifier.volumeWheel()) {
        HoverIcon(icon, if (volume <= 0.0) "Unmute" else "Mute", size = size, iconSize = 19.dp, shape = shape) {
            if (volume > 0.0) { before = volume; Player.setVolume(0.0) } else Player.setVolume(before.takeIf { it > 0 } ?: 0.8)
        }
    }
}

/**
 * The volume level as the web player draws it: a 4dp track filled to the
 * level, with a white knob. Click or drag along it; the wheel and, once it
 * has the keyboard, the arrow keys move it too.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun VolumeLevel(modifier: Modifier = Modifier) {
    val volume by Player.volume.collectAsState()
    var width by remember { mutableFloatStateOf(1f) }
    val fill = MaterialTheme.colorScheme.onSurface
    val track = quaternaryFill()
    fun set(x: Float) = Player.setVolume((x / width).coerceIn(0f, 1f).toDouble())
    Box(
        modifier.width(96.dp).height(16.dp).volumeWheel()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } }
            .semantics {
                contentDescription = "Volume"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(volume.toFloat(), 0f..1f)
                setProgress { Player.setVolume(it.coerceIn(0f, 1f).toDouble()); true }
            }
            .focusable()
            .onKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionRight, androidx.compose.ui.input.key.Key.DirectionUp -> { Player.setVolume(Player.volume.value + 0.05); true }
                    androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionDown -> { Player.setVolume(Player.volume.value - 0.05); true }
                    else -> false
                }
            }
            .drawBehind {
                val h = 4.dp.toPx()
                val y = (size.height - h) / 2
                val x = size.width * volume.toFloat().coerceIn(0f, 1f)
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(fill, Offset(0f, y), Size(x, h), CornerRadius(h / 2))
                // The knob, with the faint shadow that lifts it off the track.
                val r = 6.dp.toPx()
                val at = Offset(x.coerceIn(r, size.width - r), size.height / 2)
                drawCircle(Color.Black.copy(alpha = 0.3f), r + 1.dp.toPx(), at)
                drawCircle(Color.White, r, at)
            },
    )
}

/**
 * A bare icon that a soft fill comes up behind under the pointer, giving a
 * little when pressed, as the web player's buttons do. [lit] marks a switch
 * that's on: the full ink with a dot under it, or with [litColour] given, that
 * colour on a fill of its own (the web's ot-toggle).
 */
@Composable
internal fun HoverIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    lit: Boolean = false,
    size: Dp = 34.dp,
    iconSize: Dp = 20.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    litColour: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        lit -> litColour ?: onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fill by animateColorAsState(
        when {
            lit && litColour != null && enabled -> litColour.copy(alpha = if (hovered) 0.26f else 0.2f)
            hovered && enabled -> onSurface.copy(alpha = 0.09f)
            else -> onSurface.copy(alpha = 0f)
        },
        tween(150), label = "icon fill",
    )
    Box(
        modifier.size(size).pressGive(hover, enabled).clip(shape).background(fill).hoverable(hover)
            .clickable(enabled = enabled, interactionSource = hover, indication = com.opentune.ui.components.FocusRing(shape), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(iconSize))
        // A dot under a lit toggle, as the Mac's music app marks shuffle and repeat.
        if (lit) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(3.dp).clip(CircleShape).background(ink))
    }
}

// ---- Beside the page ---------------------------------------------------------

/** A choice in a row of them: solid when chosen, plain text otherwise. */
@Composable
internal fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when { selected -> onSurface; hovered -> onSurface.copy(alpha = 0.08f); else -> Color.Transparent }, tween(160), label = "segment",
    )
    val ink by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant, tween(160), label = "segment ink")
    Text(
        label, color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Behind the whole window, sidebar and top bar included: the playing cover
 * blown up and blurred into a soft field of its colours across the top, under
 * a wash of its main colour that eases into the page colour further down.
 * The see-through chrome takes its colour from this rather than standing
 * beside the page as a grey slab. A new song's cover fades in over the old.
 */
@Composable
internal fun WindowBackdrop(cover: String?, clear: Boolean = false) {
    val theme by AppSettings.theme.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    // "Colour from artwork" off: the window stays plain grey.
    if (!theme.colorFromArtwork) return
    val page = MaterialTheme.colorScheme.background
    val seed = rememberCoverColor(cover, page)
    // Over Windows' material the wash fades out to nothing, leaving the material below it.
    val base = if (clear) page.copy(alpha = 0f) else page
    val wash by animateColorAsState(if (cover == null) base else tint(seed, page, 0.22f), tween(800), label = "window wash")
    Box(Modifier.fillMaxWidth().height(BACKDROP_HEIGHT)) {
        // The picture itself only where blur is welcome; the wash alone carries the colour otherwise.
        if (cover != null && !ui.reduceBlur) {
            // The picture fades out on its own before the foot of the box, so it never ends on a
            // line, even over Windows' material where the wash on top fades to nothing.
            androidx.compose.animation.Crossfade(
                cover, animationSpec = tween(800), label = "window cover",
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(COVER_FADE, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    },
            ) { url ->
                coil3.compose.AsyncImage(
                    url, null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { alpha = COVER_ALPHA }
                        .blur(COVER_BLUR, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle),
                )
            }
        }
        // Thin over the picture at the top, the page colour by the foot, so there's no line where it ends.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to wash.copy(alpha = 0.3f),
                    0.5f to androidx.compose.ui.graphics.lerp(wash, page, 0.6f).copy(alpha = if (clear) 0.35f else 0.8f),
                    1f to base,
                ),
            ),
        )
    }
}

private val BACKDROP_HEIGHT = 560.dp
private val COVER_BLUR = 110.dp
private const val COVER_ALPHA = 0.75f
/** Full strength over the top half of the box, gone by its foot. */
private val COVER_FADE = Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent)
