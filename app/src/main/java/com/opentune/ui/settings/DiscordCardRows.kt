package com.opentune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.delay

/**
 * How the Discord card reads, as the web player offers it, as rows on the Discord section of
 * Settings: the activity it heads with ("Listening to", "Playing", "Watching", "Competing in"),
 * its name, the status set while it shows, what member lists show, and its two buttons. Each
 * change shows on the card at once. They can be set before an account is connected.
 */
private fun discordSet(change: (DiscordRpc.Options) -> DiscordRpc.Options) = DiscordRpc.setOptions(change(DiscordRpc.options.value))

@Composable
private fun discordOptions(): DiscordRpc.Options = DiscordRpc.options.collectAsState().value

@Composable
fun DiscordActivityRow() {
    val o = discordOptions()
    Choice("Activity", DiscordRpc.ActivityType.entries, o.activityType, { it.label }, summary = "How the card is headed: ${o.activityType.label} ${o.activityName.ifBlank { "OpenTune" }}") { v ->
        discordSet { it.copy(activityType = v) }
    }
}

@Composable
fun DiscordNameRow() {
    val o = discordOptions()
    TextSetting("Name", "OpenTune", o.activityName, 128) { v -> discordSet { it.copy(activityName = v) } }
}

@Composable
fun DiscordStatusRow() {
    val o = discordOptions()
    Choice("Status", DiscordRpc.OnlineStatus.entries, o.status, { it.label }, summary = "Your Discord status while the card shows") { v -> discordSet { it.copy(status = v) } }
}

@Composable
fun DiscordLeadRow() {
    val o = discordOptions()
    Choice("Member lists show", DiscordRpc.Lead.entries, o.lead, { it.label }, summary = "The line under your name in server member lists") { v -> discordSet { it.copy(lead = v) } }
}

@Composable
fun DiscordListenButtonRows() {
    val o = discordOptions()
    ToggleRow("Listen button", o.button1Visible, { v -> discordSet { it.copy(button1Visible = v) } }, summary = "Opens the song on YouTube Music")
    if (o.button1Visible) TextSetting("Listen button text", "Listen on YouTube Music", o.button1Text, 32) { v -> discordSet { it.copy(button1Text = v) } }
}

@Composable
fun DiscordOpenTuneButtonRows() {
    val o = discordOptions()
    ToggleRow("OpenTune button", o.button2Visible, { v -> discordSet { it.copy(button2Visible = v) } }, summary = "Opens OpenTune's page, or the web address you give as its text")
    if (o.button2Visible) TextSetting("OpenTune button text", "Get OpenTune", o.button2Text, 32) { v -> discordSet { it.copy(button2Text = v) } }
}

@Composable
fun DiscordCardNote() {
    Text(
        "Button text can use {song_name}, {artist_name} and {album_name}, up to 32 characters: Discord drops a card with a longer label. " +
            "A web address as the OpenTune button's text makes the button open it. This uses your Discord account's own sign-in, which Discord's terms don't allow.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** A row whose value opens a short menu of the choices. */
@Composable
private fun <T> Choice(title: String, choices: List<T>, selected: T, label: (T) -> String, summary: String? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(title, summary = summary, onClick = { open = true }, trailing = {
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
