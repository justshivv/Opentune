package com.opentune.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.sponsorblock.SponsorBlock

/** Which SponsorBlock categories are skipped. */
@Composable
fun SponsorBlockDialog(onDismiss: () -> Unit) {
    val pb by AppSettings.playback.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip in music videos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Parts of YouTube videos that SponsorBlock users have marked. Songs from the catalogue rarely have any; music videos often do.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SponsorBlock.CATEGORIES.forEach { (key, category) ->
                    val on = key in pb.sponsorBlockCategories
                    fun toggle() = AppSettings.updatePlayback {
                        it.copy(sponsorBlockCategories = if (on) it.sponsorBlockCategories - key else it.sponsorBlockCategories + key)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = { toggle() })
                        Text(category.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    "Segments from sponsor.ajay.app, CC BY-NC-SA 4.0. Only a short hash of the video id is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
