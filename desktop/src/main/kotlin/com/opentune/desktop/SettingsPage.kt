package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.opentune.core.BuildConfig
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqPreset
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.launch

/** The phone's settings that mean something on a computer, saved in the same form. */
@Composable
internal fun SettingsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = LocalPageBottom.current)) {
        PageTitle("Settings")
        Column(Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AppearanceSection()
            PlaybackSection()
            SourcesSection()
            EqualizerSection()
            LyricsSection()
            ServerSection()
            Section("About") {
                Text("OpenTune ${BuildConfig.VERSION_NAME} for computers", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Settings and playlists are kept in ${appContext.getFilesDir().parentFile.absolutePath}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun PlaybackSection() {
    val playback by AppSettings.playback.collectAsState()
    val sound by AppSettings.sound.collectAsState()
    val sleep by Player.sleep.collectAsState()
    Section("Playback") {
        SwitchRow("Autoplay", "When the queue ends, keep going with similar songs", playback.autoplay, AppSettings::setAutoplay)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(120.dp))
            Slider(
                sound.speed, { v -> AppSettings.updateSound { it.copy(speed = (v * 20).toInt() / 20f) } },
                valueRange = 0.5f..2f, modifier = Modifier.weight(1f),
            )
            Text("%.2f×".format(sound.speed), modifier = Modifier.width(56.dp).padding(start = 8.dp))
            TextButton(onClick = { AppSettings.updateSound { it.copy(speed = 1f) } }, enabled = sound.speed != 1f) { Text("Reset") }
        }
        SleepChips(sleep)
    }
}

/** Off, a choice of minutes, or the end of the song, as the phone's sleep timer offers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepChips(sleep: Player.Sleep?) {
    Column {
        Text("Sleep timer", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilterChip(sleep == null, { Player.sleepIn(null) }, label = { Text("Off") })
            listOf(15, 30, 45, 60, 90).forEach { m ->
                FilterChip((sleep as? Player.Sleep.At)?.minutes == m, { Player.sleepIn(m) }, label = { Text("$m min") })
            }
            FilterChip(sleep == Player.Sleep.EndOfSong, { Player.sleepAtEndOfSong() }, label = { Text("End of song") })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerSection() {
    val eq by AppSettings.equalizer.collectAsState()
    Section("Equalizer") {
        SwitchRow("Equalizer", "Fifteen bands from 25 Hz to 16 kHz, with the phone's presets", eq.enabled) { on ->
            AppSettings.updateEqualizer { it.copy(enabled = on) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EqPreset.entries.forEach { p ->
                FilterChip(
                    eq.bands == p.bands,
                    { AppSettings.updateEqualizer { it.copy(enabled = true, bands = p.bands) } },
                    label = { Text(p.label) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            EQ_BANDS_HZ.forEachIndexed { i, hz ->
                Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%+.0f".format(eq.bands.getOrElse(i) { 0f }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Slider(
                            eq.bands.getOrElse(i) { 0f },
                            { v -> AppSettings.updateEqualizer { e -> e.copy(enabled = true, bands = e.bands.toMutableList().also { b -> b[i] = (v * 2).toInt() / 2f }) } },
                            valueRange = -12f..12f,
                            modifier = Modifier.vertical().fillMaxWidth(),
                        )
                    }
                    Text(if (hz >= 1000f) "${(hz / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }}k" else hz.toInt().toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        LabelledSlider("Bass", eq.bassDb, -12f..12f, "%+.1f dB") { v -> AppSettings.updateEqualizer { it.copy(bassDb = v) } }
        LabelledSlider("Treble", eq.trebleDb, -12f..12f, "%+.1f dB") { v -> AppSettings.updateEqualizer { it.copy(trebleDb = v) } }
        LabelledSlider("Balance", eq.balance, -1f..1f, "%+.2f") { v -> AppSettings.updateEqualizer { it.copy(balance = v) } }
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(120.dp))
        Slider(value, { onChange((it * 10).toInt() / 10f) }, valueRange = range, modifier = Modifier.weight(1f))
        Text(format.format(value), modifier = Modifier.width(80.dp).padding(start = 8.dp))
    }
}

/** A slider stood on its end, low at the bottom. */
private fun Modifier.vertical() = graphicsLayer {
    rotationZ = 270f
    transformOrigin = TransformOrigin(0f, 0f)
}.layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(minWidth = constraints.minHeight, maxWidth = constraints.maxHeight, minHeight = constraints.minWidth, maxHeight = constraints.maxWidth),
    )
    layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
}

@Composable
private fun LyricsSection() {
    val lyrics by AppSettings.lyrics.collectAsState()
    Section("Lyrics") {
        SwitchRow("Prefer word-by-word lyrics", "Keep looking past line-synced lyrics for ones timed word by word", lyrics.preferWordSynced) { on ->
            AppSettings.updateLyrics { it.copy(preferWordSynced = on) }
        }
        Text(
            "Sources, asked all at once and chosen between in this order",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val order = lyrics.ordered
        order.forEachIndexed { i, entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.source.label, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.source.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { move(i, 1) }, enabled = i < order.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                Switch(entry.enabled, { on -> AppSettings.updateLyrics { l -> l.copy(sources = l.ordered.map { if (it.source == entry.source) it.copy(enabled = on) else it }) } })
            }
        }
    }
}

private fun move(i: Int, by: Int) = AppSettings.updateLyrics { l ->
    val list = l.ordered.toMutableList()
    val j = (i + by).coerceIn(list.indices)
    list.add(j, list.removeAt(i))
    l.copy(sources = list)
}

/** Your own Navidrome, Gonic, Airsonic or other Subsonic server, as on the phone. */
@Composable
private fun ServerSection() {
    val server by Subsonic.server.collectAsState()
    val scope = rememberCoroutineScope()
    Section("Your music server") {
        val s = server
        if (s != null) {
            Text("Connected to ${s.url} as ${s.user}", style = MaterialTheme.typography.bodyLarge)
            Text("Its albums are under Library › Your server.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = Subsonic::disconnect) { Text("Disconnect") }
            return@Section
        }
        Text(
            "Navidrome, Gonic, Airsonic and other Subsonic servers. Songs play as MP3, which the server converts.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var address by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(address, { address = it }, label = { Text("Address") }, placeholder = { Text("https://music.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
            )
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
                onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        Subsonic.connect(address.trim(), user.trim(), password).onFailure { problem = it.message ?: "Couldn't connect" }
                        busy = false
                    }
                },
                enabled = !busy && address.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
        ) { Text(if (busy) "Connecting…" else "Connect") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection() {
    val theme by AppSettings.theme.collectAsState()
    Section("Appearance") {
        Text("Theme")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.opentune.data.settings.ThemeMode.entries.forEach { mode ->
                FilterChip(theme.mode == mode, { AppSettings.updateTheme { it.copy(mode = mode) } }, label = { Text(mode.label) })
            }
        }
        SwitchRow("Pure black", "Black surfaces in dark mode", theme.pureBlack) { on -> AppSettings.updateTheme { it.copy(pureBlack = on) } }
        Text("Font")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.opentune.data.settings.AppFont.entries.filter { it != com.opentune.data.settings.AppFont.CUSTOM }.forEach { font ->
                FilterChip(theme.font == font, { AppSettings.updateTheme { it.copy(font = font) } }, label = { Text(font.label) })
            }
        }
        Text("Text weight")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.opentune.data.settings.TextWeight.entries.forEach { weight ->
                FilterChip(theme.textWeight == weight, { AppSettings.updateTheme { it.copy(textWeight = weight) } }, label = { Text(weight.label) })
            }
        }
        Text("Player background")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(com.opentune.data.settings.PlayerBackground.MESH, com.opentune.data.settings.PlayerBackground.GRADIENT, com.opentune.data.settings.PlayerBackground.AMOLED).forEach { bg ->
                FilterChip(theme.playerBackground == bg, { AppSettings.updateTheme { it.copy(playerBackground = bg) } }, label = { Text(bg.label) })
            }
        }
    }
}
