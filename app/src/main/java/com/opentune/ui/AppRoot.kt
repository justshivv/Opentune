package com.opentune.ui

import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import com.opentune.ui.server.ServerAlbumScreen
import com.opentune.ui.server.ServerArtistScreen
import com.opentune.ui.server.ServerArtistsScreen
import com.opentune.ui.server.ServerNav
import com.opentune.ui.server.ServerPlaylistScreen
import com.opentune.ui.server.ServerScreen
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.EnterExitState
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.toSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.artworkAt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalOverscrollFactory
import com.opentune.ui.components.RubberBandOverscrollFactory
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentune.data.MusicRepository
import kotlinx.coroutines.flow.first
import com.opentune.data.model.SearchResult
import com.opentune.data.model.SearchFilter
import com.opentune.data.library.LibraryStore
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ShelfItem
import com.opentune.ui.browse.ArtistScreen
import com.opentune.ui.browse.CollectionScreen
import com.opentune.ui.browse.SongActions
import com.opentune.ui.account.LoginScreen
import com.opentune.ui.components.LocalBackdrop
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.LocalSongMenu
import com.opentune.ui.components.SongMenuActions
import com.opentune.ui.library.DownloadsScreen
import com.opentune.ui.library.LibraryNav
import com.opentune.ui.library.LikedScreen
import com.opentune.ui.library.LocalPlaylistScreen
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.opentune.ui.explore.ExploreScreen
import com.opentune.ui.explore.MoodScreen
import com.opentune.ui.home.HomeScreen
import com.opentune.ui.library.LibraryScreen
import com.opentune.ui.library.LocalMusicScreen
import com.opentune.ui.library.ReplayScreen
import com.opentune.ui.wrapped.WrappedScreen
import com.opentune.ui.player.MiniPlayerBar
import com.opentune.ui.player.PlayerScreen
import com.opentune.ui.search.SearchScreen
import com.opentune.ui.settings.EqualizerScreen
import com.opentune.ui.settings.SettingsScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** A tab tapped again while its root page is showing, for the page to scroll back to its top and refresh. */
object TabEvents {
    private val _reselected = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val reselected: kotlinx.coroutines.flow.SharedFlow<String> = _reselected
    fun reselect(route: String) { _reselected.tryEmit(route) }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    EXPLORE("explore", "Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
    PODCASTS("podcasts", "Podcasts", Icons.Outlined.Podcasts, Icons.Rounded.Podcasts),
    LIBRARY("library", "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
}

private const val SEARCH_ROUTE = "search"

private val chromeTabs = Tab.entries.map { ChromeTab(it.label, it.icon, it.selectedIcon) }

@Composable
fun AppRoot(vm: PlayerViewModel) {
    val nav = rememberNavController()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val playbackError by vm.playbackError.collectAsState()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // The docked player folded into its strip, on screens wide enough for one.
    var paneCollapsed by rememberSaveable { mutableStateOf(false) }
    var playerCovers by remember { mutableStateOf(false) }
    val haze = rememberHazeState()
    val uiScope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    // Floating glass blurs a recording of the page (and bends it, with Liquid Glass on).
    val sampled = com.opentune.ui.components.backdropBlurOn()
    // Scrolling any page down folds the bottom bar into one row; scrolling up unfolds it.
    val chromeScroll = rememberChromeScroll()
    val chromeUi by com.opentune.data.settings.AppSettings.ui.collectAsState()
    // A page opened from inside the player (the artist, from a tap on their
    // name) folds the player away; back from that page opens the player
    // again, as it was left. Held: the page the player was over, and the page
    // it opened, by back stack entry.
    var playerReturn by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fromPlayer: (go: () -> Unit) -> Unit = remember(nav) {
        { go ->
            val wasOpen = playerOpen
            val below = nav.currentBackStackEntry?.id
            playerOpen = false
            go()
            val opened = nav.currentBackStackEntry?.id
            playerReturn = if (wasOpen && below != null && opened != null && opened != below) below to opened else null
        }
    }
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            val (below, opened) = playerReturn ?: return@collect
            if (opened in nav.stackIds()) return@collect
            playerReturn = null
            // Back to where the player was opened over: the player comes back too.
            if (entry.id == below && vm.currentSong.value != null) {
                if (!chromeUi.reduceAnimation) com.opentune.ui.components.PlayerMorph.openSheet()
                playerOpen = true
            }
        }
    }

    // A newer release on GitHub, offered with an Update button. Checked each
    // time the app comes to the front and hourly while it stays there (the
    // check itself goes online at most every few hours), since a music app
    // is rarely started afresh.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var update by remember { mutableStateOf<com.opentune.data.UpdateCheck.Release?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            com.opentune.data.Usage.active()
            com.opentune.ui.components.Support.usedToday(appContext)
            while (true) {
                if (com.opentune.data.settings.AppSettings.ui.value.checkForUpdates && update == null) {
                    update = com.opentune.data.UpdateCheck.checkIfDue(appContext)
                }
                kotlinx.coroutines.delay(60 * 60 * 1000L)
            }
        }
    }
    // After an update, once: what's changed.
    var whatsNew by remember { mutableStateOf<List<Pair<String, List<com.opentune.ui.components.WhatsNew.Note>>>>(emptyList()) }
    LaunchedEffect(Unit) { whatsNew = com.opentune.ui.components.WhatsNew.due(appContext, com.opentune.BuildConfig.VERSION_NAME) }
    if (whatsNew.isNotEmpty() && update == null) {
        com.opentune.ui.components.WhatsNewSheet(whatsNew, com.opentune.BuildConfig.VERSION_NAME, onDismiss = { whatsNew = emptyList() })
    }
    // Once a month, after some days of use, a community invitation,
    // sliding up above the dock; never over an update or What's new, and not
    // straight after opening.
    var askStar by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(45_000)
        askStar = com.opentune.ui.components.Support.shouldAsk(appContext)
    }
    val starCard: @Composable () -> Unit = {
        androidx.compose.animation.AnimatedVisibility(
            visible = askStar && !playerOpen && update == null && whatsNew.isEmpty(),
            enter = androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
        ) {
            com.opentune.ui.components.StarCard(onDismiss = { askStar = false })
        }
    }
    update?.let { r ->
        com.opentune.ui.settings.UpdateDialog(
            r,
            onDismiss = { update = null },
            onNotNow = { shown -> com.opentune.data.UpdateCheck.skip(appContext, shown.version); update = null },
        )
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(route) {
        Tab.entries.firstOrNull { it.route == route }?.let { tab = it }
        chromeScroll.expand()
    }
    LaunchedEffect(playbackError) { playbackError?.let { com.opentune.data.Pills.error(it) } }
    LaunchedEffect(Unit) { com.opentune.data.together.Together.notices.collect { com.opentune.data.Pills.info(it) } }
    LaunchedEffect(Unit) { com.opentune.cast.Cast.messages.collect { com.opentune.data.Pills.info(it) } }
    // A newer pill cuts the one showing short, so a run of swipes doesn't queue up notes.
    LaunchedEffect(Unit) { com.opentune.ui.components.QueueNotices.messages.collect { com.opentune.data.Pills.done(it) } }
    LaunchedEffect(Unit) { com.opentune.playback.SmartShuffle.notices.collect { com.opentune.data.Pills.info(it) } }

    // Kept across recompositions (opening the player, play/pause): a new
    // instance each time would recompose every screen and list handed one.
    val actions = remember(song?.videoId, isPlaying) {
        SongActions(
            currentVideoId = song?.videoId,
            isPlaying = isPlaying,
            playAll = { songs, index, shuffle, source -> vm.playAll(songs, index, shuffle, source) },
            playNext = vm::playNext,
            addToQueue = vm::addToQueue,
            smartShuffle = { songs, source -> vm.smartShuffle(songs, source) },
        )
    }
    val openItem: (ShelfItem) -> Unit = remember(nav, vm) { { item ->
        val browseId = item.browseId
        when {
            browseId != null -> nav.openBrowse(browseId)
            else -> item.toSong()?.let { vm.play(it, "Home") }
        }
    } }
    // A long press on an album or playlist card: its songs are fetched when an action needs them.
    val collectionMenu = remember(nav, vm) {
        fun withSongs(id: String, use: (List<com.opentune.data.model.Song>) -> Unit) {
            uiScope.launch {
                val songs = runCatching { MusicRepository.collection(id).songs }.getOrNull().orEmpty()
                if (songs.isEmpty()) com.opentune.data.Pills.error("Couldn't load its songs just now") else use(songs)
            }
        }
        com.opentune.ui.components.CollectionMenuActions(
            play = { id, title, shuffle -> withSongs(id) { vm.playAll(it, 0, shuffle, title) } },
            // Last first, so the list plays next in its own order.
            playNext = { id -> withSongs(id) { songs -> songs.asReversed().forEach(vm::playNext); com.opentune.data.Pills.done("${songs.size} songs play next") } },
            addToQueue = { id -> withSongs(id) { songs -> songs.forEach(vm::addToQueue); com.opentune.data.Pills.done("${songs.size} songs added to the queue") } },
            downloadAll = { id -> withSongs(id) { songs -> songs.forEach { com.opentune.data.download.Downloads.enqueue(appContext.applicationContext, it) }; com.opentune.data.Pills.done("Downloading ${songs.size} songs") } },
            open = { id, type -> fromPlayer { nav.openBrowse(id, type) } },
        )
    }
    // `song` is read when a menu action runs, so the menu doesn't need rebuilding as songs change.
    val songMenu = remember(nav, vm) { SongMenuActions(
        playNext = vm::playNext,
        addToQueue = vm::addToQueue,
        startRadio = vm::startRadio,
        openAlbum = { id -> fromPlayer { nav.openBrowse(id, BrowseType.ALBUM) } },
        openArtist = { id -> fromPlayer { nav.openBrowse(id, BrowseType.ARTIST) } },
        dislike = { s ->
            // The player skips it, and its later copies in the queue, itself.
            LibraryStore.dislike(s)
            com.opentune.data.Pills.done("You won't hear \"${s.title}\" in autoplay again")
        },
        notInterested = { s ->
            LibraryStore.hideSong(s)
            vm.player.dropHiddenSmartShuffleSongs()
            com.opentune.data.Pills.withAction("\"${s.title}\" won't be suggested", "Undo") { LibraryStore.unhideSong(s.videoId) }
        },
        dontPlayArtist = dontPlay@{ s ->
            val artist = LibraryStore.hideArtist(s) ?: return@dontPlay
            vm.player.dropHiddenSmartShuffleSongs()
            com.opentune.data.Pills.withAction("Songs by ${artist.name} won't be suggested", "Undo") { LibraryStore.unhideArtist(artist.key) }
        },
        keepInPlaylist = { s, id ->
            LibraryStore.addToPlaylist(id, s)
            com.opentune.playback.SmartShuffle.keep(s.videoId)
            LibraryStore.playlist(id)?.let { p -> com.opentune.data.Pills.done("Added to ${p.name}") }
        },
        progressOf = { s -> if (s.videoId == vm.currentSong.value?.videoId) vm.positionMs() to vm.durationMs.value else null },
        viewArtist = { s ->
            uiScope.launch {
                val id = MusicRepository.artistIdFor(s)
                if (id == null) {
                    com.opentune.data.Pills.error("Couldn't find ${s.artist}'s page")
                } else {
                    fromPlayer { nav.openBrowse(id, BrowseType.ARTIST) }
                }
            }
        },
        viewArtistNamed = { s, name ->
            uiScope.launch {
                val id = MusicRepository.artistIdFor(s, name)
                if (id == null) {
                    com.opentune.data.Pills.error("Couldn't find $name's page")
                } else {
                    fromPlayer { nav.openBrowse(id, BrowseType.ARTIST) }
                }
            }
        },
        playVideoVersion = { s ->
            uiScope.launch {
                val video = runCatching { MusicRepository.search("${s.title} ${s.artist}", SearchFilter.VIDEOS) }.getOrNull()
                    ?.firstNotNullOfOrNull { (it as? SearchResult.Track)?.song?.takeIf { v -> v.videoId != s.videoId } }
                when {
                    video == null -> com.opentune.data.Pills.error("Couldn't find a music video for this song")
                    s.videoId == song?.videoId -> {
                        vm.playNext(video)
                        vm.skipNext()
                    }
                    else -> vm.play(video, "Video version")
                }
            }
        },
    ) }
    val libraryNav = remember(nav) { LibraryNav(
        downloads = { nav.navigate("downloads") },
        downloaded = { id -> nav.navigate("downloaded/${Uri.encode(id)}") },
        local = { nav.navigate("local") },
        replay = { nav.navigate("replay") },
        settings = { nav.navigate("settings") },
        liked = { nav.navigate("liked") },
        playlist = { id -> nav.navigate("playlist/${Uri.encode(id)}") },
        browse = { id -> nav.openBrowse(id) },
        server = { nav.navigate("server") },
        radio = { nav.navigate("radio") },
        together = { nav.navigate("together") },
        importPlaylist = { nav.navigate("import") },
        wrapped = { nav.navigate("wrapped") },
    ) }
    val serverNav = remember(nav) {
        ServerNav(
            album = { id -> nav.navigate("server/album/${Uri.encode(id)}") },
            artist = { id -> nav.navigate("server/artist/${Uri.encode(id)}") },
            playlist = { id -> nav.navigate("server/playlist/${Uri.encode(id)}") },
            artists = { nav.navigate("server/artists") },
        )
    }
    // Links opened from outside: a song plays with its radio, anything else opens its page.
    val incoming by Links.incoming.collectAsState()
    LaunchedEffect(incoming) {
        when (val link = incoming) {
            is LinkTarget.Song -> {
                vm.player.connected.first { it }
                val queue = runCatching { MusicRepository.watchQueue(link.videoId) }.getOrDefault(emptyList())
                val at = queue.indexOfFirst { it.videoId == link.videoId }
                // A radio, though it arrives as a list: smart shuffle doesn't apply.
                if (at >= 0) vm.playAll(queue, at, false, "Shared link", fromList = false)
                else vm.play(com.opentune.data.model.Song(link.videoId, "Shared song", "", null), "Shared link")
                playerOpen = true
            }
            is LinkTarget.Browse -> nav.openBrowse(link.browseId)
            is LinkTarget.SpotifyImport -> nav.navigate("import?link=${Uri.encode(link.text)}")
            is LinkTarget.Together -> { playerOpen = false; nav.navigate("together?code=${link.code}") }
            is LinkTarget.Recognize -> { playerOpen = false; nav.navigate("recognize?listen=true") { launchSingleTop = true } }
            is LinkTarget.Player -> playerOpen = true
            is LinkTarget.PlaySearch -> {
                vm.player.connected.first { it }
                if (link.query.isBlank()) {
                    val recent = com.opentune.data.history.History.recents(com.opentune.data.history.History.records.value, 50)
                    if (recent.isNotEmpty()) vm.playAll(recent, 0, true, "Recently played")
                } else {
                    // YouTube's best match leads the mixed search; autoplay follows it with radio.
                    val match = runCatching { MusicRepository.search(link.query, SearchFilter.ALL) }.getOrDefault(emptyList())
                        .firstNotNullOfOrNull { (it as? SearchResult.TopTrack)?.song ?: (it as? SearchResult.Track)?.song }
                    if (match != null) vm.play(match, link.query) else com.opentune.data.Pills.error("Nothing found for \"${link.query}\"")
                }
            }
            null -> return@LaunchedEffect
        }
        Links.consumed()
    }
    // The Wrapped story fills the screen, like the login page.
    val chromeRoute = route != "login" && route != "wrapped"
    val chromeVisible = chromeRoute && !playerCovers
    // The window's size class picks the chrome. A fold or unfold only changes
    // what's around the pages: the NavHost and the player keep their place in
    // the tree, so neither starts over (the activity handles the size change
    // itself rather than being recreated; see the manifest).
    val window = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    val layout = appLayout(window.width.value.toInt(), window.height.value.toInt(), song != null, paneCollapsed, chromeUi.wideLayout, chromeUi.sidePlayer)
    // Docked beside the page, the player is never also opened over it.
    val sheetOpen = playerOpen && !layout.sidePlayer
    val currentLayout by androidx.compose.runtime.rememberUpdatedState(layout)
    LaunchedEffect(playerOpen) {
        // Asked for the player (a shared song, say) while it's docked: unfold the pane.
        if (playerOpen && currentLayout.sidePlayer) {
            paneCollapsed = false
            playerOpen = false
        }
    }
    // Unfolding with the full player open: the pane shows it. Folding back
    // brings the full player back, as it was left.
    LaunchedEffect(layout.sidePlayer) { if (layout.sidePlayer && playerOpen) paneCollapsed = false }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val content = if (layout.nav == NavChrome.DOCK) {
        PaddingValues(bottom = navInset + CHROME_TAB_HEIGHT + 24.dp + if (song != null) CHROME_MINI_HEIGHT + 8.dp else 0.dp)
    } else {
        PaddingValues(bottom = navInset + 16.dp + if (layout.nowPlaying == NowPlaying.BAR) CHROME_MINI_HEIGHT + 12.dp else 0.dp)
    }
    // Room the rail and the docked player take beside the pages, eased as
    // the pane folds and unfolds.
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val startGutter = if (layout.nav == NavChrome.RAIL && chromeRoute) RAIL_GUTTER + safe.calculateStartPadding(direction) else 0.dp
    val endGutterTarget = when {
        !chromeRoute -> 0.dp
        layout.nowPlaying == NowPlaying.PANE -> PANE_GUTTER + safe.calculateEndPadding(direction)
        layout.nowPlaying == NowPlaying.STRIP -> STRIP_GUTTER + safe.calculateEndPadding(direction)
        else -> 0.dp
    }
    val endGutter by androidx.compose.animation.core.animateDpAsState(
        endGutterTarget,
        if (chromeUi.reduceAnimation) androidx.compose.animation.core.snap() else tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "endGutter",
    )
    // Pages in a readable column on wide windows, centred in the room left;
    // the ones that fill the screen still do.
    val pageArea = Modifier.padding(start = startGutter, end = endGutter)
        .then(layout.contentMaxWidthDp?.takeIf { chromeRoute }?.let { Modifier.widthIn(max = it.dp) } ?: Modifier)
    // The pages each tab had open above its root when it was left, as routes,
    // so coming back to the tab brings them back (a playlist opened from Home
    // is still there after a look at the Library).
    val tabPages = remember { mutableMapOf<Tab, List<String>>() }
    // The whole back stack is only public as this restricted, long-stable flow.
    @android.annotation.SuppressLint("RestrictedApi")
    fun pagesAbove(root: Tab): List<String> {
        val stack = nav.currentBackStack.value.filter { it.destination.route != null }
        val at = stack.indexOfLast { it.destination.route == root.route }
        if (at < 0) return emptyList()
        return stack.drop(at + 1).mapNotNull { e ->
            val template = e.destination.route ?: return@mapNotNull null
            template.replace(Regex("""\{([^}]+)\}""")) { m -> Uri.encode(e.arguments?.get(m.groupValues[1])?.toString().orEmpty()) }
        }
    }
    val selectTab: (Int) -> Unit = { i ->
        // A tab chosen is somewhere new, not a way back to the player.
        playerReturn = null
        val t = Tab.entries[i]
        when {
            // On the tab's root already: Home scrolls to the top and refreshes.
            t == tab && route == t.route -> TabEvents.reselect(t.route)
            // A sub-page or Search opened from this tab: back to its root.
            t == tab -> { tabPages.remove(t); nav.popBackStack(t.route, inclusive = false) }
            else -> {
                tabPages[tab] = pagesAbove(tab)
                tab = t
                if (t == Tab.HOME) nav.popBackStack(Tab.HOME.route, inclusive = false)
                else nav.navigate(t.route) {
                    popUpTo(Tab.HOME.route)
                    launchSingleTop = true
                }
                // And the pages that were open in it, as they were left.
                tabPages.remove(t).orEmpty().forEach { r -> runCatching { nav.navigate(r) } }
            }
        }
    }
    val openSearch = { if (route != SEARCH_ROUTE) nav.navigate(SEARCH_ROUTE) { launchSingleTop = true } }

    // The opening animation, once per launch: the app comes forward out of
    // the mark as the screen dives into it.
    var opening by remember { mutableStateOf(!com.opentune.ui.components.Opening.played && chromeUi.openingAnimation && !chromeUi.reduceAnimation) }
    var dive by remember { androidx.compose.runtime.mutableFloatStateOf(if (opening) 0f else 1f) }
    LaunchedEffect(Unit) { com.opentune.ui.components.Opening.played = true }

    // Back on the first page while music plays: the app shrinks up into a pill at the
    // top, towards the notification's player, and goes to the background. Coming
    // back finds it full size again.
    val leave = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { leave.snapTo(0f) } }
    val atRoot = entry != null && nav.previousBackStackEntry == null
    BackHandler(enabled = isPlaying && atRoot && !sheetOpen && !chromeUi.reduceAnimation && leave.targetValue == 0f) {
        val activity = appContext as? android.app.Activity
        uiScope.launch {
            // Long enough to read as one gesture, on a curve that starts briskly and
            // glides into place, the way iOS sends an app home.
            leave.animateTo(1f, androidx.compose.animation.core.tween(560, easing = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f)))
            if (activity == null || !activity.moveTaskToBack(true)) { leave.snapTo(0f); return@launch }
            // The shrink was the closing; skip the system's own on top of it where it allows.
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        }
    }

    CompositionLocalProvider(
        LocalHazeState provides haze,
        LocalSongMenu provides songMenu,
        com.opentune.ui.components.LocalCollectionMenu provides collectionMenu,
        LocalChromeScroll provides chromeScroll,
        // iOS-style rubber band at the ends of every list.
        LocalOverscrollFactory provides RubberBandOverscrollFactory,
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                if (dive < 1f) {
                    val s = 1.14f - 0.14f * (1f - (1f - dive) * (1f - dive))
                    scaleX = s
                    scaleY = s
                }
                val p = leave.value
                if (p > 0f) {
                    // Shrinking toward the top centre, cropped down to a pill as it goes.
                    // The crop runs a little ahead of the shrink, so the rounded pill
                    // shape is there early and the rest is it settling into place.
                    val s = 1f - 0.68f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    translationY = 30.dp.toPx() * p
                    val crop = 1f - (1f - p) * (1f - p)
                    shape = com.opentune.ui.components.pillCrop(crop, 375.dp.toPx(), 100.dp.toPx())
                    clip = true
                    // Lifted off the screen as it goes, and softening into the pill.
                    shadowElevation = 28.dp.toPx() * p
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && p > 0.35f) {
                        val b = 10.dp.toPx() * ((p - 0.35f) / 0.65f)
                        renderEffect = androidx.compose.ui.graphics.BlurEffect(b, b)
                    }
                    alpha = if (p < 0.8f) 1f else 1f - (p - 0.8f) / 0.2f
                }
            },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (sampled) Modifier.layerBackdrop(backdrop) else Modifier)
                    // Painted inside the recorded layer: a see-through backdrop would let
                    // the sharp page show through the glass instead of the blurred copy.
                    .background(MaterialTheme.colorScheme.background)
                    .hazeSource(haze)
                    .nestedScroll(chromeScroll),
            ) {
                // Glass inside the pages is part of the layer the chrome blurs, so it
                // can't blur that layer itself: it's filled, unless a page records
                // its own content for an overlay (GlassPage).
                CompositionLocalProvider(LocalHazeState provides null) {
                NavHost(
                    navController = nav,
                    startDestination = Tab.HOME.route,
                    modifier = Modifier.align(Alignment.TopCenter).then(pageArea).fillMaxSize(),
                    enterTransition = { PageMotion.enter(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    exitTransition = { PageMotion.exit(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    popEnterTransition = { PageMotion.popEnter(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    popExitTransition = { PageMotion.popExit(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                ) {
                    composable(Tab.HOME.route) {
                        HomeScreen(
                            contentPadding = content,
                            actions = actions,
                            onItemClick = openItem,
                            onOpenSettings = { nav.navigate("settings") },
                            onRecognize = { nav.navigate("recognize") },
                            covered = playerCovers,
                        )
                    }
                    composable(Tab.EXPLORE.route) {
                        ExploreScreen(content, onMoodClick = { mood ->
                            nav.navigate("mood/${Uri.encode(mood.browseId)}?params=${Uri.encode(mood.params.orEmpty())}&title=${Uri.encode(mood.title)}")
                        }, onItemClick = openItem)
                    }
                    composable(Tab.PODCASTS.route) {
                        com.opentune.ui.podcasts.PodcastsScreen(content, actions, onOpenShow = { id -> nav.navigate("podcast/${Uri.encode(id)}") })
                    }
                    composable("podcast/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        com.opentune.ui.podcasts.PodcastShowScreen(
                            e.arguments?.getString("id").orEmpty(),
                            content,
                            actions,
                            onBack = { nav.popBackStack() },
                            onOpenShow = { id -> nav.navigate("podcast/${Uri.encode(id)}") },
                        )
                    }
                    composable(Tab.LIBRARY.route) {
                        LibraryScreen(contentPadding = content, actions = actions, nav = libraryNav)
                    }
                    composable(SEARCH_ROUTE) {
                        SearchScreen(
                            contentPadding = content,
                            currentVideoId = song?.videoId,
                            isPlaying = isPlaying,
                            onPlay = { vm.play(it, "Search") },
                            onPlayNext = vm::playNext,
                            onAddToQueue = vm::addToQueue,
                            onBrowse = { item: BrowseItem -> nav.openBrowse(item.browseId, item.type) },
                            onRecognize = { nav.navigate("recognize") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            onOpenEqualizer = { nav.navigate("equalizer") },
                            onOpenReplay = { nav.navigate("replay") },
                            onOpenWrapped = { nav.navigate("wrapped") },
                            onSignIn = { nav.navigate("login") },
                            onOpenDownloads = { nav.navigate("downloads") },
                            onOpenSpotify = { nav.navigate("import") },
                            onOpenDevices = { nav.navigate("devices") },
                        )
                    }
                    composable("devices") {
                        com.opentune.ui.settings.LinkDeviceScreen(
                            content,
                            onBack = { nav.popBackStack() },
                            onSignIn = { nav.navigate("login") },
                            onTogether = { nav.navigate("together") },
                        )
                    }
                    composable("login") { LoginScreen(onDone = { nav.popBackStack() }) }
                    composable("downloads") { DownloadsScreen(content, actions, onBack = { nav.popBackStack() }) }
                    composable("downloaded/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        com.opentune.ui.library.DownloadedListScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    composable("liked") {
                        LikedScreen(content, actions, onBack = { nav.popBackStack() }, onOpenYouTubeLikes = { nav.openBrowse("VLLM", BrowseType.PLAYLIST) })
                    }
                    composable("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        LocalPlaylistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    composable("equalizer") { EqualizerScreen(content, onBack = { nav.popBackStack() }) }
                    composable("local") { LocalMusicScreen(content, actions, onBack = { nav.popBackStack() }) }
                    composable("replay") { ReplayScreen(content, actions, onBack = { nav.popBackStack() }, onOpenWrapped = { nav.navigate("wrapped") }) }
                    composable("wrapped") { WrappedScreen(actions, onBack = { nav.popBackStack() }, player = vm.player) }
                    composable("recognize?listen={listen}", listOf(navArgument("listen") { type = NavType.BoolType; defaultValue = false })) { e ->
                        com.opentune.ui.recognize.RecognizeScreen(
                            content, onPlay = { vm.play(it, "Found by listening") }, onBack = { nav.popBackStack() },
                            listenAtOnce = e.arguments?.getBoolean("listen") == true,
                        )
                    }
                    composable(
                        "import?link={link}",
                        listOf(navArgument("link") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { e ->
                        com.opentune.ui.spotify.ImportScreen(
                            content,
                            actions,
                            initialLink = e.arguments?.getString("link"),
                            onBack = { nav.popBackStack() },
                            onOpenPlaylist = { id -> nav.navigate("playlist/${Uri.encode(id)}") },
                            onOpenExportify = { appContext.startActivity(android.content.Intent(appContext, com.opentune.ui.spotify.ExportifyActivity::class.java)) },
                        )
                    }
                    composable("radio") { com.opentune.ui.radio.RadioScreen(content, actions, onBack = { nav.popBackStack() }) }
                    composable(
                        "together?code={code}",
                        arguments = listOf(androidx.navigation.navArgument("code") { nullable = true; defaultValue = null }),
                    ) { entry ->
                        com.opentune.ui.together.TogetherScreen(content, entry.arguments?.getString("code"), onBack = { nav.popBackStack() })
                    }
                    composable("server") { ServerScreen(content, actions, serverNav, onBack = { nav.popBackStack() }) }
                    composable("server/artists") { ServerArtistsScreen(content, serverNav, onBack = { nav.popBackStack() }) }
                    composable("server/album/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        ServerAlbumScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() }, onArtist = serverNav.artist)
                    }
                    composable("server/artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        ServerArtistScreen(e.arguments?.getString("id").orEmpty(), content, serverNav, onBack = { nav.popBackStack() })
                    }
                    composable("server/playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        ServerPlaylistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    composable("collection/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        CollectionScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    composable("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        ArtistScreen(
                            e.arguments?.getString("id").orEmpty(), content, actions,
                            onBack = { nav.popBackStack() },
                            onItemClick = openItem,
                            onShowAll = { shelf ->
                                shelf.moreBrowseId?.let { id ->
                                    nav.navigate("artist-items/${Uri.encode(id)}?params=${Uri.encode(shelf.moreParams.orEmpty())}&title=${Uri.encode(shelf.title)}")
                                }
                            },
                        )
                    }
                    composable(
                        "artist-items/{id}?params={params}&title={title}",
                        listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("params") { type = NavType.StringType; defaultValue = "" },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { e ->
                        val args = e.arguments
                        com.opentune.ui.browse.ArtistItemsScreen(
                            title = args?.getString("title").orEmpty(),
                            browseId = args?.getString("id").orEmpty(),
                            params = args?.getString("params")?.takeIf { it.isNotEmpty() },
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            onItemClick = openItem,
                        )
                    }
                    composable(
                        "mood/{id}?params={params}&title={title}",
                        listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("params") { type = NavType.StringType; defaultValue = "" },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { e ->
                        val args = e.arguments
                        MoodScreen(
                            title = args?.getString("title").orEmpty(),
                            browseId = args?.getString("id").orEmpty(),
                            params = args?.getString("params")?.takeIf { it.isNotEmpty() },
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            onItemClick = openItem,
                        )
                    }
                }
                }
            }

            // Pages blur and fade as they pass under the status bar.
            if (route != "login") com.opentune.ui.components.TopEdgeFrost(Modifier.align(Alignment.TopCenter))

            // Floating chrome: the mini player above the nav pill and search button.
            // It sits outside the recorded layer, so its Liquid Glass can sample it.
            if (chromeVisible) CompositionLocalProvider(LocalBackdrop provides backdrop.takeIf { sampled }) {
            val openPlayer: (Boolean) -> Unit = { _ ->
                // The whole player grows out of the mini player, from wherever a pull left it.
                if (!chromeUi.reduceAnimation) com.opentune.ui.components.PlayerMorph.openSheet()
                playerOpen = true
            }
            if (layout.nav == NavChrome.DOCK) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                starCard()
                BottomChrome(
                    inline = chromeScroll.inline,
                    tabs = chromeTabs,
                    selected = if (route == SEARCH_ROUTE) null else tab.ordinal,
                    onSelect = selectTab,
                    onExpand = chromeScroll::expand,
                    searchSelected = route == SEARCH_ROUTE,
                    onSearch = openSearch,
                    mini = if (song == null) null else { folded, m ->
                        MiniPlayerBar(vm, onExpand = { openPlayer(folded) }, modifier = m, inline = folded)
                    },
                    motion = chromeUi.dockMotion,
                    lens = chromeUi.dockLens,
                    reduceMotion = chromeUi.reduceAnimation,
                )
            }
            } else {
                // Wide windows: the tabs in a rail down the start edge, and the mini
                // player along the bottom of the page unless the player is docked.
                NavRail(
                    tabs = chromeTabs,
                    selected = if (route == SEARCH_ROUTE) null else tab.ordinal,
                    onSelect = selectTab,
                    searchSelected = route == SEARCH_ROUTE,
                    onSearch = openSearch,
                    modifier = Modifier.align(Alignment.CenterStart),
                    lens = chromeUi.dockLens,
                    reduceMotion = chromeUi.reduceAnimation,
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).then(pageArea).fillMaxWidth()
                        .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    starCard()
                    if (layout.nowPlaying == NowPlaying.BAR) {
                        MiniPlayerBar(vm, onExpand = { openPlayer(false) }, modifier = Modifier.fillMaxWidth().height(CHROME_MINI_HEIGHT))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = layout.nowPlaying == NowPlaying.STRIP,
                enter = fadeIn(tween(if (chromeUi.reduceAnimation) 120 else 260, delayMillis = if (chromeUi.reduceAnimation) 0 else 160)),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                NowPlayingStrip(vm, onExpand = { paneCollapsed = false })
            }
            }

            // Expanded windows: the player itself, docked on the end edge while a
            // song is loaded. It folds into the strip above, and back.
            androidx.compose.animation.AnimatedVisibility(
                visible = layout.nowPlaying == NowPlaying.PANE && chromeRoute,
                enter = if (chromeUi.reduceAnimation) fadeIn(tween(160)) else slideInHorizontally(tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it } + fadeIn(tween(220)),
                exit = if (chromeUi.reduceAnimation) fadeOut(tween(120)) else slideOutHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { it } + fadeOut(tween(260)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                PlayerPaneFrame {
                    PlayerScreen(
                        vm,
                        onCollapse = { paneCollapsed = true },
                        onTogether = { nav.navigate("together") },
                        onEqualizer = { nav.navigate("equalizer") },
                        docked = true,
                    )
                }
            }

            val sheetMorph = !chromeUi.reduceAnimation
            androidx.compose.runtime.SideEffect { com.opentune.ui.components.PlayerMorph.sheetMorph = sheetMorph }
            // The player itself, whichever way it comes and goes.
            val playerSheet: @Composable (settled: Boolean) -> Unit = { settled ->
                // Registered after the NavHost's own handler, so back closes the
                // player first instead of popping the page behind it.
                BackHandler(enabled = sheetOpen) { playerOpen = false }
                // Once the player is fully over the page, the chrome under it is
                // taken out: no second position loop or glass redrawn behind it.
                var still by remember { mutableStateOf(true) }
                LaunchedEffect(settled, still) { playerCovers = settled && still }
                DisposableEffect(Unit) { onDispose { playerCovers = false } }
                PlayerScreen(
                    vm,
                    onCollapse = { playerOpen = false },
                    onCovering = { still = it },
                    onTogether = { fromPlayer { nav.navigate("together") } },
                    onEqualizer = { fromPlayer { nav.navigate("equalizer") } },
                )
            }
            if (sheetMorph) {
                // Out of the mini player as one card, and back into it.
                var sheetSettled by remember { mutableStateOf(false) }
                com.opentune.ui.components.PlayerSheet(open = sheetOpen && song != null, onSettled = { sheetSettled = it }) {
                    playerSheet(sheetSettled)
                }
            } else {
                AnimatedVisibility(
                    visible = sheetOpen && song != null,
                    enter = PageMotion.playerIn(chromeUi.playerMotion, chromeUi.reduceAnimation),
                    exit = PageMotion.playerOut(chromeUi.playerMotion, chromeUi.reduceAnimation),
                ) {
                    playerSheet(transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible)
                }
            }
            // Notes about what was just done float in over everything, the player included.
            com.opentune.ui.components.PillHost(chromeUi.reduceAnimation, Modifier.align(Alignment.TopCenter))
        }
        if (opening) {
            com.opentune.ui.components.OpeningReveal(onDive = { dive = it }, onDone = { opening = false; dive = 1f })
        }
    }
}

/** The ids of the entries on the back stack, top last. The whole stack is only public as this restricted, long-stable flow. */
@android.annotation.SuppressLint("RestrictedApi")
private fun NavHostController.stackIds(): List<String> = currentBackStack.value.map { it.id }

private fun NavHostController.openBrowse(browseId: String, type: BrowseType = MusicRepository.typeOf(browseId)) {
    val route = when {
        // A podcast show has its own page.
        browseId.startsWith("MPSP") -> "podcast/{id}"
        type == BrowseType.ARTIST -> "artist/{id}"
        else -> "collection/{id}"
    }
    // Already on that page (a second tap while the first was still finding it):
    // stay, so back doesn't have to go through the same page twice.
    val here = currentBackStackEntry
    if (here?.destination?.route == route && here.arguments?.getString("id") == browseId) return
    navigate(route.replace("{id}", Uri.encode(browseId)))
}
