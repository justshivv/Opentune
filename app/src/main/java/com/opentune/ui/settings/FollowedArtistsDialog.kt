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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.releases.NewReleases
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.launch

/** The artists followed for new-release alerts, with unfollow and a check right now. */
@Composable
fun FollowedArtistsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val followed by NewReleases.followed.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Followed artists") },
        text = {
            if (followed.isEmpty()) {
                Text("Open an artist and tap \"Follow for new releases\".", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(followed, key = { it.browseId }) { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(a.thumbnailUrl, Modifier.size(40.dp), CircleShape, placeholder = Icons.Rounded.Person)
                            Spacer(Modifier.width(12.dp))
                            Text(a.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { NewReleases.unfollow(context, a.browseId) }) { Icon(Icons.Rounded.Close, "Unfollow ${a.name}") }
                        }
                    }
                    status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) } }
                }
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
        dismissButton = {
            if (followed.isNotEmpty()) {
                SheetButton("Check now", onClick = {
                    status = "Checking…"
                    scope.launch {
                        status = runCatching { NewReleases.check() }.fold(
                            { found ->
                                NewReleases.notify(context, found)
                                if (found.isEmpty()) "Nothing new since the last check." else "${found.size} new release(s); see your notifications."
                            },
                            { "Couldn't check: ${it.message}" },
                        )
                    }
                })
            }
        },
    )
}
