package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.opentune.ui.components.glass
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import dev.chrisbanes.haze.hazeSource
import com.opentune.core.BuildConfig
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqPreset
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.launch

/** The groups of settings, in the bar at the top and down the page. */
private enum class Group(val label: String) { LOOK("Look"), PLAYBACK("Playback"), SOURCES("Sources"), SOUND("Equalizer"), LYRICS("Lyrics"), ACCOUNTS("Accounts"), SERVER("Your server"), ABOUT("About") }

/**
 * Settings, grouped into cards down a centred column, with a bar of the
 * groups that glides to each. The bar sits under the window's player bar
 * and is frosted over the cards sliding under it: it blurs a layer of its
 * own, since glass can't blur the layer it's drawn in.
 */
@Composable
internal fun SettingsPage() {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val at by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { (list.firstVisibleItemIndex - 1).coerceIn(0, Group.entries.lastIndex) } }
    val top = LocalPageTop.current
    val haze = dev.chrisbanes.haze.rememberHazeState()
    val blur = !AppSettings.ui.collectAsState().value.reduceBlur
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = top + GROUP_BAR + 24.dp, bottom = LocalPageBottom.current),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().then(if (blur) Modifier.hazeSource(haze) else Modifier),
        ) {
            item { Column(Modifier.column()) { Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) } }
            Group.entries.forEach { g ->
                item(key = g) {
                    Box(Modifier.column()) {
                        when (g) {
                            Group.LOOK -> AppearanceSection()
                            Group.PLAYBACK -> PlaybackSection()
                            Group.SOURCES -> SourcesSection()
                            Group.SOUND -> EqualizerSection()
                            Group.LYRICS -> LyricsSection()
                            Group.ACCOUNTS -> ConnectedSection()
                            Group.SERVER -> ServerSection()
                            Group.ABOUT -> Section("About") {
                                Text("OpenTune ${BuildConfig.VERSION_NAME} for computers", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Settings and playlists are kept in ${appContext.getFilesDir().parentFile.absolutePath}.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        // The groups, frosted over the cards, each gliding to its card.
        androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalHazeState provides haze.takeIf { blur }) {
            Box(
                Modifier.padding(top = top).fillMaxWidth().height(GROUP_BAR)
                    .glass(androidx.compose.ui.graphics.RectangleShape, MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.column().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Group.entries.forEachIndexed { i, g ->
                        Segment(g.label, at == i) { scope.launch { list.animateScrollToItem(i + 1, 0) } }
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)))
            }
        }
    }
}

/** How tall the bar of setting groups is. */
private val GROUP_BAR = 56.dp

/** The settings column: as wide as the page allows, up to a comfortable reading width, in the middle. */
private fun Modifier.column(): Modifier = this.padding(horizontal = 32.dp).widthIn(max = 880.dp).fillMaxWidth()

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    val ui by AppSettings.ui.collectAsState()
    Section("Look") {
        Choices("Theme", com.opentune.data.settings.ThemeMode.entries, theme.mode, { it.label }) { v -> AppSettings.updateTheme { it.copy(mode = v) } }
        Text("Accent", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.opentune.data.settings.SEED_COLORS.forEach { c ->
                val chosen = theme.seedColor == c
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                        .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { AppSettings.updateTheme { it.copy(seedColor = c) } },
                )
            }
        }
        Choices("Font", com.opentune.data.settings.AppFont.entries.filter { it != com.opentune.data.settings.AppFont.CUSTOM }, theme.font, { it.label }) { v -> AppSettings.updateTheme { it.copy(font = v) } }
        Choices("Text weight", com.opentune.data.settings.TextWeight.entries, theme.textWeight, { it.label }) { v -> AppSettings.updateTheme { it.copy(textWeight = v) } }
        SwitchRow("Blur effects", "Frosted glass on the player, the bars and the search box, blurring what scrolls under them", !ui.reduceBlur) { on -> AppSettings.updateUi { it.copy(reduceBlur = !on) } }
        if (!ui.reduceBlur) Choices("Glass", com.opentune.data.settings.GlassStyle.entries.filter { it != com.opentune.data.settings.GlassStyle.LIQUID }, ui.glassStyle, { it.label }) { v -> AppSettings.updateUi { it.copy(glassStyle = v) } }
        SwitchRow("Smooth motion", "Pages glide in, covers lift under the pointer, rows rise into place", !ui.reduceAnimation) { on -> AppSettings.updateUi { it.copy(reduceAnimation = !on) } }
        SwitchRow("Colour from the song", "A soft wash of the playing cover's colour behind the pages", theme.colorFromArtwork) { on -> AppSettings.updateTheme { it.copy(colorFromArtwork = on) } }
        SwitchRow("Artist signatures (beta)", "An artist's handwritten signature over their photo, in place of their name, when there's one", ui.artistSignatures) { on ->
            AppSettings.updateUi { it.copy(artistSignatures = on) }
        }
        if (ui.artistSignatures) SignatureServiceRow(ui.signatureApi)
        Choices("Player background", com.opentune.data.settings.PlayerBackground.entries, theme.playerBackground, { it.label }) { v -> AppSettings.updateTheme { it.copy(playerBackground = v) } }
        Choices("Player buttons", com.opentune.data.settings.ControlStyle.entries, ui.controlStyle, { it.label }) { v -> AppSettings.updateUi { it.copy(controlStyle = v) } }
        Choices("Player opens", com.opentune.data.settings.PlayerMotion.entries, ui.playerMotion, { it.label }) { v -> AppSettings.updateUi { it.copy(playerMotion = v) } }
        if (WindowsFrame.isWindows) WindowRows()
    }
}

/** Windows only: the title bar OpenTune draws, and Windows 11's material behind the window. */
@Composable
private fun WindowRows() {
    val own by WindowsFrame.ownFrame.collectAsState()
    SwitchRow(
        "OpenTune's own title bar",
        "Close, minimise and zoom at the head of the sidebar, and the window is moved by the empty parts of the bars." +
            if (own != WindowsFrame.drawn) " Takes effect when OpenTune next opens." else "",
        own,
    ) { WindowsFrame.setOwnFrame(it) }
    // A material needs the window made see-through, which happens only with the app's own title bar on Windows 11.
    if (WindowsFrame.transparent) {
        val material by WindowsFrame.material.collectAsState()
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Choices("Window material", WindowsFrame.Material.entries, material, { it.label }) { WindowsFrame.setMaterial(it, dark) }
    }
}

/** The signature service's address, checked against its health address before it's kept; blank is OpenTune's default. */
@Composable
private fun SignatureServiceRow(current: String) {
    var address by remember(current) { mutableStateOf(current) }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            address, { address = it; problem = null }, singleLine = true, label = { Text("Signature service") },
            isError = problem != null, supportingText = problem?.let { p -> { Text(p) } }, modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            enabled = !checking && address.trim() != current,
            onClick = {
                val base = com.opentune.data.covers.ArtistSignatures.normalize(address.ifBlank { com.opentune.data.covers.ArtistSignatures.DEFAULT_API })
                if (base == null) { problem = "Use an address starting with https://"; return@OutlinedButton }
                checking = true
                scope.launch {
                    val ok = com.opentune.data.covers.ArtistSignatures.reachable(base)
                    checking = false
                    if (ok) {
                        com.opentune.data.covers.ArtistSignatures.clear()
                        AppSettings.updateUi { it.copy(signatureApi = base) }
                    } else problem = "That service didn't answer"
                }
            },
        ) { Text(if (checking) "Checking…" else "Save") }
    }
}

/** A setting's name over a row of chips, one per choice. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(title: String, choices: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { c -> FilterChip(c == selected, { onPick(c) }, label = { Text(label(c)) }) }
    }
}

/** Discord's "Listening to" card and Last.fm scrobbles. */
@Composable
private fun ConnectedSection() {
    val discord by DiscordPresence.enabled.collectAsState()
    val discordStatus by DiscordPresence.status.collectAsState()
    val lastFm by LastFmDesktop.state.collectAsState()
    val waiting by LastFmDesktop.waiting.collectAsState()
    var problem by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf(lastFm.ownKey) }
    var secret by remember { mutableStateOf(lastFm.ownSecret) }
    Section("Discord and Last.fm") {
        SwitchRow(
            "Show on Discord",
            when (discordStatus) {
                DiscordPresence.Status.Connected -> "The song you play shows on your Discord profile while the Discord app is open."
                DiscordPresence.Status.NotRunning -> "Waiting for the Discord app. Open it on this computer and the song shows on your profile."
                DiscordPresence.Status.NoApp -> "Needs a Discord application named OpenTune, which the card is titled with. Paste its id below."
                DiscordPresence.Status.Off -> "Show the song you play on your Discord profile, through the Discord app on this computer."
            },
            discord, DiscordPresence::setEnabled,
        )
        if (!DiscordPresence.buildHasApp) {
            val saved by DiscordPresence.ownAppId.collectAsState()
            var appId by remember(saved) { mutableStateOf(saved) }
            val valid = appId.isBlank() || appId.trim().matches(Regex("[0-9]{17,20}"))
            Text(
                "This build has no Discord application of its own. Create one named OpenTune at discord.com/developers/applications and paste its Application ID.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                appId, { appId = it; if (it.isBlank() || it.trim().matches(Regex("[0-9]{17,20}"))) DiscordPresence.setOwnAppId(it) },
                label = { Text("Discord Application ID") }, singleLine = true, isError = !valid,
                supportingText = if (valid) null else ({ Text("An application id is 17 to 20 digits.") }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Last.fm", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        lastFm.session != null -> "Scrobbling as ${lastFm.session!!.user}" + if (lastFm.queue.isNotEmpty()) " · ${lastFm.queue.size} waiting to send" else ""
                        waiting -> "Press “Yes, allow access” on the Last.fm page that opened in your browser."
                        else -> "Add the songs you play to your Last.fm profile once you’ve heard half of them, or four minutes."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                lastFm.session != null -> OutlinedButton(onClick = LastFmDesktop::signOut) { Text("Disconnect") }
                waiting -> OutlinedButton(onClick = LastFmDesktop::cancelSignIn) { Text("Cancel") }
                else -> Button(
                    enabled = LastFmDesktop.buildHasKeys || (key.isNotBlank() && secret.isNotBlank()),
                    onClick = {
                        problem = null
                        if (!LastFmDesktop.buildHasKeys) LastFmDesktop.setOwnKeys(key, secret)
                        LastFmDesktop.signIn { problem = it }
                    },
                ) { Text("Connect") }
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (lastFm.session != null) {
            SwitchRow("Scrobble", "Send what you play here to Last.fm.", lastFm.scrobbling, LastFmDesktop::setScrobbling)
        } else if (!LastFmDesktop.buildHasKeys) {
            Text(
                "This build has no Last.fm application of its own. Create one free at last.fm/api/account/create and paste its key and secret.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secret, { secret = it }, label = { Text("Shared secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}
