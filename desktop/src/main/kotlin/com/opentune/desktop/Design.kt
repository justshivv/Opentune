package com.opentune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.ui.components.glass

/** How far a page's title has to scroll away before the frosted bar takes its place. */
private const val TITLE_GONE_PX = 90

/**
 * A page that scrolls under a frosted bar: [title] big at the top of the
 * list, and once it has scrolled away, small in a glass bar across the top
 * that blurs what slides under it, as a Mac app's toolbar does.
 */
@Composable
internal fun ScrollPage(
    title: String,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 30.dp,
    trailing: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val bar by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > TITLE_GONE_PX } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(top = 28.dp, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(spacing)) {
            item(key = "page-title") {
                Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
        TitleBar(title, bar)
    }
}

/** The small frosted bar a page's title moves into as it scrolls away. */
@Composable
internal fun BoxScope.TitleBar(title: String, shown: Boolean) {
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 2 },
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
    ) {
        Box(
            Modifier.fillMaxWidth().height(54.dp + DesktopWindow.topInset).glass(RoundedCornerShape(0.dp), MaterialTheme.colorScheme.background.copy(alpha = 0.62f))
                .padding(top = DesktopWindow.topInset, start = GUTTER),
            contentAlignment = Alignment.CenterStart,
        ) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * The head of an album, playlist or mix page: the cover large and lifted,
 * over a soft blur of itself in its own colours; what kind of list it is,
 * its name, who made it and how long it runs; Play, Shuffle and whatever
 * else the page offers.
 */
@Composable
internal fun CollectionHero(
    cover: @Composable (Modifier) -> Unit,
    coverUrl: String?,
    kind: String,
    title: String,
    subtitle: String,
    songs: List<Song>?,
    round: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    val colour = coverAccent(coverUrl)
    val shape: Shape = if (round) CircleShape else RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth()) {
        // The cover itself, huge and softly blurred, behind the head, fading out into the page's accent wash.
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.7f), 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) {
            SoftBackdrop(coverUrl, Modifier.fillMaxSize())
            // A little darker at the top for the window's bar, richer in the cover's colour below.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 1f to colour.copy(alpha = 0.35f))))
        }
        Row(Modifier.fillMaxWidth().padding(start = GUTTER, end = GUTTER, top = 44.dp + DesktopWindow.topInset, bottom = 28.dp), verticalAlignment = Alignment.Bottom) {
            cover(Modifier.size(228.dp).shadow(36.dp, shape, ambientColor = colour, spotColor = colour).clip(shape))
            Column(Modifier.padding(start = 28.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(kind.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                songs?.takeIf { it.isNotEmpty() }?.let { Text(lengthLine(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ready = !songs.isNullOrEmpty()
                    PrimaryButton("Play", Icons.Rounded.PlayArrow, enabled = ready) { songs?.let { Player.playAll(it, 0) } }
                    SecondaryButton("Shuffle", Icons.Rounded.Shuffle, enabled = ready) { songs?.let { Player.playAll(it.shuffled(), 0) } }
                    extra()
                }
            }
        }
    }
}

/**
 * The colour a collection page wears, from its cover: the cover's livelier
 * colour, lifted when the cover is very dark so a black cover still gives a
 * colour, as the phone's album pages do.
 */
@Composable
internal fun coverAccent(url: String?): Color {
    val base = MaterialTheme.colorScheme.background
    val raw = rememberCoverColor(url, base)
    val lifted = if (url == null) raw else vivid(raw)
    return androidx.compose.animation.animateColorAsState(lifted, tween(700), label = "cover accent").value
}

/**
 * A cover's colour made to carry a page: saturated enough to read as a
 * colour rather than grey, and bright enough that a dark cover doesn't turn
 * the page black, without washing a near-grey cover into a false hue.
 */
internal fun vivid(c: Color): Color {
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    val (h, sat, v) = Triple(hsv[0], hsv[1], hsv[2])
    // Truly grey covers stay grey, just lifted; anything with a hint of colour gets it drawn out.
    val s2 = if (sat < 0.08f) sat else sat.coerceIn(0.5f, 0.85f)
    val v2 = v.coerceIn(0.42f, 0.78f)
    val rgb = java.awt.Color.HSBtoRGB(h, s2, v2)
    return Color(rgb or 0xFF000000.toInt())
}

/** "12 songs, 48 min", from the songs' own lengths. */
internal fun lengthLine(songs: List<Song>): String {
    val seconds = songs.sumOf { s -> s.durationText?.let { Player.seconds(it) }?.toInt() ?: 0 }
    val count = if (songs.size == 1) "1 song" else "${songs.size} songs"
    if (seconds <= 0) return count
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return count + ", " + if (h > 0) "$h h $m min" else "$m min"
}

/** A filled pill in the accent ink, for the one thing a page is for. */
@Composable
internal fun PrimaryButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val ink = MaterialTheme.colorScheme.surface
    Row(
        Modifier.hoverLift(hover, 1.04f).shadow(if (enabled) 10.dp else 0.dp, CircleShape).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.3f))
            .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick)
            .padding(start = 18.dp, end = 22.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A quiet frosted pill beside the main one. */
@Composable
internal fun SecondaryButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(onSurface.copy(alpha = if (hovered && enabled) 0.18f else 0.1f), tween(140), label = "secondary")
    Row(
        Modifier.hoverLift(hover, 1.03f).clip(CircleShape).background(fill)
            .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = onSurface.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(19.dp))
        Text(label, color = onSurface.copy(alpha = if (enabled) 1f else 0.4f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

/** The column heads over a track list: number, title, length. */
@Composable
internal fun TrackHeader() {
    Column(Modifier.padding(horizontal = GUTTER - 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
            Text("Title", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Schedule, "Length", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 92.dp).size(16.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** A track list's rows, each a [TrackRow], for a LazyColumn. */
internal fun LazyListScope.tracks(songs: List<Song>, onRemove: ((Song) -> Unit)? = null) {
    itemsIndexed(songs, key = { i, s -> "t$i:${s.videoId}" }) { i, song -> TrackRow(i, song, songs, onRemove) }
}

/**
 * One track: its number (a play mark under the pointer, moving bars while
 * it plays), cover, title and artist, then on the right its length and, on
 * hover, add to playlist, like, and remove where the list allows.
 */
@Composable
internal fun TrackRow(i: Int, song: Song, songs: List<Song>, onRemove: ((Song) -> Unit)? = null) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val liked by Library.liked.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val current = queue.getOrNull(index)?.videoId == song.videoId
    var adding by remember { mutableStateOf(false) }
    val tools by animateFloatAsState(if (hovered || isLiked || adding) 1f else 0f, tween(140), label = "row tools")
    Row(
        Modifier.padding(horizontal = GUTTER - 12.dp).fillMaxWidth().hoverWash(hover, RoundedCornerShape(10.dp))
            .clickable(interactionSource = hover, indication = null) { Player.playAll(songs, i) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp)) {
            when {
                current -> PlayingBars(playing)
                hovered -> Icon(Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(20.dp))
                else -> Text("${i + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Cover(song.thumbnailUrl, 44.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) railAccent() else MaterialTheme.colorScheme.onSurface,
            )
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.graphicsLayer { alpha = tools }, verticalAlignment = Alignment.CenterVertically) {
            Box {
                HoverIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", size = 32.dp, iconSize = 19.dp) { adding = true }
                AddToPlaylistMenu(song, adding) { adding = false }
            }
            LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, size = 20.dp)
        }
        Text(song.durationText.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp).padding(start = 10.dp))
        if (onRemove != null) {
            Box(Modifier.graphicsLayer { alpha = tools }) { HoverIcon(Icons.Rounded.Close, "Remove", size = 30.dp, iconSize = 17.dp) { onRemove(song) } }
        } else {
            Spacer(Modifier.width(30.dp))
        }
    }
}

/** A list's tracks still on their way: rows of the right shape, shimmering. */
internal fun LazyListScope.trackPlaceholders() {
    items(8) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Placeholder(Modifier.width(18.dp).height(12.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.width(18.dp))
            Placeholder(Modifier.size(44.dp), RoundedCornerShape(8.dp))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Placeholder(Modifier.width(240.dp).height(14.dp), RoundedCornerShape(6.dp))
                Placeholder(Modifier.width(150.dp).height(11.dp), RoundedCornerShape(5.dp))
            }
        }
    }
}

/** A collection page laid out: the hero, the column heads, the tracks, under the frosted bar. */
@Composable
internal fun CollectionLayout(
    title: String,
    coverUrl: String?,
    songs: List<Song>?,
    failed: Boolean,
    onRemove: ((Song) -> Unit)? = null,
    empty: String = "Nothing to play here",
    hero: @Composable () -> Unit,
) {
    val state = rememberLazyListState()
    val bar by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    val base = MaterialTheme.colorScheme.background
    val accent = coverAccent(coverUrl)
    // The whole page in the cover's colour: strong at the top, deepening to a dark, tinted ground.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint(accent, base, 0.62f), 0.35f to tint(accent, base, 0.32f), 0.75f to tint(accent, base, 0.16f), 1f to tint(accent, base, 0.1f)))) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = LocalPageBottom.current)) {
            item(key = "hero") { hero() }
            when {
                failed -> item(key = "failed") { Message("Couldn't open $title", "Check your connection and try again.") }
                songs == null -> trackPlaceholders()
                songs.isEmpty() -> item(key = "empty") { Message(empty, null) }
                else -> {
                    item(key = "head") { TrackHeader() }
                    tracks(songs, onRemove)
                }
            }
        }
        TitleBar(title, bar)
    }
}

/** A soft, colour-only blur of a cover, as the full player's backdrop draws it, filling [modifier]. */
@Composable
internal fun SoftBackdrop(url: String?, modifier: Modifier) {
    Box(modifier) { SoftCover(url, strength = 1f) { 0f } }
}
