package com.opentune.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork

/** Real listening history, with an explicit action rather than an ambiguous tappable hero. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RecentListeningCard(song: Song, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(colors.secondaryContainer, colors.surfaceContainerHigh)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Back in rotation",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSecondaryContainer,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(song.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(76.dp), MaterialTheme.shapes.medium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSecondaryContainer)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSecondaryContainer)
            }
        }
        // Wrap instead of shrinking action labels on narrow phones or with large text.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Text("Play again", Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(
                onClick = onShuffle,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                ),
            ) {
                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                Text("Shuffle recents", Modifier.padding(start = 8.dp))
            }
        }
    }
}
