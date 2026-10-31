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
enum class SidePanel(val label: String) { NOW("Now playing"), LYRICS("Lyrics"), QUEUE("Queue") }



/** A thin progress line: click or drag along it to move through the song. */
@Composable
internal fun SeekLine(progress: Progress, modifier: Modifier) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thick by androidx.compose.animation.core.animateDpAsState(if (hovered || dragging != null) 4.dp else 2.dp, tween(140), label = "seek line")
    val color = MaterialTheme.colorScheme.onSurface
    val shown = dragging ?: progress.fraction()
    fun seekTo(f: Float) { if (progress.durationMs > 0) Player.seekTo((f.coerceIn(0f, 1f) * progress.durationMs).toLong()) }
    Box(
        modifier.height(10.dp).hoverable(hover)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(progress.durationMs) { detectTapGestures { seekTo(it.x / width) } }
            .pointerInput(progress.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = it.x / width },
                    onDragEnd = { dragging?.let(::seekTo); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) }
            }
            .drawBehind {
                val h = thick.toPx()
                val y = size.height - h
                drawRoundRect(color.copy(alpha = 0.14f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(color.copy(alpha = 0.85f), Offset(0f, y), Size(size.width * shown.coerceIn(0f, 1f), h), CornerRadius(h / 2))
            },
    )
}

/** The speaker, muting on click, and a short slider beside it. */
@Composable
internal fun VolumeControl(slider: Boolean) {
    val volume by Player.volume.collectAsState()
    var before by remember { mutableStateOf(0.8) }
    val icon = when {
        volume <= 0.0 -> Icons.AutoMirrored.Rounded.VolumeOff
        volume < 0.5 -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    HoverIcon(icon, if (volume <= 0.0) "Unmute" else "Mute", iconSize = 19.dp) {
        if (volume > 0.0) { before = volume; Player.setVolume(0.0) } else Player.setVolume(before.takeIf { it > 0 } ?: 0.8)
    }
    if (!slider) return
    var width by remember { mutableFloatStateOf(1f) }
    val color = MaterialTheme.colorScheme.onSurface
    fun set(x: Float) = Player.setVolume((x / width).coerceIn(0f, 1f).toDouble())
    Box(
        Modifier.width(96.dp).height(20.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } }
            .drawBehind {
                val h = 3.dp.toPx()
                val y = (size.height - h) / 2
                drawRoundRect(color.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(color.copy(alpha = 0.8f), Offset(0f, y), Size(size.width * volume.toFloat(), h), CornerRadius(h / 2))
                drawCircle(color, 5.dp.toPx(), Offset(size.width * volume.toFloat(), size.height / 2))
            },
    )
}

/** A bare icon that a soft circle comes up behind under the pointer; [lit] is white rather than grey. */
@Composable
internal fun HoverIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    lit: Boolean = false,
    size: Dp = 34.dp,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(onSurface.copy(alpha = if (hovered && enabled) 0.09f else 0f), tween(140), label = "icon fill")
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        lit -> onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(fill).hoverable(hover)
            .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(iconSize))
        // A dot under a lit toggle, as the Mac's music app marks shuffle and repeat.
        if (lit) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(3.dp).clip(CircleShape).background(onSurface))
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
            androidx.compose.animation.Crossfade(cover, animationSpec = tween(800), label = "window cover") { url ->
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
