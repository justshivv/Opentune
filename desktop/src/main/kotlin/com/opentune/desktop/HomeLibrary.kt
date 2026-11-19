package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.history.DailyMixes
import com.opentune.data.history.PlayRecord
import com.opentune.data.history.SpeedDial
import com.opentune.data.history.Wrapped
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A mood or genre opened from Explore or Home's row of them. */
internal data class MoodKey(val title: String, val browseId: String, val params: String?)

/** Every play on this computer, newest first, read again when a new one is recorded. */
@Composable
private fun rememberHistory(): List<PlayRecord> {
    val recent by Library.recent.collectAsState()
    val history by produceState(emptyList<PlayRecord>(), recent) {
        value = withContext(Dispatchers.IO) { Library.history() }
    }
    return history
}

// ---- Home --------------------------------------------------------------------

/**
 * Home: the greeting with a button to load the recommendations again; any banners; then
 * Recents, what you played lately as a grid of song rows four tall that scrolls sideways; your
 * mixes and most played; then YouTube Music's rows (quick picks as rows of songs, the rest as
 * square cards), page after page as you scroll, carrying on with new releases and the charts
 * when they run out; moods to start from, and your liked songs.
 */
@Composable
internal fun HomePage(onOpen: (ShelfItem) -> Unit, onOpenAny: (Any) -> Unit) {
    var shelves by rememberSaveable { mutableStateOf<List<Pages.Shelf>?>(null) }
    var next by rememberSaveable { mutableStateOf<String?>(null) }
    var extrasDone by rememberSaveable { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recent by Library.recent.collectAsState()
    val liked by LikedSongs.all.collectAsState()
    val history = rememberHistory()
    val moods by produceState(emptyList<MoodGenre>()) { value = runCatching { Pages.moods() }.getOrDefault(emptyList()) }
    fun load() {
        failed = false
        scope.launch {
            runCatching { Pages.home() }
                .onSuccess { shelves = it.shelves; next = it.next }
                .onFailure { failed = true }
        }
    }
    fun add(more: List<Pages.Shelf>) {
        // A row sent twice is shown once.
        val have = shelves.orEmpty().mapTo(HashSet()) { it.title.lowercase() }
        shelves = shelves.orEmpty() + more.filter { it.title.lowercase() !in have }
    }
    fun more() {
        if (loadingMore || shelves == null) return
        val token = next
        if (token == null && extrasDone) return
        loadingMore = true
        scope.launch {
            if (token != null) {
                runCatching { Pages.homeMore(token) }.onSuccess { add(it.shelves); next = it.next }.onFailure { next = null }
            } else {
                add(Pages.moreForHome())
                extrasDone = true
            }
            loadingMore = false
        }
    }
    // The heading's refresh: the feed from the start again.
    fun refresh() {
        shelves = null; next = null; extrasDone = false
        load()
    }
    // Come back to (Back from an album opened here), it shows what it had rather than loading again.
    LaunchedEffect(Unit) { if (shelves == null) load() }
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, next, shelves, extrasDone) { if (nearEnd) more() }

    val speedDial = remember(history) { SpeedDial.fromHistory(history, limit = 27) }
    val mixes = remember(history) { DailyMixes.build(history) }
    val loaded = shelves
    // The dashboard's banners for computers (it names them "web") and its featured playlists.
    val runtime by com.opentune.data.config.RemoteConfig.state.collectAsState()
    val closedBanners by com.opentune.data.config.HomeDismissals.banners.collectAsState()
    val closedPlaylists by com.opentune.data.config.HomeDismissals.playlists.collectAsState()
    LaunchedEffect(Unit) { runCatching { com.opentune.data.config.RemoteConfig.refresh() } }
    val banners = runtime.banners.filter { "web" in it.platforms && it.id !in closedBanners }
    val featured = runtime.playlists.takeIf { com.opentune.data.config.HomeDismissals.showsPlaylists(it.map { p -> p.id }, closedPlaylists) }.orEmpty()
    ScrollPage(
        greeting(), state = list, webGutter = true, spacing = 44.dp,
        trailing = { RefreshButton(spinning = loaded == null && !failed, onClick = ::refresh) },
    ) {
        banners.forEach { b -> item(key = "banner:${b.id}") { DashboardBanner(b) } }
        if (recent.isNotEmpty()) item(key = "recents") {
            SongGrid("Recents", recent.take(40), actions = {
                PageIconButton(Icons.Rounded.Shuffle, "Shuffle recents") { Player.playAll(recent.shuffled(), 0) }
            })
        }
        if (mixes.isNotEmpty() || speedDial.isNotEmpty()) item(key = "picks") {
            ShelfSection("Top picks for you", "Made from what you play") { TopPicks(mixes, speedDial.take(8), onOpenMix = { onOpenAny(it) }) }
        }
        if (featured.isNotEmpty()) item(key = "featured") {
            ShelfSection(runtime.title("featured-playlists", "From the community"), action = {
                PageIconButton(Icons.Rounded.Close, "Hide these playlists") {
                    com.opentune.data.config.HomeDismissals.dismissPlaylists(featured.map { it.id })
                }
            }) {
                val since = remember { System.nanoTime() }
                ShelfScroller(ShelfKind.SQUARE) {
                    shelfItems(featured.map { p -> ShelfItem(p.title, "Playlist • OpenTune picks", p.artwork, null, p.browseId) }, ShelfKind.SQUARE, since) { _, card ->
                        SquareLockup(card, onOpen = { onOpen(card) }, onPlay = rememberCardPlay(card))
                    }
                }
            }
        }
        when {
            failed -> item(key = "failed") { Message("Couldn't load Home", "Check your connection and try again.", action = "Try again" to ::load) }
            loaded == null -> items(3, key = { "placeholder$it" }) { ShelfPlaceholder() }
            else -> {
                itemsIndexed(loaded, key = { i, _ -> "yt$i" }) { i, shelf ->
                    if (isSongShelf(shelf)) SongGrid(shelf.title, shelf.items.mapNotNull(Pages::songOf), subtitle = shelf.subtitle) else ShelfRow(shelf, onOpen)
                }
                if (next != null || !extrasDone) item(key = "more") { ShelfPlaceholder() }
            }
        }
        if (moods.isNotEmpty() && loaded != null) item(key = "moods") {
            ShelfSection("Moods & genres", onSeeAll = null) { MoodRow(moods) { onOpenAny(MoodKey(it.title, it.browseId, it.params)) } }
        }
        if (liked.isNotEmpty() && loaded != null) item(key = "liked") {
            ShelfRow(Pages.Shelf("From your liked songs", liked.take(20).map(Pages::cardOf)), onOpen)
        }
    }
}

/** Home's refresh: a 40dp disc of the faintest fill, stronger under the pointer; its arrows turn while the feed loads. */
@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val still = stillMotion()
    val turn = if (spinning && !still) {
        rememberInfiniteTransition(label = "refresh").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "refresh turn").value
    } else 0f
    CircleButton(
        Icons.Rounded.Refresh, "Refresh recommendations", enabled = !spinning, size = 40.dp, iconSize = 20.dp, ring = false,
        iconModifier = Modifier.graphicsLayer { rotationZ = turn }, onClick = onClick,
    )
}

/** A row of songs (quick picks, trending): shown as columns of song rows, not as cards. */
private fun isSongShelf(shelf: Pages.Shelf) =
    shelf.title.trim().equals("Quick picks", ignoreCase = true) || shelf.items.size >= 8 && shelf.items.count { it.videoId != null } * 10 >= shelf.items.size * 9

/** The greeting: late night, morning, afternoon or evening. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 0..4 -> "Late night"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * A banner from the dashboard: a softly tinted card with 12dp corners, its title in bold, its
 * words in grey, its button an accent pill, and a cross in the corner to close it for good.
 */
@Composable
private fun DashboardBanner(banner: com.opentune.data.config.RemoteBanner) {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    val shape = RoundedCornerShape(12.dp)
    // The accent at 12% over the faintest fill.
    val tintFill = androidx.compose.ui.graphics.lerp(WebInk.quinary, WebInk.key.copy(alpha = 1f), 0.12f)
    Box(Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().clip(shape).background(tintFill)) {
        Column(Modifier.padding(start = 20.dp, end = 52.dp, top = 18.dp, bottom = 18.dp)) {
            Text(banner.title, style = PageType.cardTitle, color = PageInk.text, modifier = Modifier.padding(bottom = 4.dp))
            if (banner.body.isNotBlank()) Text(banner.body, style = PageType.cardLine, color = PageInk.grey, modifier = Modifier.widthIn(max = 620.dp))
            val source = remember { MutableInteractionSource() }
            Text(
                "${banner.action} ↗", style = PageType.button, color = Color.White,
                modifier = Modifier.padding(top = 12.dp).pressScale(source).clip(CircleShape).background(WebInk.keyFill)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { runCatching { links.openUri(banner.url) } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // The cross in the corner, closing the banner for good.
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            PageIconButton(Icons.Rounded.Close, "Dismiss ${banner.title}") { com.opentune.data.config.HomeDismissals.dismissBanner(banner.id) }
        }
    }
}

/**
 * Plays an album or playlist card straight from the round button on its cover: its songs are
 * fetched and played from the first. Null for a song (it plays from the card) or an artist.
 */
@Composable
internal fun rememberCardPlay(item: ShelfItem): (() -> Unit)? {
    val scope = rememberCoroutineScope()
    val id = item.browseId?.takeIf { item.videoId == null && !it.startsWith("UC") } ?: return null
    return remember(id) {
        {
            scope.launch {
                runCatching { Pages.songsOf(id) }
                    .onSuccess { if (it.isNotEmpty()) Player.playAll(it, 0) else Notice.show("Nothing to play in ${item.title}") }
                    .onFailure { Notice.show("Couldn't play ${item.title}") }
            }
            Unit
        }
    }
}

/**
 * One of Home's rows of cards: its title and strapline, and its cards (an artist's round) that
 * scroll sideways under the page's arrows. A song plays from its card, with the rest of the row
 * after it; anything else opens, and plays from the button over its cover.
 */
@Composable
internal fun ShelfRow(shelf: Pages.Shelf, onOpen: (ShelfItem) -> Unit) {
    val songs = remember(shelf) { shelf.items.mapNotNull(Pages::songOf) }
    val artists = shelf.items.isNotEmpty() && shelf.items.all { it.browseId?.startsWith("UC") == true }
    val kind = if (artists) ShelfKind.CIRCLE else ShelfKind.SQUARE
    val since = remember(shelf) { System.nanoTime() }
    ShelfSection(shelf.title, shelf.subtitle) {
        ShelfScroller(kind) {
            shelfItems(shelf.items, kind, since) { _, item ->
                if (artists) {
                    CircleLockup(item, onOpen = { onOpen(item) }, line = item.subtitle.takeIf { it.isNotBlank() && it != "Artist" })
                } else {
                    val song = Pages.songOf(item)
                    val play = { if (song != null) Player.playAll(songs, songs.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)) else onOpen(item) }
                    SquareLockup(item, onOpen = play, song = song, onPlay = if (song != null) play else rememberCardPlay(item))
                }
            }
        }
    }
}

/** A shelf's shape, shimmering, while Home loads. */
@Composable
internal fun ShelfPlaceholder() = ShelfSkeleton(ShelfKind.SQUARE)

/** Songs as four rows that scroll sideways in columns, each with its cover, title, artist and ⋮ menu. */
@Composable
internal fun SongGrid(title: String, songs: List<Song>, rows: Int = 4, subtitle: String? = null, actions: (@Composable () -> Unit)? = null) {
    TrackShelf(title, songs, rows = rows, subtitle = subtitle, actions = actions)
}

/**
 * Top picks: square cards, your daily mixes as a burst of their colour with three of their covers
 * in circles, then the songs you play most as their covers.
 */
@Composable
private fun TopPicks(mixes: List<DailyMixes.Mix>, most: List<ShelfItem>, onOpenMix: (DailyMixes.Mix) -> Unit) {
    val songs = remember(most) { most.mapNotNull(Pages::songOf) }
    val picks: List<Any> = mixes + most
    val since = remember { System.nanoTime() }
    ShelfScroller(ShelfKind.SQUARE) {
        shelfItems(picks, ShelfKind.SQUARE, since) { _, pick ->
            when (pick) {
                is DailyMixes.Mix -> MixCard(pick, onOpen = { onOpenMix(pick) })
                is ShelfItem -> {
                    val song = Pages.songOf(pick)
                    val play = {
                        val at = songs.indexOfFirst { it.videoId == pick.videoId }
                        if (at >= 0) Player.playAll(songs, at)
                    }
                    SquareLockup(pick.copy(subtitle = "Played most • ${pick.subtitle}"), onOpen = play, song = song, onPlay = play)
                }
            }
        }
    }
}

/** Three covers in overlapping circles, as a mix's card shows who's in it, sized to the card. */
@Composable
private fun MixCircles(covers: List<String>) {
    BoxWithConstraints(Modifier.fillMaxWidth(0.86f).aspectRatio(1f)) {
        val w = maxWidth
        covers.getOrNull(0)?.let { Cover(it, w * 0.6f, CircleShape, Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 8.dp)) }
        covers.getOrNull(1)?.let { Cover(it, w * 0.41f, CircleShape, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) }
        covers.getOrNull(2)?.let { Cover(it, w * 0.34f, CircleShape, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** A daily mix as a square card: a burst of its colour with three of its covers, its name and who's in it, and the play button under the pointer. */
@Composable
private fun MixCard(mix: DailyMixes.Mix, onOpen: () -> Unit) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val colour = rememberCoverColor(mix.covers.firstOrNull(), base)
    SquareLockup(
        ShelfItem(mix.title, "Mix • ${mix.subtitle}", mix.covers.firstOrNull(), null, null),
        onOpen = onOpen,
        art = { m ->
            Box(m.background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(colour, Color.White, 0.12f), androidx.compose.ui.graphics.lerp(colour, Color.Black, 0.35f)))), contentAlignment = Alignment.Center) {
                MixCircles(mix.covers.filterNotNull())
            }
        },
        onPlay = { Player.playAll(mix.songs, 0) },
    )
}

/** A mood's tile with its cover, fetched as it first shows. */
@Composable
private fun MoodTileFor(mood: MoodGenre, modifier: Modifier, height: Dp, onOpen: () -> Unit) {
    val cover by produceState(mood.thumbnailUrl, mood.title) { value = runCatching { Pages.moodCover(mood) }.getOrNull() }
    MoodTile(mood.title, moodColour(mood.title), cover, onOpen, modifier, height)
}

/** Moods and genres as a row of tiles that scrolls sideways, for Home. */
@Composable
private fun MoodRow(moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit) {
    val since = remember { System.nanoTime() }
    ShelfScroller(ShelfKind.TILES) {
        shelfItems(moods, ShelfKind.TILES, since, key = { it.title }) { _, m ->
            MoodTileFor(m, Modifier.width(LocalColumnWidth.current), 150.dp) { onOpen(m) }
        }
    }
}

/**
 * Every mood and genre as tiles in a grid across the width: five columns on a wide window, fewer
 * as it narrows, 15dp apart.
 */
@Composable
internal fun MoodGrid(moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit) {
    val gap = 15.dp
    BoxWithConstraints(Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth()) {
        val cols = when {
            maxWidth >= 1000.dp -> 5
            maxWidth >= 780.dp -> 4
            maxWidth >= 540.dp -> 3
            else -> 2
        }
        val cell = (maxWidth - gap * (cols - 1)) / cols
        val since = remember { System.nanoTime() }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            moods.chunked(cols).forEachIndexed { r, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEachIndexed { c, m ->
                        Box(Modifier.riseIn(delayMs = ((r * cols + c).coerceAtMost(10)) * 25, distance = 10.dp, since = since)) {
                            MoodTileFor(m, Modifier.width(cell), 188.dp) { onOpen(m) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Explore: every mood and genre as a grid of tiles, then what's just come out and what's
 * climbing (new albums and singles as cards, trending songs in rows, then the charts).
 */
@Composable
internal fun NewPage(onOpen: (ShelfItem) -> Unit) {
    val open = LocalOpen.current
    // Kept with the page, so Back to Explore finds it as it was, scrolled where it was.
    val moods = rememberKept { runCatching { Pages.moods() }.getOrDefault(emptyList()) }
    val shelves = rememberKept { runCatching { Pages.moreForHome() }.getOrDefault(emptyList()) }
    ScrollPage("Explore", webGutter = true, spacing = 44.dp) {
        when (val m = moods) {
            null -> item(key = "moods-wait") { MoodGridPlaceholder() }
            else -> if (m.isNotEmpty()) item(key = "moods") {
                ShelfSection("Moods & genres") { MoodGrid(m) { open(MoodKey(it.title, it.browseId, it.params)) } }
            }
        }
        when (val loaded = shelves) {
            null -> items(2, key = { "wait$it" }) { ShelfPlaceholder() }
            else -> if (loaded.isEmpty()) {
                if (moods?.isEmpty() == true) item(key = "none") { PageEmpty(Icons.Rounded.SearchOff, "Couldn't load Explore", "Check your connection and try again.") }
            } else items(loaded, key = { "new:" + it.title }) { shelf ->
                    if (isSongShelf(shelf)) SongGrid(shelf.title, shelf.items.mapNotNull(Pages::songOf), subtitle = shelf.subtitle) else ShelfRow(shelf, onOpen)
                }
        }
    }
}

/** The moods grid's shape, shimmering, while it loads. */
@Composable
private fun MoodGridPlaceholder() {
    Column(Modifier.padding(horizontal = LocalGutter.current), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Placeholder(Modifier.padding(bottom = 1.dp).width(220.dp).height(26.dp), RoundedCornerShape(6.dp))
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                repeat(5) { Placeholder(Modifier.weight(1f).height(188.dp), RoundedCornerShape(10.dp)) }
            }
        }
    }
}

/** A mood or genre's own page: its rows of playlists. */
@Composable
internal fun MoodPage(mood: MoodKey, onOpen: (ShelfItem) -> Unit) {
    val shelves = rememberKept(mood) { runCatching { Pages.shelves(mood.browseId, mood.params) }.getOrDefault(emptyList()) }
    ScrollPage(mood.title, eyebrow = "Mood & genre", webGutter = true, spacing = 44.dp) {
        when (val loaded = shelves) {
            null -> items(3) { ShelfPlaceholder() }
            else -> if (loaded.isEmpty()) item { PageEmpty(Icons.Rounded.SearchOff, "Nothing here right now", "Try another mood or genre.") }
                else items(loaded) { ShelfRow(it, onOpen) }
        }
    }
}

/** A daily mix: the head, then its songs. */
@Composable
internal fun MixPage(mix: DailyMixes.Mix) {
    CollectionLayout(mix.title, mix.covers.firstOrNull(), mix.songs, failed = false) {
        CollectionHero(
            cover = { m -> Mosaic(mix.covers.filterNotNull(), m, RoundedCornerShape(0.dp)) },
            coverUrl = mix.covers.firstOrNull(), kind = "Mix • Made for you", title = mix.title, subtitle = mix.subtitle, songs = mix.songs,
            extra = { ListMoreButton(mix.songs, link = null) },
        )
    }
}

// ---- Library -----------------------------------------------------------------

/** The Library's tabs. */
private enum class LibTab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists"), OVERVIEW("Overview") }

/** Who a song is by, for the Artists tab: the first name of several. */
private fun leadArtist(artist: String): String =
    artist.split(", ", " & ", " x ", " X ", " feat. ", " ft. ", " featuring ", " and ").first().trim()

/** An artist or album in the Library: who or what, a picture, where it opens (when it has a page), how many songs. */
private data class LibEntry(val title: String, val line: String, val cover: String?, val browseId: String?, val songs: List<Song>, val round: Boolean)

/** A card in the Playlists tab. */
private class LibCard(
    val key: String,
    val title: String,
    val line: String,
    val cover: String?,
    val onOpen: () -> Unit,
    val onPlay: (() -> Unit)? = null,
    val art: (@Composable (Modifier) -> Unit)? = null,
)

/** What a tab's content needs from the frame around it. */
private class LibContext(
    val query: String,
    val az: Boolean,
    val grid: Boolean,
    val columns: Int,
    val label: String,
    /** Shows the Songs tab narrowed to [query], as an artist or album without a page of its own opens. */
    val showSongs: (String) -> Unit,
)

private fun matches(q: String, vararg fields: String?) = q.isBlank() || fields.any { it?.contains(q.trim(), ignoreCase = true) == true }

/**
 * The frame of the Library, Liked songs and Recently played: a search box across the top that
 * narrows the tab you're on, an A–Z switch and the grid/list switch beside it, then tabs spread
 * across the width (Songs, Artists, Albums, and on the Library itself Playlists and an Overview)
 * over a hairline, the chosen one underlined. Songs are the striped track rows; artists round
 * photos; albums and playlists square cards; in a grid or a list.
 *
 * The tab, what's typed, A–Z, grid or list and how far it's scrolled are kept with the page, so
 * coming back to it (Back from a playlist opened here, say) finds it as it was left. [loading]
 * shows the rows' shapes rather than "nothing here" while the account's library is still read.
 */
@Composable
private fun LibraryFrame(
    label: String,
    songs: List<Song>,
    tabs: List<LibTab>,
    empty: Triple<ImageVector, String, String>,
    followed: List<ShelfItem> = emptyList(),
    saved: List<ShelfItem> = emptyList(),
    head: (@Composable () -> Unit)? = null,
    playlists: (LazyListScope.(LibContext) -> Unit)? = null,
    overview: (LazyListScope.() -> Unit)? = null,
    startTab: Int = 0,
    loading: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab.coerceIn(0, tabs.lastIndex)) }
    var query by rememberSaveable { mutableStateOf("") }
    var az by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    val shown = tabs.getOrElse(tab) { tabs.first() }
    // Worked out once for each change, not on every pass over the list: a big library is thousands.
    val artists = remember(songs, followed) { libraryArtists(songs, followed) }
    val albums = remember(songs, saved) { libraryAlbums(songs, saved) }
    val shownSongs = remember(songs, query, az) {
        songs.filter { matches(query, it.title, it.artist, it.albumName) }.let { if (az) it.sortedBy { s -> s.title.lowercase() } else it }
    }
    val shownArtists = remember(artists, query, az) { filterEntries(artists, query, az) }
    val shownAlbums = remember(albums, query, az) { filterEntries(albums, query, az) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val gutter = Web.gutter(width)
        val room = width - gutter * 2
        val columns = ((room + 17.dp) / (188.dp + 17.dp)).toInt().coerceAtLeast(2)
        val ctx = LibContext(query, az, grid, columns, label) { q -> query = q; tab = tabs.indexOf(LibTab.SONGS).coerceAtLeast(0) }
        CompositionLocalProvider(LocalGutter provides gutter, LocalPageWidth provides width) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = LocalPageTop.current + 20.dp, bottom = LocalPageBottom.current)) {
                head?.let { h -> item(key = "head") { Box(Modifier.padding(start = gutter, end = gutter, bottom = 22.dp)) { h() } } }
                item(key = "bar") {
                    Row(Modifier.padding(horizontal = gutter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PageSearchField(query, { query = it }, "Search your music", Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        PageTextToggle("A–Z", az, if (az) "Sorted A to Z" else "Sort A to Z") { az = !az }
                        Spacer(Modifier.width(4.dp))
                        PageIconButton(
                            if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            if (grid) "Show as a list" else "Show as a grid", size = 44.dp, iconSize = 22.dp,
                        ) { grid = !grid }
                    }
                }
                item(key = "tabs") {
                    PageTabs(tabs.map { it.label }, tab, { tab = it }, Modifier.padding(start = gutter, end = gutter, top = 14.dp, bottom = 18.dp))
                }
                when (shown) {
                    LibTab.SONGS -> songsTab(songs, shownSongs, ctx, empty, loading)
                    LibTab.ARTISTS -> entriesTab(artists, shownArtists, ctx, Triple(Icons.Rounded.Person, "No artists", "The artists you follow, and those of songs you keep, show up here."), "artist", loading)
                    LibTab.ALBUMS -> entriesTab(albums, shownAlbums, ctx, Triple(Icons.Rounded.Album, "No albums", "Albums you save, and the albums of songs you keep, show up here."), "album", loading)
                    LibTab.PLAYLISTS -> playlists?.invoke(this, ctx)
                    LibTab.OVERVIEW -> overview?.invoke(this)
                }
            }
        }
    }
}

private fun filterEntries(entries: List<LibEntry>, query: String, az: Boolean) =
    entries.filter { matches(query, it.title, it.line) }.let { if (az) it.sortedBy { e -> e.title.lowercase() } else it }

/** The artists of [songs] (each song under its first-named artist) after the ones you follow on YouTube Music. */
private fun libraryArtists(songs: List<Song>, followed: List<ShelfItem>): List<LibEntry> {
    val byName = LinkedHashMap<String, MutableList<Song>>()
    songs.forEach { s -> leadArtist(s.artist).takeIf { it.isNotBlank() }?.let { byName.getOrPut(it.lowercase()) { mutableListOf() }.add(s) } }
    val follows = followed.distinctBy { it.browseId ?: it.title.lowercase() }.map { a ->
        val mine = byName.remove(a.title.lowercase()).orEmpty()
        LibEntry(a.title, if (mine.isEmpty()) "Artist" else songCount(mine.size), a.thumbnailUrl, a.browseId, mine, round = true)
    }
    return follows + byName.values.map { list ->
        val name = leadArtist(list.first().artist)
        LibEntry(name, songCount(list.size), list.first().thumbnailUrl, list.firstNotNullOfOrNull { it.artistId?.takeIf { id -> id.startsWith("UC") } }, list, round = true)
    }
}

/** A part of the Overview tab, with room under it before the next. */
private fun LazyListScope.section(key: String, content: @Composable () -> Unit) =
    item(key = key) { Box(Modifier.padding(bottom = 44.dp)) { content() } }

/** The albums of [songs] that know theirs, after the albums saved on YouTube Music. */
private fun libraryAlbums(songs: List<Song>, saved: List<ShelfItem>): List<LibEntry> {
    val byAlbum = LinkedHashMap<String, MutableList<Song>>()
    songs.forEach { s -> s.albumName?.takeIf { it.isNotBlank() }?.let { byAlbum.getOrPut(it.lowercase()) { mutableListOf() }.add(s) } }
    val keep = saved.distinctBy { it.browseId ?: it.title.lowercase() }.map { a ->
        val mine = byAlbum.remove(a.title.lowercase()).orEmpty()
        LibEntry(a.title, a.subtitle, a.thumbnailUrl, a.browseId, mine, round = false)
    }
    return keep + byAlbum.values.map { list ->
        val first = list.first()
        LibEntry(first.albumName.orEmpty(), "Album • ${leadArtist(first.artist)}", first.thumbnailUrl, list.firstNotNullOfOrNull { it.albumId }, list, round = false)
    }
}

private fun LazyListScope.noMatches(ctx: LibContext) {
    item(key = "no-match") { PageEmpty(Icons.Rounded.SearchOff, "No matches", "Nothing in ${ctx.label} matches “${ctx.query.trim()}”.") }
}

/** The Songs tab: the striped rows (songs read best as rows; the grid view is for the other tabs). */
private fun LazyListScope.songsTab(all: List<Song>, shown: List<Song>, ctx: LibContext, empty: Triple<ImageVector, String, String>, loading: Boolean) {
    if (all.isEmpty()) {
        if (loading) trackPlaceholders() else item(key = "empty") { PageEmpty(empty.first, empty.second, empty.third) }
        return
    }
    if (shown.isEmpty()) return noMatches(ctx)
    tracks(shown)
}

/** The Artists or Albums tab: round photos or square covers in a grid, or rows in the list view. */
private fun LazyListScope.entriesTab(all: List<LibEntry>, shown: List<LibEntry>, ctx: LibContext, empty: Triple<ImageVector, String, String>, kind: String, loading: Boolean) {
    if (all.isEmpty()) {
        if (loading) item(key = "wait-$kind") { ShelfPlaceholder() } else item(key = "empty-$kind") { PageEmpty(empty.first, empty.second, empty.third) }
        return
    }
    if (shown.isEmpty()) return noMatches(ctx)
    if (ctx.grid) {
        cardGrid(shown, ctx.columns, key = { "$kind:${it.browseId ?: it.title}" }) { e -> LibEntryCard(e, ctx) }
    } else {
        itemsIndexed(shown, key = { _, e -> "$kind-row:${e.browseId ?: e.title}" }) { i, e -> LibEntryRow(i, e, ctx) }
    }
}

/** Lays [items] in rows of [columns] square cells for a LazyColumn, each row its own item so a long grid stays light. */
private fun <T> LazyListScope.cardGrid(items: List<T>, columns: Int, key: (T) -> Any, cell: @Composable (T) -> Unit) {
    val rows = items.chunked(columns)
    itemsIndexed(rows, key = { i, r -> "grid$i:" + key(r.first()) }) { _, row ->
        Row(Modifier.padding(start = LocalGutter.current, end = LocalGutter.current, bottom = 28.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
            row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Opens an artist or album: its page, when it has one, or else the Songs tab narrowed to it. */
@Composable
private fun entryOpen(e: LibEntry, ctx: LibContext): () -> Unit {
    val open = LocalOpen.current
    return {
        if (e.browseId != null) open(ShelfItem(e.title, if (e.round) "Artist" else e.line, e.cover, null, e.browseId))
        else ctx.showSongs(e.title)
    }
}

@Composable
private fun LibEntryCard(e: LibEntry, ctx: LibContext) {
    val onOpen = entryOpen(e, ctx)
    val play = if (e.songs.isNotEmpty()) ({ Player.playAll(e.songs, 0) }) else rememberCardPlay(ShelfItem(e.title, e.line, e.cover, null, e.browseId))
    if (e.round) CircleLockup(ShelfItem(e.title, e.line, e.cover, null, e.browseId), onOpen, line = e.line, fill = true)
    else SquareLockup(ShelfItem(e.title, e.line, e.cover, null, e.browseId), onOpen = onOpen, onPlay = play, fill = true)
}

/** An artist, album or playlist as a striped row: its picture, name and a grey line. */
@Composable
private fun LibListRow(i: Int, title: String, line: String, round: Boolean, onOpen: () -> Unit, picture: @Composable (Modifier) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val stripe = if (i % 2 == 0) PageInk.stripe else PageInk.stripe.copy(alpha = 0f)
    val fill by animateColorAsState(if (hovered || focused) PageInk.hover else stripe, tween(PAGE_HOVER_MS), label = "entry fill")
    Row(
        Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().height(70.dp).clip(RoundedCornerShape(4.dp)).background(fill)
            .hoverable(source).clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "$title, $line" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        picture(Modifier.size(56.dp).clip(if (round) CircleShape else RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = PageType.rowTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotBlank()) Text(line, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibEntryRow(i: Int, e: LibEntry, ctx: LibContext) {
    LibListRow(i, e.title, e.line, e.round, entryOpen(e, ctx)) { m -> Box(m) { CoverFill(e.cover, Modifier.fillMaxSize()) } }
}

/** The Playlists tab: a card to start one, Liked songs, then every playlist (yours, then the account's). */
private fun LazyListScope.playlistsTab(cards: List<LibCard>, ctx: LibContext) {
    // The first two (New playlist, Liked songs) stay first, whatever's typed or sorted.
    val fixed = cards.take(2)
    val rest = cards.drop(2).filter { matches(ctx.query, it.title, it.line) }.let { if (ctx.az) it.sortedBy { c -> c.title.lowercase() } else it }
    val list = fixed + rest
    if (ctx.grid) {
        cardGrid(list, ctx.columns, key = { "pl:${it.key}" }) { c -> PlaylistCard(c) }
    } else {
        itemsIndexed(list, key = { _, c -> "pl-row:${c.key}" }) { i, c ->
            LibListRow(i, c.title, c.line, round = false, onOpen = c.onOpen) { m ->
                Box(m) { if (c.art != null) c.art.invoke(Modifier.fillMaxSize()) else CoverFill(c.cover, Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun PlaylistCard(c: LibCard) {
    // An account playlist plays its songs from the button on its cover, fetched as it's pressed.
    val play = c.onPlay ?: if (c.art == null && c.key.startsWith("yt:")) rememberCardPlay(ShelfItem(c.title, c.line, c.cover, null, c.key.removePrefix("yt:"))) else null
    SquareLockup(ShelfItem(c.title, c.line, c.cover, null, null), onOpen = c.onOpen, onPlay = play, art = c.art, fill = true)
}

/**
 * The Playlists tab's cards, and the Playlists page's: New playlist, Liked songs (the account's
 * and this computer's, as one), then every playlist in the order the sidebar lists them.
 */
@Composable
private fun rememberPlaylistCards(
    liked: List<Song>,
    entries: List<LibPlaylist>,
    onNew: () -> Unit,
    onShelf: (Shelf) -> Unit,
    onPlaylist: (String) -> Unit,
    onOpenAny: (Any) -> Unit,
): List<LibCard> {
    val gradient = { colours: List<Color>, icon: ImageVector -> @Composable { m: Modifier -> IconCover(icon, colours, m) } }
    val newArt = remember { @Composable { m: Modifier ->
        Box(m.background(WebInk.quinary), contentAlignment = Alignment.Center) { VectorGlyph(Icons.Rounded.Add, PageInk.grey, Modifier.size(48.dp)) }
    } }
    val likedArt = remember { gradient(listOf(Color(0xFF5B3CFF), Color(0xFFB7A6FF)), Icons.Rounded.Favorite) }
    return remember(liked, entries) {
        buildList {
            add(LibCard("new", "New playlist", "Start one here", null, onOpen = onNew, art = newArt))
            add(LibCard("liked", "Liked songs", "Playlist • ${songCount(liked.size)}", null, onOpen = { onShelf(Shelf.LIKED) },
                onPlay = { Player.playAll(liked, 0) }.takeIf { liked.isNotEmpty() }, art = likedArt))
            entries.forEach { e ->
                when (e) {
                    is LibPlaylist.Local -> {
                        val p = e.playlist
                        add(LibCard("local:${p.id}", p.name, "Playlist • ${songCount(p.songs.size)}", p.covers.firstOrNull(), onOpen = { onPlaylist(p.id) },
                            onPlay = { Player.playAll(p.songs.map { it.toSong() }, 0) }.takeIf { p.songs.isNotEmpty() }, art = { m -> Mosaic(p.covers, m, RoundedCornerShape(0.dp)) }))
                    }
                    is LibPlaylist.OnAccount -> {
                        val p = e.item
                        add(LibCard("yt:${p.browseId}", p.title, p.subtitle.ifBlank { "Playlist" }, p.thumbnailUrl, onOpen = { onOpenAny(p) }))
                    }
                }
            }
        }
    }
}

/** Every playlist and the account's liked songs, as the Library and the sidebar show them. */
@Composable
private fun rememberLibraryPlaylists(): List<LibPlaylist> {
    val local by Library.playlists.collectAsState()
    val account by AccountLibrary.state.collectAsState()
    return remember(local, account.playlists) { libraryPlaylists(local, account.playlists) }
}

/** Liked songs or Recently played's head over the frame: its name, how many and how long, Shuffle and Play, and [extra]. */
@Composable
private fun LibHead(title: String, songs: List<Song>, line: String? = null, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PageType.pageTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(songs.takeIf { it.isNotEmpty() }?.let(::pageLengthLine), line).joinToString(" • ")
            if (facts.isNotBlank()) Text(facts, style = PageType.meta, color = PageInk.grey, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            extra?.invoke()
            PageRoundButton(Icons.Rounded.Shuffle, "Shuffle $title", size = 52.dp, enabled = songs.isNotEmpty()) { Player.playAll(songs.shuffled(), 0) }
            PageRoundButton(Icons.Rounded.PlayArrow, "Play $title", solid = true, size = 52.dp, iconSize = 28.dp, enabled = songs.isNotEmpty()) { Player.playAll(songs, 0) }
        }
    }
}

/**
 * The Library: every song you keep (liked here or on the account, added to the account's
 * library, or in one of your playlists) in the frame's tabs, with their artists and albums and
 * those the account follows and saved, every playlist (yours, then the account's), and an
 * Overview of your year, your mixes and what's on repeat.
 */
@Composable
internal fun LibraryHome(onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit, onOpenAny: (Any) -> Unit, startTab: Int = 0) {
    val liked by LikedSongs.all.collectAsState()
    val recent by Library.recent.collectAsState()
    val playlists by Library.playlists.collectAsState()
    val server by Subsonic.server.collectAsState()
    val history = rememberHistory()
    var naming by remember { mutableStateOf(false) }
    val now = remember(history) { System.currentTimeMillis() }
    val year = remember(history) {
        val start = java.time.LocalDate.now().withDayOfYear(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Wrapped.summarize(history, start, now)
    }
    val month = remember(history) { Wrapped.summarize(history, now - 30L * 86_400_000L, now) }
    val mixes = remember(history) { DailyMixes.build(history) }
    // Signed in: the account's own library on YouTube Music too, read once and kept (see AccountLibrary).
    val account by AccountLibrary.state.collectAsState()
    LaunchedEffect(Unit) { AccountLibrary.refreshIfStale() }
    val entries = rememberLibraryPlaylists()
    val kept = remember(liked, account.saved, playlists) {
        librarySongs(liked, account.saved, playlists.flatMap { p -> p.songs.map { it.toSong() } })
    }
    val cards = rememberPlaylistCards(liked, entries, onNew = { naming = true }, onShelf, onPlaylist, onOpenAny)
    val since = remember { System.nanoTime() }
    LibraryFrame(
        startTab = startTab,
        label = "your library",
        songs = kept,
        tabs = listOf(LibTab.SONGS, LibTab.ARTISTS, LibTab.ALBUMS, LibTab.PLAYLISTS, LibTab.OVERVIEW),
        empty = Triple(Icons.Rounded.LibraryMusic, "No songs", "Songs you like or add to a playlist show up here."),
        followed = account.artists, saved = account.albums,
        loading = account.loading,
        playlists = { ctx -> playlistsTab(cards, ctx) },
        overview = {
            section("year") { YearCard(year) }
            section("shortcuts") {
                Tiles {
                    Shortcut(Icons.Rounded.Favorite, "Liked songs", songCount(liked.size), heart = true) { onShelf(Shelf.LIKED) }
                    Shortcut(Icons.Rounded.History, "Recently played", if (recent.isEmpty()) "Nothing yet" else songCount(recent.size)) { onShelf(Shelf.RECENT) }
                    server?.let { s -> Shortcut(Icons.Rounded.Storage, "Your server", s.url.substringAfter("://").substringBefore('/')) { onShelf(Shelf.SERVER) } }
                    Shortcut(Icons.AutoMirrored.Rounded.QueueMusic, "All playlists", songCountOf(entries.size, "playlist")) { onShelf(Shelf.PLAYLISTS) }
                }
            }
            if (mixes.isNotEmpty()) section("mixes") {
                ShelfSection("Your mixes", "Made fresh each day from what you play") {
                    ShelfScroller(ShelfKind.SQUARE) {
                        shelfItems(mixes, ShelfKind.SQUARE, since, key = { it.id }) { _, mix -> MixCard(mix, onOpen = { onOpenAny(mix) }) }
                    }
                }
            }
            if (month.topSongs.isNotEmpty()) section("top-songs") {
                ShelfSection("On repeat this month", "${month.plays} plays, ${month.minutes} minutes") {
                    val songs = month.topSongs.mapNotNull { it.song }.take(10)
                    Column(Modifier.padding(horizontal = LocalGutter.current).widthIn(max = 900.dp)) {
                        month.topSongs.take(5).forEachIndexed { i, e ->
                            RankRow(i + 1, e) { e.song?.let { s -> Player.playAll(songs, songs.indexOfFirst { it.videoId == s.videoId }.coerceAtLeast(0)) } }
                        }
                    }
                }
            }
            if (month.topArtists.isNotEmpty()) section("top-artists") {
                ShelfSection("Your top artists", "This month") {
                    ShelfScroller(ShelfKind.CIRCLE) {
                        shelfItems(month.topArtists.take(12), ShelfKind.CIRCLE, since, key = { it.title }) { _, a ->
                            CircleLockup(ShelfItem(a.title, "", a.thumbnailUrl, null, null), onOpen = { a.song?.let { Player.playAll(listOf(it), 0) } }, line = "${a.plays} plays")
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) section("recent") {
                TrackShelf("Recently played", recent, onSeeAll = { onShelf(Shelf.RECENT) })
            }
        },
    )
    if (naming) {
        NewPlaylistDialog(onDismiss = { naming = false }) { made ->
            naming = false
            when (made) {
                is MadePlaylist.Local -> onPlaylist(made.id)
                is MadePlaylist.OnAccount -> onOpenAny(made.item)
            }
        }
    }
}

/**
 * One of the Library's lists opened on its own: Liked songs and Recently played in the Library's
 * frame under their name with Shuffle and Play; every playlist as a grid; your server.
 */
@Composable
internal fun LibraryShelfPage(shelf: Shelf, onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit, onOpenAny: (Any) -> Unit = {}) {
    val liked by LikedSongs.all.collectAsState()
    val played by Library.recent.collectAsState()
    val server by Subsonic.server.collectAsState()
    val account by AccountLibrary.state.collectAsState()
    when (shelf) {
        Shelf.LIKED -> LibraryFrame(
            label = "Liked songs", songs = liked, tabs = listOf(LibTab.SONGS, LibTab.ARTISTS, LibTab.ALBUMS),
            empty = Triple(Icons.Rounded.FavoriteBorder, "No liked songs", "Click the heart on any song and it shows up here."),
            head = { LibHead("Liked songs", liked, line = if (account.signedIn) "ON THIS COMPUTER AND YOUTUBE MUSIC" else null) },
            loading = account.loading,
        )
        Shelf.RECENT -> LibraryFrame(
            label = "Recently played", songs = played, tabs = listOf(LibTab.SONGS, LibTab.ARTISTS, LibTab.ALBUMS),
            empty = Triple(Icons.Rounded.History, "Nothing here yet", "Songs you play show up here."),
            head = {
                LibHead("Recently played", played, line = "ON THIS COMPUTER", extra = if (account.signedIn) ({
                    // What the account played anywhere, as YouTube Music keeps it: its own page.
                    PageRoundButton(Icons.Rounded.History, "Your YouTube Music history", size = 52.dp) {
                        onOpenAny(ShelfItem("Your YouTube Music history", "History • YouTube Music", null, null, "FEmusic_history"))
                    }
                }) else null)
            },
        )
        Shelf.PLAYLISTS -> PlaylistsPage(liked, onShelf, onPlaylist, onOpenAny)
        Shelf.SERVER -> Box(Modifier.fillMaxSize().padding(top = LocalPageTop.current + 12.dp)) {
            if (server != null) ServerShelf() else Message("No music server connected", "Connect one in Settings.")
        }
    }
}

/** Every playlist as cards in a grid, under the page's name: the same cards as the Library's Playlists tab. */
@Composable
private fun PlaylistsPage(liked: List<Song>, onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit, onOpenAny: (Any) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    val entries = rememberLibraryPlaylists()
    val cards = rememberPlaylistCards(liked, entries, onNew = { naming = true }, onShelf, onPlaylist, onOpenAny)
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = Web.gutter(maxWidth)
        val columns = ((maxWidth - gutter * 2 + 17.dp) / (188.dp + 17.dp)).toInt().coerceAtLeast(2)
        CompositionLocalProvider(LocalGutter provides gutter, LocalPageWidth provides maxWidth) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = LocalPageTop.current + 24.dp, bottom = LocalPageBottom.current)) {
                item(key = "title") { Text("Playlists", style = PageType.pageTitle, color = PageInk.text, modifier = Modifier.padding(start = gutter, end = gutter, bottom = 20.dp)) }
                cardGrid(cards, columns, key = { "pl:${it.key}" }) { c -> PlaylistCard(c) }
            }
        }
    }
    if (naming) {
        NewPlaylistDialog(onDismiss = { naming = false }) { made ->
            naming = false
            when (made) {
                is MadePlaylist.Local -> onPlaylist(made.id)
                is MadePlaylist.OnAccount -> onOpenAny(made.item)
            }
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"
private fun songCountOf(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

/** The Overview's tiles, wrapping onto a second line when the window is narrow. */
@Composable
private fun Tiles(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * Your year so far, as the phone's Library card shows it: minutes, plays,
 * songs and artists, with the song you've played most and its cover.
 */
@Composable
private fun YearCard(summary: Wrapped.Summary) {
    val top = summary.topSongs.firstOrNull()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val colour = rememberCoverColor(top?.thumbnailUrl, base)
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(tint(colour, base, 0.55f), tint(colour, base, 0.18f)))),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR ${java.time.Year.now().value} SO FAR", style = PageType.kicker, color = Color.White.copy(alpha = 0.8f))
                if (summary.isEmpty) {
                    Text("Play some music and your year shows here", style = PageType.heading, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("${"%,d".format(summary.minutes)} minutes", style = PageType.bigTitle(40f), color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 14.dp)) {
                        Stat("${summary.plays}", "plays")
                        Stat("${summary.songs}", "songs")
                        Stat("${summary.artists}", "artists")
                    }
                    summary.topArtists.firstOrNull()?.let {
                        Text("Top artist: ${it.title}", style = PageType.cardLine, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
            if (top != null) {
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Column(Modifier.padding(start = 20.dp).width(160.dp).hoverable(hover), horizontalAlignment = Alignment.CenterHorizontally) {
                    LockupArt(top.thumbnailUrl, CardShape, hovered, { top.song?.let { Player.playAll(listOf(it), 0) } }, "Play ${top.title}")
                    Text(top.title, color = Color.White, style = PageType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(if (top.plays == 1) "Played once" else "Played ${top.plays} times", color = Color.White.copy(alpha = 0.75f), style = PageType.cardLine)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = PageType.heading, color = Color.White)
        Text(label, style = PageType.cardLine, color = Color.White.copy(alpha = 0.75f))
    }
}

/**
 * A wide tile leading into part of the Library: 12dp corners on the faintest fill, stronger under
 * the pointer, an icon on a mark (the accent's for liked songs), the title in bold and a grey line.
 */
@Composable
private fun Shortcut(icon: ImageVector, title: String, subtitle: String, heart: Boolean = false, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val focused by hover.collectIsFocusedAsState()
    val fill by animateColorAsState(if (hovered || focused) PageInk.hover else PageInk.stripe, tween(PAGE_HOVER_MS), label = "shortcut")
    Row(
        Modifier.pressScale(hover).width(270.dp).clip(RoundedCornerShape(10.dp)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title, $subtitle" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (heart) WebInk.keyFill else PageInk.button),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (heart) Color.White else PageInk.text, modifier = Modifier.size(22.dp)) }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = PageType.cardTitle, color = PageInk.text, maxLines = 1)
            Text(subtitle, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A numbered row in a top list, striped as a track row: 70dp, the 56dp cover, 17sp lines. */
@Composable
private fun RankRow(rank: Int, entry: Wrapped.Entry, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val stripe = if (rank % 2 == 1) PageInk.stripe else PageInk.stripe.copy(alpha = 0f)
    val fill by animateColorAsState(if (hovered) PageInk.hover else stripe, tween(PAGE_HOVER_MS), label = "rank fill")
    Row(
        Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(4.dp)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Play ${entry.title}" }
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = PageType.small, color = PageInk.grey, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            CoverFill(entry.thumbnailUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(entry.title, style = PageType.rowTitle, color = PageInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle, style = PageType.cardLine, color = PageInk.grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (entry.plays == 1) "1 play" else "${entry.plays} plays", style = PageType.small, color = PageInk.grey)
    }
}
