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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.opentune.ui.components.liquidGlassOn
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
import com.opentune.ui.player.MiniPlayerBar
import com.opentune.ui.player.PlayerScreen
import com.opentune.ui.search.SearchScreen
import com.opentune.ui.settings.EqualizerScreen
import com.opentune.ui.settings.SettingsScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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
    var playerCovers by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    val uiScope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val liquid = liquidGlassOn()
    // Scrolling any page down folds the bottom bar into one row; scrolling up unfolds it.
    val chromeScroll = rememberChromeScroll()
    val chromeUi by com.opentune.data.settings.AppSettings.ui.collectAsState()

    // Once a day at start: a newer release on GitHub, offered with an Update button.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    var update by remember { mutableStateOf<com.opentune.data.UpdateCheck.Release?>(null) }
    LaunchedEffect(Unit) {
        if (com.opentune.data.settings.AppSettings.ui.value.checkForUpdates) {
            update = com.opentune.data.UpdateCheck.checkIfDue(appContext)
        }
    }
    update?.let { r ->
        com.opentune.ui.settings.UpdateDialog(
            r,
            onDismiss = { update = null },
            onNotNow = { com.opentune.data.UpdateCheck.skip(appContext, r.version); update = null },
        )
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(route) {
        Tab.entries.firstOrNull { it.route == route }?.let { tab = it }
        chromeScroll.expand()
    }
    LaunchedEffect(playbackError) { playbackError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { com.opentune.data.together.Together.notices.collect { snackbar.showSnackbar(it) } }

    // Kept across recompositions (opening the player, play/pause): a new
    // instance each time would recompose every screen and list handed one.
    val actions = remember(song?.videoId, isPlaying) {
        SongActions(
            currentVideoId = song?.videoId,
            isPlaying = isPlaying,
            playAll = { songs, index, shuffle, source -> vm.playAll(songs, index, shuffle, source) },
            playNext = vm::playNext,
            addToQueue = vm::addToQueue,
        )
    }
    val openItem: (ShelfItem) -> Unit = remember(nav, vm) { { item ->
        val browseId = item.browseId
        when {
            browseId != null -> nav.openBrowse(browseId)
            else -> item.toSong()?.let { vm.play(it, "Home") }
        }
    } }
    // `song` is read when a menu action runs, so the menu doesn't need rebuilding as songs change.
    val songMenu = remember(nav, vm) { SongMenuActions(
        playNext = vm::playNext,
        addToQueue = vm::addToQueue,
        startRadio = vm::startRadio,
        openAlbum = { id -> playerOpen = false; nav.openBrowse(id, BrowseType.ALBUM) },
        openArtist = { id -> playerOpen = false; nav.openBrowse(id, BrowseType.ARTIST) },
        dislike = { s ->
            LibraryStore.dislike(s)
            if (s.videoId == song?.videoId) vm.skipNext()
            uiScope.launch { snackbar.showSnackbar("You won't hear \"${s.title}\" in autoplay again") }
        },
        viewArtist = { s ->
            uiScope.launch {
                val id = MusicRepository.artistIdFor(s)
                if (id == null) {
                    snackbar.showSnackbar("Couldn't find ${s.artist}'s page")
                } else {
                    playerOpen = false
                    nav.openBrowse(id, BrowseType.ARTIST)
                }
            }
        },
        playVideoVersion = { s ->
            uiScope.launch {
                val video = runCatching { MusicRepository.search("${s.title} ${s.artist}", SearchFilter.VIDEOS) }.getOrNull()
                    ?.firstNotNullOfOrNull { (it as? SearchResult.Track)?.song?.takeIf { v -> v.videoId != s.videoId } }
                when {
                    video == null -> snackbar.showSnackbar("Couldn't find a music video for this song")
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
                if (at >= 0) vm.playAll(queue, at, false, "Shared link")
                else vm.play(com.opentune.data.model.Song(link.videoId, "Shared song", "", null), "Shared link")
                playerOpen = true
            }
            is LinkTarget.Browse -> nav.openBrowse(link.browseId)
            is LinkTarget.SpotifyImport -> nav.navigate("import?link=${Uri.encode(link.text)}")
            is LinkTarget.Together -> { playerOpen = false; nav.navigate("together?code=${link.code}") }
            is LinkTarget.PlaySearch -> {
                vm.player.connected.first { it }
                if (link.query.isBlank()) {
                    val recent = com.opentune.data.history.History.recents(com.opentune.data.history.History.records.value, 50)
                    if (recent.isNotEmpty()) vm.playAll(recent, 0, true, "Recently played")
                } else {
                    // YouTube's best match leads the mixed search; autoplay follows it with radio.
                    val match = runCatching { MusicRepository.search(link.query, SearchFilter.ALL) }.getOrDefault(emptyList())
                        .firstNotNullOfOrNull { (it as? SearchResult.TopTrack)?.song ?: (it as? SearchResult.Track)?.song }
                    if (match != null) vm.play(match, link.query) else snackbar.showSnackbar("Nothing found for \"${link.query}\"")
                }
            }
            null -> return@LaunchedEffect
        }
        Links.consumed()
    }
    val chromeVisible = route != "login" && !playerCovers
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val content = PaddingValues(bottom = navInset + CHROME_TAB_HEIGHT + 24.dp + if (song != null) CHROME_MINI_HEIGHT + 8.dp else 0.dp)

    CompositionLocalProvider(
        LocalHazeState provides haze,
        LocalSongMenu provides songMenu,
        // iOS-style rubber band at the ends of every list.
        LocalOverscrollFactory provides RubberBandOverscrollFactory,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (liquid) Modifier.layerBackdrop(backdrop) else Modifier)
                    // Painted inside the recorded layer: a see-through backdrop would let
                    // the sharp page show through the glass instead of the blurred copy.
                    .background(MaterialTheme.colorScheme.background)
                    .hazeSource(haze)
                    .nestedScroll(chromeScroll),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Tab.HOME.route,
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
                            onOpenSearch = { nav.navigate(SEARCH_ROUTE) { launchSingleTop = true } },
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
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            onOpenEqualizer = { nav.navigate("equalizer") },
                            onOpenReplay = { nav.navigate("replay") },
                            onSignIn = { nav.navigate("login") },
                            onOpenDownloads = { nav.navigate("downloads") },
                            onOpenSpotify = { nav.navigate("import") },
                        )
                    }
                    composable("login") { LoginScreen(onDone = { nav.popBackStack() }) }
                    composable("downloads") { DownloadsScreen(content, actions, onBack = { nav.popBackStack() }) }
                    composable("liked") {
                        LikedScreen(content, actions, onBack = { nav.popBackStack() }, onOpenYouTubeLikes = { nav.openBrowse("VLLM", BrowseType.PLAYLIST) })
                    }
                    composable("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        LocalPlaylistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                    }
                    composable("equalizer") { EqualizerScreen(content, onBack = { nav.popBackStack() }) }
                    composable("local") { LocalMusicScreen(content, actions, onBack = { nav.popBackStack() }) }
                    composable("replay") { ReplayScreen(content, actions, onBack = { nav.popBackStack() }) }
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
                        ArtistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() }, onItemClick = openItem)
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

            // Pages blur and fade as they pass under the status bar.
            if (route != "login") com.opentune.ui.components.TopEdgeFrost(Modifier.align(Alignment.TopCenter))

            // Floating chrome: the mini player above the nav pill and search button.
            // It sits outside the recorded layer, so its Liquid Glass can sample it.
            if (chromeVisible) CompositionLocalProvider(LocalBackdrop provides backdrop.takeIf { liquid }) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                SnackbarHost(snackbar)
                BottomChrome(
                    inline = chromeScroll.inline,
                    tabs = chromeTabs,
                    selected = if (route == SEARCH_ROUTE) null else tab.ordinal,
                    onSelect = { i ->
                        val t = Tab.entries[i]
                        when {
                            // Already in this tab, on its root, a sub-page or Search
                            // opened from it: back to the tab's root.
                            t == tab -> nav.popBackStack(t.route, inclusive = false)
                            // Home is the start, so it's reached by popping. Navigating to
                            // it with restoreState would bring back whatever was popped:
                            // a non-inclusive pop files that state under Home's id too.
                            t == Tab.HOME -> {
                                tab = t
                                nav.popBackStack(Tab.HOME.route, inclusive = false, saveState = true)
                            }
                            else -> {
                                tab = t
                                nav.navigate(t.route) {
                                    popUpTo(Tab.HOME.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    },
                    onExpand = chromeScroll::expand,
                    searchSelected = route == SEARCH_ROUTE,
                    onSearch = { if (route != SEARCH_ROUTE) nav.navigate(SEARCH_ROUTE) { launchSingleTop = true } },
                    mini = if (song == null) null else { folded, m -> MiniPlayerBar(vm, onExpand = { playerOpen = true }, modifier = m, inline = folded) },
                    motion = chromeUi.dockMotion,
                    lens = chromeUi.dockLens,
                    reduceMotion = chromeUi.reduceAnimation,
                )
            }
            }

            AnimatedVisibility(
                visible = playerOpen && song != null,
                enter = PageMotion.playerIn(chromeUi.playerMotion, chromeUi.reduceAnimation),
                exit = PageMotion.playerOut(chromeUi.playerMotion, chromeUi.reduceAnimation),
            ) {
                // Registered after the NavHost's own handler, so back closes the
                // player first instead of popping the page behind it.
                BackHandler(enabled = playerOpen) { playerOpen = false }
                // Once the player has slid fully over the page, the chrome under it is
                // taken out: no second position loop or glass redrawn behind it.
                val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
                var still by remember { mutableStateOf(true) }
                LaunchedEffect(settled, still) { playerCovers = settled && still }
                DisposableEffect(Unit) { onDispose { playerCovers = false } }
                PlayerScreen(
                    vm,
                    onCollapse = { playerOpen = false },
                    onCovering = { still = it },
                    onTogether = { playerOpen = false; nav.navigate("together") },
                )
            }
        }
    }
}

private fun NavHostController.openBrowse(browseId: String, type: BrowseType = MusicRepository.typeOf(browseId)) {
    val id = Uri.encode(browseId)
    // A podcast show has its own page.
    if (browseId.startsWith("MPSP")) return navigate("podcast/$id")
    when (type) {
        BrowseType.ARTIST -> navigate("artist/$id")
        else -> navigate("collection/$id")
    }
}
