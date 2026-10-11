package com.opentune.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.ui.components.LocalSongMenu

/** An album stage with the title above the art and a dedicated action rail. */
@Composable
internal fun FlowPlayerStage(
    song: Song, index: Int, total: Int,
    artwork: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 14.dp)) {
        Text(song.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val viewArtist = LocalSongMenu.current?.viewArtist
        Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("flow-player-artist").padding(top = 4.dp).then(if (viewArtist != null && !song.videoId.startsWith("local:")) Modifier.clickable { viewArtist(song) } else Modifier))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
            val showTrackNumber = maxHeight >= 220.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cover renderers are square and width-led. Give them a real square that
                // fits both axes, so their aspect ratio never escapes a short stage.
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    val side = minOf(maxWidth, maxHeight)
                    artwork(Modifier.size(side).align(Alignment.Center).testTag("flow-player-artwork"))
                }
                Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showTrackNumber) {
                        Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        Text("of $total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                    actions()
                }
            }
        }
    }
}
