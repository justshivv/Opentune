package com.opentune.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.radio.Radio
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What the list under the chips shows. */
private sealed interface RadioView {
    data object Favourites : RadioView
    data object NearYou : RadioView
    data object Popular : RadioView
    data class Genre(val tag: String) : RadioView
}

private sealed interface Load {
    data object Loading : Load
    data class Done(val stations: List<Radio.Station>) : Load
    data class Failed(val message: String) : Load
}

/**
 * Internet radio: favourites, stations near you, the most played worldwide,
 * genres and search, from Radio Browser's directory.
 */
@Composable
fun RadioScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val favourites by Radio.favourites.collectAsState()
    val recent by Radio.recent.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var view by remember { mutableStateOf<RadioView>(if (favourites.isNotEmpty()) RadioView.Favourites else RadioView.NearYou) }
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var attempt by remember { mutableStateOf(0) }
    val country = remember { Locale.getDefault().country.takeIf { it.length == 2 } }

    LaunchedEffect(query, view, attempt) {
        val q = query.trim()
        if (q.isEmpty() && view == RadioView.Favourites) return@LaunchedEffect
        load = Load.Loading
        if (q.isNotEmpty()) delay(350)
        load = try {
            Load.Done(
                when {
                    q.isNotEmpty() -> Radio.search(q)
                    view == RadioView.NearYou && country != null -> Radio.inCountry(country)
                    view is RadioView.Genre -> Radio.byTag((view as RadioView.Genre).tag)
                    else -> Radio.popular()
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "No connection")
        }
    }

    fun play(station: Radio.Station) {
        Radio.played(station)
        actions.playAll(listOf(station.toSong()), 0, false, "Radio")
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Radio", onBack = onBack) }
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search 50,000 stations") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (query.isBlank()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(view == RadioView.Favourites, { view = RadioView.Favourites }, { Text("Favourites") }) }
                    if (country != null) item { FilterChip(view == RadioView.NearYou, { view = RadioView.NearYou }, { Text("Near you") }) }
                    item { FilterChip(view == RadioView.Popular, { view = RadioView.Popular }, { Text("Popular") }) }
                    items(Radio.GENRES) { tag ->
                        FilterChip(view == RadioView.Genre(tag), { view = RadioView.Genre(tag) }, { Text(tag.replaceFirstChar { it.titlecase() }) })
                    }
                }
            }
        }

        if (query.isBlank() && view == RadioView.Favourites) {
            if (favourites.isEmpty() && recent.isEmpty()) {
                item {
                    MessageState(
                        Icons.Rounded.Radio,
                        "No favourite stations yet",
                        message = "Tap the heart on a station to keep it here.",
                    )
                }
            }
            if (favourites.isNotEmpty()) {
                items(favourites, key = { "f:${it.uuid}" }) { StationRow(it, actions.currentVideoId == it.id, ::play) }
            }
            val others = recent.filterNot { r -> favourites.any { it.uuid == r.uuid } }
            if (others.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                items(others, key = { "r:${it.uuid}" }) { StationRow(it, actions.currentVideoId == it.id, ::play) }
            }
        } else {
            when (val l = load) {
                Load.Loading -> item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                is Load.Failed -> item { ErrorState(l.message, onRetry = { attempt++ }) }
                is Load.Done -> {
                    if (l.stations.isEmpty()) item { MessageState(Icons.Rounded.Radio, "No stations found") }
                    items(l.stations, key = { it.uuid }) { StationRow(it, actions.currentVideoId == it.id, ::play) }
                }
            }
        }
        item {
            Text(
                "Stations from radio-browser.info, a free, community-kept directory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun StationRow(station: Radio.Station, playing: Boolean, onPlay: (Radio.Station) -> Unit) {
    val favourites by Radio.favourites.collectAsState()
    val favourite = favourites.any { it.uuid == station.uuid }
    Row(
        Modifier.fillMaxWidth().clickable { onPlay(station) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(station.favicon, Modifier.size(52.dp), RoundedCornerShape(12.dp), placeholder = Icons.Rounded.Radio)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                station.details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { Radio.setFavourite(station, !favourite) }) {
            Icon(
                if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favourite) "Remove from favourites" else "Add to favourites",
                tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
