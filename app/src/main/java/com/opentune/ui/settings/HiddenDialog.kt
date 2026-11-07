package com.opentune.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton

/** Songs marked "Not interested" and artists marked "Don't play", each with Unhide. */
@Composable
fun HiddenDialog(onDismiss: () -> Unit) {
    val hidden by LibraryStore.hidden.collectAsState()
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden songs and artists") },
        text = {
            if (hidden.isEmpty) {
                Text(
                    "Nothing hidden. Use \"Not interested\" or \"Don't play\" in a song's menu to keep it out of autoplay, radio, smart shuffle and Home.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            "Kept out of autoplay, radio, smart shuffle and Home. They still play from your playlists and when you pick them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (hidden.artists.isNotEmpty()) {
                        item { Heading("Artists") }
                        items(hidden.artists, key = { "a:${it.key}" }) { a ->
                            HiddenRow(null, a.name, null, CircleShape, Icons.Rounded.Person) { LibraryStore.unhideArtist(a.key) }
                        }
                    }
                    if (hidden.songs.isNotEmpty()) {
                        item { Heading("Songs") }
                        items(hidden.songs, key = { "s:${it.videoId}" }) { s ->
                            HiddenRow(s.thumbnailUrl.artworkAt(ROW_ART_PX), s.title, s.artist, RoundedCornerShape(8.dp), Icons.Rounded.MusicNote) { LibraryStore.unhideSong(s.videoId) }
                        }
                    }
                }
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun HiddenRow(
    artwork: String?,
    title: String,
    subtitle: String?,
    shape: androidx.compose.ui.graphics.Shape,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    onUnhide: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, Modifier.size(40.dp), shape, placeholder = placeholder)
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onUnhide) { Text("Unhide") }
    }
}
