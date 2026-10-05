package com.opentune.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.opentune.ui.explore.ExploreScreen
import com.opentune.ui.explore.MoodScreen
import com.opentune.ui.home.HomeScreen
import com.opentune.ui.player.MiniPlayerBar
import com.opentune.ui.player.PlayerScreen
import com.opentune.ui.search.SearchScreen
import com.opentune.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    EXPLORE("explore", "Explore", Icons.Outlined.Explore, Icons.Filled.Explore),
    SEARCH("search", "Search", Icons.Outlined.Search, Icons.Filled.Search),
}

@Composable
fun AppRoot(vm: PlayerViewModel) {
    val nav = rememberNavController()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val playbackError by vm.playbackError.collectAsState()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }

    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        Tab.entries.firstOrNull { it.route == entry?.destination?.route }?.let { tab = it }
    }
    LaunchedEffect(playbackError) { playbackError?.let { snackbar.showSnackbar(it) } }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    val actions = SongActions(
        currentVideoId = song?.videoId,
        isPlaying = isPlaying,
        playAll = { songs, index, shuffle -> vm.playAll(songs, index, shuffle) },
        playNext = vm::playNext,
        addToQueue = vm::addToQueue,
    )
    val openItem: (ShelfItem) -> Unit = { item ->
        val browseId = item.browseId
        when {
            browseId != null -> nav.openBrowse(browseId)
            else -> item.toSong()?.let(vm::play)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayerBar(vm, onExpand = { playerOpen = true })
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = {
                                    if (tab == t) {
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
                                icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Screens draw their own top bars under the status bar; only the
            // bottom (mini player + tabs) is reserved.
            val content = PaddingValues(bottom = padding.calculateBottomPadding())
            NavHost(
                navController = nav,
                startDestination = Tab.HOME.route,
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 10 } },
            ) {
                composable(Tab.HOME.route) {
                    HomeScreen(content, onItemClick = openItem, onOpenSettings = { nav.navigate("settings") })
                }
                composable(Tab.EXPLORE.route) {
                    ExploreScreen(content, onMoodClick = { mood ->
                        nav.navigate("mood/${Uri.encode(mood.browseId)}?params=${Uri.encode(mood.params.orEmpty())}&title=${Uri.encode(mood.title)}")
                    })
                }
                composable(Tab.SEARCH.route) {
                    SearchScreen(
                        contentPadding = content,
                        currentVideoId = song?.videoId,
                        isPlaying = isPlaying,
                        onPlay = vm::play,
                        onPlayNext = vm::playNext,
                        onAddToQueue = vm::addToQueue,
                        onBrowse = { item: BrowseItem -> nav.openBrowse(item.browseId, item.type) },
                    )
                }
                composable("settings") { SettingsScreen(content, onBack = { nav.popBackStack() }) }
                composable("collection/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    CollectionScreen(entry.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() })
                }
                composable("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    ArtistScreen(entry.arguments?.getString("id").orEmpty(), content, actions, onBack = { nav.popBackStack() }, onItemClick = openItem)
                }
                composable(
                    "mood/{id}?params={params}&title={title}",
                    listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("params") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val args = entry.arguments
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

        AnimatedVisibility(
            visible = playerOpen && song != null,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(280)),
        ) {
            PlayerScreen(vm, onCollapse = { playerOpen = false })
        }
    }
}

private fun NavHostController.openBrowse(browseId: String, type: BrowseType = MusicRepository.typeOf(browseId)) {
    val id = Uri.encode(browseId)
    when (type) {
        BrowseType.ARTIST -> navigate("artist/$id")
        else -> navigate("collection/$id")
    }
}
