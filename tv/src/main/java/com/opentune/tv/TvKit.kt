package com.opentune.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage

/** The ring a focused thing wears: bright and plain, readable on any cover. */
private val FocusRing = Color.White

/**
 * Anything the remote can land on. The focused one grows a little, takes a ring
 * and a lighter fill, and rises above its neighbours; OK on it calls [onClick].
 */
@Composable
fun TvSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    scale: Float = 1.06f,
    container: Color = TvColors.Surface,
    focusedContainer: Color = TvColors.SurfaceHigh,
    ring: Boolean = true,
    role: Role = Role.Button,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // The cursor from a phone's trackpad lights what it is over just as the remote's focus does.
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lit = focused || hovered
    val grow by animateFloatAsState(if (lit) scale else 1f, tween(140), label = "grow")
    val fill by animateColorAsState(if (lit) focusedContainer else container, tween(140), label = "fill")
    Box(
        modifier
            .zIndex(if (lit) 1f else 0f)
            .graphicsLayer { scaleX = grow; scaleY = grow }
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .clip(shape)
            .background(fill, shape)
            .then(if (lit && ring) Modifier.border(3.dp, FocusRing, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, role = role, onClick = onClick),
    ) { content(lit) }
}

/** A square of cover art with its title and credit under it. [round] for an artist's face. */
@Composable
fun TvCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 210.dp,
    round: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val shape = if (round) CircleShape else RoundedCornerShape(14.dp)
    Column(modifier.width(size)) {
        TvSurface(onClick = onClick, shape = shape, scale = 1.07f, onFocused = onFocused, modifier = Modifier.size(size)) {
            Cover(imageUrl, Modifier.size(size), shape)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Cover art, or a quiet note where there is none. */
@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp)) {
    Box(modifier.clip(shape).background(TvColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Rounded.MusicNote, null, tint = TvColors.TextDim, modifier = Modifier.size(44.dp))
        else AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/** A tab in the bar at the top. */
@Composable
fun TvPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    TvSurface(
        onClick = onClick,
        shape = CircleShape,
        scale = 1.08f,
        container = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
        focusedContainer = Color.White,
        ring = false,
        role = Role.Tab,
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) { focused ->
        val ink = if (focused) Color.Black else if (selected) TvColors.Text else TvColors.TextDim
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, label, tint = ink, modifier = Modifier.size(26.dp))
            else Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
        }
    }
}

/** A round button: [active] when what it stands for is on (liked, shuffling), [primary] for the one that matters. */
@Composable
fun TvIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    active: Boolean = false,
    size: Dp = if (primary) 76.dp else 60.dp,
    activeTint: Color = TvColors.Accent,
    focusRequester: FocusRequester? = null,
) {
    TvSurface(
        onClick = onClick,
        shape = CircleShape,
        scale = 1.12f,
        container = if (primary) Color.White else Color.White.copy(alpha = 0.10f),
        focusedContainer = if (primary) Color.White else Color.White.copy(alpha = 0.30f),
        modifier = modifier.size(size).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) { _ ->
        Icon(
            icon, description,
            tint = when {
                primary -> Color.Black
                active -> activeTint
                else -> TvColors.Text
            },
            modifier = Modifier.align(Alignment.Center).size(size * 0.5f),
        )
    }
}

/** A wide button with a label, for the plain actions: Play, Shuffle, Sign in. */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    TvSurface(
        onClick = onClick,
        shape = CircleShape,
        scale = 1.06f,
        container = if (primary) Color.White else Color.White.copy(alpha = 0.12f),
        focusedContainer = if (primary) Color.White else Color.White.copy(alpha = 0.30f),
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) { _ ->
        val ink = if (primary) Color.Black else TvColors.Text
        Row(Modifier.padding(horizontal = 30.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
        }
    }
}

/**
 * A titled row of things, scrolling sideways. Moving back into a row returns to the item
 * that was last on, rather than the first.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvShelf(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = TvSafe.Horizontal),
        )
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
            modifier = Modifier.padding(horizontal = TvSafe.Horizontal),
        )
        Spacer(Modifier.height(14.dp))
        // Room above and below so a focused card, which grows, isn't clipped by the row.
        LazyRow(
            contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.focusRestorer(),
            content = content,
        )
    }
}

/** One line of a track list: number, title and credit, and the length at the end. */
@Composable
fun TvTrackRow(
    number: Int?,
    title: String,
    subtitle: String,
    length: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    imageUrl: String? = null,
    trailing: String? = null,
) {
    TvSurface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        scale = 1.02f,
        container = if (playing) Color.White.copy(alpha = 0.08f) else Color.Transparent,
        focusedContainer = Color.White.copy(alpha = 0.18f),
        modifier = modifier.fillMaxWidth(),
    ) { _ ->
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (number != null) Text(
                if (playing) "♪" else number.toString(), style = MaterialTheme.typography.bodyMedium,
                color = if (playing) TvColors.Accent else TvColors.TextDim, modifier = Modifier.width(44.dp),
            )
            if (imageUrl != null) {
                Cover(imageUrl, Modifier.size(56.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) TvColors.Accent else TvColors.Text,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelSmall, color = TvColors.Accent, modifier = Modifier.padding(start = 12.dp))
            }
            if (length != null) Text(length, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** A quiet line for an empty or failed page. */
@Composable
fun TvNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim,
        modifier = modifier.padding(horizontal = TvSafe.Horizontal, vertical = 24.dp),
    )
}

/** The wide thin ratio of a shelf card's art, for callers that want a 16:9 tile. */
fun Modifier.wide(): Modifier = this.aspectRatio(16f / 9f)
