package com.opentune.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Close
import com.opentune.data.config.RemoteBanner

@Composable
internal fun RemoteBannerCard(banner: RemoteBanner) {
    val links = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(banner.title, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                // Closed for good; a new banner shows again.
                androidx.compose.material3.IconButton(onClick = { com.opentune.data.config.HomeDismissals.dismissBanner(banner.id) }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Dismiss")
                }
            }
            if (banner.body.isNotBlank()) Text(banner.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { runCatching { links.openUri(banner.url) } }, modifier = Modifier.padding(end = 12.dp)) { Text(banner.action) }
        }
    }
}
