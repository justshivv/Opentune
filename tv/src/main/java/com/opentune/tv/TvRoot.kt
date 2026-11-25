package com.opentune.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where the TV app can be: one of the tabs, a page opened from one, or the player. */
sealed interface Dest {
    data object Home : Dest
    data object Explore : Dest
    data object Library : Dest
    data object Search : Dest
    data object Settings : Dest
    data object NowPlaying : Dest
    data class Collection(val browseId: String, val title: String? = null, val art: String? = null) : Dest
    data class Artist(val browseId: String, val name: String? = null) : Dest
    data class Mood(val browseId: String, val params: String?, val title: String) : Dest
    data object Liked : Dest
    data class Local(val id: String) : Dest
}

/**
 * What a screen can ask of the app: open a page, or play something.
 *
 * The pages form a stack: a tab is its root, [open] puts a page on top and [back] takes it off.
 * Each page on the stack has a [PageMemory] (focus, loaded data) and its saved scroll state, so
 * Back returns to it as it was left.
 */
class TvNav(val stack: SnapshotStateList<Dest>, val player: TvPlayer, val remote: TvRemote? = null) {
    /** A search to run when the Search page opens: what the viewer said to the TV's voice search. */
    var pendingSearch by mutableStateOf<String?>(null)

    val current: Dest get() = stack.last()

    private var nextId = 0
    private val memories = ArrayList<PageMemory>()

    /** The memory of the page at [index] in the stack. */
    internal fun memoryAt(index: Int): PageMemory {
        while (memories.size > stack.size) memories.removeAt(memories.lastIndex)
        while (memories.size <= index) memories.add(PageMemory(nextId++))
        return memories[index]
    }

    /** The ids of the pages on the stack, whose saved state the shell keeps. */
    internal fun liveIds(): Set<Int> = (0..stack.lastIndex).mapTo(HashSet()) { memoryAt(it).id }

    fun open(dest: Dest) {
        if (stack.last() == dest) return
        memoryAt(stack.lastIndex)
        stack.add(dest)
        memoryAt(stack.lastIndex)
    }

    /** Opens the tab [dest] fresh, at its top, with nothing above it. */
    fun goTab(dest: Dest) {
        memories.clear()
        stack.clear()
        stack.add(dest)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        memoryAt(stack.lastIndex).wantsFocus = true
        return true
    }

    /** Plays [song] on its own and shows the player. */
    fun play(song: Song, source: String) { player.play(song, source); open(Dest.NowPlaying) }

    /** Plays [songs] from [index], and shows the player. */
    fun playList(songs: List<Song>, index: Int, source: String, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        player.playAll(songs, index, shuffle, source)
        open(Dest.NowPlaying)
    }

    /** A card from a shelf: a song plays; an album, playlist or artist opens. */
    fun openShelfItem(item: ShelfItem, from: String) {
        val id = item.browseId
        when {
            id != null -> when (MusicRepository.typeOf(id)) {
                BrowseType.ARTIST -> open(Dest.Artist(id, item.title))
                else -> open(Dest.Collection(id, item.title, item.thumbnailUrl))
            }
            item.videoId != null -> play(item.asSong(), from)
        }
    }
}

/** The song a shelf card stands for, credited to the artist and not to the card's kind. */
fun ShelfItem.asSong(): Song = Song(
    videoId = videoId.orEmpty(), title = title,
    artist = com.opentune.data.innertube.InnertubeParser.artistFromSubtitle(subtitle),
    thumbnailUrl = thumbnailUrl, isExplicit = explicit.takeIf { it },
)

/** The tabs in the bar, in order. Search sits first, as an icon, where TV apps keep it. */
private val Tabs = listOf(Dest.Search, Dest.Home, Dest.Explore, Dest.Library)

/**
 * The TV app's shell: the bar along the top (tabs, what's playing, Settings) and the page under
 * it. Navigation is top tabs rather than a side rail: the screen is wide, so shelves keep the full
 * width, the tabs' names are always readable without a rail that opens and closes, and Up from a
 * page's first row reaches them.
 *
 * Focus: a page takes the focus when it opens (its entry item, else its first item); Back
 * returns to the item last focused on the page beneath. Up into the bar lands on the current tab.
 * Back goes up one page, then to Home, and from Home leaves the app.
 */
@Composable
fun TvRoot(player: TvPlayer, voice: MutableStateFlow<String?> = MutableStateFlow(null), remote: TvRemote? = null) {
    val stack = remember { mutableStateListOf<Dest>(Dest.Home) }
    val nav = remember(player, remote) { TvNav(stack, player, remote) }
    TvShell(nav, voice)
}

/** [TvRoot] around a given [nav], which tests fill beforehand. */
@Composable
internal fun TvShell(nav: TvNav, voice: MutableStateFlow<String?> = remember { MutableStateFlow(null) }) {
    val stack = nav.stack
    val player = nav.player
    val remote = nav.remote
    val current = stack.last()

    KeepAwake(player, onPlayer = current == Dest.NowPlaying)

    // A paired phone's trackpad and buttons act on this screen; what's playing goes back to it.
    val activity = LocalContext.current.findActivity()
    if (remote != null && activity != null) {
        val input = remember(activity, nav, remote) { TvRemoteInput(activity, remote.pointer, nav) }
        DisposableEffect(input) { remote.attach(input); onDispose { remote.detach(input) } }
        val phonesNow by remote.controllers.collectAsState()
        LaunchedEffect(phonesNow.isNotEmpty(), input) {
            while (phonesNow.isNotEmpty()) { remote.push(input.now()); delay(NOW_EVERY_MS) }
        }
    }

    // "Play … on NepTune" from the voice search opens Search with it typed in.
    val spoken by voice.collectAsState()
    LaunchedEffect(spoken) {
        spoken?.let { nav.goTab(Dest.Search); nav.pendingSearch = it; voice.value = null }
    }

    // Back goes up through pages, then home, and only from home leaves the app.
    BackHandler(enabled = stack.size > 1 || current != Dest.Home) {
        if (!nav.back()) nav.goTab(Dest.Home)
    }

    // Each page's scroll positions, kept while it is on the stack and dropped once it leaves.
    val saved = rememberSaveableStateHolder()
    val kept = remember { HashSet<Int>() }
    val live = nav.liveIds()
    LaunchedEffect(live) {
        (kept - live).forEach { saved.removeState(it) }
        kept.retainAll(live); kept.addAll(live)
    }

    val memory = nav.memoryAt(stack.lastIndex)
    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        Backdrop(nav, current)
        Column(Modifier.fillMaxSize()) {
            if (current != Dest.NowPlaying) TopBar(nav, current, onBarFocused = { memory.wantsFocus = false })
            key(memory.id) {
                val pageFocus = remember { FocusRequester() }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .focusRequester(pageFocus)
                        .onFocusChanged { if (it.hasFocus) memory.wantsFocus = false }
                        .focusRestorer()
                        .focusGroup()
                        .fadeTopEdge(),
                ) {
                    saved.SaveableStateProvider(memory.id) {
                        CompositionLocalProvider(LocalPageMemory provides memory) { Page(nav, current) }
                    }
                }
                EnterPage(memory, pageFocus)
            }
        }
        if (remote != null) TvCursor(remote.pointer)
    }
}

/** The page for [dest]. A new page is added here and to [Dest]. */
@Composable
private fun Page(nav: TvNav, dest: Dest) {
    when (dest) {
        Dest.Home -> HomeScreen(nav)
        Dest.Explore -> ExploreScreen(nav)
        Dest.Library -> LibraryScreen(nav)
        Dest.Search -> SearchScreen(nav)
        Dest.Settings -> SettingsScreen(nav)
        Dest.NowPlaying -> NowPlayingScreen(nav)
        Dest.Liked -> LikedScreen(nav)
        is Dest.Local -> LocalPlaylistScreen(nav, dest.id)
        is Dest.Collection -> CollectionScreen(nav, dest)
        is Dest.Artist -> ArtistScreen(nav, dest)
        is Dest.Mood -> MoodScreen(nav, dest)
    }
}

/**
 * Puts the focus into a page that wants it: the item it last had (by focusKey), else its entry
 * item, else its first focusable one. Waits a frame so a page that places its own focus (Search's
 * field, the player's Play) goes first, and tries again as items arrive (a page still loading).
 * Stops once anything in the page has the focus, or the viewer moves along the bar.
 */
@Composable
private fun EnterPage(memory: PageMemory, pageFocus: FocusRequester) {
    // Nothing takes the focus in touch mode; a remote's first key switches to key mode, so try again then.
    val inputModes = LocalInputModeManager.current
    LaunchedEffect(memory) {
        withFrameNanos { }
        val since = SystemClock.uptimeMillis()
        // A remembered item that doesn't come back (the page's data changed) gives way to the first.
        launch { delay(REMEMBERED_WAIT_MS); if (memory.wantsFocus) memory.arrivals++ }
        snapshotFlow { Triple(memory.wantsFocus, memory.arrivals, inputModes.inputMode) }.collect { (wants, _, _) ->
            if (!wants) return@collect
            val remembered = memory.focusKey?.let { memory.requesters[it] }
            val waiting = memory.focusKey != null && SystemClock.uptimeMillis() - since < REMEMBERED_WAIT_MS
            runCatching {
                when {
                    remembered != null -> remembered.requestFocus()
                    waiting -> Unit
                    memory.entry != null -> memory.entry!!.requestFocus()
                    else -> pageFocus.requestFocus(FocusDirection.Enter)
                }
            }
        }
    }
}

private const val REMEMBERED_WAIT_MS = 1_500L

/**
 * Rows scrolled up fade out under the bar instead of showing cut in half. A mask, not a coloured
 * overlay, so it suits a cover-tinted page as well as a plain one. The page is drawn once into its
 * own layer and only redrawn when it changes, so a still page costs nothing more.
 */
private fun Modifier.fadeTopEdge(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = TvSpace.XL.toPx()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }

/** Keeps the TV from dimming while the player is open and music plays; otherwise its screensaver may come. */
@Composable
private fun KeepAwake(player: TvPlayer, onPlayer: Boolean) {
    val playing by player.isPlaying.collectAsState()
    val view = LocalView.current
    val awake = playing && onPlayer
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }
}

/** The player and an album's page take a still tint from its cover; every other page is plain. */
@Composable
private fun Backdrop(nav: TvNav, current: Dest) {
    when (current) {
        Dest.NowPlaying -> {
            val song by nav.player.currentSong.collectAsState()
            CoverTint(song?.thumbnailUrl)
        }
        is Dest.Collection -> CoverTint(current.art)
        else -> Unit
    }
}

@Composable
private fun CoverTint(url: String?) {
    if (url == null) return
    val colors = rememberCoverColors(url)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.top, colors.bottom, TvColors.Background))))
}

/** How often the phone is told what's playing, which keeps its progress bar moving. */
private const val NOW_EVERY_MS = 700L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The bar: the tabs at the left; what's playing and Settings at the right. Up into it from a page
 * lands on the current tab, not on whatever is nearest. [onBarFocused] tells the shell the viewer
 * is choosing in the bar, so a page still loading doesn't pull the focus away.
 */
@Composable
private fun TopBar(nav: TvNav, current: Dest, onBarFocused: () -> Unit) {
    val root = nav.stack.first()
    val requesters = remember { (Tabs + Dest.Settings).associateWith { FocusRequester() } }
    val currentTab = requesters[root] ?: requesters.getValue(Dest.Home)
    Row(
        Modifier.fillMaxWidth()
            .padding(start = TvSafe.Horizontal, end = TvSafe.Horizontal, top = TvSafe.Vertical, bottom = TvSpace.M)
            .focusProperties {
                onEnter = { if (requestedFocusDirection != FocusDirection.Enter) runCatching { currentTab.requestFocus() } }
            }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpace.S),
    ) {
        Tabs.forEach { tab ->
            BarTab(
                label = tabName(tab),
                icon = if (tab == Dest.Search) Icons.Rounded.Search else null,
                selected = tab == root,
                focusRequester = requesters.getValue(tab),
                onFocused = onBarFocused,
                onClick = { nav.goTab(tab) },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { NowPlayingStrip(nav, onFocused = onBarFocused) }
        val account by AccountStore.account.collectAsState()
        val photo = account?.thumbnailUrl
        if (photo != null) TvSurface(
            onClick = { nav.goTab(Dest.Settings) }, shape = TvShapes.Round,
            container = if (root == Dest.Settings) TvColors.SurfaceHigh else Color.Transparent,
            focusRequester = requesters.getValue(Dest.Settings), onFocused = onBarFocused, role = Role.Tab,
            modifier = Modifier.size(BAR_HEIGHT).semantics { this.selected = root == Dest.Settings },
        ) { _ -> Cover(photo, Modifier.align(Alignment.Center).size(40.dp), TvShapes.Round, px = TvArt.Small) }
        else BarTab(
            label = "Settings", icon = Icons.Rounded.Settings, selected = root == Dest.Settings,
            focusRequester = requesters.getValue(Dest.Settings), onFocused = onBarFocused,
            onClick = { nav.goTab(Dest.Settings) },
        )
    }
}

private val BAR_HEIGHT = 52.dp

private fun tabName(tab: Dest) = when (tab) {
    Dest.Home -> "Home"
    Dest.Explore -> "Explore"
    Dest.Library -> "Library"
    Dest.Search -> "Search"
    else -> "Settings"
}

/** A tab: its name (or an [icon] alone, named for screen readers). The current one is filled and bright. */
@Composable
private fun BarTab(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    TvSurface(
        onClick = onClick,
        shape = TvShapes.Pill,
        container = if (selected) TvColors.SurfaceHigh else Color.Transparent,
        focusedContainer = TvColors.SurfaceTop,
        role = Role.Tab,
        focusRequester = focusRequester,
        onFocused = onFocused,
        modifier = Modifier.height(BAR_HEIGHT).then(if (icon != null) Modifier.width(BAR_HEIGHT) else Modifier)
            .semantics { this.selected = selected },
    ) { lit ->
        val ink = if (lit || selected) TvColors.Text else TvColors.TextDim
        if (icon != null) Icon(icon, label, tint = ink, modifier = Modifier.align(Alignment.Center).size(TvSizes.Icon))
        else Text(label, style = TvType.Label, color = ink, maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp))
    }
}

/**
 * What's playing, small, in the bar: the cover and title (OK opens the player), and Play/Pause
 * beside it. Nothing when nothing plays. It reads only the song and whether it plays, never the
 * position, so it redraws when those change and not as the song goes on.
 */
@Composable
private fun NowPlayingStrip(nav: TvNav, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val song by nav.player.currentSong.collectAsState()
    val playing by nav.player.isPlaying.collectAsState()
    val s = song ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
        TvSurface(
            onClick = { nav.open(Dest.NowPlaying) },
            shape = TvShapes.Pill,
            container = TvColors.Surface,
            onFocused = onFocused,
            modifier = Modifier.height(BAR_HEIGHT).widthIn(max = 340.dp).weight(1f, fill = false),
        ) { _ ->
            Row(Modifier.padding(start = 6.dp, end = 22.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Cover(s.thumbnailUrl, Modifier.size(40.dp), TvShapes.Round, px = TvArt.Small)
                Column(Modifier.padding(start = TvSpace.M)) {
                    Text(s.title, style = TvType.Meta, color = TvColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TvSurface(
            onClick = nav.player::togglePlayPause, shape = TvShapes.Round,
            container = TvColors.Surface, onFocused = onFocused,
            modifier = Modifier.size(BAR_HEIGHT),
        ) { _ ->
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                tint = TvColors.Text, modifier = Modifier.align(Alignment.Center).size(TvSizes.Icon),
            )
        }
    }
}
