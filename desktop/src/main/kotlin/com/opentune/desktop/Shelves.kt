package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.ui.components.glass
import kotlinx.coroutines.launch
import coil3.request.crossfade

/**
 * What a shelf holds, with the web's columns for it (components/shelf.ts): how many rows, the
 * gap between them, a column's width on a narrow window, and how many columns share the width
 * once the viewport is 1000, 1260, 1580 and 1940px wide.
 */
internal enum class ShelfKind(val rows: Int, val rowGap: Dp, val narrowWidth: Dp, val columns: IntArray) {
    SQUARE(1, 24.dp, 144.dp, intArrayOf(4, 5, 6, 6)),
    TRACKS(4, 0.dp, 270.dp, intArrayOf(2, 3, 4, 4)),
    HORIZONTAL(2, 24.dp, 298.dp, intArrayOf(2, 3, 4, 4)),
    VIDEO(1, 24.dp, 200.dp, intArrayOf(3, 4, 5, 5)),
    CIRCLE(1, 24.dp, 118.dp, intArrayOf(6, 8, 10, 10)),
    TILES(2, 20.dp, 200.dp, intArrayOf(4, 5, 6, 6)),
}

/** A column's width and the gap between columns, for a shelf [pageWidth] wide. */
internal data class ShelfColumns(val width: Dp, val gap: Dp, val perPage: Int)

internal fun shelfColumns(kind: ShelfKind, pageWidth: Dp, gutter: Dp): ShelfColumns {
    val viewport = pageWidth + Web.Sidebar
    // Below 1000px the columns keep a fixed width and the shelf scrolls (shelf.ts).
    if (viewport < 1000.dp) return ShelfColumns(kind.narrowWidth, 10.dp, 1)
    val n = kind.columns[
        when {
            viewport >= 1940.dp -> 3
            viewport >= 1580.dp -> 2
            viewport >= 1260.dp -> 1
            else -> 0
        },
    ]
    val gap = Web.ShelfGap
    val width = ((pageWidth - gutter * 2 - gap * (n - 1)) / n).coerceAtLeast(kind.narrowWidth * 0.75f)
    return ShelfColumns(width, gap, n)
}

/** A column's width in the shelf around it, so a lockup can size itself to it. */
internal val LocalColumnWidth = compositionLocalOf { 172.dp }

/**
 * A shelf's scrolling row (ShelfView.tsx's ShelfScroller): columns sized to share the width, the
 * page's gutter at each end, and at each end a round frosted arrow that rises in while the
 * pointer is over the shelf (or the keyboard reaches it) and pages the row by a screenful.
 * [content] fills the row's columns; [LocalColumnWidth] tells each how wide to be.
 */
@Composable
internal fun ShelfScroller(kind: ShelfKind, modifier: Modifier = Modifier, content: LazyListScope.(ShelfColumns) -> Unit) {
    val gutter = LocalGutter.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = shelfColumns(kind, maxWidth, gutter)
        val state = rememberLazyListState()
        val hover = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxWidth().hoverable(hover)) {
            CompositionLocalProvider(LocalColumnWidth provides columns.width) {
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(columns.gap),
                ) { content(columns) }
            }
            ShelfArrows(state, hover, columns, gutter)
        }
    }
}

@Composable
private fun BoxScope.ShelfArrows(state: LazyListState, hover: MutableInteractionSource, columns: ShelfColumns, gutter: Dp) {
    val hovered by hover.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    val still = stillMotion()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Pages by one screen of columns, as the web's arrows do, landing on a column's edge.
    fun page(direction: Int) = scope.launch {
        val step = with(density) { (columns.width + columns.gap).toPx() }
        val at = state.firstVisibleItemIndex * step + state.firstVisibleItemScrollOffset
        val viewport = state.layoutInfo.viewportSize.width - with(density) { (gutter * 2).toPx() }
        val count = (viewport / step).toInt().coerceAtLeast(1)
        val target = (Math.round(at / step) + direction * count).coerceAtLeast(0) * step
        if (still) state.scrollBy(target - at) else state.animateScrollBy(target - at, tween(480, easing = Web.Emph))
    }
    ShelfArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page", hovered, state.canScrollBackward, Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { page(-1) }
    ShelfArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page", hovered, state.canScrollForward, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { page(1) }
}

/**
 * One of a shelf's arrows (.ot-arrow): a 40px frosted disc that fades in over 200ms and grows
 * from 0.8 over 350ms while the shelf is under the pointer, a little larger under the pointer
 * itself and smaller pressed. Out of reach when the shelf can't go that way.
 */
@Composable
private fun ShelfArrow(icon: ImageVector, label: String, shelfHovered: Boolean, can: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val focused by press.collectIsFocusedAsState()
    val shown = can && (shelfHovered || focused)
    val still = stillMotion()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (still) 0 else 200), label = "arrow alpha")
    val grow by animateFloatAsState(if (shown || still) 1f else 0.8f, tween(if (still) 0 else 350, easing = Web.Settle), label = "arrow scale")
    if (!can && alpha == 0f) return
    // The glass only while the arrow shows: hidden, it stays in place for the keyboard but draws nothing.
    val glass = if (alpha > 0f) Modifier.glass(CircleShape, MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)) else Modifier
    Box(
        modifier.graphicsLayer { this.alpha = alpha; scaleX = grow; scaleY = grow }
            .pressScale(press, hoverScale = 1.08f).size(40.dp)
            .shadow(if (alpha > 0f) 12.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .then(glass)
            .hoverable(press)
            .clickable(enabled = can, interactionSource = press, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = WebInk.primary, modifier = Modifier.size(24.dp)) }
}

/**
 * A shelf's title (ShelfView.tsx's ShelfTitle): 22px bold, the gutter in from the page's edge and
 * 14px above the shelf. With [onSeeAll] the title leads to the shelf's own page, a chevron after
 * it that steps aside and brightens under the pointer. [subtitle] runs under it in grey;
 * [action] sits at the far end.
 */
@Composable
internal fun ShelfHeader(title: String, onSeeAll: (() -> Unit)? = null, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    val gutter = LocalGutter.current
    Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = 14.dp).heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (onSeeAll == null) {
                Text(title, style = WebType.title2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            } else {
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                val focused by source.collectIsFocusedAsState()
                val lit = hovered || focused
                val still = stillMotion()
                val nudge by animateFloatAsState(if (lit && !still) 2f else 0f, tween(200, easing = Web.Settle), label = "chevron")
                val ink by animateColorAsState(if (lit) WebInk.primary else WebInk.secondary, tween(200, easing = Web.Settle), label = "chevron ink")
                Row(
                    Modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onSeeAll)
                        .semantics { heading(); contentDescription = "$title, see all" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = WebType.title2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink,
                        modifier = Modifier.padding(start = 2.dp).size(22.dp).graphicsLayer { translationX = nudge * density },
                    )
                }
            }
            subtitle?.let { Text(it, style = WebType.body, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
        }
        action?.invoke()
    }
}

/** A titled shelf: [ShelfHeader] over its content. */
@Composable
internal fun ShelfSection(
    title: String,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column {
        ShelfHeader(title, onSeeAll, subtitle, action)
        content()
    }
}

/**
 * Lays the items of a shelf in its columns, [kind]'s rows to a column, filling each column top
 * to bottom as the web's grid does; the first ten rise in one after another (motion.css's
 * ot-item-in), only when the shelf first shows.
 */
internal fun <T> LazyListScope.shelfItems(items: List<T>, kind: ShelfKind, since: Long, key: ((T) -> Any)? = null, item: @Composable (Int, T) -> Unit) {
    val columns = items.chunked(kind.rows)
    itemsIndexed(columns, key = key?.let { k -> { i, c -> "col$i:" + k(c.first()) } }) { col, group ->
        Column(
            Modifier.width(LocalColumnWidth.current).riseIn(delayMs = (col.coerceAtMost(7)) * 35, distance = 12.dp, since = if (col < 10) since else -1L),
            verticalArrangement = Arrangement.spacedBy(kind.rowGap),
        ) {
            group.forEachIndexed { r, t -> item(col * kind.rows + r, t) }
        }
    }
}

// ---- Lockups -----------------------------------------------------------------

/**
 * A card's artwork (.ot-lockup-art): under the pointer it lifts 4px with a soft shadow over
 * 400ms, the cover zooms to 1.06 in its frame over 700ms, a shade rises from the bottom over
 * 300ms and, for something playable, the play disc comes up with it. Still with smooth motion
 * off, where the shade and disc simply appear.
 */
@Composable
internal fun LockupArt(
    url: String?,
    shape: Shape,
    hovered: Boolean,
    onOpen: () -> Unit,
    openLabel: String,
    modifier: Modifier = Modifier,
    aspect: Float = 1f,
    onPlay: (() -> Unit)? = null,
    playLabel: String = "Play",
    art: (@Composable (Modifier) -> Unit)? = null,
) {
    val still = stillMotion()
    val lift by animateFloatAsState(if (hovered && !still) 1f else 0f, tween(400, easing = Web.Settle), label = "lift")
    val zoom by animateFloatAsState(if (hovered && !still) 1.06f else 1f, tween(700, easing = Web.Settle), label = "zoom")
    val shade by animateFloatAsState(if (hovered) 1f else 0f, tween(if (still) 0 else 300), label = "shade")
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect)
            .graphicsLayer { translationY = -4.dp.toPx() * lift }
            .shadow(14.dp * lift, shape, ambientColor = WebInk.radiosity, spotColor = WebInk.radiosity)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = openLabel },
    ) {
        val zoomed = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }
        if (art != null) art(zoomed) else CoverFill(url, zoomed)
        // The shade: clear at the top, darker toward the bottom, where the play disc sits.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = shade }.background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.42f))))
        // Artwork's hairline edge, so a dark cover keeps its shape on a dark page.
        Box(Modifier.matchParentSize().border(0.5.dp, WebInk.artworkStroke, shape))
        if (onPlay != null) PlayPlatter(hovered, playLabel, Modifier.align(Alignment.BottomEnd).padding(10.dp), onPlay)
    }
}

/** The cover filling its frame, cropped to it. */
@Composable
internal fun CoverFill(url: String?, modifier: Modifier) {
    val context = coil3.compose.LocalPlatformContext.current
    coil3.compose.AsyncImage(
        model = remember(url) { coil3.request.ImageRequest.Builder(context).data(com.opentune.data.subsonic.Subsonic.resolveCover(url)).crossfade(200).build() },
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * The play disc in a card's corner (Lockups.tsx's PlayPlatter, .ot-platter): 40px in the
 * accent with a white triangle, coming up from 10px lower and 0.8 its size as it fades in, a
 * little larger under the pointer itself and smaller pressed.
 */
@Composable
private fun PlayPlatter(shown: Boolean, label: String, modifier: Modifier, onPlay: () -> Unit) {
    val still = stillMotion()
    val press = remember { MutableInteractionSource() }
    val focused by press.collectIsFocusedAsState()
    val visible = shown || focused
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (still) 0 else 200), label = "platter alpha")
    val rise by animateFloatAsState(if (visible || still) 1f else 0f, tween(400, easing = Web.Settle), label = "platter rise")
    Box(
        modifier.graphicsLayer {
            this.alpha = alpha
            translationY = (1f - rise) * 10.dp.toPx()
            val s = 0.8f + 0.2f * rise
            scaleX = s; scaleY = s
        }
            .pressScale(press, hoverScale = 1.1f).size(40.dp)
            .shadow(if (alpha > 0f) 10.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(CircleShape).background(WebInk.keyFill)
            .hoverable(press)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.PlayArrow, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/**
 * A text that leads somewhere, underlined under the pointer as the web's links are.
 */
@Composable
internal fun LinkText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, onClick: (() -> Unit)?, modifier: Modifier = Modifier, align: TextAlign? = null) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
        textDecoration = if (hovered && onClick != null) TextDecoration.Underline else null,
        modifier = if (onClick == null) modifier else modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
    )
}

/**
 * A square card (Lockups.tsx's SquareLockup): the artwork with its hover lift, then 8px below
 * it the title in 12px, the explicit mark, the ⋯ menu for a song under the pointer, and the grey
 * line. A song plays from its card and opens its menu on a right click; anything else opens.
 */
@Composable
internal fun SquareLockup(
    item: ShelfItem,
    onOpen: () -> Unit,
    song: Song? = null,
    aspect: Float = 1f,
    shape: Shape = RoundedCornerShape(Web.ArtMedium),
    art: (@Composable (Modifier) -> Unit)? = null,
    onPlay: (() -> Unit)? = song?.let { { onOpen() } },
    fill: Boolean = false,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // In a shelf a card takes its column's width; in a grid ([fill]) the cell's.
    val sized = if (fill) Modifier.fillMaxWidth() else Modifier.width(LocalColumnWidth.current)
    val body = @Composable {
        Column(sized.hoverable(hover)) {
            LockupArt(item.thumbnailUrl, shape, hovered, onOpen, item.title, aspect = aspect, onPlay = onPlay, playLabel = "Play ${item.title}", art = art)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinkText(item.title, WebType.callout, WebInk.primary, onOpen, Modifier.weight(1f, fill = false))
                    if (item.explicit) ExplicitBadge()
                    if (song != null) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.graphicsLayer { alpha = if (hovered) 1f else 0f }) { SongMoreButton(song, size = 24.dp) }
                    }
                }
                if (item.subtitle.isNotBlank()) Text(item.subtitle, style = WebType.callout, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (song != null) SongContextMenu(song) { body() } else body()
}

/**
 * An artist's round card (CircleLockup): the photo, lifting and zooming under the pointer as a
 * cover does, and 10px under it the name in 13px, centred, underlined under the pointer.
 */
@Composable
internal fun CircleLockup(item: ShelfItem, onOpen: () -> Unit, line: String? = null) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier.width(LocalColumnWidth.current).hoverable(hover),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockupArt(item.thumbnailUrl, CircleShape, hovered, onOpen, item.title)
        Text(
            item.title, style = WebType.body, color = WebInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            textDecoration = if (hovered) TextDecoration.Underline else null, modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        )
        line?.let { Text(it, style = WebType.callout, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
}

/**
 * A song in a shelf of rows (Lockups.tsx's TrackLockup): 55px tall, the 40px cover with a play
 * mark over it under the pointer, the title in 13px and who sings it in 12px, the ⋯ menu at the
 * end; the row's faint fill comes at once under the pointer and fades over 200ms after.
 */
@Composable
internal fun TrackLockup(song: Song, onPlay: () -> Unit, current: Boolean = false, playing: Boolean = false, line: String = song.artist) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val focused by hover.collectIsFocusedAsState()
    val lit = hovered || focused
    val fill by animateColorAsState(if (lit) WebInk.rowHover else WebInk.rowHover.copy(alpha = 0f), tween(if (lit) 0 else 200), label = "row fill")
    val mark by animateFloatAsState(if (lit) 1f else 0f, tween(200), label = "row mark")
    SongContextMenu(song) {
        Row(
            Modifier.width(LocalColumnWidth.current).height(55.dp).clip(RoundedCornerShape(Web.TrackRow)).background(fill)
                .hoverable(hover)
                .clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onPlay)
                .semantics { contentDescription = "Play ${song.title}, ${song.artist}" }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Web.ArtSmall)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                CoverFill(song.thumbnailUrl, Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().border(0.5.dp, WebInk.artworkStroke, RoundedCornerShape(Web.ArtSmall)))
                // The play mark under the pointer; bars while it's the song playing.
                Box(Modifier.matchParentSize().graphicsLayer { alpha = if (current) 1f else mark }.background(Color(0x66333333)), contentAlignment = Alignment.Center) {
                    if (current && !lit) PlayingBars(playing) else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(song.title, style = WebType.body, color = if (current) WebInk.key else WebInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (song.isExplicit == true) ExplicitBadge()
                }
                if (line.isNotBlank()) Text(line, style = WebType.callout, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.graphicsLayer { alpha = mark }) { SongMoreButton(song, size = 28.dp) }
        }
    }
}

/**
 * Songs in four rows to a column that scroll sideways (ShelfView.tsx's TrackShelf), as Quick
 * picks, Recently played and an artist's Top songs: any one plays the whole shelf from there.
 */
@Composable
internal fun TrackShelf(title: String, songs: List<Song>, onSeeAll: (() -> Unit)? = null, subtitle: String? = null, rows: Int = ShelfKind.TRACKS.rows) {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val playingId = queue.getOrNull(index)?.videoId
    val since = remember(songs) { System.nanoTime() }
    val perColumn = rows.coerceAtMost(songs.size).coerceAtLeast(1)
    ShelfSection(title, subtitle, onSeeAll) {
        ShelfScroller(ShelfKind.TRACKS) {
            val columns = songs.chunked(perColumn)
            itemsIndexed(columns) { col, group ->
                Column(Modifier.width(LocalColumnWidth.current).riseIn(delayMs = col.coerceAtMost(7) * 35, distance = 12.dp, since = if (col < 10) since else -1L)) {
                    group.forEachIndexed { r, song ->
                        val at = col * perColumn + r
                        TrackLockup(song, onPlay = { Player.playAll(songs, at) }, current = song.videoId == playingId, playing = playing)
                    }
                }
            }
        }
    }
}

/**
 * A browse tile (CategoryTiles.tsx): a 16:9 tile of a mood or genre's colour, lit from its top
 * right corner, its name in 22px bold at the bottom left; a touch larger under the pointer.
 */
@Composable
internal fun CategoryTile(title: String, colour: Color, onOpen: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val still = stillMotion()
    val grow by animateFloatAsState(if (hovered && !still) 1.015f else 1f, tween(200, easing = Web.Panel), label = "tile")
    val shape = RoundedCornerShape(Web.ArtLarge)
    Box(
        Modifier.width(LocalColumnWidth.current).aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = grow; scaleY = grow }
            .clip(shape)
            .background(Brush.linearGradient(listOf(colour, androidx.compose.ui.graphics.lerp(colour, Color.Black, 0.45f))))
            // Lit from the top right corner, as the web's tiles are (a radial wash of the colour toward white).
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(androidx.compose.ui.graphics.lerp(colour, Color.White, 0.3f).copy(alpha = 0.7f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width, 0f), radius = size.width * 0.9f,
                    ),
                )
            }
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = WebType.title2.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.3f), blurRadius = 8f, offset = androidx.compose.ui.geometry.Offset(0f, 1f))),
        )
    }
}

/** A shelf's shape, shimmering, while it loads (the web's ShelfSkeleton). */
@Composable
internal fun ShelfSkeleton(kind: ShelfKind = ShelfKind.SQUARE) {
    val gutter = LocalGutter.current
    Column {
        Placeholder(Modifier.padding(start = gutter, bottom = 14.dp).width(180.dp).height(22.dp), RoundedCornerShape(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = shelfColumns(kind, maxWidth, gutter)
            Row(Modifier.padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(columns.gap)) {
                repeat(columns.perPage.coerceAtLeast(5)) {
                    Column(Modifier.width(columns.width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Placeholder(Modifier.fillMaxWidth().aspectRatio(1f), if (kind == ShelfKind.CIRCLE) CircleShape else RoundedCornerShape(Web.ArtMedium))
                        Spacer(Modifier.height(2.dp))
                        Placeholder(Modifier.fillMaxWidth(0.7f).height(12.dp), RoundedCornerShape(6.dp))
                        Placeholder(Modifier.fillMaxWidth(0.45f).height(12.dp), RoundedCornerShape(6.dp))
                    }
                }
            }
        }
    }
}
