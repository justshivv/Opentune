package com.opentune.ui.explore

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialkolor.ktx.harmonize
import com.opentune.data.MusicRepository
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.UiState
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.home.HomeShelfView
import com.opentune.ui.rememberLoader
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(contentPadding: PaddingValues, onMoodClick: (MoodGenre) -> Unit) {
    val loader = rememberLoader("explore") { MusicRepository.moodsAndGenres() }
    val state by loader.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Explore", style = MaterialTheme.typography.headlineSmall) })
        when (val s = state) {
            is UiState.Loading -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(12) { Placeholder(Modifier.fillMaxWidth().height(64.dp), MaterialTheme.shapes.medium) }
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
                    columns = GridCells.Adaptive(160.dp),
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
                        items(section.items) { mood ->
                            MoodTile(mood, onClick = { onMoodClick(mood) })
                        }
                    }
                }
            }
        }
    }
}

/** A colored tile with a stripe down its edge, hue picked from the title so it's stable. */
@Composable
private fun MoodTile(mood: MoodGenre, onClick: () -> Unit) {
    val hue = (mood.title.hashCode().absoluteValue % 360).toFloat()
    val accent = Color.hsv(hue, 0.55f, 0.85f).harmonize(MaterialTheme.colorScheme.primary, true)
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        Text(
            mood.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 12.dp),
        )
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
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
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
