package com.opentune.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.opentune.data.Ringtones
import com.opentune.data.model.Song
import kotlinx.coroutines.launch

/**
 * Sets [song] as the ringtone, notification sound or alarm. Asks for
 * "Modify system settings" the first time, which Android grants on its own
 * settings page, and finishes the job when the user comes back.
 */
@Composable
fun RingtoneDialog(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(Ringtones.Kind.RINGTONE) }
    var busy by remember { mutableStateOf(false) }

    fun apply() {
        busy = true
        scope.launch {
            val result = runCatching { Ringtones.set(context, song, kind) }
            busy = false
            Toast.makeText(
                context,
                result.fold({ "${song.title} is your ${kind.label.lowercase()}" }, { "Couldn't set it: ${it.message}" }),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) apply() else Toast.makeText(context, "Storage access is needed to save the ringtone", Toast.LENGTH_SHORT).show()
    }
    fun proceed() {
        if (Ringtones.needsStoragePermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            apply()
        }
    }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Ringtones.allowed(context)) proceed()
        else Toast.makeText(context, "OpenTune needs \"Modify system settings\" to change the ringtone", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use as") },
        text = {
            Column {
                Ringtones.Kind.entries.forEach { k ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { kind = k }.padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Text(k.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (!Ringtones.allowed(context)) {
                    Text(
                        "Android will ask you to allow OpenTune to modify system settings. That's what lets an app change the default sounds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (Ringtones.allowed(context)) {
                    proceed()
                } else {
                    systemSettings.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()))
                }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
