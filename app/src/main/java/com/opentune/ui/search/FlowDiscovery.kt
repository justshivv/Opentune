package com.opentune.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.ui.components.FlowCollectionTile

/** These are search entry points, not fabricated recommendations or playlists. */
@Composable
internal fun FlowDiscovery(onSearch: (String) -> Unit, onRecognize: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Start with a feeling", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCollectionTile(Icons.Rounded.Headphones, "Deep focus", "Instrumental & ambient", { onSearch("instrumental ambient focus") }, Modifier.weight(1f), accent = true)
            FlowCollectionTile(Icons.Rounded.NightsStay, "After hours", "Jazz & late-night sounds", { onSearch("late night jazz") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCollectionTile(Icons.Rounded.Bolt, "More energy", "Dance & electronic", { onSearch("dance electronic") }, Modifier.weight(1f))
            FlowCollectionTile(Icons.Rounded.WbSunny, "Slow mornings", "Acoustic & easy listening", { onSearch("acoustic morning") }, Modifier.weight(1f))
        }
        if (onRecognize != null) OutlinedButton(onClick = onRecognize, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Name a song playing nearby")
        }
    }
}
