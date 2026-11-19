package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.items
import com.opentune.data.model.ShelfItem

/** How wide the page is, for the parts of it that change with the width as the web's do. */
internal val LocalPageWidth = compositionLocalOf { 1200.dp }

/**
 * Which row of a track list is under the pointer, shared by the list's rows: the hairline above
 * a row goes when it or the row before it is lit, as the web's rows do.
 */
internal val LocalTrackHover = compositionLocalOf<MutableIntState?> { null }

/**
 * A page that scrolls, headed by its title: for the content pages ([webGutter]) bold 30sp with
 * the pages' 20dp gutter, otherwise the web's 800-weight title and the app's own margins (Search,
 * Listen together). [eyebrow] is a short line over the title; [trailing] sits at the head's far
 * end. A null [title] leaves the head out. The head rises in as the page opens.
 */
@Composable
internal fun ScrollPage(
    title: String?,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 36.dp,
    trailing: (@Composable () -> Unit)? = null,
    eyebrow: String? = null,
    webGutter: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val gutter = if (webGutter) Web.gutter(width) else GUTTER
        val hover = remember { mutableIntStateOf(-1) }
        CompositionLocalProvider(LocalGutter provides gutter, LocalPageWidth provides width, LocalTrackHover provides hover) {
            LazyColumn(state = state, contentPadding = PaddingValues(top = LocalPageTop.current, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(spacing)) {
                if (title != null) item(key = "page-title") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = if (webGutter) 24.dp else if (eyebrow != null) 36.dp else 32.dp).riseIn(),
                        verticalAlignment = if (webGutter) Alignment.CenterVertically else Alignment.Bottom,
                    ) {
                        Column(Modifier.weight(1f)) {
                            eyebrow?.let { Text(it, style = if (webGutter) PageType.kicker else webType(13f, 16f, 600), color = if (webGutter) PageInk.grey else WebInk.key, modifier = Modifier.padding(bottom = if (webGutter) 4.dp else 9.dp)) }
                            Text(
                                title,
                                style = if (webGutter) PageType.pageTitle else WebType.header(Web.headerSize(width), if (eyebrow != null) -0.03f else 0f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                            )
                            subtitle?.let { Text(it, style = if (webGutter) PageType.cardLine else WebType.title3, color = if (webGutter) PageInk.grey else WebInk.secondary, modifier = Modifier.padding(top = 6.dp)) }
                        }
                        trailing?.let { Box(Modifier.padding(start = 16.dp)) { it() } }
                    }
                }
                content()
            }
        }
    }
}

/** Search within a list: whether its field is open, and what's typed in it. Set by [CollectionLayout] for its head's button. */
internal class PageListFilter {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
}

internal val LocalListFilter = compositionLocalOf<PageListFilter?> { null }

/** "75 SONGS • 4 HR 17 MIN", from the songs' own lengths. */
internal fun pageLengthLine(songs: List<Song>): String {
    val seconds = songs.sumOf { s -> s.durationText?.let { Player.seconds(it) }?.toInt() ?: 0 }
    val count = if (songs.size == 1) "1 SONG" else "${songs.size} SONGS"
    if (seconds <= 0) return count
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return "$count • " + if (h > 0) "$h HR $m MIN" else if (m > 0) "$m MIN" else "${seconds % 60} SEC"
}

/**
 * The head of an album, playlist or mix page: the cover large and square on the left (360dp on a
 * wide window, smaller as it narrows) and beside it, centred, what kind of list it is in small
 * spaced capitals, the title in bold, who made it, a grey line of facts ("PLAYLIST • 2025 • 75
 * SONGS • 4 HR 17 MIN"), and a row of round buttons: Shuffle, Play, search in the list, then
 * [extra] (the list's ⋯ menu). [kind] may carry facts after its first word ("Album • 2020").
 * On a narrow window it stacks, centred.
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
    val gutter = LocalGutter.current
    val width = LocalPageWidth.current
    val stacked = width < 600.dp
    val coverSize = if (stacked) 240.dp else (width * 0.27f).coerceIn(200.dp, 360.dp)
    val titleSize = if (stacked) 30f else (width.value * 0.033f).coerceIn(30f, 44f)
    val shape: Shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    val ready = !songs.isNullOrEmpty()
    val kinds = kind.split(" • ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
    val kicker = kinds.firstOrNull()?.uppercase().orEmpty()
    val meta = (kinds.map { it.uppercase() } + listOfNotNull(songs?.takeIf { it.isNotEmpty() }?.let(::pageLengthLine))).joinToString(" • ")
    val filter = LocalListFilter.current
    val align = if (stacked) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (stacked) TextAlign.Center else TextAlign.Start

    val art = @Composable {
        Box(Modifier.riseIn(durationMs = 500, distance = 12.dp, fromScale = 0.96f).size(coverSize)) {
            cover(Modifier.matchParentSize().clip(shape).semantics { contentDescription = "$title cover" })
            Box(Modifier.matchParentSize().border(0.5.dp, WebInk.artworkStroke, shape))
        }
    }
    val words = @Composable {
        Column(Modifier.riseIn(delayMs = 60), horizontalAlignment = align) {
            if (kicker.isNotBlank()) Text(kicker, style = PageType.kicker, color = PageInk.grey, maxLines = 1)
            Text(
                title, style = PageType.bigTitle(titleSize), color = PageInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = textAlign, modifier = Modifier.padding(top = 6.dp).semantics { heading() },
            )
            if (subtitle.isNotBlank()) Text(subtitle, style = PageType.owner, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = Modifier.padding(top = 12.dp))
            if (meta.isNotBlank()) Text(meta, style = PageType.meta, color = PageInk.grey, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PageRoundButton(Icons.Rounded.Shuffle, "Shuffle", enabled = ready) { songs?.let { Player.playAll(it.shuffled(), 0) } }
                PageRoundButton(Icons.Rounded.PlayArrow, "Play", solid = true, iconSize = 32.dp, enabled = ready) { songs?.let { Player.playAll(it, 0) } }
                if (filter != null) PageRoundButton(Icons.Rounded.Search, if (filter.open) "Close search" else "Search in $title", enabled = ready) {
                    filter.open = !filter.open
                    if (!filter.open) filter.query = ""
                }
                extra()
            }
        }
    }

    if (stacked) {
        Column(
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = LocalPageTop.current + 16.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            art()
            Spacer(Modifier.height(22.dp))
            words()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = LocalPageTop.current + 20.dp, bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            art()
            Box(Modifier.padding(start = 32.dp).weight(1f)) { words() }
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

/** The web's Play pill, for the one thing a page is for. */
@Composable
internal fun PrimaryButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    PillButton40(label, icon, enabled, minWidth = 120.dp, onClick = onClick)
}

/**
 * A quiet pill beside the main one, as Home's links on the web (.ot-home-links): the faintest
 * fill, a stronger one under the pointer, 40px tall to stand level with Play.
 */
@Composable
internal fun SecondaryButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered && enabled) WebInk.quaternary else WebInk.quinary, tween(150), label = "secondary")
    val ink = WebInk.primary.copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        Modifier.pressScale(source).height(40.dp).clip(RoundedCornerShape(24.dp)).background(fill)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Text(label, color = ink, style = WebType.bodyEmph, maxLines = 1, softWrap = false)
    }
}

/**
 * Where a track row's columns fall, for a row [rowWidth] wide: the artist from 45% of the width
 * and the album from 62%, the length and buttons in [right] at the end. A narrow row drops the
 * album first, then the artist, which then runs under the title.
 */
internal data class PageTrackColumns(val title: Dp, val artist: Dp?, val album: Dp?, val right: Dp)

/** Index (40), a gap, the 56dp cover and the gap after it. */
private val TRACK_LEAD = 40.dp + 8.dp + 56.dp + 16.dp
private const val TRACK_MIN_COLUMN = 110

internal fun pageTrackColumns(rowWidth: Dp, buttons: Int): PageTrackColumns {
    val right = 56.dp + 12.dp + 34.dp * buttons + 6.dp
    val artistX = rowWidth * 0.45f
    val albumX = rowWidth * 0.62f
    val end = rowWidth - right
    val showAlbum = end - albumX - 16.dp >= TRACK_MIN_COLUMN.dp && albumX - artistX - 16.dp >= TRACK_MIN_COLUMN.dp && artistX - TRACK_LEAD >= 160.dp
    val showArtist = showAlbum || (end - artistX - 16.dp >= TRACK_MIN_COLUMN.dp && artistX - TRACK_LEAD >= 140.dp)
    return when {
        showAlbum -> PageTrackColumns(artistX - TRACK_LEAD - 16.dp, albumX - artistX - 16.dp, end - albumX - 16.dp, right)
        showArtist -> PageTrackColumns(artistX - TRACK_LEAD - 16.dp, end - artistX - 16.dp, null, right)
        else -> PageTrackColumns((end - TRACK_LEAD - 12.dp).coerceAtLeast(60.dp), null, null, right)
    }
}

/**
 * A track list's rows, each a [TrackRow], for a LazyColumn. [onRemove] gives each row a
 * remove button (your own playlists); [album] names the album for rows that don't know theirs.
 * [numbered] is kept for callers from before, when an album's rows had no covers.
 */
internal fun LazyListScope.tracks(songs: List<Song>, onRemove: ((Song) -> Unit)? = null, numbered: Boolean = false, album: String? = null) {
    itemsIndexed(songs, key = { i, s -> "t$i:${s.videoId}" }) { i, song -> TrackRow(i, song, songs, onRemove, album = album) }
}

/**
 * One track, as the reference lays a list out: a full-width row 70dp tall, every other one on a
 * faint stripe; its number, the 56dp cover, the title, then the artist and album in columns, and
 * at the end its length and buttons (remove from your own playlist, like, add to a playlist or
 * the queue). Under the pointer the row brightens and the number turns into a play mark; the
 * playing row keeps a stronger fill, its title in the accent and bars for its number. A click
 * plays the list from here; a right click opens the song's menu.
 */
@Composable
internal fun TrackRow(i: Int, song: Song, songs: List<Song>, onRemove: ((Song) -> Unit)? = null, numbered: Boolean = false, album: String? = null) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = hovered || focused
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val liked by Library.liked.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val current = queue.getOrNull(index)?.videoId == song.videoId
    val stripe = if (i % 2 == 0) PageInk.stripe else PageInk.stripe.copy(alpha = 0f)
    val fill by animateColorAsState(
        when { current -> PageInk.current; lit -> PageInk.hover; else -> stripe },
        tween(PAGE_HOVER_MS), label = "row fill",
    )
    val gutter = LocalGutter.current
    val cols = pageTrackColumns(LocalPageWidth.current - gutter * 2, if (onRemove != null) 3 else 2)
    val play = { if (current) Player.toggle() else Player.playAll(songs, i) }
    var adding by remember { mutableStateOf(false) }
    SongContextMenu(song) {
        Row(
            Modifier.padding(horizontal = gutter).fillMaxWidth().height(70.dp)
                .clip(RoundedCornerShape(4.dp)).background(fill)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = play)
                .semantics { contentDescription = "${song.title}, ${song.artist}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                when {
                    lit -> Icon(if (current && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (current && playing) "Pause" else "Play", tint = PageInk.text, modifier = Modifier.size(20.dp))
                    current -> PlayingBars(playing)
                    else -> Text("${i + 1}", style = PageType.small.copy(fontFeatureSettings = "tnum"), color = PageInk.grey, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                CoverFill(song.thumbnailUrl, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(cols.title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(song.title, style = PageType.rowTitle, color = if (current) WebInk.key else PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (song.isExplicit == true) ExplicitBadge()
                }
                if (cols.artist == null && song.artist.isNotBlank()) Text(song.artist, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            cols.artist?.let { w ->
                Spacer(Modifier.width(16.dp))
                LinkText(song.artist, PageType.row, PageInk.soft, artistLink(song), Modifier.width(w))
            }
            cols.album?.let { w ->
                Spacer(Modifier.width(16.dp))
                val name = song.albumName?.takeIf { it.isNotBlank() } ?: album
                LinkText(name ?: "—", PageType.row, PageInk.grey, if (name != null) albumLink(song) else null, Modifier.width(w))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.width(cols.right), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text(
                    song.durationText.orEmpty(), style = PageType.small.copy(fontFeatureSettings = "tnum"), color = PageInk.grey, maxLines = 1,
                    textAlign = TextAlign.End, modifier = Modifier.width(56.dp),
                )
                Spacer(Modifier.width(12.dp))
                if (onRemove != null) PageIconButton(Icons.Rounded.RemoveCircleOutline, "Remove ${song.title} from this playlist") { onRemove(song) }
                PageIconButton(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike ${song.title}" else "Like ${song.title}", lit = isLiked) {
                    Library.setLiked(song, !isLiked)
                }
                Box {
                    PageIconButton(Icons.Rounded.Add, "Add ${song.title} to a playlist or the queue") { adding = true }
                    AddToPlaylistMenu(song, adding, withQueue = true) { adding = false }
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/** A row's artist as a link to their page, when the song knows it. */
@Composable
private fun artistLink(song: Song): (() -> Unit)? {
    val open = LocalOpen.current
    return song.artistId?.takeIf { it.startsWith("UC") }?.let { id -> { open(ShelfItem(song.artist, "Artist", null, null, id)) } }
}

/** A row's album as a link to its page, when the song knows it. */
@Composable
private fun albumLink(song: Song): (() -> Unit)? {
    val open = LocalOpen.current
    return song.albumId?.let { id -> { open(ShelfItem(song.albumName ?: song.title, "Album • ${song.artist}", song.thumbnailUrl, null, id)) } }
}

/** A list's tracks still on their way: striped rows of the right shape, shimmering. */
internal fun LazyListScope.trackPlaceholders() {
    items(8) { i ->
        Row(
            Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().height(70.dp).clip(RoundedCornerShape(4.dp))
                .background(if (i % 2 == 0) PageInk.stripe else Color.Transparent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(48.dp))
            Placeholder(Modifier.size(56.dp), RoundedCornerShape(4.dp))
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Placeholder(Modifier.width(240.dp).height(14.dp), RoundedCornerShape(6.dp))
                Placeholder(Modifier.width(150.dp).height(12.dp), RoundedCornerShape(5.dp))
            }
        }
    }
}

/**
 * A collection page laid out: the head, then the tracks, on the page's own flat colour with
 * only a faint breath of the cover's at the top. The head's search button opens a field over the
 * tracks that narrows them by title, artist or album. [album] names the album for an album's rows.
 */
@Composable
internal fun CollectionLayout(
    title: String,
    coverUrl: String?,
    songs: List<Song>?,
    failed: Boolean,
    onRemove: ((Song) -> Unit)? = null,
    empty: String = "Nothing to play here",
    album: String? = null,
    hero: @Composable () -> Unit,
) {
    val state = rememberLazyListState()
    val base = MaterialTheme.colorScheme.background
    val accent = coverAccent(coverUrl)
    val hover = remember { mutableIntStateOf(-1) }
    val filter = remember(title) { PageListFilter() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(filter.open) { if (filter.open) runCatching { focus.requestFocus() } }
    val query = filter.query.trim()
    val shown = remember(songs, query) {
        if (query.isEmpty() || songs == null) songs
        else songs.filter { s -> listOfNotNull(s.title, s.artist, s.albumName).any { it.contains(query, ignoreCase = true) } }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(base)) {
        // A faint wash of the cover's colour behind the head only; the page itself stays flat.
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(0f to tint(accent, base, 0.16f), 1f to base)))
        CompositionLocalProvider(
            LocalGutter provides Web.gutter(maxWidth), LocalPageWidth provides maxWidth, LocalTrackHover provides hover, LocalListFilter provides filter,
        ) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = LocalPageBottom.current)) {
                item(key = "hero") { hero() }
                if (filter.open) item(key = "filter") {
                    PageSearchField(
                        filter.query, { filter.query = it }, "Search in $title",
                        Modifier.padding(start = LocalGutter.current, end = LocalGutter.current, bottom = 16.dp).fillMaxWidth(), focus = focus,
                    )
                }
                when {
                    failed -> item(key = "failed") { PageEmpty(Icons.Rounded.CloudOff, "Couldn't open $title", "Check your connection and try again.") }
                    shown == null -> trackPlaceholders()
                    songs.isNullOrEmpty() -> item(key = "empty") { PageEmpty(Icons.Rounded.MusicNote, "Nothing here yet", empty) }
                    shown.isEmpty() -> item(key = "none") { PageEmpty(Icons.Rounded.SearchOff, "No matches", "Nothing in $title matches “$query”.") }
                    else -> tracks(shown, onRemove, album = album)
                }
            }
        }
    }
}

/** A soft, colour-only blur of a cover, as the full player's backdrop draws it, filling [modifier]. */
@Composable
internal fun SoftBackdrop(url: String?, modifier: Modifier) {
    Box(modifier) { SoftCover(url, strength = 1f) { 0f } }
}
