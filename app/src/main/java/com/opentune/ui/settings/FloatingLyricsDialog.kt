package com.opentune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.opentune.data.settings.AppSettings
import com.opentune.lyrics.FloatingLyrics
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text

/**
 * Before floating lyrics are turned on: what they are, and why Android has to be asked to
 * let the app display over other apps. The button opens that page in the phone's settings;
 * coming back with it allowed turns them on, and coming back without it says they stay off.
 */
@Composable
internal fun FloatingLyricsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    var declined by remember { mutableStateOf(false) }
    // Each time the app comes back to the front; only once the settings page has been opened.
    LifecycleResumeEffect(Unit) {
        if (asked) {
            if (FloatingLyrics.allowed(context)) {
                AppSettings.updateLyrics { it.copy(floating = true) }
                onDismiss()
            } else {
                declined = true
            }
        }
        onPauseOrDispose { }
    }
    FloatingDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(ImageVector.vectorResource(AppIcons.Lyrics), null) },
        title = { Text("Floating lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The line being sung shows in a small pill over other apps while music plays, and hides when you come back here. Tap it to open the player; drag it to the cross at the bottom to close it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Android needs your permission for this. On the next page, allow display over other apps, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (declined) {
                    Text(
                        "Display over other apps wasn't allowed, so floating lyrics stay off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            SheetButton(
                text = "Open settings",
                onClick = {
                    asked = runCatching { context.startActivity(FloatingLyrics.permissionIntent(context)) }.isSuccess
                    if (!asked) declined = true
                },
            )
        },
        dismissButton = { SheetButton(text = "Not now", onClick = onDismiss) },
    )
}
