package com.opentune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.data.subsonic.Subsonic

/** Library › Your server: the albums on your Subsonic server, newest, most played or at random. */
@Composable
internal fun ServerShelf() {
    var order by remember { mutableStateOf(ORDERS.first()) }
    var albums by remember(order) { mutableStateOf<List<Subsonic.Album>?>(null) }
    var failed by remember(order) { mutableStateOf(false) }
    var open by remember { mutableStateOf<Subsonic.Album?>(null) }
    LaunchedEffect(order) {
        runCatching { Subsonic.albums(order.first, 120) }.onSuccess { albums = it }.onFailure { failed = true }
    }
    open?.let { a -> return ServerAlbum(a, onBack = { open = null }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ORDERS.forEach { o -> FilterChip(order == o, { order = o }, label = { Text(o.second) }) }
        }
        val found = albums
        when {
            failed -> Message("Couldn't reach your server", "Check it's running and the address in Settings.")
            found == null -> Loading()
            found.isEmpty() -> Message("No albums on the server", null)
            else -> LazyVerticalGrid(
                GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = LocalPageBottom.current),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(found, key = { it.id }) { a ->
                    Column(Modifier.clip(MaterialTheme.shapes.medium).clickable { open = a }) {
                        Cover(a.cover, 170.dp, MaterialTheme.shapes.medium, Modifier.fillMaxWidth())
                        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            listOfNotNull(a.artist, a.year?.toString()).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val ORDERS = listOf("newest" to "Newest", "frequent" to "Most played", "alphabeticalByName" to "A to Z", "random" to "Random")

@Composable
private fun ServerAlbum(album: Subsonic.Album, onBack: () -> Unit) {
    var songs by remember(album.id) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(album.id) { mutableStateOf(false) }
    LaunchedEffect(album.id) {
        runCatching { Subsonic.album(album.id).second }.onSuccess { songs = it }.onFailure { failed = true }
    }
    val found = songs
    Column(Modifier.fillMaxSize()) {
        GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(album.cover, 132.dp, MaterialTheme.shapes.medium)
            Column(Modifier.padding(start = 20.dp).width(520.dp)) {
                Text(album.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (!found.isNullOrEmpty()) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Play", Icons.Rounded.PlayArrow) { Player.playAll(found, 0) }
                        IconButton(onClick = { Player.playAll(found.shuffled(), 0) }) { Icon(Icons.Rounded.Shuffle, "Shuffle") }
                    }
                }
            }
        }
        when {
            failed -> Message("Couldn't open ${album.name}", "Check your server is running.")
            found == null -> Loading()
            else -> SongList(found) { s, i -> Player.playAll(s, i) }
        }
    }
}
