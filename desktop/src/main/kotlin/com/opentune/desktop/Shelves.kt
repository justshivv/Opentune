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
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
 * What a shelf holds, and how wide its columns are: square cards 188dp apart by 17, songs in
 * columns of four rows about 500dp wide, artists' round photos, mood tiles.
 */
internal enum class ShelfKind(val rows: Int, val rowGap: Dp, val width: Dp, val gap: Dp) {
    SQUARE(1, 0.dp, 188.dp, 17.dp),
    TRACKS(4, 0.dp, 500.dp, 24.dp),
    HORIZONTAL(2, 24.dp, 298.dp, 17.dp),
    VIDEO(1, 0.dp, 320.dp, 17.dp),
    CIRCLE(1, 0.dp, 188.dp, 17.dp),
    TILES(1, 0.dp, 300.dp, 15.dp),
}

/** A column's width and the gap between columns, for a shelf [pageWidth] wide. */
internal data class ShelfColumns(val width: Dp, val gap: Dp, val perPage: Int)

internal fun shelfColumns(kind: ShelfKind, pageWidth: Dp, gutter: Dp): ShelfColumns {
    val room = pageWidth - gutter * 2
    // Songs keep a sliver of the next column in view on a narrow window, so it reads as a row that scrolls.
    val width = if (kind == ShelfKind.TRACKS) minOf(kind.width, room - 48.dp).coerceAtLeast(260.dp) else kind.width
    val n = ((room + kind.gap) / (width + kind.gap)).toInt().coerceAtLeast(1)
    return ShelfColumns(width, kind.gap, n)
}

/** A column's width in the shelf around it, so a lockup can size itself to it. */
internal val LocalColumnWidth = compositionLocalOf { 188.dp }

/**
 * A shelf's scrolling row: columns of the kind's width, the page's gutter at each end, and at
 * each end a round frosted arrow that rises in while the pointer is over the shelf (or the
 * keyboard reaches it) and pages the row by a screenful. [content] fills the row's columns;
 * [LocalColumnWidth] tells each how wide to be.
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
    // Pages by one screen of columns, landing on a column's edge.
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
 * One of a shelf's arrows: a 40px frosted disc that fades in while the shelf is under the
 * pointer, a little larger under the pointer itself and smaller pressed. Out of reach when the
 * shelf can't go that way.
 */
@Composable
private fun ShelfArrow(icon: ImageVector, label: String, shelfHovered: Boolean, can: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val focused by press.collectIsFocusedAsState()
    val shown = can && (shelfHovered || focused)
    val still = stillMotion()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (still) 0 else 160), label = "arrow alpha")
    val grow by animateFloatAsState(if (shown || still) 1f else 0.85f, tween(if (still) 0 else 220, easing = Web.Settle), label = "arrow scale")
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
 * A shelf's heading: bold 26sp, the gutter in from the page's edge and 16dp above the shelf, with
 * the shelf's grey strapline under it when it has one. With [onSeeAll] the title leads to the
 * shelf's own page, a chevron after it that steps aside and brightens under the pointer.
 * [action] sits at the far end.
 */
@Composable
internal fun ShelfHeader(title: String, onSeeAll: (() -> Unit)? = null, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    val gutter = LocalGutter.current
    Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 6.dp, bottom = 16.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (onSeeAll == null) {
                Text(title, style = PageType.heading, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            } else {
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                val focused by source.collectIsFocusedAsState()
                val lit = hovered || focused
                val still = stillMotion()
                val nudge by animateFloatAsState(if (lit && !still) 3f else 0f, tween(PAGE_HOVER_MS, easing = Web.Settle), label = "chevron")
                val ink by animateColorAsState(if (lit) PageInk.text else PageInk.grey, tween(PAGE_HOVER_MS), label = "chevron ink")
                Row(
                    Modifier.hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onSeeAll)
                        .semantics { heading(); contentDescription = "$title, see all" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = PageType.heading, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink,
                        modifier = Modifier.padding(start = 2.dp).size(28.dp).graphicsLayer { translationX = nudge * density },
                    )
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it.uppercase(), style = PageType.meta, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        action?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { it() } }
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
 * to bottom; the first ten rise in one after another, only when the shelf first shows.
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

/** A card's corners. */
internal val CardShape = RoundedCornerShape(6.dp)

/**
 * A card's artwork: under the pointer it lifts a little with a soft shadow, the cover eases
 * larger in its frame, a shade rises from the bottom and, for something playable, a round play
 * button comes up in its corner. Still with smooth motion off, where the shade and button simply
 * appear.
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
    /** False when the card around it takes the click (an artist's photo and name are one). */
    clickable: Boolean = true,
) {
    val still = stillMotion()
    val lift by animateFloatAsState(if (hovered && !still) 1f else 0f, tween(160, easing = Web.Settle), label = "lift")
    val zoom by animateFloatAsState(if (hovered && !still) 1.04f else 1f, tween(260, easing = Web.Settle), label = "zoom")
    val shade by animateFloatAsState(if (hovered) 1f else 0f, tween(if (still) 0 else 140), label = "shade")
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect)
            .graphicsLayer { translationY = -3.dp.toPx() * lift }
            .shadow(12.dp * lift, shape, ambientColor = WebInk.radiosity, spotColor = WebInk.radiosity)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (clickable) Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = openLabel }
                else Modifier,
            ),
    ) {
        val zoomed = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }
        if (art != null) art(zoomed) else CoverFill(url, zoomed)
        // The shade: clear at the top, darker toward the bottom, where the play button sits.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = shade }.background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.4f))))
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
 * The play button in a card's corner: 44dp in the accent with a white triangle, coming up from
 * a little lower and smaller as it fades in, larger under the pointer itself and smaller pressed.
 */
@Composable
private fun PlayPlatter(shown: Boolean, label: String, modifier: Modifier, onPlay: () -> Unit) {
    val still = stillMotion()
    val press = remember { MutableInteractionSource() }
    val focused by press.collectIsFocusedAsState()
    val visible = shown || focused
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (still) 0 else 140), label = "platter alpha")
    val rise by animateFloatAsState(if (visible || still) 1f else 0f, tween(200, easing = Web.Settle), label = "platter rise")
    Box(
        modifier.graphicsLayer {
            this.alpha = alpha
            translationY = (1f - rise) * 8.dp.toPx()
            val s = 0.85f + 0.15f * rise
            scaleX = s; scaleY = s
        }
            .pressScale(press, hoverScale = 1.08f).size(44.dp)
            .shadow(if (alpha > 0f) 10.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(CircleShape).background(WebInk.keyFill)
            .hoverable(press)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.PlayArrow, label, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

/**
 * A text that leads somewhere, underlined under the pointer.
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
 * A square card: the cover with 6dp corners and its hover lift, then under it the title in bold
 * 17sp on one line, the explicit mark, the ⋯ menu for a song under the pointer, and the grey line
 * ("Single • Artist"). A song plays from its card and opens its menu on a right click; anything
 * else opens, and plays from the round button over its corner when [onPlay] is given.
 */
@Composable
internal fun SquareLockup(
    item: ShelfItem,
    onOpen: () -> Unit,
    song: Song? = null,
    aspect: Float = 1f,
    shape: Shape = CardShape,
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
            Column(Modifier.padding(top = 10.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        LinkText(item.title, PageType.cardTitle, PageInk.text, onOpen, Modifier.weight(1f, fill = false))
                        if (item.explicit) ExplicitBadge()
                    }
                    // A song's ⋯ under the pointer only, so at rest the title has the card's width.
                    if (song != null && hovered) SongMoreButton(song, size = 26.dp)
                }
                if (item.subtitle.isNotBlank()) Text(item.subtitle, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (song != null) SongContextMenu(song) { body() } else body()
}

/**
 * An artist's round card: the photo, lifting under the pointer as a cover does, and under it the
 * name in bold, centred, underlined under the pointer, with [line] in grey beneath. The whole card
 * opens the artist, photo and name alike, under one hover.
 */
@Composable
internal fun CircleLockup(item: ShelfItem, onOpen: () -> Unit, line: String? = null, fill: Boolean = false) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        (if (fill) Modifier.fillMaxWidth() else Modifier.width(LocalColumnWidth.current)).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = item.title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockupArt(item.thumbnailUrl, CircleShape, hovered, onOpen, item.title, clickable = false)
        Text(
            item.title, style = PageType.cardTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            textDecoration = if (hovered) TextDecoration.Underline else null, modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        )
        line?.let { Text(it, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp).fillMaxWidth()) }
    }
}

/**
 * A song in a shelf of rows: the 60dp cover with 4dp corners and a play mark over it under the
 * pointer, the title in bold 17sp and who sings it in grey 15sp, and the vertical ⋮ menu at the
 * end; the row's faint fill comes up under the pointer.
 */
@Composable
internal fun TrackLockup(
    song: Song, onPlay: () -> Unit, current: Boolean = false, playing: Boolean = false, line: String = song.artist, modifier: Modifier = Modifier,
    /** The shelf's songs and this one's place in them, for picking it with the others. */
    songs: List<Song>? = null, at: Int = -1,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val focused by hover.collectIsFocusedAsState()
    val lit = hovered || focused
    val pick = if (songs != null && at >= 0) rememberRowPick(songs, at) else null
    val picked = pick?.picked == true
    val picking = pick?.selection?.active == true
    val pickedFill = keyColour().copy(alpha = 0.16f)
    val fill by animateColorAsState(if (picked) pickedFill else if (lit) PageInk.hover else PageInk.hover.copy(alpha = 0f), tween(PAGE_HOVER_MS), label = "row fill")
    val mark by animateFloatAsState(if (lit && !picking) 1f else 0f, tween(PAGE_HOVER_MS), label = "row mark")
    val shape = RoundedCornerShape(4.dp)
    SongContextMenu(song) {
        Row(
            modifier.width(LocalColumnWidth.current).height(76.dp).clip(RoundedCornerShape(6.dp)).background(fill)
                .hoverable(hover)
                .then(pick?.capture ?: Modifier)
                .clickable(interactionSource = hover, indication = null, role = Role.Button) { pick?.click(onPlay) ?: onPlay() }
                .semantics { contentDescription = "Play ${song.title}, ${song.artist}" }
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                CoverFill(song.thumbnailUrl, Modifier.fillMaxSize())
                // The play mark under the pointer; bars while it's the song playing.
                Box(Modifier.matchParentSize().graphicsLayer { alpha = if (current) 1f else mark }.background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    if (current && !lit) PlayingBars(playing) else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                // Its box: in the cover's corner under the pointer, over the whole cover while picking.
                if (pick != null && picking) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (picked) 0.2f else 0.4f)), contentAlignment = Alignment.Center) {
                    PickBox(picked, song.title, onToggle = pick::toggle)
                } else if (pick != null && lit) PickBox(picked, song.title, Modifier.align(Alignment.TopStart), size = 16.dp, onToggle = pick::toggle)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(song.title, style = PageType.cardTitle, color = if (current) WebInk.key else PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (song.isExplicit == true) ExplicitBadge()
                }
                if (line.isNotBlank()) Text(line, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            SongMoreButton(song, size = 36.dp, vertical = true)
        }
    }
}

/**
 * Songs in four rows to a column that scroll sideways, as Recents, Quick picks and an artist's
 * Top songs: any one plays the whole shelf from there. The grid button by the heading lays them
 * all out at once, in as many columns as fit, and back.
 */
@Composable
internal fun TrackShelf(
    title: String,
    songs: List<Song>,
    onSeeAll: (() -> Unit)? = null,
    subtitle: String? = null,
    rows: Int = ShelfKind.TRACKS.rows,
    actions: (@Composable () -> Unit)? = null,
) {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val playingId = queue.getOrNull(index)?.videoId
    val since = remember(songs) { System.nanoTime() }
    val perColumn = rows.coerceAtMost(songs.size).coerceAtLeast(1)
    var spread by remember { mutableStateOf(false) }
    val gutter = LocalGutter.current
    ShelfSection(title, subtitle, onSeeAll, action = {
        actions?.invoke()
        if (songs.size > perColumn) PageIconButton(
            if (spread) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
            if (spread) "Show $title as a row" else "Show all of $title", lit = spread,
        ) { spread = !spread }
    }) {
        if (spread) {
            // Everything at once: as many columns as fit the width, filled row by row.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                val gap = ShelfKind.TRACKS.gap
                val n = ((maxWidth + gap) / (ShelfKind.TRACKS.width * 0.8f + gap)).toInt().coerceIn(1, 4)
                val w = (maxWidth - gap * (n - 1)) / n
                CompositionLocalProvider(LocalColumnWidth provides w) {
                    Column {
                        songs.chunked(n).forEachIndexed { r, line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                line.forEachIndexed { c, song ->
                                    val at = r * n + c
                                    TrackLockup(song, onPlay = { Player.playAll(songs, at) }, current = song.videoId == playingId, playing = playing, songs = songs, at = at)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            ShelfScroller(ShelfKind.TRACKS) {
                val columns = songs.chunked(perColumn)
                itemsIndexed(columns) { col, group ->
                    Column(Modifier.width(LocalColumnWidth.current).riseIn(delayMs = col.coerceAtMost(7) * 35, distance = 12.dp, since = if (col < 10) since else -1L)) {
                        group.forEachIndexed { r, song ->
                            val at = col * perColumn + r
                            TrackLockup(song, onPlay = { Player.playAll(songs, at) }, current = song.videoId == playingId, playing = playing, songs = songs, at = at)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A mood or genre's tile, 188dp tall with 10dp corners: a solid band of its colour down the left
 * 28% of the tile, the rest its playlist's cover dyed deep in the same colour (or the colour in a
 * gradient while there's no cover), and its name in bold white at the top left. It brightens and
 * grows a touch under the pointer.
 */
@Composable
internal fun MoodTile(title: String, colour: Color, cover: String?, onOpen: () -> Unit, modifier: Modifier = Modifier, height: Dp = 188.dp) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = hovered || focused
    val still = stillMotion()
    val grow by animateFloatAsState(if (lit && !still) 1.02f else 1f, tween(160, easing = Web.Settle), label = "tile grow")
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(PAGE_HOVER_MS), label = "tile glow")
    val shape = RoundedCornerShape(10.dp)
    val deep = androidx.compose.ui.graphics.lerp(colour, Color.Black, 0.35f)
    Box(
        modifier.height(height)
            .graphicsLayer { scaleX = grow; scaleY = grow }
            .clip(shape)
            .background(colour)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = title },
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.28f).fillMaxSize().background(colour))
            Box(
                Modifier.weight(0.72f).fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .background(Brush.linearGradient(listOf(colour, deep)))
                    .drawWithContent {
                        drawContent()
                        // Dyed in the tile's colour: multiplied through, then a veil of it over the top.
                        drawRect(colour, blendMode = BlendMode.Multiply)
                        drawRect(colour.copy(alpha = 0.35f))
                    },
            ) { if (cover != null) CoverFill(cover, Modifier.fillMaxSize()) }
        }
        Box(Modifier.matchParentSize().graphicsLayer { alpha = glow }.background(Color.White.copy(alpha = 0.07f)))
        Text(
            title, color = Color.White, style = PageType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** The tile's colour for a mood: the one YouTube Music gives it, or one picked from its name. */
internal fun moodColour(title: String): Color {
    Pages.moodColour(title)?.let { return Color(it) }
    val palette = listOf(0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457, 0xFF4E342E, 0xFF283593, 0xFF9E9D24)
    return Color(palette[(title.hashCode() and 0x7fffffff) % palette.size])
}

/** A shelf's shape, shimmering, while it loads. */
@Composable
internal fun ShelfSkeleton(kind: ShelfKind = ShelfKind.SQUARE) {
    val gutter = LocalGutter.current
    Column {
        Placeholder(Modifier.padding(start = gutter, bottom = 16.dp).width(200.dp).height(26.dp), RoundedCornerShape(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = shelfColumns(kind, maxWidth, gutter)
            Row(Modifier.padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(columns.gap)) {
                repeat(columns.perPage + 1) {
                    Column(Modifier.width(columns.width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Placeholder(Modifier.fillMaxWidth().aspectRatio(1f), if (kind == ShelfKind.CIRCLE) CircleShape else CardShape)
                        Spacer(Modifier.height(4.dp))
                        Placeholder(Modifier.fillMaxWidth(0.7f).height(14.dp), RoundedCornerShape(6.dp))
                        Placeholder(Modifier.fillMaxWidth(0.45f).height(12.dp), RoundedCornerShape(6.dp))
                    }
                }
            }
        }
    }
}
