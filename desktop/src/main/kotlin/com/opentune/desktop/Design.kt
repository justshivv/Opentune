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

/** How wide the page is, for the parts of it that change with the width as the web's do. */
internal val LocalPageWidth = compositionLocalOf { 1200.dp }

/**
 * Which row of a track list is under the pointer, shared by the list's rows: the hairline above
 * a row goes when it or the row before it is lit, as the web's rows do.
 */
internal val LocalTrackHover = compositionLocalOf<MutableIntState?> { null }

/**
 * A page that scrolls, headed as the web player's pages are (PageHeader.tsx, Home's heading):
 * the title in 800 weight, 30 to 44px with the width, 32px under the bar (36 with an [eyebrow],
 * the accent-coloured line over Home's title); [trailing] at the head's far end. The head rises
 * in as the page opens. [webGutter] gives the page the web's margins, 40px once it's wide;
 * without it the page keeps the app's own.
 */
@Composable
internal fun ScrollPage(
    title: String,
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
                item(key = "page-title") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = if (eyebrow != null) 36.dp else 32.dp).riseIn(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(Modifier.weight(1f)) {
                            eyebrow?.let { Text(it, style = webType(13f, 16f, 600), color = WebInk.key, modifier = Modifier.padding(bottom = 9.dp)) }
                            Text(
                                title, style = WebType.header(Web.headerSize(width), if (eyebrow != null) -0.03f else 0f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                            )
                            subtitle?.let { Text(it, style = WebType.title3, color = WebInk.secondary, modifier = Modifier.padding(top = 6.dp)) }
                        }
                        trailing?.let { Box(Modifier.padding(start = 16.dp)) { it() } }
                    }
                }
                content()
            }
        }
    }
}

/**
 * The head of an album, playlist or mix page, as the web's DetailHeader: the cover 270px (220 on
 * a narrower window) with 28px corners and a soft glow of itself, over a wash of its own colours
 * that fades into the page; beside it the title and who made it in 26px, what kind of list it is
 * and how long it runs, and at the cover's foot Shuffle, Play and whatever else the page offers.
 * The cover rises and settles as the page opens, the names and buttons just after it. On a
 * narrow window it all stacks, centred.
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
    val viewport = LocalPageWidth.current + Web.Sidebar
    val stacked = viewport < 768.dp
    val coverSize = if (viewport < 1000.dp) 220.dp else 270.dp
    val shape: Shape = if (round) CircleShape else RoundedCornerShape(Web.ArtLarge)
    val page = MaterialTheme.colorScheme.background
    val ready = !songs.isNullOrEmpty()
    val meta = listOfNotNull(kind.takeIf { it.isNotBlank() }, songs?.takeIf { it.isNotEmpty() }?.let(::lengthLine)).joinToString(" · ")

    val art = @Composable {
        Box(Modifier.riseIn(durationMs = 650, distance = 16.dp, fromScale = 0.92f).size(coverSize)) {
            // A blurred copy behind the cover: its glow (Apple's radiosity, as the web draws it).
            cover(Modifier.matchParentSize().graphicsLayer { scaleX = 0.88f; scaleY = 0.88f; alpha = 0.3f; translationY = 12.dp.toPx() }.blur(20.dp, BlurredEdgeTreatment.Unbounded))
            cover(
                Modifier.matchParentSize().shadow(28.dp, shape, ambientColor = WebInk.radiosity, spotColor = Color.Black.copy(alpha = 0.45f)).clip(shape)
                    .semantics { contentDescription = "$title cover" },
            )
            Box(Modifier.matchParentSize().border(0.5.dp, WebInk.artworkStroke, shape))
        }
    }
    val names = @Composable { align: Alignment.Horizontal ->
        Column(Modifier.riseIn(delayMs = 70), horizontalAlignment = align) {
            Text(
                title, style = WebType.largeTitleEmph, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (stacked) TextAlign.Center else TextAlign.Start, modifier = Modifier.semantics { heading() },
            )
            if (subtitle.isNotBlank()) Text(subtitle, style = WebType.largeTitle, color = WebInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, style = WebType.calloutEmph, color = WebInk.secondary, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
    val actions = @Composable {
        Row(Modifier.riseIn(delayMs = 140).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Icons.Rounded.Shuffle, "Shuffle", enabled = ready) { songs?.let { Player.playAll(it.shuffled(), 0) } }
            PillButton40("Play", Icons.Rounded.PlayArrow, enabled = ready) { songs?.let { Player.playAll(it, 0) } }
            extra()
        }
    }

    Box(Modifier.fillMaxWidth()) {
        CoverWash(coverUrl, page)
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = LocalPageTop.current + 12.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                art()
                Spacer(Modifier.height(20.dp))
                names(Alignment.CenterHorizontally)
                actions()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = LocalPageTop.current + 16.dp, bottom = 40.dp).height(IntrinsicSize.Min)) {
                art()
                Column(Modifier.padding(start = 34.dp).weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.height(37.dp))
                    names(Alignment.Start)
                    Spacer(Modifier.weight(1f))
                    actions()
                }
            }
        }
    }
}

/**
 * The cover blurred past recognition across the head of the page, reaching 140px below it and
 * fading out from a quarter of the way down, under a veil of the page's colour so a pale patch
 * can't turn into a bright cloud behind the title (opentune.css's .ot-wash). It fades in.
 */
@Composable
private fun BoxScope.CoverWash(url: String?, page: Color) {
    val still = stillMotion()
    val shown = remember { androidx.compose.animation.core.Animatable(if (still) 0.75f else 0f) }
    LaunchedEffect(url) { shown.animateTo(0.75f, tween(800, easing = Web.Emph)) }
    Box(
        Modifier.matchParentSize().extendBelow(140.dp)
            .graphicsLayer { alpha = shown.value; compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0.25f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        SoftBackdrop(url, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(page.copy(alpha = 0.42f)))
    }
}

/** Lays this out [extra] taller than its place, reaching down past it, as the wash does under the head. */
private fun Modifier.extendBelow(extra: Dp): Modifier = layout { measurable, constraints ->
    val more = extra.roundToPx()
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(w, h + more))
    layout(w, h) { placeable.place(0, 0) }
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

/** How a track list lays out: in columns once the window is wide enough, with an Album column when the songs know theirs. */
private data class TrackColumns(val wide: Boolean, val album: Boolean)

@Composable
private fun trackColumns(songs: List<Song>): TrackColumns =
    TrackColumns(wide = LocalPageWidth.current + Web.Sidebar >= 768.dp, album = songs.any { !it.albumName.isNullOrBlank() })

/** The width of a track list's last column, for the length and the row's buttons. */
private val TIME_COLUMN = 132.dp

/**
 * The column heads over a playlist's rows (TrackList.tsx's Header): Song, Artist, Album and
 * Time in 12px grey, 32px tall. Not on a narrow window, where the rows fold to two lines.
 */
@Composable
internal fun TrackHeader(songs: List<Song> = emptyList()) {
    val cols = trackColumns(songs)
    if (!cols.wide) return
    val style = WebType.calloutEmph
    val ink = WebInk.secondary
    Row(Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Song", style = style, color = ink, modifier = Modifier.weight(36.5f).padding(start = 7.dp))
        Text("Artist", style = style, color = ink, modifier = Modifier.weight(25.25f))
        if (cols.album) Text("Album", style = style, color = ink, modifier = Modifier.weight(25.25f))
        Text("Time", style = style, color = ink, textAlign = TextAlign.End, modifier = Modifier.width(TIME_COLUMN).padding(end = 18.dp))
    }
}

/**
 * A track list's rows, each a [TrackRow], for a LazyColumn. [numbered] gives an album's rows,
 * numbered and without covers.
 */
internal fun LazyListScope.tracks(songs: List<Song>, onRemove: ((Song) -> Unit)? = null, numbered: Boolean = false) {
    itemsIndexed(songs, key = { i, s -> "t$i:${s.videoId}" }) { i, song -> TrackRow(i, song, songs, onRemove, numbered) }
}

/**
 * One track, as the web's track lists draw it (TrackList.tsx): a playlist's row 54px tall with
 * the cover, title, artist, album and length in columns, or an album's 46px row with its number
 * and the artists under the title. A hairline over each row, gone around the one lit; under
 * the pointer a faint fill comes at once and fades over 150ms after, the number or cover turns
 * into a play button and the ⋯ menu takes the length's place; the playing row keeps a stronger
 * fill. A heart stays on a liked song; [onRemove] adds a cross for lists that can lose a song.
 */
@Composable
internal fun TrackRow(i: Int, song: Song, songs: List<Song>, onRemove: ((Song) -> Unit)? = null, numbered: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = hovered || focused
    val shared = LocalTrackHover.current
    if (shared != null) {
        LaunchedEffect(lit) {
            if (lit) shared.intValue = i else if (shared.intValue == i) shared.intValue = -1
        }
    }
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playing by Player.playing.collectAsState()
    val liked by Library.liked.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val playingId = queue.getOrNull(index)?.videoId
    val current = playingId == song.videoId
    val beforeCurrent = i > 0 && songs.getOrNull(i - 1)?.videoId == playingId
    val litIndex = shared?.intValue ?: -1
    val line = i > 0 && !(lit || current || beforeCurrent || litIndex == i || litIndex == i - 1)
    val divider = WebInk.divider
    val currentFill = WebInk.primary.copy(alpha = 0.12f)
    val fill by animateColorAsState(
        when { current -> currentFill; lit -> WebInk.rowHover; else -> WebInk.rowHover.copy(alpha = 0f) },
        tween(if (lit) 0 else 150), label = "row fill",
    )
    val tools by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 0 else 150), label = "row tools")
    val cols = trackColumns(songs)
    val shape = RoundedCornerShape(if (numbered) Web.TrackRow else 12.dp)
    val play = { if (current) Player.toggle() else Player.playAll(songs, i) }
    SongContextMenu(song) {
        Row(
            Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().height(if (numbered) 46.dp else 54.dp)
                .drawBehind { if (line) drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
                .clip(shape).background(fill)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = play)
                .semantics { contentDescription = "${song.title}, ${song.artist}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(if (cols.wide && !numbered) 36.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                if (numbered) {
                    Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                        when {
                            lit -> Icon(if (current && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (current && playing) "Pause" else "Play", tint = WebInk.primary, modifier = Modifier.size(16.dp))
                            current -> PlayingBars(playing)
                            else -> Text("${i + 1}", style = WebType.body, color = WebInk.secondary)
                        }
                    }
                } else {
                    Box(Modifier.padding(start = 7.dp).size(40.dp).clip(RoundedCornerShape(Web.ArtSmall)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        CoverFill(song.thumbnailUrl, Modifier.fillMaxSize())
                        Box(Modifier.matchParentSize().border(0.5.dp, WebInk.artworkStroke, RoundedCornerShape(Web.ArtSmall)))
                        if (lit || current) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (lit) 0.3f else 0.45f)), contentAlignment = Alignment.Center) {
                            if (lit) Icon(if (current && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            else PlayingBars(playing)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(song.title, style = WebType.body, color = if (current) WebInk.key else WebInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (song.isExplicit == true) ExplicitBadge()
                    }
                    // An album's rows, and a playlist's on a narrow window, carry the artists under the title.
                    if ((numbered || !cols.wide) && song.artist.isNotBlank()) Text(song.artist, style = WebType.callout, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cols.wide && !numbered) {
                Text(song.artist, style = WebType.body, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(25.25f).padding(end = 10.dp))
                if (cols.album) Text(song.albumName.orEmpty(), style = WebType.body, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(25.25f).padding(end = 16.dp))
            }
            Box(Modifier.width(TIME_COLUMN), contentAlignment = Alignment.CenterEnd) {
                // At rest: the length, 18px in from the end (12 on an album) under the Time head, a heart before it on a liked song.
                Row(
                    Modifier.padding(end = if (numbered) 12.dp else 18.dp).graphicsLayer { alpha = 1f - tools },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isLiked) Icon(Icons.Rounded.Favorite, "Liked", tint = WebInk.secondary, modifier = Modifier.size(14.dp))
                    Text(song.durationText.orEmpty(), style = WebType.body.copy(fontFeatureSettings = "tnum"), color = WebInk.secondary, maxLines = 1)
                }
                // Under the pointer the row's buttons take the length's place, the ⋯ menu among them, as on the web.
                if (tools > 0f) Row(Modifier.padding(end = 6.dp).graphicsLayer { alpha = tools }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, size = 16.dp) }
                    SongMoreButton(song, size = 28.dp)
                    if (onRemove != null) HoverIcon(Icons.Rounded.Close, "Remove ${song.title}", size = 28.dp, iconSize = 16.dp) { onRemove(song) }
                }
            }
        }
    }
}

/** A list's tracks still on their way: rows of the right shape, shimmering. */
internal fun LazyListScope.trackPlaceholders() {
    items(8) {
        Row(Modifier.padding(horizontal = LocalGutter.current).height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            Placeholder(Modifier.padding(start = 7.dp).size(40.dp), RoundedCornerShape(Web.ArtSmall))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Placeholder(Modifier.width(240.dp).height(13.dp), RoundedCornerShape(6.dp))
                Placeholder(Modifier.width(150.dp).height(11.dp), RoundedCornerShape(5.dp))
            }
        }
    }
}

/**
 * A collection page laid out: the hero, the column heads, the tracks, with the web's margins.
 * An album (every song with the same cover) gets numbered rows without covers, as on the web.
 */
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
    val base = MaterialTheme.colorScheme.background
    val accent = coverAccent(coverUrl)
    val hover = remember { mutableIntStateOf(-1) }
    // The whole page in the cover's colour: strong at the top, deepening to a dark, tinted ground.
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint(accent, base, 0.5f), 0.35f to tint(accent, base, 0.26f), 0.75f to tint(accent, base, 0.14f), 1f to tint(accent, base, 0.1f)))) {
        CompositionLocalProvider(LocalGutter provides Web.gutter(maxWidth), LocalPageWidth provides maxWidth, LocalTrackHover provides hover) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = LocalPageBottom.current)) {
                item(key = "hero") { hero() }
                when {
                    failed -> item(key = "failed") { Message("Couldn't open $title", "Check your connection and try again.") }
                    songs == null -> trackPlaceholders()
                    songs.isEmpty() -> item(key = "empty") { Message(empty, null) }
                    else -> {
                        val album = songs.size > 1 && songs.all { it.thumbnailUrl == songs.first().thumbnailUrl }
                        if (!album) item(key = "head") { TrackHeader(songs) }
                        tracks(songs, onRemove, numbered = album)
                    }
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
