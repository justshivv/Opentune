package com.opentune.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import com.opentune.ui.components.FocusRing
import com.opentune.ui.components.glass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import dev.chrisbanes.haze.hazeSource
import com.opentune.core.BuildConfig
import com.opentune.data.settings.AppFont
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.EQ_BANDS_HZ
import com.opentune.data.settings.EqPreset
import com.opentune.data.settings.GlassStyle
import com.opentune.data.settings.PlayerBackground
import com.opentune.data.settings.PlayerMotion
import com.opentune.data.settings.SEED_COLORS
import com.opentune.data.settings.TextWeight
import com.opentune.data.settings.ThemeMode
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.launch

/** The groups of settings, in the bar at the top and down the page. */
private enum class Group(val label: String, val colour: Color) {
    LOOK("Appearance", Color(0xFF8E5BD8)),
    PLAYBACK("Playback", Color(0xFF2FA84F)),
    SOURCES("Audio sources", Color(0xFFE5483A)),
    SOUND("Equalizer", Color(0xFFF08A24)),
    LYRICS("Lyrics", Color(0xFFD6337A)),
    ACCOUNTS("Accounts", Color(0xFF3478F6)),
    SERVER("Your server", Color(0xFF5E5CE6)),
    ABOUT("About", Color(0xFF636366)),
}

/** Each group's glyph, white on its colour, as the web player's settings list has them. */
private val Group.icon: androidx.compose.ui.graphics.vector.ImageVector get() = when (this) {
    Group.LOOK -> androidx.compose.material.icons.Icons.Rounded.Palette
    Group.PLAYBACK -> androidx.compose.material.icons.Icons.Rounded.PlayArrow
    Group.SOURCES -> androidx.compose.material.icons.Icons.Rounded.GraphicEq
    Group.SOUND -> androidx.compose.material.icons.Icons.Rounded.Equalizer
    Group.LYRICS -> androidx.compose.material.icons.Icons.Rounded.Lyrics
    Group.ACCOUNTS -> androidx.compose.material.icons.Icons.Rounded.Link
    Group.SERVER -> androidx.compose.material.icons.Icons.Rounded.Storage
    Group.ABOUT -> androidx.compose.material.icons.Icons.Rounded.Info
}

/**
 * Settings, as the web player lays them out: on a wide window the list of groups stays at the
 * left while their cards scroll beside it, a click gliding to one; on a narrow one the groups are
 * a frosted bar of chips over the cards. Each group is a heading over a soft card of its rows.
 */
@Composable
internal fun SettingsPage() {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val at by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { (list.firstVisibleItemIndex - 1).coerceIn(0, Group.entries.lastIndex) } }
    val top = LocalPageTop.current
    val haze = dev.chrisbanes.haze.rememberHazeState()
    val blur = !AppSettings.ui.collectAsState().value.reduceBlur
    // Asked for from the account menu: the Accounts section.
    val toAccounts by ChromeSettingsJump.accounts.collectAsState()
    androidx.compose.runtime.LaunchedEffect(toAccounts) {
        if (toAccounts) { ChromeSettingsJump.accounts.value = false; list.animateScrollToItem(Group.ACCOUNTS.ordinal + 1, 0) }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1020.dp
        androidx.compose.foundation.lazy.LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = top + (if (wide) 28.dp else GROUP_BAR + 24.dp), bottom = LocalPageBottom.current),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().then(if (blur) Modifier.hazeSource(haze) else Modifier),
        ) {
            item { Column(Modifier.column(wide)) { Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) } }
            Group.entries.forEach { g ->
                item(key = g) {
                    Box(Modifier.column(wide)) {
                        when (g) {
                            Group.LOOK -> AppearanceSection()
                            Group.PLAYBACK -> PlaybackSection()
                            Group.SOURCES -> SourcesSection()
                            Group.SOUND -> EqualizerSection()
                            Group.LYRICS -> LyricsSection()
                            Group.ACCOUNTS -> AccountSection { ConnectedRows() }
                            Group.SERVER -> ServerSection()
                            Group.ABOUT -> AboutSection()
                        }
                    }
                }
            }
        }
        if (wide) {
            // The groups at the left of the frame, staying put while the cards scroll beside them.
            Box(Modifier.fillMaxWidth().padding(top = top + 28.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(horizontal = 32.dp).widthIn(max = FRAME).fillMaxWidth()) {
                    Column(Modifier.width(SIDE).padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Group.entries.forEachIndexed { i, g ->
                            SideGroup(g, at == i) { scope.launch { list.animateScrollToItem(i + 1, 0) } }
                        }
                    }
                }
            }
        } else {
            // The groups, frosted over the cards, each gliding to its card.
            androidx.compose.runtime.CompositionLocalProvider(com.opentune.ui.components.LocalHazeState provides haze.takeIf { blur }) {
                Box(
                    Modifier.padding(top = top).fillMaxWidth().height(GROUP_BAR)
                        .glass(androidx.compose.ui.graphics.RectangleShape, MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.column(false).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Group.entries.forEachIndexed { i, g ->
                            Segment(g.label, at == i) { scope.launch { list.animateScrollToItem(i + 1, 0) } }
                        }
                    }
                }
            }
        }
    }
}

/** One group in the settings list: its glyph on a tile of its colour, its name; the one in view is lit. */
@Composable
private fun SideGroup(g: Group, current: Boolean, onClick: () -> Unit) {
    val fill by androidx.compose.animation.animateColorAsState(
        if (current) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent, label = "sideGroup",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(fill).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(g.colour), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(g.icon, null, Modifier.size(16.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(g.label, style = MaterialTheme.typography.titleMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** How tall the bar of setting groups is, on a narrow window. */
private val GROUP_BAR = 56.dp

/** The list of groups beside the cards on a wide window, and the frame the two share. */
private val SIDE = 204.dp
private val FRAME = 204.dp + 48.dp + 680.dp

/** The settings column: beside the list of groups on a wide window, centred in the page on a narrow one. */
private fun Modifier.column(wide: Boolean): Modifier =
    if (wide) this.padding(horizontal = 32.dp).widthIn(max = FRAME).fillMaxWidth().padding(start = SIDE + 48.dp)
    else this.padding(horizontal = 32.dp).widthIn(max = 880.dp).fillMaxWidth()

/**
 * A group of settings as the web player draws one: its name above, its rows on a soft card
 * with hairlines between them, and an optional note under the card.
 */
@Composable
internal fun Section(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Sections(title) {
        RowsCard(content = content)
        footer?.let { CardFooter(it) }
    }
}

/** A group with more than one card under its name, as the web's Appearance has Now Playing below the rest. */
@Composable
internal fun Sections(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        content()
    }
}

/**
 * A setting and its switch, stacked as the phone's lists have them. Settings itself draws its
 * switches as [ToggleRow]s now; this stays for the pages that still use it (Listen Together).
 */
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
    val bitPerfect by AudioDevices.bitPerfect.collectAsState()
    Section("Playback") {
        ToggleRow("Autoplay", "When the queue ends, keep going with similar songs", playback.autoplay, AppSettings::setAutoplay)
        OutputDeviceRow()
        MenuRow(
            "Crossfade", detail = "Off, each song starts on the sample after the last one ends",
            choices = listOf(0, 2, 4, 6, 8, 10, 12), selected = playback.crossfadeSeconds.coerceIn(0, 12),
            label = { if (it == 0) "Off" else "$it s" },
        ) { s -> AppSettings.updatePlayback { it.copy(crossfadeSeconds = s) } }
        ToggleRow(
            "Even out loudness", "Brings YouTube songs to one level, using YouTube's own measure of each",
            playback.loudnessNormalization, { on -> AppSettings.updatePlayback { it.copy(loudnessNormalization = on) } },
        )
        if (playback.loudnessNormalization) {
            MenuRow(
                "Level", choices = com.opentune.data.settings.VolumeLevel.entries, selected = playback.volumeLevel,
                label = { it.label }, summary = { it.summary },
            ) { level -> AppSettings.updatePlayback { it.copy(volumeLevel = level) } }
        }
        ToggleRow(
            "Bit-perfect lossless",
            if (NativeAudio.exclusiveSupported) "An add-on's FLAC goes to the device at its own rate and depth, with the device to itself. No equalizer, crossfade or levelling on it"
            else "An add-on's FLAC goes to the device at its own rate and depth. No equalizer, crossfade or levelling on it",
            bitPerfect, AudioDevices::setBitPerfect,
        )
        SliderRow(
            "Speed", sound.speed, 0.5f..2f, "%.2f×".format(sound.speed),
            reset = if (sound.speed != 1f) ({ AppSettings.updateSound { it.copy(speed = 1f) } }) else null,
        ) { v -> AppSettings.updateSound { it.copy(speed = (v * 20).toInt() / 20f) } }
        SleepRow(sleep)
    }
}

/** The sleep timer's choices, as the phone's: off, a number of minutes, or the end of the song. */
private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

/** The sleep timer as a row with its choices in a menu, as the web's selects. */
@Composable
private fun SleepRow(sleep: Player.Sleep?) {
    // 0 is off, -1 the end of the song, anything else minutes.
    val current = when (sleep) {
        null -> 0
        Player.Sleep.EndOfSong -> -1
        is Player.Sleep.At -> sleep.minutes
    }
    MenuRow(
        "Sleep timer", detail = null,
        choices = listOf(0) + SLEEP_MINUTES + -1, selected = current,
        label = { when (it) { 0 -> "Off"; -1 -> "End of song"; else -> "$it min" } },
    ) { m ->
        when (m) {
            0 -> Player.sleepIn(null)
            -1 -> Player.sleepAtEndOfSong()
            else -> Player.sleepIn(m)
        }
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
            SLEEP_MINUTES.forEach { m ->
                FilterChip((sleep as? Player.Sleep.At)?.minutes == m, { Player.sleepIn(m) }, label = { Text("$m min") })
            }
            FilterChip(sleep == Player.Sleep.EndOfSong, { Player.sleepAtEndOfSong() }, label = { Text("End of song") })
        }
    }
}

@Composable
private fun EqualizerSection() {
    val eq by AppSettings.equalizer.collectAsState()
    Section("Equalizer") {
        ToggleRow("Equalizer", "Fifteen bands from 25 Hz to 16 kHz, with the phone's presets", eq.enabled, { on ->
            AppSettings.updateEqualizer { it.copy(enabled = on) }
        })
        // The bands that match no preset read as Custom; picking a preset turns the equalizer on, as before.
        MenuRow(
            "Preset", choices = EqPreset.entries, selected = EqPreset.entries.firstOrNull { it.bands == eq.bands },
            label = { it?.label ?: "Custom" }, summary = { it?.summary?.takeIf(String::isNotBlank) },
        ) { p -> if (p != null) AppSettings.updateEqualizer { it.copy(enabled = true, bands = p.bands) } }
        Row(
            Modifier.hairline(TEXT_INSET).fillMaxWidth().height(236.dp).padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EQ_BANDS_HZ.forEachIndexed { i, hz ->
                val name = if (hz >= 1000f) "${(hz / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }}k" else hz.toInt().toString()
                Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%+.0f".format(eq.bands.getOrElse(i) { 0f }), style = rowDetailStyle().copy(fontSize = 11.sp), color = inkSecondary())
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        WebSlider(
                            eq.bands.getOrElse(i) { 0f }, -12f..12f, "$name Hz",
                            { v -> AppSettings.updateEqualizer { e -> e.copy(enabled = true, bands = e.bands.toMutableList().also { b -> b[i] = (v * 2).toInt() / 2f }) } },
                            Modifier.vertical().fillMaxWidth(),
                        )
                    }
                    Text(name, style = rowDetailStyle().copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        SliderRow(
            "Preamp", eq.preampDb, -12f..6f, "%+.1f dB".format(eq.preampDb),
            detail = "Lifts or lowers everything before the bands; boosts are taken back off so nothing clips",
            reset = if (eq.preampDb != 0f) ({ AppSettings.updateEqualizer { it.copy(preampDb = 0f) } }) else null,
        ) { v -> AppSettings.updateEqualizer { it.copy(preampDb = (v * 2).toInt() / 2f) } }
        SliderRow("Bass", eq.bassDb, -12f..12f, "%+.1f dB".format(eq.bassDb)) { v -> AppSettings.updateEqualizer { it.copy(bassDb = (v * 10).toInt() / 10f) } }
        SliderRow("Treble", eq.trebleDb, -12f..12f, "%+.1f dB".format(eq.trebleDb)) { v -> AppSettings.updateEqualizer { it.copy(trebleDb = (v * 10).toInt() / 10f) } }
        SliderRow("Balance", eq.balance, -1f..1f, "%+.2f".format(eq.balance)) { v -> AppSettings.updateEqualizer { it.copy(balance = (v * 10).toInt() / 10f) } }
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
    Sections("Lyrics") {
        RowsCard {
            ToggleRow("Prefer word-by-word lyrics", "Keep looking past line-synced lyrics for ones timed word by word", lyrics.preferWordSynced, { on ->
                AppSettings.updateLyrics { it.copy(preferWordSynced = on) }
            })
        }
        CardHeading("Sources")
        RowsCard {
            val order = lyrics.ordered
            order.forEachIndexed { i, entry ->
                // The whole row turns the source on or off; the arrows beside the switch move it.
                ToggleRow(
                    entry.source.label, entry.source.summary, entry.enabled,
                    { on -> AppSettings.updateLyrics { l -> l.copy(sources = l.ordered.map { if (it.source == entry.source) it.copy(enabled = on) else it }) } },
                    leading = { Text("${i + 1}", style = rowDetailStyle().copy(fontWeight = FontWeight.SemiBold), color = inkTertiary()) },
                    leadingSize = 18.dp,
                    extra = {
                        SmallIconButton(Icons.Rounded.KeyboardArrowUp, "Move ${entry.source.label} up", enabled = i > 0) { move(i, -1) }
                        SmallIconButton(Icons.Rounded.KeyboardArrowDown, "Move ${entry.source.label} down", enabled = i < order.lastIndex) { move(i, 1) }
                    },
                )
            }
        }
        CardFooter("Asked all at once, and chosen between in this order.")
    }
}

private fun move(i: Int, by: Int) = AppSettings.updateLyrics { l ->
    val list = l.ordered.toMutableList()
    val j = (i + by).coerceIn(list.indices)
    list.add(j, list.removeAt(i))
    l.copy(sources = list)
}

/** Your own Navidrome, Gonic, Airsonic or other Subsonic server, as on the phone: an integration row, its sign-in under it. */
@Composable
private fun ServerSection() {
    val server by Subsonic.server.collectAsState()
    val scope = rememberCoroutineScope()
    val s = server
    Section(
        "Your music server",
        footer = if (s != null) "Its albums are under Library › Your server."
        else "Navidrome, Gonic, Airsonic and other Subsonic servers. Songs play as MP3, which the server converts.",
    ) {
        IntegrationRow(
            "Music server", if (s != null) "Connected" else "Not connected",
            tile = { IconTile(Icons.Rounded.Storage, Brand.Server) },
            detail = s?.let { "${it.url} as ${it.user}" },
        ) { if (s != null) RowAction("Disconnect", Subsonic::disconnect) }
        if (s != null) return@Section
        var address by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        FieldRow("Address", address, { address = it }, placeholder = "https://music.example.com", inset = ICON_INSET)
        FieldRow("User", user, { user = it }, inset = ICON_INSET)
        FieldRow("Password", password, { password = it }, secret = true, inset = ICON_INSET)
        problem?.let { NoteRow(it, colour = MaterialTheme.colorScheme.error, inset = ICON_INSET) }
        ButtonsRow(inset = ICON_INSET) {
            KeyPill(
                if (busy) "Connecting…" else "Connect",
                onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        Subsonic.connect(address.trim(), user.trim(), password).onFailure { problem = it.message ?: "Couldn't connect" }
                        busy = false
                    }
                },
                enabled = !busy && address.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun AppearanceSection() {
    val theme by AppSettings.theme.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    Sections("Appearance") {
        RowsCard {
            SegmentedRow("Theme", choices = ThemeMode.entries, selected = theme.mode, label = { it.label }) { v -> AppSettings.updateTheme { it.copy(mode = v) } }
            ToggleRow("Pure black", "A black background in the dark theme, rather than a dark grey", theme.pureBlack, { on -> AppSettings.updateTheme { it.copy(pureBlack = on) } })
            ToggleRow("Colour from the song", "A soft wash of the playing cover's colour behind the pages", theme.colorFromArtwork, { on -> AppSettings.updateTheme { it.copy(colorFromArtwork = on) } })
            AccentRow(theme.seedColor)
            // The first choice is DM Sans here, the web player's typeface, rather than the system's own sans.
            SegmentedRow(
                "Font", choices = AppFont.entries.filter { it != AppFont.CUSTOM }, selected = theme.font,
                label = { if (it == AppFont.SYSTEM) "DM Sans" else it.label },
            ) { v -> AppSettings.updateTheme { it.copy(font = v) } }
            SegmentedRow("Text weight", choices = TextWeight.entries, selected = theme.textWeight, label = { it.label }) { v -> AppSettings.updateTheme { it.copy(textWeight = v) } }
        }
        CardHeading("Glass and motion")
        RowsCard {
            ToggleRow("Blur effects", "Frosted glass on the player, the bars and the search box, blurring what scrolls under them", !ui.reduceBlur, { on -> AppSettings.updateUi { it.copy(reduceBlur = !on) } })
            // Liquid Glass first, as the web player draws it (the default), then Frosted, the
            // phone's Apple-like lens style, which is the frost alone here as the desktop has no lens for it.
            if (!ui.reduceBlur) MenuRow(
                "Glass", choices = GlassStyle.entries, selected = ui.glassStyle,
                label = { it.label },
                summary = {
                    when (it) {
                        GlassStyle.WEB -> "Clear glass that bends and brightens what's behind it at the edges, with a lit rim"
                        GlassStyle.LIQUID -> "Soft, clear frost under a thin film"
                    }
                },
            ) { v -> AppSettings.updateUi { it.copy(glassStyle = v) } }
            ToggleRow("Smooth motion", "Pages glide in, covers lift under the pointer, rows rise into place", !ui.reduceAnimation, { on -> AppSettings.updateUi { it.copy(reduceAnimation = !on) } })
            ToggleRow("Artist signatures (beta)", "An artist's handwritten signature over their photo, in place of their name, when there's one", ui.artistSignatures, { on ->
                AppSettings.updateUi { it.copy(artistSignatures = on) }
            })
            if (ui.artistSignatures) SignatureServiceRow(ui.signatureApi)
        }
        CardHeading("Now Playing")
        RowsCard {
            MenuRow("Player background", choices = PlayerBackground.entries, selected = theme.playerBackground, label = { it.label }) { v -> AppSettings.updateTheme { it.copy(playerBackground = v) } }
            MenuRow("Player opens", choices = PlayerMotion.entries, selected = ui.playerMotion, label = { it.label }, summary = { it.summary }) { v -> AppSettings.updateUi { it.copy(playerMotion = v) } }
        }
        if (TrayPrefs.supported || WindowsFrame.isWindows) {
            CardHeading("Window")
            RowsCard {
                if (TrayPrefs.supported) {
                    val tray by TrayPrefs.keepPlaying.collectAsState()
                    ToggleRow("Keep playing in the tray", "Closing the window leaves the music playing; the tray icon brings it back or quits", tray, TrayPrefs::setKeepPlaying)
                }
                if (WindowsFrame.isWindows) WindowRows()
            }
        }
    }
}

/** The accent colours, named for screen readers in the order the phone lists them. */
private val ACCENT_NAMES = listOf("Red", "Amber", "Yellow", "Green", "Teal", "Sky", "Indigo", "Purple", "Pink", "Brown")

/** The accent swatches, as the web's accent picker: the name above, round swatches under it, the chosen one ringed and ticked. */
@Composable
private fun AccentRow(chosen: Int) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    Column(Modifier.hairline(TEXT_INSET).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 16.dp)) {
        Text("Accent", style = rowTitleStyle())
        Row(
            Modifier.padding(top = 12.dp).selectableGroup().semantics { contentDescription = "Accent" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SEED_COLORS.forEachIndexed { i, c ->
                val on = chosen == c
                val colour = Color(c)
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                val grow by animateFloatAsState(if (hovered && !still) 1.08f else 1f, if (still) snap() else tween(200), label = "swatch")
                Box(
                    Modifier.size(36.dp).scale(grow)
                        .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .padding(3.dp).clip(CircleShape).background(colour).hoverable(source)
                        .selectable(on, source, FocusRing(CircleShape), role = Role.RadioButton) { AppSettings.updateTheme { it.copy(seedColor = c) } }
                        .semantics { contentDescription = ACCENT_NAMES.getOrElse(i) { "Accent ${i + 1}" } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = inkOn(colour))
                }
            }
        }
    }
}

/** Windows only: the title bar OpenTune draws, and Windows 11's material behind the window. */
@Composable
private fun WindowRows() {
    val own by WindowsFrame.ownFrame.collectAsState()
    ToggleRow(
        "OpenTune's own title bar",
        "Close, minimise and zoom at the head of the sidebar, and the window is moved by the empty parts of the bars." +
            if (own != WindowsFrame.drawn) " Takes effect when OpenTune next opens." else "",
        own,
        { WindowsFrame.setOwnFrame(it) },
    )
    if (WindowsFrame.isWindows) {
        val renderer by WindowsFrame.renderer.collectAsState()
        val inUse = WindowsFrame.rendererOf(WindowsFrame.drawingWith)
        SegmentedRow(
            "Renderer",
            detail = "How the window is drawn${inUse?.let { " (now ${it.label})" } ?: ""}. Automatic is OpenGL, or Direct3D with Mica or Acrylic on. If OpenTune closes on its own as it opens, the next start moves to another by itself. Takes effect when OpenTune next opens.",
            choices = WindowsFrame.Renderer.entries, selected = renderer, label = { it.label },
        ) { WindowsFrame.setRenderer(it) }
    }
    // A material needs the window made see-through, which happens only with the app's own title bar
    // on Windows 11, and only when the window opened with a material chosen.
    if (WindowsFrame.materialsPossible) {
        val material by WindowsFrame.material.collectAsState()
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val restart = (material != WindowsFrame.Material.OFF) != WindowsFrame.transparent
        SegmentedRow(
            "Window material",
            detail = "Mica or Acrylic shows the desktop's colour through the window. Off is the plain window, the steadiest on every computer." +
                if (restart) " Takes effect when OpenTune next opens." else "",
            choices = WindowsFrame.Material.entries, selected = material, label = { it.label },
        ) { WindowsFrame.setMaterial(it, dark) }
    }
}

/** The signature service's address, checked against its health address before it's kept; blank is OpenTune's default. */
@Composable
private fun SignatureServiceRow(current: String) {
    var address by remember(current) { mutableStateOf(current) }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SettingRow(
        "Signature service", detail = problem, detailColour = MaterialTheme.colorScheme.error,
    ) {
        WebField(address, { address = it; problem = null }, "Signature service", error = problem != null, placeholder = com.opentune.data.covers.ArtistSignatures.DEFAULT_API)
        RowAction(
            if (checking) "Checking…" else "Save",
            enabled = !checking && address.trim() != current,
            onClick = {
                val base = com.opentune.data.covers.ArtistSignatures.normalize(address.ifBlank { com.opentune.data.covers.ArtistSignatures.DEFAULT_API })
                if (base == null) { problem = "Use an address starting with https://"; return@RowAction }
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
        )
    }
}

/**
 * Discord's "Listening to" card and Last.fm scrobbles, as the web's integration rows: each
 * service's square, its name, its state and its action, with what it needs set (an
 * application id, a key and secret) and how its card reads shown under it only when it's on.
 */
@Composable
private fun ConnectedRows() {
    val discord by DiscordPresence.enabled.collectAsState()
    val discordStatus by DiscordPresence.status.collectAsState()
    val lastFm by LastFmDesktop.state.collectAsState()
    val waiting by LastFmDesktop.waiting.collectAsState()
    var problem by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf(lastFm.ownKey) }
    var secret by remember { mutableStateOf(lastFm.ownSecret) }

    // Discord: the row is its switch, as "Show on Discord" was; its state at the right. Turned on,
    // it reads Off only until the presence loop first looks for the app, so that reads as not connected.
    IntegrationRow(
        "Discord",
        if (!discord) "Off" else when (discordStatus) {
            DiscordPresence.Status.Connected -> "Connected"
            DiscordPresence.Status.NotRunning -> "Waiting for Discord"
            DiscordPresence.Status.NoApp -> "Needs an application"
            DiscordPresence.Status.Off -> "Not connected"
        },
        tile = { DiscordTile() },
        detail = when (discordStatus) {
            DiscordPresence.Status.Connected -> "The song you play shows on your Discord profile while the Discord app is open."
            DiscordPresence.Status.NotRunning -> "Waiting for the Discord app. Open it on this computer and the song shows on your profile."
            DiscordPresence.Status.NoApp -> "Needs a Discord application named OpenTune, which the card is titled with. Paste its id below."
            DiscordPresence.Status.Off -> "Show the song you play on your Discord profile, through the Discord app on this computer."
        },
        modifier = Modifier.toggleable(discord, role = Role.Switch, onValueChange = DiscordPresence::setEnabled)
            .semantics { contentDescription = "Show on Discord" },
    ) {
        if (discordStatus == DiscordPresence.Status.Connected) LiveDot()
        WebSwitch(discord)
    }
    if (discord && !DiscordPresence.buildHasApp) {
        val saved by DiscordPresence.ownAppId.collectAsState()
        var appId by remember(saved) { mutableStateOf(saved) }
        val valid = appId.isBlank() || appId.trim().matches(Regex("[0-9]{17,20}"))
        FieldRow(
            "Application ID", appId, { appId = it; if (it.isBlank() || it.trim().matches(Regex("[0-9]{17,20}"))) DiscordPresence.setOwnAppId(it) },
            placeholder = "17 to 20 digits", inset = ICON_INSET,
            detail = "This build has no Discord application of its own. Create one named OpenTune at discord.com/developers/applications and paste its Application ID.",
            problem = if (valid) null else "An application id is 17 to 20 digits.",
        )
    }
    if (discord) DiscordCardRows()

    // Last.fm: its state, and Connect, Cancel or Disconnect beside it.
    val session = lastFm.session
    IntegrationRow(
        "Last.fm",
        when {
            session != null -> "Scrobbling as ${session.user}"
            waiting -> "Waiting for your browser"
            else -> "Not connected"
        },
        tile = { LastFmTile() },
        detail = when {
            session != null -> if (lastFm.queue.isNotEmpty()) "${lastFm.queue.size} waiting to send" else null
            waiting -> "Press “Yes, allow access” on the Last.fm page that opened in your browser."
            else -> "Add the songs you play to your Last.fm profile once you’ve heard half of them, or four minutes."
        },
    ) {
        when {
            session != null -> RowAction("Disconnect", LastFmDesktop::signOut)
            waiting -> RowAction("Cancel", LastFmDesktop::cancelSignIn)
            else -> RowAction(
                "Connect",
                enabled = LastFmDesktop.buildHasKeys || (key.isNotBlank() && secret.isNotBlank()),
                onClick = {
                    problem = null
                    if (!LastFmDesktop.buildHasKeys) LastFmDesktop.setOwnKeys(key, secret)
                    LastFmDesktop.signIn { problem = it }
                },
            )
        }
    }
    problem?.let { NoteRow(it, colour = MaterialTheme.colorScheme.error, inset = ICON_INSET) }
    if (session != null) {
        ToggleRow("Scrobble", "Send what you play here to Last.fm.", lastFm.scrobbling, LastFmDesktop::setScrobbling, Modifier.hairline(ICON_INSET).padding(start = ICON_INSET - TEXT_INSET))
    } else if (!LastFmDesktop.buildHasKeys) {
        NoteRow(
            "This build has no Last.fm application of its own. Create one free at last.fm/api/account/create and paste its key and secret.",
            inset = ICON_INSET,
        )
        FieldRow("API key", key, { key = it }, inset = ICON_INSET)
        FieldRow("Shared secret", secret, { secret = it }, secret = true, inset = ICON_INSET)
    }
}

/** Where else OpenTune is, as the web player's About lists it: rows that leave for the code, the community, the phone and TV apps, the web player. */
@Composable
private fun AboutSection() {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    // Read so a configuration pushed from the dashboard redraws these links.
    com.opentune.data.config.RemoteConfig.state.collectAsState().value
    val config = com.opentune.data.config.RemoteConfig.current
    Section("About", footer = "OpenTune is free and open source. Report bugs, suggest features and share the add-ons you build.") {
        SettingRow(
            "OpenTune ${BuildConfig.VERSION_NAME} for computers",
            detail = "Settings and playlists are kept in ${appContext.getFilesDir().parentFile.absolutePath}.",
            leading = { androidx.compose.foundation.Image(rememberLogo(Logo.APP), null, Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))) },
        )
        SavedSignInsRow()
        UpdateAndUsageRows()
        listOf(
            Triple("Source code", "GitHub", config.github) to (Icons.Rounded.Code to Color(0xFF24292F)),
            Triple("Community", "Discord", config.discord) to (null to Brand.Discord),
            Triple("OpenTune for Android", "Download", "${config.github.trimEnd('/')}/releases/latest") to (Icons.Rounded.Smartphone to Color(0xFF2FA84F)),
            Triple("OpenTune on TV", null, config.tv) to (Icons.Rounded.Tv to Color(0xFF3478F6)),
            Triple("Web player", null, config.web) to (Icons.Rounded.Language to Color(0xFF8E5BD8)),
            Triple("Build an add-on", null, "https://justshivv.github.io/Opentune/addons/") to (Icons.Rounded.Build to Color(0xFFE5483A)),
        ).plus(
            // The website, social accounts, donation page and privacy policy, each only when the dashboard sets it.
            listOfNotNull(config.website?.let { Triple("Website", null, it) to (Icons.Rounded.Language to Color(0xFF2F7D6D)) }) +
                config.socials.map { (key, url) -> Triple(SOCIAL_NAMES[key] ?: key, null, url) to (Icons.Rounded.ArrowOutward to Color(0xFF55575E)) } +
                listOfNotNull(
                    config.donate?.let { Triple("Donate", null, it) to (Icons.Rounded.Favorite to Color(0xFFE0457B)) },
                    config.privacy?.let { Triple("Privacy policy", null, it) to (Icons.Rounded.Lock to Color(0xFF55575E)) },
                ),
        ).forEach { (link, look) ->
            val (title, value, url) = link
            val (icon, colour) = look
            SettingRow(
                title,
                leading = { if (icon != null) IconTile(icon, colour) else DiscordTile() },
                onClick = { runCatching { links.openUri(url) } },
                role = Role.Button,
            ) {
                value?.let { Text(it, style = rowDetailStyle().copy(fontSize = 14.sp), color = inkSecondary()) }
                Icon(Icons.Rounded.ArrowOutward, "Opens in your browser", Modifier.size(16.dp), tint = inkTertiary())
            }
        }
    }
}

/** How the Discord card reads, as the web player sets it: rows under Discord's, inset past its square. */
@Composable
private fun DiscordCardRows() {
    val card by DiscordPresence.card.collectAsState()
    val nested = Modifier.hairline(ICON_INSET).padding(start = ICON_INSET - TEXT_INSET)
    ToggleRow("Lead with the song", "Member lists show the song's title rather than OpenTune", card.leadWithSong, { on -> DiscordPresence.setCard { it.copy(leadWithSong = on) } }, nested)
    ToggleRow("Listen button", "A button under the card that opens the song on YouTube Music", card.listenButton, { on -> DiscordPresence.setCard { it.copy(listenButton = on) } }, nested)
    if (card.listenButton) FieldRow(
        "Listen button text", card.listenLabel, { v -> DiscordPresence.setCard { it.copy(listenLabel = v.take(32)) } },
        placeholder = "Listen on YouTube Music", inset = ICON_INSET,
    )
    ToggleRow("OpenTune button", "A button under the card that opens OpenTune's page", card.openTuneButton, { on -> DiscordPresence.setCard { it.copy(openTuneButton = on) } }, nested)
    if (card.openTuneButton) FieldRow(
        "OpenTune button text", card.openTuneLabel, { v -> DiscordPresence.setCard { it.copy(openTuneLabel = v.take(32)) } },
        placeholder = "Visit OpenTune", inset = ICON_INSET,
    )
}

/** Where the sound goes: the system's default, or a device of this computer's, picked from a menu that's read afresh each time it opens. */
@Composable
private fun OutputDeviceRow() {
    val selected by AudioDevices.selected.collectAsState()
    var devices by remember { mutableStateOf(emptyList<AudioDevices.Device>()) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { devices = AudioDevices.read() }
    MenuRow(
        "Output device",
        choices = listOf("") + devices.map { it.name }.filter { it.isNotBlank() },
        selected = selected,
        detail = AudioBus.description,
        label = { it.ifBlank { "The system's default" } },
        summary = { name -> devices.firstOrNull { it.name == name }?.description?.takeIf { it.isNotBlank() && it != name } },
        onOpen = { scope.launch { devices = AudioDevices.read() } },
    ) { AudioDevices.select(it) }
}

private val SOCIAL_NAMES = mapOf("x" to "X", "instagram" to "Instagram", "youtube" to "YouTube", "telegram" to "Telegram", "reddit" to "Reddit")
