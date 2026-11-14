package com.opentune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.opentune.data.discord.DiscordRpc
import com.opentune.ui.components.FIELD_TINT
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay

/**
 * How the Discord card reads, as the web player offers it: the activity it heads with, its
 * name, the status set while it shows, what member lists show, and its two buttons.
 */
@Composable
fun DiscordCardSheet(onDismiss: () -> Unit) {
    val options by DiscordRpc.options.collectAsState()
    fun set(change: (DiscordRpc.Options) -> DiscordRpc.Options) = DiscordRpc.setOptions(change(DiscordRpc.options.value))
    FloatingCard(
        onDismiss = onDismiss,
        title = "Discord card",
        subtitle = "How what you play shows on your profile",
        expandable = true,
        startExpanded = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Choice("Activity", DiscordRpc.ActivityType.entries, options.activityType, { it.label }) { v -> set { it.copy(activityType = v) } }
            TextSetting("Name", "OpenTune", options.activityName, 128) { v -> set { it.copy(activityName = v) } }
            Choice("Status", DiscordRpc.OnlineStatus.entries, options.status, { it.label }) { v -> set { it.copy(status = v) } }
            Choice("Member lists show", DiscordRpc.Lead.entries, options.lead, { it.label }) { v -> set { it.copy(lead = v) } }
            ToggleRow("Listen button", options.button1Visible, { v -> set { it.copy(button1Visible = v) } }, summary = "Opens the song on YouTube Music")
            if (options.button1Visible) TextSetting("Listen button text", "Listen on YouTube Music", options.button1Text, 32) { v -> set { it.copy(button1Text = v) } }
            ToggleRow("OpenTune button", options.button2Visible, { v -> set { it.copy(button2Visible = v) } }, summary = "Opens OpenTune's page, or the web address you give as its text")
            if (options.button2Visible) TextSetting("OpenTune button text", "Get OpenTune", options.button2Text, 32) { v -> set { it.copy(button2Text = v) } }
            Text(
                "Button text can use {song_name}, {artist_name} and {album_name}, up to 32 characters: Discord drops a card with a longer label. " +
                    "A web address as the OpenTune button's text makes the button open it. This uses your Discord account's own sign-in, which Discord's terms don't allow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** A row whose value opens a short menu of the choices. */
@Composable
private fun <T> Choice(title: String, choices: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(title, onClick = { open = true }, trailing = {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(selected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(label(c)) }, onClick = { open = false; if (c != selected) onPick(c) })
            }
        }
    }
}

/**
 * A text setting, kept a moment after typing stops: each change resends the card, and Discord
 * only takes a few of those in a short while.
 */
@Composable
private fun TextSetting(title: String, placeholder: String, value: String, max: Int, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(700)
        onSave(text)
    }
    val c = MaterialTheme.colorScheme
    SettingRow(title, below = {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                .background(c.onSurface.copy(alpha = FIELD_TINT)).padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            BasicTextField(
                text, { text = it.take(max) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth(),
            )
        }
    })
}
