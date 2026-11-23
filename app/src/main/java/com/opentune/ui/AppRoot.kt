package com.opentune.ui


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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** A tab tapped again while its root page is showing, for the page to scroll back to its top and refresh. */
object TabEvents {
    private val _reselected = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val reselected: kotlinx.coroutines.flow.SharedFlow<String> = _reselected
    fun reselect(route: String) { _reselected.tryEmit(route) }
}

private enum class Tab(val route: String, val label: String, val iconRes: Int, val selectedIconRes: Int) {
    HOME("home", "Home", AppIcons.HomeOutline, AppIcons.Home),
    EXPLORE("explore", "Explore", AppIcons.ExploreOutline, AppIcons.Explore),
    /** YouTube Music's mixes for the account, in the Poster layout only (see RadioMixesScreen). */
    RADIO(com.opentune.ui.radio.RADIO_ROUTE, "Radio", AppIcons.Radio, AppIcons.Radio),
    PODCASTS("podcasts", "Podcasts", AppIcons.Podcasts, AppIcons.Podcasts),
    LIBRARY("library", "Library", AppIcons.LibraryMusicOutline, AppIcons.LibraryMusic),
}

private const val SEARCH_ROUTE = "search"


@Composable
fun AppRoot(vm: PlayerViewModel) {
    val nav = rememberNavController()
    // Pages opened by a tap, each once however fast the taps come (see PageOpener).
    val pages = remember(nav) { PageOpener({ nav.currentBackStackEntry?.id }, { nav.navigate(it) }) }
    // With another of the person's devices playing, its song (see Connect).
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val playbackError by vm.playbackError.collectAsState()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playerOpen) { vm.setPlayerOpen(playerOpen) }
    // The docked player folded into its strip, on screens wide enough for one.
    var paneCollapsed by rememberSaveable { mutableStateOf(false) }
    var playerCovers by remember { mutableStateOf(false) }
    val haze = rememberHazeState()
    val uiScope = rememberCoroutineScope()
    val artistLookup = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null) }
    val backdrop = rememberLayerBackdrop()
    // Floating glass blurs a recording of the page (and bends it, with Liquid Glass on).
    val sampled = com.opentune.ui.components.backdropBlurOn()
    // Scrolling any page down folds the bottom bar into one row; scrolling up unfolds it.
    val chromeScroll = rememberChromeScroll()
    val chromeUi by com.opentune.data.settings.AppSettings.ui.collectAsState()
    // Explore without a tab of its own lives on the Search page instead.
    val separateExplore = chromeUi.separateExplore
    // The Poster layout has a Radio tab of the account's mixes.
    val radioTab = chromeUi.pageLayout == com.opentune.data.settings.PageLayout.POSTER
    // Podcasts can be turned off in Settings › Appearance, their tab with them (issue 65).
    val podcastsTab = chromeUi.podcasts
    val dockTabs = remember(separateExplore, radioTab, podcastsTab) {
        Tab.entries.filter { (it != Tab.EXPLORE || separateExplore) && (it != Tab.RADIO || radioTab) && (it != Tab.PODCASTS || podcastsTab) }
    }
    val chromeTabs = dockTabs.map { ChromeTab(it.label, ImageVector.vectorResource(it.iconRes), ImageVector.vectorResource(it.selectedIconRes)) }
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
    // time the app comes to the front and periodically while it stays there (the
    // check itself goes online at most every few hours), since a music app
    // is rarely started afresh.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var update by remember { mutableStateOf<com.opentune.data.UpdateCheck.Release?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            com.opentune.data.Usage.active()
            com.opentune.ui.components.Support.usedToday(appContext)
            // The opening comes first: these checks wait a few seconds, so they
            // never compete with the first frames and the entry motion.
            kotlinx.coroutines.delay(3_000)
            while (true) {
                if (com.opentune.data.settings.AppSettings.ui.value.checkForUpdates && update == null) {
                    update = com.opentune.data.UpdateCheck.checkIfDue(appContext)
                }
                com.opentune.data.config.RemoteConfig.refresh()
                com.opentune.data.addons.AddonRefresh.refresh()
                kotlinx.coroutines.delay(5 * 60 * 1000L)
            }
        }
    }
    // After an update, once: what's changed.
    var whatsNew by remember { mutableStateOf<List<Pair<String, List<com.opentune.ui.components.WhatsNew.Note>>>>(emptyList()) }
    LaunchedEffect(Unit) { whatsNew = com.opentune.ui.components.WhatsNew.due(appContext, com.opentune.BuildConfig.VERSION_NAME) }
    if (whatsNew.isNotEmpty() && update == null) {
        com.opentune.ui.components.WhatsNewSheet(whatsNew, com.opentune.BuildConfig.VERSION_NAME, onDismiss = { whatsNew = emptyList() })
    }
    // Once a month each, after some days of use, a GitHub star or Discord invitation,
    // sliding up above the dock; never over an update or What's new, and not
    // straight after opening.
    var invite by remember { mutableStateOf<com.opentune.ui.components.Support.Invite?>(null) }
    var askStar by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(45_000)
        invite = com.opentune.ui.components.Support.due(appContext)
        askStar = invite != null
    }
    val starCard: @Composable () -> Unit = {
        val runtime by com.opentune.data.config.RemoteConfig.state.collectAsState()
        androidx.compose.animation.AnimatedVisibility(
            visible = askStar && runtime.communityPrompts && !playerOpen && update == null && whatsNew.isEmpty(),
            enter = androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
        ) {
            invite?.let { com.opentune.ui.components.InviteCard(it, onDismiss = { askStar = false }) }
        }
    }
    if (update == null) com.opentune.ui.connect.AccountSyncAsk()
    update?.let { r ->
        com.opentune.ui.settings.UpdateDialog(
            r,
            onDismiss = { update = null },
            onNotNow = { shown -> com.opentune.data.UpdateCheck.skip(appContext, shown.version); update = null },
        )
    }

    // The opening animation, once per launch: the app comes forward out of
    // the mark as the screen dives into it.
    var opening by remember { mutableStateOf(!com.opentune.ui.components.Opening.played && chromeUi.openingAnimation && !chromeUi.reduceAnimation) }
    var dive by remember { androidx.compose.runtime.mutableFloatStateOf(if (opening) 0f else 1f) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Explore's tab turned off while on it: back to Home, Explore is on the Search page now.
    LaunchedEffect(separateExplore) {
        if (!separateExplore && tab == Tab.EXPLORE) {
            tab = Tab.HOME
            if (route == Tab.EXPLORE.route) nav.popBackStack(Tab.HOME.route, inclusive = false)
        }
    }
    // Out of the Poster layout while on its Radio tab: back to Home.
    LaunchedEffect(radioTab) {
        if (!radioTab && tab == Tab.RADIO) {
            tab = Tab.HOME
            if (route == Tab.RADIO.route) nav.popBackStack(Tab.HOME.route, inclusive = false)
        }
    }
    // Podcasts turned off while on their tab: back to Home.
    LaunchedEffect(podcastsTab) {
        if (!podcastsTab && tab == Tab.PODCASTS) {
            tab = Tab.HOME
            if (route == Tab.PODCASTS.route) nav.popBackStack(Tab.HOME.route, inclusive = false)
        }
    }
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
            playNext = { id -> withSongs(id) { songs -> songs.asReversed().forEach(vm::playNext); com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("{0} songs play next", songs.size)) } },
            addToQueue = { id -> withSongs(id) { songs -> songs.forEach(vm::addToQueue); com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("{0} songs added to the queue", songs.size)) } },
            downloadAll = { id -> withSongs(id) { songs -> songs.forEach { com.opentune.data.download.Downloads.enqueue(appContext.applicationContext, it) }; com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Downloading {0} songs", songs.size)) } },
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
            com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("You won't hear \"{0}\" in autoplay again", s.title))
        },
        notInterested = { s ->
            LibraryStore.hideSong(s)
            vm.player.dropHiddenSmartShuffleSongs()
            com.opentune.data.Pills.withAction(com.opentune.i18n.Tr.f("\"{0}\" won't be suggested", s.title), "Undo") { LibraryStore.unhideSong(s.videoId) }
        },
        dontPlayArtist = dontPlay@{ s ->
            val artist = LibraryStore.hideArtist(s) ?: return@dontPlay
            vm.player.dropHiddenSmartShuffleSongs()
            com.opentune.data.Pills.withAction(com.opentune.i18n.Tr.f("Songs by {0} won't be suggested", artist.name), "Undo") { LibraryStore.unhideArtist(artist.key) }
        },
        keepInPlaylist = { s, id ->
            LibraryStore.addToPlaylist(id, s)
            com.opentune.playback.SmartShuffle.keep(s.videoId)
            LibraryStore.playlist(id)?.takeIf { it.spotifyId == null }?.let { p -> com.opentune.data.Pills.done(com.opentune.i18n.Tr.f("Added to {0}", p.name)) }
        },
        progressOf = { s -> if (s.videoId == vm.currentSong.value?.videoId) vm.positionMs() to vm.durationMs.value else null },
        // One artist lookup at a time: a slower earlier one that failed showed its
        // "Couldn't find" over the page a later tap had already opened.
        viewArtist = { s ->
            artistLookup.get()?.cancel()
            artistLookup.set(uiScope.launch {
                val id = MusicRepository.artistIdFor(s)
                if (id == null) {
                    com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't find {0}'s page", s.artist))
                } else {
                    fromPlayer { nav.openBrowse(id, BrowseType.ARTIST) }
                }
            })
        },
        viewAlbum = { s ->
            artistLookup.get()?.cancel()
            artistLookup.set(uiScope.launch {
                val id = MusicRepository.albumIdFor(s)
                if (id == null) com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't find the album for \"{0}\"", s.title))
                else fromPlayer { nav.openBrowse(id, BrowseType.ALBUM) }
            })
        },
        viewArtistNamed = { s, name ->
            artistLookup.get()?.cancel()
            artistLookup.set(uiScope.launch {
                val id = MusicRepository.artistIdFor(s, name)
                if (id == null) {
                    com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't find {0}'s page", name))
                } else {
                    fromPlayer { nav.openBrowse(id, BrowseType.ARTIST) }
                }
            })
        },
    ) }
    val libraryNav = remember(nav) { LibraryNav(
        downloads = { pages.open("downloads") },
        downloaded = { id -> pages.open("downloaded/${Uri.encode(id)}") },
        local = { pages.open("local") },
        replay = { pages.open("replay") },
        settings = { pages.open("settings") },
        liked = { pages.open("liked") },
        playlist = { id -> pages.open("playlist/${Uri.encode(id)}") },
        browse = { id -> nav.openBrowse(id) },
        server = { pages.open("server") },
        radio = { pages.open("radio") },
        together = { pages.open("together") },
        importPlaylist = { pages.open("import") },
        wrapped = { pages.open("wrapped") },
        history = { pages.open("history") },
        smart = { id -> pages.open("smart/${Uri.encode(id)}") },
        newSmart = { pages.open("smart-edit") },
        podcasts = { pages.open("podcasts/library") },
    ) }
    val serverNav = remember(nav) {
        ServerNav(
            album = { id -> pages.open("server/album/${Uri.encode(id)}") },
            artist = { id -> pages.open("server/artist/${Uri.encode(id)}") },
            playlist = { id -> pages.open("server/playlist/${Uri.encode(id)}") },
            artists = { pages.open("server/artists") },
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
            // A channel by its handle, or a playlist that may be a podcast: YouTube says which page.
            is LinkTarget.Resolve -> when (val found = MusicRepository.resolveLink(link.url)) {
                is com.opentune.data.innertube.ResolvedLink.Page -> nav.openBrowse(found.browseId)
                // A song: played as a shared song link is, by handing that link on (the effect runs again for it).
                is com.opentune.data.innertube.ResolvedLink.Track -> { Links.open("https://music.youtube.com/watch?v=${found.videoId}"); return@LaunchedEffect }
                null -> when (val back = link.fallback) {
                    is LinkTarget.Browse -> nav.openBrowse(back.browseId)
                    else -> com.opentune.data.Pills.error("Couldn't open that link")
                }
            }
            // Read for its title and artist, then found on YouTube Music; autoplay carries on from it.
            is LinkTarget.Elsewhere -> {
                vm.player.connected.first { it }
                val named = com.opentune.data.links.MusicLinks.resolve(link.url)
                val match = named?.let { n ->
                    runCatching { MusicRepository.search("${n.title} ${n.artist}".trim(), SearchFilter.SONGS) }.getOrDefault(emptyList())
                        .firstNotNullOfOrNull { (it as? SearchResult.TopTrack)?.song ?: (it as? SearchResult.Track)?.song }
                }
                when {
                    match != null -> { vm.play(match, "Shared link"); playerOpen = true }
                    named != null -> com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Couldn't find \"{0}\" on YouTube Music", named.title))
                    else -> com.opentune.data.Pills.error("Couldn't read that link")
                }
            }
            is LinkTarget.SpotifyImport -> nav.navigate("import?link=${Uri.encode(link.text)}")
            is LinkTarget.Together -> { playerOpen = false; nav.navigate("together?code=${link.code}") }
            is LinkTarget.Connect -> { playerOpen = false; nav.navigate("connect?join=${Uri.encode(link.secret)}") }
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
                    if (match != null) vm.play(match, link.query) else com.opentune.data.Pills.error(com.opentune.i18n.Tr.f("Nothing found for \"{0}\"", link.query))
                }
            }
            null -> return@LaunchedEffect
        }
        Links.consumed()
    }
    // The Wrapped story fills the screen, like the login page.
    val chromeRoute = route != "login" && route != "discord-login" && route != "wrapped"
    val chromeVisible = chromeRoute && !playerCovers
    // With the Dock slides away setting on, pages that are destinations
    // in themselves lose the dock and Search to the mini player.
    val dockAway = chromeUi.dockSlidesAway && (route == "liked" || route == "playlist/{id}" || route == "collection/{id}" ||
            route == "artist/{id}" || route == "artist-items/{id}?params={params}&title={title}" || route == "settings?page={page}" || route == "history")
    // The window's size class picks the chrome. A fold or unfold only changes
    // what's around the pages: the NavHost and the player keep their place in
    // the tree, so neither starts over (the activity handles the size change
    // itself rather than being recreated; see the manifest).
    val window = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    val layout = appLayout(window.width.value.toInt(), window.height.value.toInt(), song != null, paneCollapsed, chromeUi.wideLayout, chromeUi.sidePlayer)
    // Docked beside the page, the player is never also opened over it.
    val sheetOpen = playerOpen && !layout.sidePlayer
    com.opentune.ui.theme.SystemBars(if (sheetOpen) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.background)
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
    // The pages' bottom room follows the chrome: with the dock away on a
    // destination page, only the mini player's share of it stays.
    val contentBottom = androidx.compose.animation.core.animateDpAsState(
        when {
            layout.nav != NavChrome.DOCK -> navInset + 16.dp + if (layout.nowPlaying == NowPlaying.BAR) CHROME_MINI_HEIGHT + 12.dp else 0.dp
            dockAway -> navInset + 24.dp + if (song != null) CHROME_MINI_HEIGHT + 8.dp else 0.dp
            else -> navInset + CHROME_TAB_HEIGHT + 24.dp + if (song != null) CHROME_MINI_HEIGHT + 8.dp else 0.dp
        },
        if (chromeUi.reduceAnimation) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "contentBottom",
    )
    // Read only where a page lays out or reads it, not here: read here, every frame of that
    // easing (the first song starting, a page with the dock away) composed the whole app again.
    val content = remember(contentBottom) { BottomRoom(contentBottom) }
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
        //noinspection RestrictedApi
        val stack = nav.currentBackStack.value.filter { it.destination.route != null }
        val at = stack.indexOfLast { it.destination.route == root.route }
        if (at < 0) return emptyList()
        // Search (and song recognition) belong to no tab: kept as one of this tab's pages, coming
        // back to the tab would open Search instead. They and anything opened from them end the list.
        return stack.drop(at + 1).takeWhile { it.destination.route != SEARCH_ROUTE && it.destination.route?.startsWith("recognize") != true }.mapNotNull { e ->
            val template = e.destination.route ?: return@mapNotNull null
            filledRoute(template, { name -> e.arguments?.get(name) }, Uri::encode)
        }
    }
    val selectTab: (Int) -> Unit = { i ->
        // A tab chosen is somewhere new, not a way back to the player.
        playerReturn = null
        val t = dockTabs[i]
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
    // Tapped again on Search, the cursor goes straight into the search bar.
    val openSearch = {
        if (route != SEARCH_ROUTE) nav.navigate(SEARCH_ROUTE) { launchSingleTop = true }
        else com.opentune.ui.search.SearchFocus.request()
    }
    val openMood: (com.opentune.data.model.MoodGenre) -> Unit = { mood ->
        pages.open("mood/${Uri.encode(mood.browseId)}?params=${Uri.encode(mood.params.orEmpty())}&title=${Uri.encode(mood.title)}")
    }

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
        com.opentune.ui.components.ConnectionBanner(
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
            reduceMotion = chromeUi.reduceAnimation,
            onOnline = { com.opentune.ui.spotify.SpotifyConnection.retryFailedSync() },
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
                // Every page's account button opens the account menu, which leads here.
                val accountMenu = remember(nav) {
                    com.opentune.ui.components.AccountMenuNav(
                        addAccount = { pages.open("login") },
                        manageAccounts = { pages.open("settings?page=account") },
                        settings = { pages.open("settings") },
                    )
                }
                CompositionLocalProvider(LocalHazeState provides null, com.opentune.ui.components.LocalAccountMenu provides accountMenu) {
                    NavHost(
                        navController = nav,
                        startDestination = Tab.HOME.route,
                        modifier = Modifier.align(Alignment.TopCenter).then(pageArea).fillMaxSize(),
                        // The first appearance is no navigation: the opening page is simply
                        // there, as if it had always been — it never flies in.
                        enterTransition = { if (initialState.destination.route == targetState.destination.route) androidx.compose.animation.EnterTransition.None else PageMotion.enter(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                        exitTransition = { if (initialState.destination.route == targetState.destination.route) androidx.compose.animation.ExitTransition.None else PageMotion.exit(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                        popEnterTransition = { if (initialState.destination.route == targetState.destination.route) androidx.compose.animation.EnterTransition.None else PageMotion.popEnter(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                        popExitTransition = { if (initialState.destination.route == targetState.destination.route) androidx.compose.animation.ExitTransition.None else PageMotion.popExit(chromeUi.pageTransition, chromeUi.reduceAnimation) },
                    ) {
                        page(Tab.HOME.route) {
                            HomeScreen(
                                contentPadding = content,
                                actions = actions,
                                onItemClick = openItem,
                                onOpenSettings = { pages.open("settings") },
                                onRecognize = { pages.open("recognize") },
                                covered = playerCovers,
                                onOpenSpeedDial = { pages.open("speed-dial") },
                            )
                        }
                        page("speed-dial") {
                            com.opentune.ui.home.SpeedDialScreen(content, onItemClick = openItem, onBack = { nav.popBackStack() })
                        }
                        page(Tab.EXPLORE.route) {
                            ExploreScreen(content, onMoodClick = openMood, onItemClick = openItem, onOpenSettings = { pages.open("settings") })
                        }
                        page(Tab.RADIO.route) {
                            com.opentune.ui.radio.RadioMixesScreen(
                                content,
                                onItemClick = openItem,
                                onOpenLiked = { pages.open("liked") },
                                onOpenStations = { pages.open("radio") },
                                onSignIn = { pages.open("login") },
                                onOpenSettings = { pages.open("settings") },
                            )
                        }
                        page(Tab.PODCASTS.route) {
                            com.opentune.ui.podcasts.PodcastsScreen(content, actions, onOpenShow = { id -> pages.open("podcast/${Uri.encode(id)}") }, onOpenSettings = { pages.open("settings") },
                                onOpenAudiobooks = { pages.open("audiobooks") }, onOpenLibrary = { pages.open("podcasts/library") })
                        }
                        page("podcasts/library") {
                            com.opentune.ui.podcasts.PodcastLibraryScreen(content, actions, onBack = { nav.popBackStack() }, onOpenShow = { id -> pages.open("podcast/${Uri.encode(id)}") })
                        }
                        page("audiobooks") {
                            com.opentune.ui.podcasts.AudiobooksScreen(content, onBack = { nav.popBackStack() }, onOpenBook = { id -> pages.open("audiobook/${Uri.encode(id)}") })
                        }
                        page("audiobook/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            com.opentune.ui.podcasts.AudiobookScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                        }
                        page("podcast/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            com.opentune.ui.podcasts.PodcastShowScreen(
                                e.arguments?.getString("id").orEmpty(),
                                content,
                                actions,
                                onBack = { nav.popBackStack() },
                                onOpenShow = { id -> pages.open("podcast/${Uri.encode(id)}") },
                            )
                        }
                        page(Tab.LIBRARY.route) {
                            LibraryScreen(contentPadding = content, actions = actions, nav = libraryNav)
                        }
                        page(SEARCH_ROUTE) {
                            SearchScreen(
                                contentPadding = content,
                                currentVideoId = song?.videoId,
                                isPlaying = isPlaying,
                                onPlay = { vm.play(it, "Search") },
                                onPlayNext = vm::playNext,
                                onAddToQueue = vm::addToQueue,
                                onBrowse = { item: BrowseItem -> nav.openBrowse(item.browseId, item.type) },
                                onRecognize = { pages.open("recognize") },
                                onOpenList = { list ->
                                    list.playlistId?.let { pages.open("playlist/${Uri.encode(it)}") } ?: list.browseId?.let { nav.openBrowse(it) }
                                },
                                explore = if (separateExplore) null else ({
                                    ExploreScreen(content, onMoodClick = openMood, onItemClick = openItem, showHeader = false)
                                }),
                            )
                        }
                        page("settings?page={page}", listOf(navArgument("page") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                            SettingsScreen(
                                startPage = e.arguments?.getString("page"),
                                contentPadding = content,
                                onBack = { nav.popBackStack() },
                                onOpenEqualizer = { pages.open("equalizer") },
                                onOpenReplay = { pages.open("replay") },
                                onOpenWrapped = { pages.open("wrapped") },
                                onSignIn = { pages.open("login") },
                                onOpenDownloads = { pages.open("downloads") },
                                onOpenSpotify = { pages.open("import") },
                                onOpenDevices = { pages.open("devices") },
                                onOpenDiscord = { pages.open("discord-login") },
                            )
                        }
                        page("discord-login") { entry ->
                            LaunchedEffect(entry.id) {
                                appContext.startActivity(com.opentune.ui.account.SignInActivity.intent(appContext, discord = true))
                                if (nav.currentBackStackEntry == entry) nav.popBackStack()
                            }
                        }
                        page("devices") {
                            com.opentune.ui.settings.LinkDeviceScreen(
                                content,
                                onBack = { nav.popBackStack() },
                                onSignIn = { pages.open("login") },
                                onTogether = { pages.open("together") },
                                onRemote = { pages.open("tv-remote") },
                                onConnect = { pages.open("connect") },
                            )
                        }
                        page(
                            "connect?join={join}",
                            arguments = listOf(androidx.navigation.navArgument("join") { nullable = true; defaultValue = null }),
                        ) { entry ->
                            com.opentune.ui.connect.ConnectScreen(content, entry.arguments?.getString("join"), onBack = { nav.popBackStack() })
                        }
                        page("tv-remote") {
                            com.opentune.ui.remote.TvRemoteScreen(content, onBack = { nav.popBackStack() })
                        }
                        page("login") { entry ->
                            LaunchedEffect(entry.id) {
                                appContext.startActivity(com.opentune.ui.account.SignInActivity.intent(appContext))
                                if (nav.currentBackStackEntry == entry) nav.popBackStack()
                            }
                        }
                        page("downloads") { DownloadsScreen(content, actions, onBack = { nav.popBackStack() }, onOpenList = { id -> pages.open("downloaded/${Uri.encode(id)}") }) }
                        page("downloaded/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            com.opentune.ui.library.DownloadedListScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                        }
                        page("liked") {
                            LikedScreen(content, actions, onBack = { nav.popBackStack() })
                        }
                        page("smart/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            val id = e.arguments?.getString("id").orEmpty()
                            // Only while it's the page showing: one deleted from its editor leaves on its own
                            // once uncovered, and mustn't take the editor still sliding away with it.
                            com.opentune.ui.library.SmartPlaylistScreen(id, content, actions, onBack = { if (nav.currentBackStackEntry == e) nav.popBackStack() },
                                onEdit = { pages.open("smart-edit?id=${Uri.encode(id)}") },
                                onOpenPlaylist = { copy -> pages.open("playlist/${Uri.encode(copy)}") })
                        }
                        page("smart-edit?id={id}", listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                            val editing = e.arguments?.getString("id")
                            com.opentune.ui.library.SmartPlaylistEditor(editing, content, onBack = { nav.popBackStack() }, onSaved = { saved ->
                                nav.popBackStack()
                                // A new one opens; an edited one is already under the editor.
                                if (editing == null) nav.navigate("smart/${Uri.encode(saved)}")
                            })
                        }
                        page("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            LocalPlaylistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                        }
                        page("equalizer") { EqualizerScreen(content, onBack = { nav.popBackStack() }) }
                        page("local") { LocalMusicScreen(content, actions, onBack = { nav.popBackStack() }) }
                        page("history") { com.opentune.ui.library.ListeningHistoryScreen(content, actions, onBack = { nav.popBackStack() }) }
                        page("replay") { ReplayScreen(content, actions, onBack = { nav.popBackStack() }, onOpenWrapped = { pages.open("wrapped") }) }
                        page("wrapped") { WrappedScreen(actions, onBack = { nav.popBackStack() }, player = vm.player) }
                        page("recognize?listen={listen}", listOf(navArgument("listen") { type = NavType.BoolType; defaultValue = false })) { e ->
                            com.opentune.ui.recognize.RecognizeScreen(
                                content, onPlay = { vm.play(it, "Found by listening") }, onBack = { nav.popBackStack() },
                                listenAtOnce = e.arguments?.getBoolean("listen") == true,
                            )
                        }
                        page(
                            "import?link={link}",
                            listOf(navArgument("link") { type = NavType.StringType; nullable = true; defaultValue = null }),
                        ) { e ->
                            com.opentune.ui.spotify.ImportScreen(
                                content,
                                actions,
                                initialLink = e.arguments?.getString("link"),
                                onBack = { nav.popBackStack() },
                                onOpenPlaylist = { id -> pages.open("playlist/${Uri.encode(id)}") },
                                onOpenExportify = { appContext.startActivity(android.content.Intent(appContext, com.opentune.ui.spotify.ExportifyActivity::class.java)) },
                            )
                        }
                        page("radio") { com.opentune.ui.radio.RadioScreen(content, actions, onBack = { nav.popBackStack() }) }
                        page(
                            "together?code={code}",
                            arguments = listOf(androidx.navigation.navArgument("code") { nullable = true; defaultValue = null }),
                        ) { entry ->
                            com.opentune.ui.together.TogetherScreen(content, entry.arguments?.getString("code"), onBack = { nav.popBackStack() })
                        }
                        page("server") { ServerScreen(content, actions, serverNav, onBack = { nav.popBackStack() }) }
                        page("server/artists") { ServerArtistsScreen(content, serverNav, onBack = { nav.popBackStack() }) }
                        page("server/album/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            ServerAlbumScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() }, onArtist = serverNav.artist)
                        }
                        page("server/artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            ServerArtistScreen(e.arguments?.getString("id").orEmpty(), content, serverNav, onBack = { nav.popBackStack() })
                        }
                        page("server/playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            ServerPlaylistScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                        }
                        page("collection/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            CollectionScreen(e.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                        }
                        page("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            ArtistScreen(
                                e.arguments?.getString("id").orEmpty(), content, actions,
                                onBack = { nav.popBackStack() },
                                onItemClick = openItem,
                                onShowAll = { shelf ->
                                    shelf.moreBrowseId?.let { id ->
                                        pages.open("artist-items/${Uri.encode(id)}?params=${Uri.encode(shelf.moreParams.orEmpty())}&title=${Uri.encode(shelf.title)}")
                                    }
                                },
                            )
                        }
                        page(
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
                        page(
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

            // Pages blur and fade as they pass under the status bar. Pages on
            // GlassPage (Liked and the playlist pages) draw their own strip
            // under their top bar instead — here it would blur the bar itself.
            if (chromeRoute && route != "liked" && route != "playlist/{id}" && route != "collection/{id}") com.opentune.ui.components.TopEdgeFrost(Modifier.align(Alignment.TopCenter))

            // Solid system bars (Settings): the navigation bar's backdrop on
            // every page, the status bar's too except on GlassPage pages,
            // which draw their own inside the page (see GlassPage).
            if (chromeRoute) com.opentune.ui.theme.SystemBarBackdrops(top = route != "liked" && route != "playlist/{id}" && route != "collection/{id}", bottom = true)

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
                            inline = chromeScroll.inline && !dockAway,
                            dockAway = dockAway,
                            tabs = chromeTabs,
                            selected = if (route == SEARCH_ROUTE) null else dockTabs.indexOf(tab).takeIf { it >= 0 },
                            onSelect = selectTab,
                            onExpand = chromeScroll::expand,
                            searchSelected = route == SEARCH_ROUTE,
                            onSearch = openSearch,
                            onSearchLongPress = { if (route != "recognize?listen={listen}") nav.navigate("recognize") { launchSingleTop = true } },
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
                        selected = if (route == SEARCH_ROUTE) null else dockTabs.indexOf(tab).takeIf { it >= 0 },
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
                    PlayerStrip(vm, onExpand = { paneCollapsed = false })
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
                        onDevices = { nav.navigate("connect") },
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
                LaunchedEffect(settled, still, sheetOpen) {
                    // A drag down uncovers the bottom row bit by bit, so the chrome has to be
                    // back underneath from the first pull — otherwise it pops in whole at the let-go.
                    // sheetOpen leads: the settled report lags a frame behind the close, and in
                    // that frame the drag is already zeroed, which would blink the chrome out.
                    snapshotFlow { sheetOpen && settled && still && com.opentune.ui.components.PlayerMorph.sheetDrag <= 0f }
                        .collect { playerCovers = it }
                }
                DisposableEffect(Unit) { onDispose { playerCovers = false } }
                PlayerScreen(
                    vm,
                    onCollapse = { playerOpen = false },
                    onCovering = { still = it },
                    onTogether = { fromPlayer { nav.navigate("together") } },
                    onEqualizer = { fromPlayer { nav.navigate("equalizer") } },
                    onDevices = { fromPlayer { nav.navigate("connect") } },
                )
            }
            if (sheetMorph) {
                // Out of the mini player as one card, and back into it.
                var sheetSettled by remember { mutableStateOf(false) }
                com.opentune.ui.components.PlayerSheet(
                    open = sheetOpen && song != null,
                    onSettled = { sheetSettled = it },
                    face = { round -> MiniPlayerBar(vm, onExpand = {}, modifier = Modifier.fillMaxSize(), inline = round, replica = true) },
                ) {
                    playerSheet(sheetSettled)
                }
            } else {
                // Motion reduced (what turns the card off): the player fades in and out.
                AnimatedVisibility(
                    visible = sheetOpen && song != null,
                    enter = PageMotion.playerIn,
                    exit = PageMotion.playerOut,
                ) {
                    playerSheet(transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible)
                }
            }
            // Notes about what was just done float in over everything, the player included.
            val notificationTheme by com.opentune.data.settings.AppSettings.theme.collectAsState()
            // Keep one host alive across player transitions, but use the visible screen's palette.
            com.opentune.ui.theme.PlayerTheme(
                seed = com.opentune.ui.theme.rememberArtworkSeed(song?.thumbnailUrl),
                settings = notificationTheme,
                neutral = chromeUi.playerStyle == com.opentune.data.settings.PlayerStyle.APPLE_MUSIC,
                active = sheetOpen && song != null,
            ) {
                com.opentune.ui.components.PillHost(chromeUi.reduceAnimation, Modifier.align(Alignment.TopCenter))
            }
        }
    }
    if (opening) {
        com.opentune.ui.components.OpeningReveal(onDive = { dive = it }, onDone = { opening = false; dive = 1f })
    }
}

/** [NowPlayingStrip] following the player. */
@Composable
private fun PlayerStrip(vm: PlayerViewModel, onExpand: () -> Unit) {
    val isPlaying by vm.isPlaying.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    NowPlayingStrip(
        isPlaying = isPlaying,
        hasNext = hasNext,
        onTogglePlay = vm::togglePlayPause,
        onNext = vm::skipNext,
        onExpand = onExpand,
    ) { m -> MiniPlayerBar(vm, onExpand = onExpand, modifier = m, inline = true) }
}

/**
 * The route that opens again the page [template] made with these arguments ([arg] by name),
 * each [encode]d. An optional one that was left out (null) stays out: written in as nothing,
 * it came back as "" rather than null, so a new smart playlist's editor came back as the
 * editor of one with no id, which saved and didn't open the new list.
 */
internal fun filledRoute(template: String, arg: (String) -> Any?, encode: (String) -> String): String {
    val slot = Regex("""\{([^}]+)\}""")
    val path = template.substringBefore('?')
        .replace(slot) { m -> encode(arg(m.groupValues[1])?.toString().orEmpty()) }
    val query = template.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.mapNotNull { part ->
        val name = slot.find(part)?.groupValues?.get(1) ?: return@mapNotNull part
        val value = arg(name) ?: return@mapNotNull null
        part.replace(slot) { encode(value.toString()) }
    }
    return if (query.isEmpty()) path else path + "?" + query.joinToString("&")
}

/**
 * Opens pages for taps, each once: a second tap on the same tile or row, landing while the
 * page it opened is still sliding in (the page under it still takes taps then), would open a
 * second copy on top, and back would have to go through the same page twice. A page opened
 * here and still on top isn't opened again by the same route; anything else opens as asked.
 * [top] is the id of the back stack's top entry, read just before and just after [navigate].
 */
internal class PageOpener(private val top: () -> String?, private val navigate: (String) -> Unit) {
    private var opened: Pair<String, String>? = null

    fun open(route: String) {
        val here = top()
        if (here != null && opened == here to route) return
        navigate(route)
        // Only a page that went on top is held: one that took itself straight off again left
        // the page under it on top, which this route must still open from.
        opened = top()?.takeIf { it != here }?.let { it to route }
    }
}

/** The ids of the entries on the back stack, top last. The whole stack is only public as this restricted, long-stable flow. */
@android.annotation.SuppressLint("RestrictedApi")
private fun NavHostController.stackIds(): List<String> = currentBackStack.value.map { it.id }

private fun NavHostController.openBrowse(browseId: String, type: BrowseType = MusicRepository.typeOf(browseId)) {
    if (com.opentune.ui.library.isYouTubeLikes(browseId)) {
        navigate("liked") { launchSingleTop = true }
        return
    }
    val route = when {
        // A podcast show has its own page, from YouTube Music or from its own feed.
        browseId.startsWith("MPSP") || com.opentune.data.podcasts.PodcastFeeds.isFeedShow(browseId) -> "podcast/{id}"
        type == BrowseType.ARTIST -> "artist/{id}"
        else -> "collection/{id}"
    }
    // Already on that page (a second tap while the first was still finding it):
    // stay, so back doesn't have to go through the same page twice.
    val here = currentBackStackEntry
    if (here?.destination?.route == route && here.arguments?.getString("id") == browseId) return
    navigate(route.replace("{id}", Uri.encode(browseId)))
}

/** Room at the foot of every page for the chrome, read from [bottom] when asked for. */
internal class BottomRoom(private val bottom: androidx.compose.runtime.State<androidx.compose.ui.unit.Dp>) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = 0.dp
    override fun calculateTopPadding() = 0.dp
    override fun calculateRightPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = 0.dp
    override fun calculateBottomPadding() = bottom.value
}

/** A destination of the NavHost. */
private fun androidx.navigation.NavGraphBuilder.page(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry -> content(entry) }
