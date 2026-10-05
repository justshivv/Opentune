package com.opentune.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.opentune.data.model.BrowseType
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.model.artworkAt

/**
 * Artwork with a tinted placeholder icon while it loads or when there is none.
 *
 * The placeholder sits under a plain [AsyncImage] rather than in a
 * SubcomposeAsyncImage slot: subcomposing every cover cost a frame here and
 * there while long shelves scrolled.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    placeholder: ImageVector = Icons.Filled.MusicNote,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                placeholder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

/** One track in a list. Shows animated bars instead of the art overlay while it plays. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onPlayNext != null || onAddToQueue != null || LocalSongMenu.current != null
    val downloads by Downloads.entries.collectAsState()
    if (menuOpen) SongMenuSheet(song, onDismiss = { menuOpen = false })
    val playback by AppSettings.playback.collectAsState()
    val onSwipe = if (playback.playNextOnSwipe) onPlayNext else onAddToQueue
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    Box(modifier.fillMaxWidth()) {
        if (onSwipe != null && offsetX.value > 1f) {
            val reached = offsetX.value >= threshold
            Box(
                Modifier.matchParentSize().background(
                    if (reached) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    if (playback.playNextOnSwipe) Icons.Filled.SkipNext else Icons.AutoMirrored.Filled.QueueMusic,
                    null,
                    tint = if (reached) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .background(if (offsetX.value > 1f) MaterialTheme.colorScheme.background else Color.Transparent)
                .then(
                    if (onSwipe == null) Modifier else Modifier.pointerInput(onSwipe) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (offsetX.value >= threshold) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipe()
                                }
                                scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.7f)) }
                            },
                            onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                        ) { change, amount ->
                            change.consume()
                            scope.launch { offsetX.snapTo((offsetX.value + amount * 0.8f).coerceIn(0f, threshold * 1.6f)) }
                        }
                    },
                )
                .combinedClickable(onClick = onClick, onLongClick = { if (hasMenu) menuOpen = true })
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (leading != null) {
                leading()
            } else {
                Box(Modifier.size(52.dp)) {
                    Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.fillMaxSize())
                    if (isCurrent) {
                        Box(
                            Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            NowPlayingBars(playing = isPlaying, color = Color.White)
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
            val download = downloads[song.videoId]
            if (download != null) DownloadBadge(download.state, download.progress)
            if (hasMenu) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
            }
    }
    }
}

/** A small mark on a row: a ring while downloading, a tick once saved. */
@Composable
fun DownloadBadge(state: DownloadState, progress: Float) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (state) {
            DownloadState.DONE -> Icon(Icons.Filled.DownloadDone, "Downloaded", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            DownloadState.FAILED -> Icon(Icons.Filled.ErrorOutline, "Download failed", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/** A card in a horizontal shelf: square art (round for artists), title, subtitle. */
@Composable
fun ItemCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    type: BrowseType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 156.dp,
) {
    val round = type == BrowseType.ARTIST
    Column(
        modifier = modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Artwork(
            thumbnailUrl.artworkAt(CARD_ART_PX),
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = if (round) CircleShape else MaterialTheme.shapes.medium,
            placeholder = when (type) {
                BrowseType.ARTIST -> Icons.Filled.Person
                BrowseType.ALBUM -> Icons.Filled.Album
                BrowseType.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
                else -> Icons.Filled.MusicNote
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action?.invoke()
    }
}

/** A horizontally scrolling row of cards under a header. */
@Composable
fun <T> Shelf(
    title: String,
    items: List<T>,
    subtitle: String? = null,
    card: @Composable (T) -> Unit,
) {
    Column {
        SectionHeader(title, subtitle = subtitle)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items) { card(it) }
        }
    }
}

/** Three bars bouncing out of phase; still when paused. */
@Composable
fun NowPlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val heights = listOf(0, 180, 360).map { delay ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 420, delayMillis = delay, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "bar",
        )
    }
    Row(
        modifier = modifier.size(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { h ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (playing) h.value else 0.3f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** A pulsing gray block standing in for content that is loading. */
@Composable
fun Placeholder(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    val transition = rememberInfiniteTransition(label = "placeholder")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)),
    )
}

@Composable
fun ShelfPlaceholder() {
    Column(Modifier.padding(vertical = 12.dp)) {
        Placeholder(Modifier.padding(start = 16.dp).width(160.dp).height(22.dp))
        Row(Modifier.padding(start = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column {
                    Placeholder(Modifier.size(144.dp), MaterialTheme.shapes.medium)
                    Placeholder(Modifier.padding(top = 8.dp).width(110.dp).height(14.dp))
                }
            }
        }
    }
}

@Composable
fun SongRowPlaceholder() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Placeholder(Modifier.size(52.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Placeholder(Modifier.width(180.dp).height(14.dp))
            Placeholder(Modifier.padding(top = 6.dp).width(110.dp).height(12.dp))
        }
    }
}

/** Shown when a load fails: what happened, and a way to try again. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MessageState(
        icon = Icons.Filled.CloudOff,
        title = "Couldn't load this",
        modifier = modifier,
        message = message,
        action = { FilledTonalButton(onClick = onRetry) { Text("Try again") } },
    )
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(34.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!message.isNullOrBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        action?.invoke()
    }
}

/** Single-line text that scrolls when it doesn't fit, as on a car stereo. */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500),
    )
}
