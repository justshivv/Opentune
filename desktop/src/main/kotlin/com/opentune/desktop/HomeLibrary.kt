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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

/** A mood or genre opened from Home's chips. */
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
 * Home, headed as the web player's: the greeting in the accent over "What's playing?", with a
 * button to load the recommendations again. Then, filled as the phone fills it: the last song
 * you played, your mixes and most played, what you played lately in rows, moods and genres to
 * start from, then YouTube Music's rows (quick picks as rows of songs), page after page as you
 * scroll, carrying on with new releases and the charts when they run out.
 */
@Composable
internal fun HomePage(onOpen: (ShelfItem) -> Unit, onOpenAny: (Any) -> Unit) {
    var shelves by remember { mutableStateOf<List<Pages.Shelf>?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var extrasDone by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recent by Library.recent.collectAsState()
    val liked by Library.liked.collectAsState()
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
    // The heading's refresh: the feed from the start again, as the web's button does.
    fun refresh() {
        shelves = null; next = null; extrasDone = false
        load()
    }
    LaunchedEffect(Unit) { load() }
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
        "What's playing?", state = list, eyebrow = greeting(), webGutter = true,
        trailing = { RefreshButton(spinning = loaded == null && !failed, onClick = ::refresh) },
    ) {
        banners.forEach { b -> item(key = "banner:${b.id}") { DashboardBanner(b) } }
        recent.firstOrNull()?.let { last -> item(key = "hero") { JumpBackIn(last, recent) } }
        if (mixes.isNotEmpty() || speedDial.isNotEmpty()) item(key = "picks") {
            ShelfSection("Top picks for you") { TopPicks(mixes, speedDial.take(8), onOpenMix = { onOpenAny(it) }) }
        }
        if (recent.isNotEmpty()) item(key = "recents") { SongGrid("Recently played", recent.take(32)) }
        if (featured.isNotEmpty()) item(key = "featured") {
            ShelfSection(runtime.title("featured-playlists", "From the community"), action = {
                CircleButton(Icons.Rounded.Close, "Hide these playlists", size = 32.dp, iconSize = 16.dp, ring = false) {
                    com.opentune.data.config.HomeDismissals.dismissPlaylists(featured.map { it.id })
                }
            }) {
                val since = remember { System.nanoTime() }
                ShelfScroller(ShelfKind.SQUARE) {
                    shelfItems(featured.map { p -> ShelfItem(p.title, "OpenTune picks", p.artwork, null, p.browseId) }, ShelfKind.SQUARE, since) { _, card ->
                        SquareLockup(card, onOpen = { onOpen(card) })
                    }
                }
            }
        }
        if (moods.isNotEmpty()) item(key = "moods") {
            ShelfSection("Find your mood") { MoodTiles(moods) { onOpenAny(MoodKey(it.title, it.browseId, it.params)) } }
        }
        when {
            failed -> item(key = "failed") { Message("Couldn't load Home", "Check your connection and try again.", action = "Try again" to ::load) }
            loaded == null -> items(3, key = { "placeholder$it" }) { ShelfPlaceholder() }
            else -> {
                itemsIndexed(loaded, key = { i, _ -> "yt$i" }) { _, shelf ->
                    if (isSongShelf(shelf)) SongGrid(shelf.title, shelf.items.mapNotNull(Pages::songOf)) else ShelfRow(shelf, onOpen)
                }
                if (next != null || !extrasDone) item(key = "more") { ShelfPlaceholder() }
            }
        }
        if (liked.isNotEmpty() && loaded != null) item(key = "liked") {
            ShelfRow(Pages.Shelf("From your liked songs", liked.take(20).map(Pages::cardOf)), onOpen)
        }
    }
}

/**
 * Home's refresh (.ot-circle-button): a 44px disc of the faintest fill, stronger under the
 * pointer; its arrows turn while the feed loads.
 */
@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val still = stillMotion()
    val turn = if (spinning && !still) {
        rememberInfiniteTransition(label = "refresh").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "refresh turn").value
    } else 0f
    CircleButton(
        Icons.Rounded.Refresh, "Refresh recommendations", enabled = !spinning, size = 44.dp, iconSize = 20.dp, ring = false,
        iconModifier = Modifier.graphicsLayer { rotationZ = turn }, onClick = onClick,
    )
}

/** A row of songs (quick picks, trending): shown as columns of song rows, as the web player shows them, not as cards. */
private fun isSongShelf(shelf: Pages.Shelf) =
    shelf.title.trim().equals("Quick picks", ignoreCase = true) || shelf.items.size >= 8 && shelf.items.count { it.videoId != null } * 10 >= shelf.items.size * 9

/** The web player's greeting: late night, morning, afternoon or evening. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 0..4 -> "Late night"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * A banner from the dashboard, as the web draws it (.ot-banner): a softly tinted card with 18px
 * corners, its title in 15px, its words in grey, its button an accent pill, and a cross in the
 * corner to close it for good.
 */
@Composable
private fun DashboardBanner(banner: com.opentune.data.config.RemoteBanner) {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    val shape = RoundedCornerShape(18.dp)
    // The accent at 12% over the faintest fill, as color-mix(keyColor 12%, systemQuinary).
    val tintFill = androidx.compose.ui.graphics.lerp(WebInk.quinary, WebInk.key.copy(alpha = 1f), 0.12f)
    Box(Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().clip(shape).background(tintFill)) {
        Column(Modifier.padding(start = 20.dp, end = 52.dp, top = 18.dp, bottom = 18.dp)) {
            Text(banner.title, style = WebType.title3Emph, color = WebInk.primary, modifier = Modifier.padding(bottom = 4.dp))
            if (banner.body.isNotBlank()) Text(banner.body, style = WebType.bodyTall, color = WebInk.secondary, modifier = Modifier.widthIn(max = 520.dp))
            val source = remember { MutableInteractionSource() }
            Text(
                "${banner.action} ↗", style = WebType.bodyEmph, color = Color.White,
                modifier = Modifier.padding(top = 12.dp).pressScale(source).clip(CircleShape).background(WebInk.keyFill)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { runCatching { links.openUri(banner.url) } }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        // The cross in the corner, 10px in, closing the banner for good.
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            CircleButton(Icons.Rounded.Close, "Dismiss ${banner.title}", size = 32.dp, iconSize = 16.dp, ring = false) {
                com.opentune.data.config.HomeDismissals.dismissBanner(banner.id)
            }
        }
    }
}

/**
 * One of Home's rows of cards (ShelfView): its title and its cards, an artist's round, each
 * sized to share the width, that scroll sideways under the page's arrows. A song plays from its
 * card, with the rest of the row after it; anything else opens.
 */
@Composable
internal fun ShelfRow(shelf: Pages.Shelf, onOpen: (ShelfItem) -> Unit) {
    val songs = remember(shelf) { shelf.items.mapNotNull(Pages::songOf) }
    val artists = shelf.items.isNotEmpty() && shelf.items.all { it.browseId?.startsWith("UC") == true }
    val kind = if (artists) ShelfKind.CIRCLE else ShelfKind.SQUARE
    val since = remember(shelf) { System.nanoTime() }
    ShelfSection(shelf.title) {
        ShelfScroller(kind) {
            shelfItems(shelf.items, kind, since) { _, item ->
                if (artists) {
                    CircleLockup(item, onOpen = { onOpen(item) })
                } else {
                    val song = Pages.songOf(item)
                    val play = { if (song != null) Player.playAll(songs, songs.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)) else onOpen(item) }
                    SquareLockup(item, onOpen = play, song = song)
                }
            }
        }
    }
}

/** A shelf's shape, shimmering, while Home loads. */
@Composable
internal fun ShelfPlaceholder() = ShelfSkeleton(ShelfKind.SQUARE)

/** Songs as four rows that scroll sideways in columns, each with its cover, title and artist (the web's TrackShelf). */
@Composable
internal fun SongGrid(title: String, songs: List<Song>, rows: Int = 4) {
    TrackShelf(title, songs, rows = rows)
}

/**
 * The banner Home opens with: the last song you played, its cover large and
 * lifted on a wide card over a soft blur of itself in its own colours, with
 * Play to carry on and Shuffle for everything you played lately.
 */
@Composable
private fun JumpBackIn(song: Song, recent: List<Song>) {
    val accent = coverAccent(song.thumbnailUrl)
    val shape = RoundedCornerShape(Web.ArtLarge)
    val hover = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().height(240.dp)
            .shadow(24.dp, shape, ambientColor = accent, spotColor = accent).clip(shape).hoverable(hover),
    ) {
        // The cover gives way first as the card narrows, so the words and buttons never squeeze.
        val cover = when { maxWidth >= 620.dp -> 184.dp; maxWidth >= 440.dp -> 136.dp; else -> null }
        val roomy = maxWidth >= 540.dp
        SoftBackdrop(song.thumbnailUrl, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.6f to accent.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.15f))))
        Row(Modifier.fillMaxSize().padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
            cover?.let { Cover(song.thumbnailUrl, it, RoundedCornerShape(Web.ArtMedium), Modifier.shadow(28.dp, RoundedCornerShape(Web.ArtMedium))) }
            Column(Modifier.padding(start = if (cover != null) 28.dp else 0.dp).weight(1f)) {
                Text("JUMP BACK IN", style = webType(11f, 14f, 700, 0.12f), color = Color.White.copy(alpha = 0.75f), maxLines = 1, softWrap = false)
                Text(song.title, style = WebType.header(34f, -0.02f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(song.artist, style = WebType.title3, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Play", Icons.Rounded.PlayArrow) { Player.playAll(recent, 0) }
                    SecondaryButton(if (roomy) "Shuffle recents" else "Shuffle", Icons.Rounded.Shuffle) { Player.playAll(recent.shuffled(), 0) }
                }
            }
        }
    }
}

/**
 * Top picks: tall cards in the shelf's columns, your daily mixes as a burst of colour with three
 * of their covers in circles, then the songs you play most as full covers.
 */
@Composable
private fun TopPicks(mixes: List<DailyMixes.Mix>, most: List<ShelfItem>, onOpenMix: (DailyMixes.Mix) -> Unit) {
    val songs = remember(most) { most.mapNotNull(Pages::songOf) }
    val picks: List<Any> = mixes + most
    val since = remember { System.nanoTime() }
    ShelfScroller(ShelfKind.SQUARE) {
        shelfItems(picks, ShelfKind.SQUARE, since) { _, pick ->
            when (pick) {
                is DailyMixes.Mix -> PickCard(label = "Made for you", title = pick.title, line = pick.subtitle, colourOf = pick.covers.firstOrNull(), onClick = { onOpenMix(pick) }) {
                    MixCircles(pick.covers.filterNotNull())
                }
                is ShelfItem -> PickCard(label = "Played most", title = pick.title, line = pick.subtitle, colourOf = pick.thumbnailUrl, fullCover = pick.thumbnailUrl, onClick = {
                    val at = songs.indexOfFirst { it.videoId == pick.videoId }
                    if (at >= 0) Player.playAll(songs, at)
                })
            }
        }
    }
}

/**
 * A tall card, 3:4, with the web's 20px corners: a cover filling it, or a colour from [colourOf]
 * with [art] on it; its kind, title and a line at the foot. It lifts a little under the pointer
 * as a cover does.
 */
@Composable
private fun PickCard(label: String, title: String, line: String, colourOf: String?, fullCover: String? = null, onClick: () -> Unit, art: @Composable () -> Unit = {}) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val colour = rememberCoverColor(colourOf, base)
    val shape = RoundedCornerShape(Web.ArtMedium)
    Box(Modifier.width(LocalColumnWidth.current).hoverable(hover)) {
        LockupArt(
            fullCover, shape, hovered, onClick, title, aspect = 0.75f,
            art = { m ->
                Box(m.background(Brush.linearGradient(listOf(lerpColor(colour, Color.White, 0.12f), lerpColor(colour, Color.Black, 0.35f))))) {
                    if (fullCover != null) CoverFill(fullCover, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { art() }
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(label, style = WebType.callout, color = Color.White.copy(alpha = 0.75f))
                        Text(title, style = WebType.title3Emph, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(line, style = WebType.callout, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Three covers in overlapping circles, as a mix's card shows who's in it, sized to the card. */
@Composable
private fun MixCircles(covers: List<String>) {
    BoxWithConstraints(Modifier.fillMaxWidth(0.86f).aspectRatio(1f).padding(bottom = 40.dp)) {
        val w = maxWidth
        covers.getOrNull(0)?.let { Cover(it, w * 0.6f, CircleShape, Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 8.dp)) }
        covers.getOrNull(1)?.let { Cover(it, w * 0.41f, CircleShape, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) }
        covers.getOrNull(2)?.let { Cover(it, w * 0.34f, CircleShape, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** Moods and genres as the web's browse tiles, two rows that scroll sideways. */
@Composable
private fun MoodTiles(moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit) {
    val palette = listOf(0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457, 0xFF4E342E, 0xFF283593, 0xFF9E9D24)
    val since = remember { System.nanoTime() }
    ShelfScroller(ShelfKind.TILES) {
        shelfItems(moods, ShelfKind.TILES, since, key = { it.title }) { _, m ->
            CategoryTile(m.title, Color(palette[(m.title.hashCode() and 0x7fffffff) % palette.size])) { onOpen(m) }
        }
    }
}

/**
 * New: what's just come out and what's climbing, as BitChord's New tab has
 * it: new albums and singles as covers, trending songs in columns of rows,
 * then the charts.
 */
@Composable
internal fun NewPage(onOpen: (ShelfItem) -> Unit) {
    val shelves by produceState<List<Pages.Shelf>?>(null) { value = runCatching { Pages.moreForHome() }.getOrDefault(emptyList()) }
    ScrollPage("Explore", "New releases and what's climbing the charts", webGutter = true) {
        when (val loaded = shelves) {
            null -> items(3) { ShelfPlaceholder() }
            else -> if (loaded.isEmpty()) item { Message("Nothing new could be loaded", "Check your connection and try again.") }
                else items(loaded) { shelf ->
                    if (isSongShelf(shelf)) SongGrid(shelf.title, shelf.items.mapNotNull(Pages::songOf)) else ShelfRow(shelf, onOpen)
                }
        }
    }
}

/** A daily mix as a card: four of its covers, its name and who's in it, and the play disc under the pointer. */
@Composable
private fun MixCard(mix: DailyMixes.Mix, onOpen: () -> Unit) {
    SquareLockup(
        ShelfItem(mix.title, mix.subtitle, mix.covers.firstOrNull(), null, null),
        onOpen = onOpen,
        art = { m -> Mosaic(mix.covers.filterNotNull(), m, RoundedCornerShape(0.dp)) },
        onPlay = { Player.playAll(mix.songs, 0) },
    )
}

/** A mood or genre's own page: its rows of playlists. */
@Composable
internal fun MoodPage(mood: MoodKey, onOpen: (ShelfItem) -> Unit) {
    val shelves by produceState<List<Pages.Shelf>?>(null, mood) {
        value = runCatching { Pages.shelves(mood.browseId, mood.params) }.getOrDefault(emptyList())
    }
    ScrollPage(mood.title, webGutter = true) {
        when (val loaded = shelves) {
            null -> items(3) { ShelfPlaceholder() }
            else -> if (loaded.isEmpty()) item { Message("Nothing here right now", "Try another mood or genre.") }
                else items(loaded) { ShelfRow(it, onOpen) }
        }
    }
}

/** A daily mix: the head over a blur of its covers, then its songs. */
@Composable
internal fun MixPage(mix: DailyMixes.Mix) {
    CollectionLayout(mix.title, mix.covers.firstOrNull(), mix.songs, failed = false) {
        CollectionHero(
            cover = { m -> Mosaic(mix.covers.filterNotNull(), m) },
            coverUrl = mix.covers.firstOrNull(), kind = "Made for you", title = mix.title, subtitle = mix.subtitle, songs = mix.songs,
        )
    }
}

// ---- Library -----------------------------------------------------------------

/**
 * The Library, as the phone opens it, drawn as the web player's pages: your year so far,
 * shortcuts to liked songs, what you played and your server, your playlists as cards, what you
 * play most this month (songs and artists), and the last songs you played.
 */
@Composable
internal fun LibraryHome(onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit, onOpenAny: (Any) -> Unit) {
    val liked by Library.liked.collectAsState()
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
    // Signed in: the account's own library on YouTube Music too, as on the phone and the web player.
    val signedIn by com.opentune.data.account.AccountStore.signedIn.collectAsState()
    val accountName by com.opentune.data.account.AccountStore.account.collectAsState()
    val onAccount by produceState(emptyList<Pages.Shelf>(), signedIn, accountName) {
        value = if (signedIn) runCatching { Pages.accountLibrary() }.getOrDefault(emptyList()) else emptyList()
    }
    val since = remember { System.nanoTime() }
    ScrollPage("Library", webGutter = true) {
        item(key = "year") { YearCard(year) }
        item(key = "shortcuts") {
            Tiles {
                Shortcut(Icons.Rounded.Favorite, "Liked songs", songCount(liked.size), heart = true) { onShelf(Shelf.LIKED) }
                Shortcut(Icons.Rounded.History, "Recently played", if (recent.isEmpty()) "Nothing yet" else "${recent.size} songs") { onShelf(Shelf.RECENT) }
                server?.let { s -> Shortcut(Icons.Rounded.Storage, "Your server", s.url.substringAfter("://").substringBefore('/')) { onShelf(Shelf.SERVER) } }
                Shortcut(Icons.Rounded.Add, "New playlist", "Start one here") { naming = true }
            }
        }
        item(key = "playlists") {
            ShelfSection(
                "Playlists", if (playlists.isEmpty()) "Make one with New playlist" else songCountOf(playlists.size, "playlist"),
                onSeeAll = { onShelf(Shelf.PLAYLISTS) }.takeIf { playlists.isNotEmpty() },
            ) {
                if (playlists.isEmpty()) {
                    Text(
                        "Playlists you make show here. Add songs to one from any song's menu.",
                        style = WebType.body, color = WebInk.secondary, modifier = Modifier.padding(horizontal = LocalGutter.current),
                    )
                } else {
                    ShelfScroller(ShelfKind.SQUARE) {
                        shelfItems(playlists, ShelfKind.SQUARE, since, key = { it.id }) { _, p ->
                            SquareLockup(
                                ShelfItem(p.name, songCount(p.songs.size), p.covers.firstOrNull(), null, null),
                                onOpen = { onPlaylist(p.id) },
                                art = { m -> Mosaic(p.covers, m, RoundedCornerShape(0.dp)) },
                                onPlay = { Player.playAll(p.songs.map { it.toSong() }, 0) }.takeIf { p.songs.isNotEmpty() },
                            )
                        }
                    }
                }
            }
        }
        if (signedIn) item(key = "ytm-liked") {
            ShelfSection("On YouTube Music", accountName?.name) {
                Tiles {
                    Shortcut(Icons.Rounded.Favorite, "Liked music", "Your likes on YouTube Music", heart = true) {
                        onOpenAny(ShelfItem("Liked music", "Playlist", null, null, "VLLM"))
                    }
                    Shortcut(Icons.Rounded.History, "History", "What you played on YouTube Music") {
                        onOpenAny(ShelfItem("History", "Playlist", null, null, "FEmusic_history"))
                    }
                }
            }
        }
        onAccount.forEach { shelf -> item(key = "ytm:${shelf.title}") { ShelfRow(shelf) { onOpenAny(it) } } }
        if (mixes.isNotEmpty()) item(key = "mixes") {
            ShelfSection("Your mixes", "Made fresh each day from what you play") {
                ShelfScroller(ShelfKind.SQUARE) {
                    shelfItems(mixes, ShelfKind.SQUARE, since, key = { it.id }) { _, mix -> MixCard(mix, onOpen = { onOpenAny(mix) }) }
                }
            }
        }
        if (month.topSongs.isNotEmpty()) item(key = "top-songs") {
            ShelfSection("On repeat this month", "${month.plays} plays, ${month.minutes} minutes") {
                val songs = month.topSongs.mapNotNull { it.song }.take(10)
                Column(Modifier.padding(horizontal = LocalGutter.current).widthIn(max = 720.dp)) {
                    month.topSongs.take(5).forEachIndexed { i, e ->
                        RankRow(i + 1, e) { e.song?.let { s -> Player.playAll(songs, songs.indexOfFirst { it.videoId == s.videoId }.coerceAtLeast(0)) } }
                    }
                }
            }
        }
        if (month.topArtists.isNotEmpty()) item(key = "top-artists") {
            ShelfSection("Your top artists", "This month") {
                ShelfScroller(ShelfKind.CIRCLE) {
                    shelfItems(month.topArtists.take(12), ShelfKind.CIRCLE, since, key = { it.title }) { _, a ->
                        CircleLockup(ShelfItem(a.title, "", a.thumbnailUrl, null, null), onOpen = { a.song?.let { Player.playAll(listOf(it), 0) } }, line = "${a.plays} plays")
                    }
                }
            }
        }
        if (recent.isNotEmpty()) item(key = "recent") {
            TrackShelf("Recently played", recent, onSeeAll = { onShelf(Shelf.RECENT) })
        }
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
            naming = false
            onPlaylist(Library.createPlaylist(name))
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"
private fun songCountOf(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

/** The Library's tiles, wrapping onto a second line when the window is narrow. */
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
    val shape = RoundedCornerShape(Web.ArtMedium)
    Box(
        Modifier.padding(horizontal = LocalGutter.current).fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(tint(colour, base, 0.55f), tint(colour, base, 0.18f)))),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your ${java.time.Year.now().value} so far", style = WebType.bodyEmph, color = Color.White.copy(alpha = 0.8f))
                if (summary.isEmpty) {
                    Text("Play some music and your year shows here", style = WebType.title2, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("${"%,d".format(summary.minutes)} minutes", style = WebType.header(34f, -0.02f), color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 14.dp)) {
                        Stat("${summary.plays}", "plays")
                        Stat("${summary.songs}", "songs")
                        Stat("${summary.artists}", "artists")
                    }
                    summary.topArtists.firstOrNull()?.let {
                        Text("Top artist: ${it.title}", style = WebType.body, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
            if (top != null) {
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Column(Modifier.padding(start = 20.dp).width(150.dp).hoverable(hover), horizontalAlignment = Alignment.CenterHorizontally) {
                    LockupArt(top.thumbnailUrl, RoundedCornerShape(Web.ArtMedium), hovered, { top.song?.let { Player.playAll(listOf(it), 0) } }, "Play ${top.title}")
                    Text(top.title, color = Color.White, style = WebType.bodyEmph, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text("Played ${top.plays} times", color = Color.White.copy(alpha = 0.75f), style = WebType.callout)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = WebType.title2, color = Color.White)
        Text(label, style = WebType.callout, color = Color.White.copy(alpha = 0.75f))
    }
}

/**
 * A wide tile leading into part of the Library, drawn as the web's Settings head card
 * (.ot-set-hero): 18px corners on the faintest fill, stronger under the pointer, an icon on a
 * 12px-cornered mark (the accent's for liked songs), the title in 15px and a grey line.
 */
@Composable
private fun Shortcut(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, heart: Boolean = false, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val focused by hover.collectIsFocusedAsState()
    val fill by animateColorAsState(if (hovered || focused) WebInk.quaternary else WebInk.quinary, tween(150), label = "shortcut")
    Row(
        Modifier.pressScale(hover).width(250.dp).clip(RoundedCornerShape(18.dp)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title, $subtitle" }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (heart) WebInk.keyFill else WebInk.quaternary),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (heart) Color.White else WebInk.primary, modifier = Modifier.size(22.dp)) }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = WebType.title3Emph, color = WebInk.primary, maxLines = 1)
            Text(subtitle, style = WebType.callout, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A numbered row in a top list, drawn as a song in a shelf of rows: 55px, the 40px cover, 13px and 12px lines. */
@Composable
private fun RankRow(rank: Int, entry: Wrapped.Entry, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) WebInk.rowHover else WebInk.rowHover.copy(alpha = 0f), tween(if (hovered) 0 else 200), label = "rank fill")
    Row(
        Modifier.fillMaxWidth().height(55.dp).clip(RoundedCornerShape(Web.TrackRow)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Play ${entry.title}" }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = WebType.title3Emph, color = WebInk.secondary, modifier = Modifier.width(32.dp).padding(start = 8.dp))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Web.ArtSmall)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            CoverFill(entry.thumbnailUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(entry.title, style = WebType.body, color = WebInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle, style = WebType.callout, color = WebInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${entry.plays} plays", style = WebType.callout, color = WebInk.secondary)
    }
}
