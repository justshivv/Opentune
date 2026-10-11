package com.opentune.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.ui.theme.LocalInterface
import com.opentune.ui.theme.designSurface

/** Real history in Home, sample data only in the explicitly labelled settings preview. */
@Composable
fun DesignFeatureCard(song: Song, onPlay: () -> Unit, modifier: Modifier = Modifier, artwork: (@Composable (Modifier) -> Unit)? = null) {
    val design = LocalInterface.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val artworkSize = if (maxWidth < 300.dp) 72.dp else if (design.compact) 84.dp else 108.dp
        Column(Modifier.fillMaxWidth().designSurface(accent = true).padding(if (design.compact) 16.dp else 20.dp)) {
            Text("CONTINUE LISTENING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val artModifier = Modifier.size(artworkSize)
                if (artwork != null) artwork(artModifier) else Artwork(song.thumbnailUrl, artModifier, MaterialTheme.shapes.medium)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                }
            }
        }
    }
}
