package com.opentune.ui.browse

import com.opentune.ui.components.GlassPage
import com.opentune.ui.components.LocalHazeState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HEADER_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassBackButton
import com.opentune.ui.components.glass
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.rememberLoader

/** Callbacks every track list needs. */
@androidx.compose.runtime.Immutable
class SongActions(
    val currentVideoId: String?,
    val isPlaying: Boolean,
    /** songs, start index, shuffle, "Playing from" label. */
    val playAll: (List<Song>, Int, Boolean, String?) -> Unit,
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    /** Shuffle with similar songs mixed in: a long press on Shuffle. */
    val smartShuffle: (List<Song>, String?) -> Unit = { songs, source -> playAll(songs, 0, true, source) },
    val pause: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsingBar(
    title: String,
    listState: LazyListState,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    // Once the header scrolls away the bar frosts in: the list under it is
    // blurred and washed with the page colour, and the frost thins out over
    // a short tail below the bar instead of stopping at a hard line.
    val frost by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, androidx.compose.animation.core.tween(260), label = "frost")
    val haze = LocalHazeState.current
    val page = MaterialTheme.colorScheme.background
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var tall by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box {
        if (frost > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        // Drawn taller than the bar by the tail; the bar's own size is unchanged.
                        val h = constraints.maxHeight + FROST_TAIL.roundToPx()
                        val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                    }
                    .onSizeChanged { tall = it.height }
                    .then(
                        if (haze == null) {
                            Modifier.graphicsLayer { alpha = frost }.drawBehind {
                                drawRect(Brush.verticalGradient(0f to page.copy(alpha = 0.96f), 0.7f to page.copy(alpha = 0.9f), 1f to Color.Transparent))
                            }
                        } else {
                            Modifier.hazeEffect(haze) {
                                alpha = frost
                                backgroundColor = page
                                tints = listOf(HazeTint(page.copy(alpha = 0.6f)))
                                blurRadius = 26.dp
                                noiseFactor = 0.03f
                                progressive = HazeProgressive.verticalGradient(
                                    easing = androidx.compose.animation.core.EaseIn,
                                    startY = tall - FROST_TAIL.value * density * 1.6f,
                                    startIntensity = 1f,
                                    endY = tall.toFloat(),
                                    endIntensity = 0f,
                                )
                            }
                        },
                    ),
            )
        }
        TopAppBar(
            title = {
                androidx.compose.animation.AnimatedVisibility(
                    collapsed,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
                    exit = androidx.compose.animation.fadeOut(),
                ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            navigationIcon = { GlassBackButton(onBack, Modifier.padding(start = 8.dp).size(48.dp)) },
            actions = {
                actions()
                Spacer(Modifier.width(8.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

/** How far below the collapsed bar its frost reaches as it fades out. */
private val FROST_TAIL = 22.dp

@Composable
private fun PlayShuffleButtons(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, onSmartShuffle: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        ShuffleButton(onShuffle, onSmartShuffle, Modifier.weight(1f), enabled = enabled)
    }
}

/**
 * A list's Shuffle button; a long press starts smart shuffle, with similar
 * songs mixed in. The player's shuffle button cycles through a third state
 * instead, but this one starts a queue rather than holding a state, so a
 * second tap would have nothing to move on to. [compact] is the small round
 * glass button.
 */
@Composable
internal fun ShuffleButton(onShuffle: () -> Unit, onSmartShuffle: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    val smartAllowed = com.opentune.data.settings.AppSettings.playback.collectAsState().value.smartShuffle
    val context = androidx.compose.ui.platform.LocalContext.current
    val onLong: (() -> Unit)? = onSmartShuffle?.takeIf { smartAllowed && enabled }?.let { smart ->
        {
            android.widget.Toast.makeText(context, "Smart shuffle: similar songs mixed in", android.widget.Toast.LENGTH_SHORT).show()
            smart()
        }
    }
    val shape = if (compact) androidx.compose.foundation.shape.CircleShape else androidx.compose.material3.ButtonDefaults.filledTonalShape
    val press = Modifier.clip(shape).combinedClickable(
        enabled = enabled,
        role = androidx.compose.ui.semantics.Role.Button,
        onLongClickLabel = if (onLong != null) "Smart shuffle" else null,
        onLongClick = onLong,
        onClick = onShuffle,
    )
    if (compact) {
        Box(modifier.size(44.dp).glass(androidx.compose.foundation.shape.CircleShape).then(press), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Shuffle, "Shuffle", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        // Drawn like a FilledTonalButton, which has no long press of its own.
        val colors = MaterialTheme.colorScheme
        androidx.compose.material3.Surface(
            shape = shape,
            color = if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = 0.12f),
            contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
            modifier = modifier.height(52.dp).then(press),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shuffle, null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The cover blurred and stretched behind a page's header, fading into the
 * page below it. Before Android 12, which can't blur, a tiny copy of the
 * cover stretched to fill makes the same soft wash.
 */
@Composable
internal fun HeaderBackdrop(url: String?, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.clipToBounds()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Artwork(
                url,
                Modifier.matchParentSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f; alpha = 0.85f }.blur(70.dp),
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
        } else {
            Artwork(
                url.artworkAt(24),
                Modifier.matchParentSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f; alpha = 0.7f },
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to background.copy(alpha = 0.15f), 0.55f to background.copy(alpha = 0.45f), 1f to background),
            ),
        )
    }
}

@Composable
fun CollectionScreen(
    browseId: String,
    contentPadding: PaddingValues,
    actions: SongActions,
    onBack: () -> Unit,
) {
    val owner = com.opentune.data.library.LibraryCache.owner()
    val loader = rememberLoader("collection:$owner:$browseId", saved = {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.opentune.data.library.LibraryCache.collection(browseId, owner)?.let { it.copy(songs = com.opentune.data.ContentFilter.songs(it.songs)) }
        }
    }) { MusicRepository.collection(browseId) }
    val state by loader.state.collectAsState()
    val listState = rememberLazyListState()
    val isAlbum = MusicRepository.typeOf(browseId) == BrowseType.ALBUM
    val title = (state as? UiState.Success)?.data?.title.orEmpty()

    GlassPage(Modifier.fillMaxSize(), overlay = { CollapsingBar(title, listState, onBack) }) {
        when (val s = state) {
            is UiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorState(s.message, { loader.reload() }) }
            else -> {
                val c = (s as? UiState.Success)?.data
                LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                    item(key = "header") {
                        Box {
                            HeaderBackdrop(c?.thumbnailUrl.artworkAt(HEADER_ART_PX), Modifier.matchParentSize())
                            Column(
                                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp, start = 24.dp, end = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (c == null) {
                                    Placeholder(Modifier.size(232.dp), MaterialTheme.shapes.large)
                                    Placeholder(Modifier.padding(top = 20.dp).width(200.dp).height(24.dp))
                                    Placeholder(Modifier.padding(top = 8.dp).width(140.dp).height(16.dp))
                                } else {
                                    Artwork(
                                        c.thumbnailUrl.artworkAt(HEADER_ART_PX),
                                        Modifier.size(232.dp).shadow(24.dp, MaterialTheme.shapes.large),
                                        shape = MaterialTheme.shapes.large,
                                        placeholder = Icons.Filled.Album,
                                    )
                                    Text(
                                        c.title,
                                        style = MaterialTheme.typography.headlineSmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 20.dp),
                                    )
                                    // An album with any explicit track carries the mark, as YouTube Music shows it.
                                    if (isAlbum && c.songs.any { it.isExplicit == true }) {
                                        com.opentune.ui.components.SubtitleLine(
                                            listOf(c.subtitle, "${c.songs.size} songs").filter { it.isNotBlank() }.joinToString(" • "),
                                            explicit = true,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    } else {
                                        Text(
                                            listOf(c.subtitle, "${c.songs.size} songs").filter { it.isNotBlank() }.joinToString(" • "),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                                PlayShuffleButtons(
                                    enabled = !c?.songs.isNullOrEmpty(),
                                    onPlay = { c?.let { actions.playAll(it.songs, 0, false, it.title) } },
                                    onShuffle = { c?.let { actions.playAll(it.songs, 0, true, it.title) } },
                                    onSmartShuffle = { c?.let { actions.smartShuffle(it.songs, it.title) } },
                                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                                )
                                if (c != null) {
                                    val savedLists by com.opentune.data.library.LibraryStore.savedLists.collectAsState()
                                    val saved = savedLists.any { it.browseId == c.browseId }
                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    val own = rememberOwnPlaylist(c)
                                    com.opentune.ui.library.ListTools(
                                        c.songs, onAddSongs = null, Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                        list = com.opentune.ui.library.DownloadedListInfo(c.browseId, c.title, c.subtitle, c.thumbnailUrl),
                                        saved = saved,
                                        onSave = {
                                            com.opentune.data.library.LibraryStore.setSaved(
                                                com.opentune.data.library.SavedList(c.browseId, c.title, c.subtitle, c.thumbnailUrl), !saved,
                                            )
                                        },
                                        onShare = { own?.share() ?: com.opentune.ui.share.ListShare.shareList(context, c.title, c.browseId) },
                                        privacy = own?.privacy,
                                        onPrivacy = own?.let { o -> { o.choose() } },
                                    )
                                }
                            }
                        }
                    }
                    if (c == null) {
                        items(8) { SongRowPlaceholder() }
                    } else {
                        itemsIndexed(c.songs, key = { i, song -> "$i:${song.videoId}" }) { i, song ->
                            SongListItem(
                                song = song,
                                onClick = { actions.playAll(c.songs, i, false, c.title) },
                                isCurrent = song.videoId == actions.currentVideoId,
                                isPlaying = actions.isPlaying,
                                leading = if (isAlbum) {
                                    {
                                        Box(Modifier.size(width = 28.dp, height = 52.dp), Alignment.Center) {
                                            Text(
                                                "${i + 1}",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = if (song.videoId == actions.currentVideoId) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                onPlayNext = { actions.playNext(song) },
                                onAddToQueue = { actions.addToQueue(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}
