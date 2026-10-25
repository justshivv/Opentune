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
import com.opentune.data.config.RemoteBanner

@Composable
internal fun RemoteBannerCard(banner: RemoteBanner) {
    val links = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(banner.title, style = MaterialTheme.typography.titleLarge)
            if (banner.body.isNotBlank()) Text(banner.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { runCatching { links.openUri(banner.url) } }) { Text(banner.action) }
        }
    }
}
