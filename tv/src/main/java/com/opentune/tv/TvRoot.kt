package com.opentune.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon

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

/** The pages the bar names; everything else opens over one of them and Back returns to it. */
private val Tabs = listOf(Dest.Home, Dest.Explore, Dest.Library, Dest.NowPlaying)

/** What a screen can ask of the app: open a page, or play something. */
class TvNav(val stack: SnapshotStateList<Dest>, val player: TvPlayer) {
    /** A search to run when the Search page opens: what the viewer said to the TV's voice search. */
    var pendingSearch by mutableStateOf<String?>(null)

    val current: Dest get() = stack.last()

    fun open(dest: Dest) { if (stack.last() != dest) stack.add(dest) }
    fun goTab(dest: Dest) { stack.clear(); stack.add(dest) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false

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

@Composable
fun TvRoot(player: TvPlayer, voice: MutableStateFlow<String?> = MutableStateFlow(null)) {
    val stack = remember { mutableStateListOf<Dest>(Dest.Home) }
    val nav = remember(player) { TvNav(stack, player) }
    val current = stack.last()
    val song by player.currentSong.collectAsState()
    val playing by player.isPlaying.collectAsState()

    // A TV dims and starts its screensaver after a few minutes without a key press; while the
    // player is open and music plays, the cover and lyrics stay on screen instead.
    val view = LocalView.current
    val awake = playing && current == Dest.NowPlaying
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    // "Play … on OpenTune" from the voice search opens Search with it typed in.
    val spoken by voice.collectAsState()
    LaunchedEffect(spoken) {
        spoken?.let { nav.goTab(Dest.Search); nav.pendingSearch = it; voice.value = null }
    }

    // Back goes up through pages, then home, and only from home leaves the app.
    BackHandler(enabled = stack.size > 1 || current != Dest.Home) {
        if (!nav.back()) nav.goTab(Dest.Home)
    }

    // The player and a song's page take their colours from its cover; the rest stay neutral.
    val colors = rememberCoverColors(when (current) {
        Dest.NowPlaying -> song?.thumbnailUrl
        is Dest.Collection -> current.art
        else -> null
    })
    Box(
        Modifier.fillMaxSize().background(TvColors.Background)
            .background(Brush.verticalGradient(listOf(colors.top, colors.bottom, TvColors.Background))),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (current != Dest.NowPlaying) TopBar(nav, current, player)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (current) {
                    Dest.Home -> HomeScreen(nav)
                    Dest.Explore -> ExploreScreen(nav)
                    Dest.Library -> LibraryScreen(nav)
                    Dest.Search -> SearchScreen(nav)
                    Dest.Settings -> SettingsScreen(nav)
                    Dest.NowPlaying -> NowPlayingScreen(nav)
                    Dest.Liked -> LikedScreen(nav)
                    is Dest.Local -> LocalPlaylistScreen(nav, current.id)
                    is Dest.Collection -> CollectionScreen(nav, current)
                    is Dest.Artist -> ArtistScreen(nav, current)
                    is Dest.Mood -> MoodScreen(nav, current)
                }
            }
        }
    }
}

@Composable
private fun TopBar(nav: TvNav, current: Dest, player: TvPlayer) {
    val song by player.currentSong.collectAsState()
    val playing by player.isPlaying.collectAsState()
    val account by AccountStore.account.collectAsState()
    val root = nav.stack.first()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("OpenTune", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tabs.forEach { tab ->
                TvPill(
                    label = when (tab) { Dest.Home -> "Home"; Dest.Explore -> "Explore"; Dest.Library -> "Library"; else -> "Now Playing" },
                    selected = tab == root && current !is Dest.NowPlaying || tab == current,
                    onClick = { if (tab == Dest.NowPlaying) nav.open(tab) else nav.goTab(tab) },
                )
            }
            TvPill("Search", selected = root == Dest.Search, icon = Icons.Rounded.Search, onClick = { nav.goTab(Dest.Search) })
            TvPill("Settings", selected = root == Dest.Settings, icon = Icons.Rounded.Settings, onClick = { nav.goTab(Dest.Settings) })
        }
        Spacer(Modifier.weight(1f))
        // What's playing, small, so a song keeps its place while you browse; OK opens the player.
        song?.let { s ->
            TvSurface(
                onClick = { nav.open(Dest.NowPlaying) },
                shape = CircleShape,
                container = Color.White.copy(alpha = 0.10f),
                focusedContainer = Color.White.copy(alpha = 0.28f),
                modifier = Modifier.widthIn(max = 380.dp),
            ) { _ ->
                Row(Modifier.padding(start = 6.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(s.thumbnailUrl, Modifier.size(44.dp), CircleShape)
                    Column(Modifier.padding(start = 12.dp).weight(1f, fill = false)) {
                        Text(s.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                        tint = TvColors.Text, modifier = Modifier.padding(start = 12.dp).size(26.dp),
                    )
                }
            }
        }
        account?.thumbnailUrl?.let { photo ->
            Cover(photo, Modifier.padding(start = 16.dp).size(44.dp).clip(CircleShape), CircleShape)
        }
    }
}

