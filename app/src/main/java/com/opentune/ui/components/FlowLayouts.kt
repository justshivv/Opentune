package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song

/** Flow's artwork-led listening card, shared by Home and the live design preview. */
@Composable
fun FlowListeningCard(song: Song, onPlay: () -> Unit, modifier: Modifier = Modifier, artwork: (@Composable (Modifier) -> Unit)? = null, actionLabel: String = "Play") {
    Box(modifier.fillMaxWidth().heightIn(min = 280.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer)) {
        val art = Modifier.matchParentSize()
        if (artwork != null) artwork(art) else Artwork(song.thumbnailUrl, art, RoundedCornerShape(32.dp))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .05f), Color.Black.copy(alpha = .88f)))))
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Surface(color = Color.Black.copy(alpha = .35f), contentColor = Color.White, shape = CircleShape) {
                Text("YOUR NEXT LISTEN", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(88.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(58.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(if (actionLabel == "Play") Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.ArrowForward, actionLabel, Modifier.size(30.dp))
                }
            }
        }
    }
}

/** A responsive collection tile: the entire tile is a single accessible target. */
@Composable
fun FlowCollectionTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(if (accent) colors.primaryContainer else colors.surfaceContainerHigh)
        .clickable(onClick = onClick).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = if (accent) colors.onPrimaryContainer else colors.primary)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
