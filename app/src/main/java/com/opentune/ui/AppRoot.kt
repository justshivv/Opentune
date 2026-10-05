package com.opentune.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ShelfItem
import com.opentune.ui.browse.ArtistScreen
import com.opentune.ui.browse.CollectionScreen
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.glass
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

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.Home),
    EXPLORE("explore", "Explore", Icons.Rounded.Explore),
    LIBRARY("library", "Library", Icons.Rounded.LibraryMusic),
}

private const val SEARCH_ROUTE = "search"

/** Room kept under scrolling content for the floating nav bar and mini player. */
private val NAV_HEIGHT = 72.dp
private val MINI_HEIGHT = 74.dp

@Composable
fun AppRoot(vm: PlayerViewModel) {
    val nav = rememberNavController()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val playbackError by vm.playbackError.collectAsState()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val haze = rememberHazeState()

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(route) { Tab.entries.firstOrNull { it.route == route }?.let { tab = it } }
    LaunchedEffect(playbackError) { playbackError?.let { snackbar.showSnackbar(it) } }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    val actions = SongActions(
        currentVideoId = song?.videoId,
        isPlaying = isPlaying,
        playAll = { songs, index, shuffle, source -> vm.playAll(songs, index, shuffle, source) },
        playNext = vm::playNext,
        addToQueue = vm::addToQueue,
    )
    val openItem: (ShelfItem) -> Unit = { item ->
        val browseId = item.browseId
        when {
            browseId != null -> nav.openBrowse(browseId)
            else -> item.toSong()?.let { vm.play(it, "Home") }
        }
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val content = PaddingValues(bottom = navInset + NAV_HEIGHT + 24.dp + if (song != null) MINI_HEIGHT else 0.dp)

    CompositionLocalProvider(LocalHazeState provides haze) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().hazeSource(haze)) {
                NavHost(
                    navController = nav,
                    startDestination = Tab.HOME.route,
                    enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 10 } },
                ) {
                    composable(Tab.HOME.route) {
                        HomeScreen(
                            contentPadding = content,
                            actions = actions,
                            onItemClick = openItem,
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(Tab.EXPLORE.route) {
                        ExploreScreen(content, onMoodClick = { mood ->
                            nav.navigate("mood/${Uri.encode(mood.browseId)}?params=${Uri.encode(mood.params.orEmpty())}&title=${Uri.encode(mood.title)}")
                        })
                    }
                    composable(Tab.LIBRARY.route) {
                        LibraryScreen(
                            contentPadding = content,
                            actions = actions,
                            onOpenLocal = { nav.navigate("local") },
                            onOpenReplay = { nav.navigate("replay") },
                            onOpenSettings = { nav.navigate("settings") },
                        )
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
                        )
                    }
                    composable("equalizer") { EqualizerScreen(content, onBack = { nav.popBackStack() }) }
                    composable("local") { LocalMusicScreen(content, actions, onBack = { nav.popBackStack() }) }
                    composable("replay") { ReplayScreen(content, actions, onBack = { nav.popBackStack() }) }
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

            // Floating chrome: the mini player above the nav pill and search button.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                SnackbarHost(snackbar)
                MiniPlayerBar(vm, onExpand = { playerOpen = true })
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NavPill(
                        selected = if (route == SEARCH_ROUTE) null else tab,
                        onSelect = { t ->
                            if (route == t.route) {
                                nav.popBackStack(t.route, inclusive = false)
                            } else {
                                tab = t
                                nav.navigate(t.route) {
                                    popUpTo(Tab.HOME.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SearchButton(selected = route == SEARCH_ROUTE) {
                        if (route != SEARCH_ROUTE) nav.navigate(SEARCH_ROUTE) { launchSingleTop = true }
                    }
                }
            }

            AnimatedVisibility(
                visible = playerOpen && song != null,
                enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it } + fadeIn(),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(280)),
            ) {
                PlayerScreen(vm, onCollapse = { playerOpen = false })
            }
        }
    }
}

/** The glass pill holding the three main tabs; the current one sits in a lighter inset pill. */
@Composable
private fun NavPill(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier.height(NAV_HEIGHT).glass(shape).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val isSelected = t == selected
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else Color.Transparent,
                label = "tabBg",
            )
            val fg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabFg",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp))
                    .background(bg)
                    .clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = fg, modifier = Modifier.size(26.dp))
                Text(t.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
            }
        }
    }
}

@Composable
private fun SearchButton(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(NAV_HEIGHT)
            .glass(CircleShape, if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, "Search", Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

private fun NavHostController.openBrowse(browseId: String, type: BrowseType = MusicRepository.typeOf(browseId)) {
    val id = Uri.encode(browseId)
    when (type) {
        BrowseType.ARTIST -> navigate("artist/$id")
        else -> navigate("collection/$id")
    }
}
