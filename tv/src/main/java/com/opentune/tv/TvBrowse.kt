package com.opentune.tv

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.opentune.data.ExploreFeed
import com.opentune.data.MusicRepository
import com.opentune.data.history.History
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song

/*
 * Home, Explore and a mood's page: rows of cards. Each screen loads its data and hands it to a
 * plain `…Content` composable, which tests draw with made-up data.
 */

@Composable
fun HomeScreen(nav: TvNav) {
    // Kept per account: signing in (or out) loads the account's own Home, not the one kept from before.
    val accounts by com.opentune.data.account.AccountStore.accounts.collectAsState()
    val active = accounts.firstOrNull { it.active }?.id
    val (state, retry) = rememberLoad(if (active == null) "home" else "home:$active") {
        if (homeLoadedFor != UNLOADED && active != homeLoadedFor) MusicRepository.refreshHomeRecommendations()
        homeLoadedFor = active
        MusicRepository.home()
    }
    val more by MusicRepository.homeMore.collectAsState()
    val records by History.records.collectAsState()
    val recents = remember(records) { History.recents(records, 16).distinctBy { it.videoId } }
    HomeContent(
        recents = recents, feed = state, more = more, retry = retry,
        onSong = { nav.play(it, "Home") }, onItem = { nav.openShelfItem(it, "Home") },
    )
}

/** The account Home was last loaded for, so a change of account drops the shelves made for the one before. */
private var homeLoadedFor: String? = UNLOADED
private const val UNLOADED = "\u0000"

/**
 * Home: the songs played last ("Listen again"), then YouTube Music's shelves. The recents come
 * from this TV and show at once; the shelves fill in under them.
 */
@Composable
internal fun HomeContent(
    recents: List<Song>,
    feed: Load<List<HomeShelf>>,
    more: List<HomeShelf>,
    retry: () -> Unit,
    onSong: (Song) -> Unit,
    onItem: (ShelfItem) -> Unit,
) {
    TvPage {
        if (recents.isNotEmpty()) item(key = "recents") {
            TvShelf("Listen again") {
                items(recents, key = { it.videoId }) { song ->
                    SongCard(song, { onSong(song) }, focusKey = "recents/${song.videoId}")
                }
            }
        }
        when (feed) {
            Load.Loading -> item(key = "loading") { TvLoadingState() }
            Load.Failed -> item(key = "failed") { TvErrorState(retry) }
            is Load.Done -> {
                if (feed.value.isEmpty() && more.isEmpty() && recents.isEmpty()) item(key = "empty") {
                    TvEmptyState("Nothing here yet")
                }
                shelves(feed.value, "home", onItem)
                shelves(more, "more", onItem)
            }
        }
    }
}

/** A feed's shelves, one row each, keyed so each card can be focused again on coming back. */
internal fun LazyListScope.shelves(list: List<HomeShelf>, prefix: String, onItem: (ShelfItem) -> Unit) {
    itemsIndexed(list.filter { it.items.isNotEmpty() }, key = { i, s -> "$prefix:$i:${s.title}" }) { i, shelf ->
        val shelfKey = "$prefix:$i"
        TvShelf(shelf.title, subtitle = shelf.subtitle.takeIf { it.isNotBlank() }) {
            itemsIndexed(shelf.items, key = { j, item -> "$j:${item.browseId ?: item.videoId}" }) { j, item ->
                ShelfCard(item, { onItem(item) }, focusKey = "$shelfKey/$j:${item.browseId ?: item.videoId}")
            }
        }
    }
}

@Composable
fun ExploreScreen(nav: TvNav) {
    val (state, retry) = rememberLoad("explore") { ExploreFeed.load() }
    ExploreContent(
        canvas = state, retry = retry,
        onArtist = { nav.open(Dest.Artist(it.browseId, it.name)) },
        onMood = { nav.open(Dest.Mood(it.browseId, it.params, it.title)) },
        onItem = { nav.openShelfItem(it, "Explore") },
    )
}

/** The order Explore's rows come in: what's new first, then the charts, then moods and genres. */
private val ExploreOrder = listOf(ExploreFeed.Kind.NEW, ExploreFeed.Kind.CHART, ExploreFeed.Kind.MOOD, ExploreFeed.Kind.GENRE)

/**
 * Explore: artists to jump to (round faces), new releases and charts (covers), then moods and
 * genres as plain coloured tiles, which need no images at all.
 */
@Composable
internal fun ExploreContent(
    canvas: Load<ExploreFeed.Canvas>,
    retry: () -> Unit,
    onArtist: (ExploreFeed.Artist) -> Unit,
    onMood: (com.opentune.data.model.MoodGenre) -> Unit,
    onItem: (ShelfItem) -> Unit,
) {
    TvPage {
        when (canvas) {
            Load.Loading -> item(key = "loading") { TvLoadingState() }
            Load.Failed -> item(key = "failed") { TvErrorState(retry) }
            is Load.Done -> {
                val c = canvas.value
                if (c.artists.isEmpty() && c.tiles.isEmpty()) item(key = "empty") { TvEmptyState("Nothing here yet") }
                if (c.artists.isNotEmpty()) item(key = "artists") {
                    TvShelf("Artists") {
                        items(c.artists, key = { it.browseId }) { a ->
                            TvCard(a.name, null, a.thumbnailUrl, { onArtist(a) }, kind = TvCardKind.Round, focusKey = "artists/${a.browseId}")
                        }
                    }
                }
                ExploreOrder.forEach { kind ->
                    val tiles = c.tiles.filter { it.kind == kind }
                    if (tiles.isNotEmpty()) item(key = "kind:$kind") {
                        TvShelf(kind.label) {
                            items(tiles, key = { it.key }) { tile ->
                                val focusKey = "$kind/${tile.key}"
                                when (tile) {
                                    is ExploreFeed.Tile.Mood -> TvTextTile(tile.mood.title, { onMood(tile.mood) }, focusKey = focusKey)
                                    is ExploreFeed.Tile.Item -> ShelfCard(tile.item, { onItem(tile.item) }, focusKey = focusKey)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A mood or genre: the shelves YouTube Music keeps for it, under its name. */
@Composable
fun MoodScreen(nav: TvNav, mood: Dest.Mood) {
    val (state, retry) = rememberLoad(mood) { MusicRepository.shelves(mood.browseId, mood.params) }
    TvPage(title = mood.title) {
        when (val s = state) {
            Load.Loading -> item(key = "loading") { TvLoadingState() }
            Load.Failed -> item(key = "failed") { TvErrorState(retry) }
            is Load.Done -> shelves(s.value, "mood") { nav.openShelfItem(it, mood.title) }
        }
    }
}
