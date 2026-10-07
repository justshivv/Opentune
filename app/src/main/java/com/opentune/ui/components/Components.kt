package com.opentune.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import coil3.request.ImageRequest
import coil3.request.crossfade

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
    /** Applied to the image inside the clipped frame, e.g. to move it within it. */
    imageModifier: Modifier = Modifier,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val ui by AppSettings.ui.collectAsState()
    val request = remember(context, url, ui.reduceAnimation) {
        ImageRequest.Builder(context).data(url).crossfade(if (ui.reduceAnimation) 0 else 220).build()
    }
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
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().then(imageModifier),
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
    /** Off for rows inside a sideways-scrolling list, whose scroll the swipe would otherwise take. */
    swipeToQueue: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onPlayNext != null || onAddToQueue != null || LocalSongMenu.current != null
    val download by remember(song.videoId) { Downloads.entry(song.videoId) }
        .collectAsState(Downloads.entryNow(song.videoId))
    if (menuOpen) SongMenuSheet(song, onDismiss = { menuOpen = false })
    val playback by AppSettings.playback.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val rowColor by animateColorAsState(
        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f) else Color.Transparent,
        tween(if (ui.reduceAnimation) 0 else 220),
        label = "currentSong",
    )
    val onSwipe = when {
        !swipeToQueue -> null
        playback.playNextOnSwipe -> onPlayNext
        else -> onAddToQueue
    }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = com.opentune.ui.components.rememberHaptics()
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
                .semantics {
                    if (isCurrent) stateDescription = if (isPlaying) "Now playing" else "Paused"
                }
                .graphicsLayer { translationX = offsetX.value }
                .background(if (offsetX.value > 1f) MaterialTheme.colorScheme.background else rowColor)
                .then(
                    if (onSwipe == null) Modifier else Modifier.pointerInput(onSwipe) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (offsetX.value >= threshold) {
                                    haptics.press()
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
            download?.let { DownloadBadge(it.state, it.progress) }
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
            .pressable(onClick, onClickLabel = "Open $title")
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
            fontWeight = FontWeight.SemiBold,
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
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
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
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items) { card(it) }
        }
    }
}

/** Three bars bouncing out of phase; still when paused. */
@Composable
fun NowPlayingBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val ui by AppSettings.ui.collectAsState()
    if (playing && !ui.reduceAnimation) {
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
        Bars(color, modifier) { i -> heights[i].value }
    } else {
        // Paused: still bars, and no animation asking for frames.
        Bars(color, modifier) { 0.3f }
    }
}

/** Three bars drawn at the given heights; read while drawing, so only the draw repeats. */
@Composable
private fun Bars(color: Color, modifier: Modifier, height: (Int) -> Float) {
    Canvas(modifier.size(20.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 2) / 3
        val r = CornerRadius(2.dp.toPx())
        repeat(3) { i ->
            val h = size.height * height(i)
            drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), r)
        }
    }
}

/**
 * A skeleton block standing in for content that is loading: a soft band of
 * light sweeps across it. The band is placed by the block's position on
 * screen and one clock, so every skeleton on a page shines as one sheet
 * rather than each on its own. Still when animations are reduced.
 */
@Composable
fun Placeholder(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sheen = Color.White.copy(alpha = if (dark) 0.07f else 0.55f)
    val screenPx = with(LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    var left by remember { mutableFloatStateOf(0f) }
    // Only here to redraw every frame; the band's place comes from the shared clock.
    val tick = if (ui.reduceAnimation) null else rememberInfiniteTransition(label = "skeleton")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(SHIMMER_MS.toInt(), easing = LinearEasing)), label = "tick")
    Box(
        modifier
            .onGloballyPositioned { left = it.positionInWindow().x }
            .clip(shape)
            .drawBehind {
                drawRect(base)
                if (tick == null) return@drawBehind
                tick.value
                val band = 220.dp.toPx()
                val phase = (android.os.SystemClock.uptimeMillis() % SHIMMER_MS) / SHIMMER_MS.toFloat()
                val x = -band + phase * (screenPx + band * 2) - left
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent, 0.5f to sheen, 1f to Color.Transparent,
                        start = androidx.compose.ui.geometry.Offset(x - band / 2, 0f),
                        end = androidx.compose.ui.geometry.Offset(x + band / 2, size.height * 0.35f),
                    ),
                )
            },
    )
}

private const val SHIMMER_MS = 1_500L

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
