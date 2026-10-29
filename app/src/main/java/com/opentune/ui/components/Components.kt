package com.opentune.ui.components

import androidx.compose.foundation.indication
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import com.opentune.data.settings.AppSettings
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import androidx.compose.ui.text.withLink
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
    /** Hears whether the cover is showing (loaded, so cached) each time that changes. */
    onLoaded: ((Boolean) -> Unit)? = null,
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
                modifier = Modifier.matchParentSize().then(imageModifier),
                onState = {
                    loaded = it is AsyncImagePainter.State.Success
                    onLoaded?.invoke(loaded)
                },
            )
        }
    }
}

/**
 * YouTube Music's explicit mark: a small square "E" in front of a subtitle,
 * the way the official apps show it. Read out as "Explicit".
 */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier
            .size(15.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.22f))
            .semantics { contentDescription = "Explicit" },
        contentAlignment = Alignment.Center,
    ) {
        Text("E", color = color, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

/** A one-line subtitle with the explicit mark in front when [explicit]. */
@Composable
fun SubtitleLine(
    text: String,
    explicit: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    if (!explicit) {
        Text(text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = color, textAlign = textAlign)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start) {
        ExplicitBadge(color = color)
        if (text.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(text, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = color)
        }
    }
}

/** Between the names in a line of artists: commas, "&", " x ", "and", "feat.", "ft.", "with". */
private val ARTIST_JOINS = Regex("""\s*,\s*|\s+&\s+|\s+x\s+|\s+and\s+|\s+(?:feat\.?|ft\.?|featuring|with)\s+""", RegexOption.IGNORE_CASE)

/** A link that looks like the text around it in every state. */
private val PLAIN_LINK = androidx.compose.ui.text.TextLinkStyles(
    style = androidx.compose.ui.text.SpanStyle(),
    focusedStyle = androidx.compose.ui.text.SpanStyle(),
    hoveredStyle = androidx.compose.ui.text.SpanStyle(),
    pressedStyle = androidx.compose.ui.text.SpanStyle(),
)

/**
 * A song's artists on one line, each name its own link: tapping a name opens
 * that artist's page, not just the first one's. Names cut off at the end of
 * the line can't be reached, so only the ones shown can be tapped.
 */
@Composable
fun ArtistLinks(
    artists: String,
    onArtist: ((String) -> Unit)?,
    explicit: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (onArtist == null) { SubtitleLine(artists, explicit, modifier, style, color); return }
    val text = remember(artists, onArtist) {
        androidx.compose.ui.text.buildAnnotatedString {
            var at = 0
            fun name(end: Int) {
                val n = artists.substring(at, end)
                val trimmed = n.trim()
                if (trimmed.isEmpty()) { append(n); return }
                // Drawn like the plain line it replaces: no underline, no colour, no
                // press highlight. Only a tap on the name gives it away as a link.
                withLink(androidx.compose.ui.text.LinkAnnotation.Clickable(trimmed, PLAIN_LINK) { onArtist(trimmed) }) { append(n) }
            }
            ARTIST_JOINS.findAll(artists).forEach { m ->
                name(m.range.first)
                append(m.value)
                at = m.range.last + 1
            }
            name(artists.length)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (explicit) { ExplicitBadge(color = color); Spacer(Modifier.width(6.dp)) }
        Text(text, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = color)
    }
}

/** One track in a list. Shows animated bars instead of the art overlay while it plays. */
@OptIn(ExperimentalFoundationApi::class)
/**
 * How far in a long track (a podcast episode, a mix, anything kept by
 * [com.opentune.data.library.ResumePoints]) was left, as a thin bar along the
 * bottom of its artwork. Nothing for a track that wasn't started or was finished.
 */
@Composable
fun ListenedBar(videoId: String, modifier: Modifier = Modifier) {
    val long by com.opentune.data.library.ResumePoints.points.collectAsState()
    val episodes by com.opentune.data.podcasts.Podcasts.progress.collectAsState()
    val fraction = remember(videoId, long, episodes) {
        val episode = episodes[videoId]
        when {
            episode != null -> if (episode.finished || com.opentune.data.podcasts.Podcasts.resumeAt(videoId) == null) 0f else episode.fraction
            else -> long[videoId]?.fraction ?: 0f
        }
    }
    if (fraction <= 0.01f) return
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(Color.White))
    }
}

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
    /**
     * Swiping the row plays it next or queues it (see [SwipeToQueueRow]), when
     * Settings allow. Off for rows inside a sideways-scrolling list, whose
     * scroll the swipe would otherwise take.
     */
    swipeToQueue: Boolean = true,
    /** Inside one of your own playlists: the long-press menu can take the song out of it. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /**
     * Inside one of your own playlists: swiping toward the start of the line
     * takes the song out, and toward the end adds it to the queue.
     */
    onSwipeRemove: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    // A long press lifts the row out with its menu beside it; the ⋮ opens the full menu.
    var contextOpen by remember { mutableStateOf(false) }
    val anchor = remember { MenuAnchor() }
    val view = androidx.compose.ui.platform.LocalView.current
    val haptics = rememberHaptics()
    val hasMenu = onPlayNext != null || onAddToQueue != null || LocalSongMenu.current != null
    val download by remember(song.videoId) { Downloads.entry(song.videoId) }
        .collectAsState(Downloads.entryNow(song.videoId))
    if (menuOpen) SongMenuSheet(song, onDismiss = { menuOpen = false })
    val at = anchor.rect
    if (contextOpen && at != null) SongContextMenu(song, at, onDismiss = { contextOpen = false }, onRemoveFromPlaylist = onRemoveFromPlaylist) { SongPreview(song) }
    val playback by AppSettings.playback.collectAsState()
    // Toward the end of the line plays next, toward the start adds to the queue.
    val swipes = swipeToQueue && playback.swipeToQueue
    SwipeToQueueRow(
        title = song.title,
        onPlayNext = onPlayNext.takeIf { swipes },
        onAddToQueue = onAddToQueue.takeIf { swipes || onSwipeRemove != null },
        modifier = modifier.fillMaxWidth(),
        onRemove = onSwipeRemove,
    ) { swipeActions ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(swipeActions)
                .menuAnchor(anchor, view)
                .menuClickable(onClick = onClick, onLongClick = {
                    if (hasMenu) {
                        haptics.press()
                        if (anchor.rect != null) contextOpen = true else menuOpen = true
                    }
                })
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (leading != null) {
                leading()
            } else {
                Box(Modifier.size(52.dp)) {
                    Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.fillMaxSize())
                    ListenedBar(song.videoId, Modifier.align(Alignment.BottomCenter))
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
                SubtitleLine(
                    listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                    explicit = song.isExplicit == true,
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

/** The row as it shows lifted out over its long-press menu. */
@Composable
internal fun SongPreview(song: Song) {
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(song.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(52.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            SubtitleLine(listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "), explicit = song.isExplicit == true)
        }
        Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What a card's long press opens: a song's menu or an album's or playlist's. */
sealed interface CardMenu {
    class ForSong(val song: Song) : CardMenu
    class ForList(val list: MenuList) : CardMenu
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

/** A card as it shows lifted out over its long-press menu. */
@Composable
internal fun CardPreview(title: String, subtitle: String, thumbnailUrl: String?, round: Boolean) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Artwork(thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.fillMaxWidth().aspectRatio(1f), shape = if (round) CircleShape else MaterialTheme.shapes.medium)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    explicit: Boolean = false,
    /** Applied to the cover inside its frame, e.g. to move it (see livingArt). */
    imageModifier: Modifier = Modifier,
    /** Hears whether the cover has loaded (see [Artwork]). */
    onArtLoaded: ((Boolean) -> Unit)? = null,
    /** What a long press opens; nothing when null. */
    menu: CardMenu? = null,
) {
    val round = type == BrowseType.ARTIST
    val anchor = remember { MenuAnchor() }
    val view = androidx.compose.ui.platform.LocalView.current
    val haptics = rememberHaptics()
    var contextOpen by remember { mutableStateOf(false) }
    val at = anchor.rect
    if (contextOpen && at != null && menu != null) {
        val preview: @Composable () -> Unit = { CardPreview(title, subtitle, thumbnailUrl, round) }
        when (menu) {
            is CardMenu.ForSong -> SongContextMenu(menu.song, at, onDismiss = { contextOpen = false }, preview = preview)
            is CardMenu.ForList -> CollectionContextMenu(menu.list, at, onDismiss = { contextOpen = false }, preview = preview)
        }
    }
    Column(
        modifier = modifier
            .width(width)
            .menuAnchor(anchor, view)
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (menu == null) Modifier.clickable(onClick = onClick)
                else Modifier.menuClickable(onClick = onClick, onLongClick = { haptics.press(); contextOpen = true }),
            )
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
            imageModifier = imageModifier,
            onLoaded = onArtLoaded,
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
        if (subtitle.isNotBlank() || explicit) {
            SubtitleLine(
                subtitle,
                explicit = explicit,
                style = MaterialTheme.typography.bodySmall,
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
            Text(com.opentune.ui.theme.tr(title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    if (playing) {
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
                        0f to sheen.copy(alpha = 0f), 0.5f to sheen, 1f to sheen.copy(alpha = 0f),
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

/**
 * combinedClickable for an item whose long press opens a menu. A tap ripples as usual;
 * a long press stops the ripple as the menu opens. Held through the menu, the ripple
 * was still fading on the item when the lifted copy settled back onto it.
 */
@Composable
fun Modifier.menuClickable(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val pressed = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shown = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val current = remember { arrayOfNulls<androidx.compose.foundation.interaction.PressInteraction.Press>(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(pressed) {
        pressed.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> { current[0] = i; shown.emit(i) }
                is androidx.compose.foundation.interaction.PressInteraction.Release ->
                    if (current[0] === i.press) { current[0] = null; shown.emit(i) }
                is androidx.compose.foundation.interaction.PressInteraction.Cancel ->
                    if (current[0] === i.press) { current[0] = null; shown.emit(i) }
                else -> shown.emit(i)
            }
        }
    }
    return this
        // The app's own: a ripple on a phone, the focus ring on a TV.
        .indication(shown, androidx.compose.foundation.LocalIndication.current)
        .combinedClickable(
            interactionSource = pressed,
            indication = null,
            onLongClick = onLongClick?.let { long ->
                {
                    current[0]?.let { press ->
                        current[0] = null
                        scope.launch { shown.emit(androidx.compose.foundation.interaction.PressInteraction.Cancel(press)) }
                    }
                    long()
                }
            },
            onClick = onClick,
        )
}
