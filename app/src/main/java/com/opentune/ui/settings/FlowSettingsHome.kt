package com.opentune.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.FlowCollectionTile
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.NavRow

@Composable
internal fun FlowSettingsPersonalization(onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Make it yours", style = MaterialTheme.typography.headlineMedium)
        Text("Your space. Your sound. Your rhythm.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCollectionTile(Icons.Rounded.Palette, "Look and feel", "Design, colour & glass", { onOpen("look") }, Modifier.weight(1f), accent = true)
            FlowCollectionTile(Icons.Rounded.Animation, "Motion", "Dock, pages & transitions", { onOpen("motion") }, Modifier.weight(1f))
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            NavRow("Player", { onOpen("player") }, summary = "Layouts, cover art & controls", icon = Icons.Rounded.Album)
        }
    }
}
