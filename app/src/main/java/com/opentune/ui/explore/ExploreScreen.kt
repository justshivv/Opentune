package com.opentune.ui.explore

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.UiState
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.home.HomeShelfView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.pressable
import kotlinx.coroutines.sync.withPermit
import com.opentune.ui.rememberLoader
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(contentPadding: PaddingValues, onMoodClick: (MoodGenre) -> Unit) {
    val loader = rememberLoader("explore") { MusicRepository.moodsAndGenres() }
    val state by loader.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        PageHeader("Explore")
        when (val s = state) {
            is UiState.Loading -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(12) { Placeholder(Modifier.fillMaxWidth().height(112.dp), RoundedCornerShape(18.dp)) }
            }
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                ErrorState(s.message, onRetry = { loader.reload() })
            }
            is UiState.Success -> if (s.data.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                    MessageState(Icons.Filled.Explore, "Nothing to explore", message = "YouTube Music didn't send any moods or genres.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    s.data.forEach { section ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(section.items, key = { "${section.title}:${it.browseId}:${it.params}" }) { mood ->
                            MoodTile(mood, onClick = { onMoodClick(mood) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Covers for mood tiles, fetched lazily (the first playlist on each mood's
 * page), a few at a time, and kept for the session.
 */
private object MoodCovers {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val gate = kotlinx.coroutines.sync.Semaphore(3)

    fun cached(mood: MoodGenre): String? = mood.thumbnailUrl ?: cache[mood.browseId + mood.params]

    suspend fun load(mood: MoodGenre): String? {
        cached(mood)?.let { return it }
        return gate.withPermit {
            runCatching {
                MusicRepository.shelves(mood.browseId, mood.params)
                    .firstNotNullOfOrNull { shelf -> shelf.items.firstNotNullOfOrNull { it.thumbnailUrl } }
            }.getOrNull()?.also { cache[mood.browseId + mood.params] = it }
        }
    }
}

/**
 * A colored card with the mood's name and a tilted cover poking out of the
 * corner. The hue comes from the title, so each mood keeps its color.
 */
@Composable
private fun MoodTile(mood: MoodGenre, onClick: () -> Unit) {
    val hue = (mood.title.hashCode().absoluteValue % 360).toFloat()
    val top = Color.hsv(hue, 0.62f, 0.62f)
    val bottom = Color.hsv((hue + 18f) % 360f, 0.70f, 0.42f)
    var cover by remember(mood) { mutableStateOf(MoodCovers.cached(mood)) }
    LaunchedEffect(mood) { if (cover == null) cover = MoodCovers.load(mood) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(top, bottom))),
    ) {
        Text(
            mood.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp, end = 70.dp),
        )
        AnimatedVisibility(
            cover != null,
            enter = fadeIn(tween(400)) + scaleIn(tween(400), 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Artwork(
                cover.artworkAt(CARD_ART_PX),
                Modifier
                    .offset(x = 14.dp, y = 14.dp)
                    .size(86.dp)
                    .graphicsLayer { rotationZ = 22f }
                    .shadow(10.dp, RoundedCornerShape(10.dp)),
                RoundedCornerShape(10.dp),
            )
        }
    }
}

/** A mood or genre's own page: shelves of playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodScreen(
    title: String,
    browseId: String,
    params: String?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
) {
    val loader = rememberLoader("mood:$browseId:$params") { MusicRepository.shelves(browseId, params) }
    val state by loader.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack = onBack)
        when (val s = state) {
            is UiState.Loading -> LazyColumn { items(3) { ShelfPlaceholder() } }
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                ErrorState(s.message, onRetry = { loader.reload() })
            }
            is UiState.Success -> LazyColumn(contentPadding = contentPadding) {
                items(s.data) { HomeShelfView(it, onItemClick) }
            }
        }
    }
}
