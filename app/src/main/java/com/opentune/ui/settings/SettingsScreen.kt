package com.opentune.ui.settings

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.opentune.BuildConfig
import com.opentune.data.account.AccountStore
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.settings.PaletteStyleOption
import com.opentune.data.settings.PlayerBackground
import com.opentune.data.settings.SEED_COLORS
import com.opentune.data.settings.ThemeMode
import com.opentune.playback.AudioCache
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.liquidGlassSupported
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.PillSegmented
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One searchable row: what it's called and the words it can be found by. */
private class Entry(val title: String, val summary: String = "", val content: @Composable () -> Unit)

private class Section(val title: String, val entries: List<Entry>)

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onSignIn: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = settingsSections(onOpenEqualizer, onOpenReplay, onSignIn, onOpenDownloads)
    val q = query.trim()
    val visible = sections.mapNotNull { s ->
        val matches = if (q.isEmpty()) s.entries else s.entries.filter {
            it.title.contains(q, true) || it.summary.contains(q, true) || s.title.contains(q, true)
        }
        if (matches.isEmpty()) null else Section(s.title, matches)
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Settings", onBack = onBack) }
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (visible.isEmpty()) {
            item {
                Text("No settings match \"$q\".", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
        }
        visible.forEach { section ->
            item(key = "label:${section.title}") { GroupLabel(section.title) }
            item(key = "card:${section.title}") {
                GroupCard {
                    section.entries.forEachIndexed { i, entry ->
                        if (i > 0) RowDivider()
                        entry.content()
                    }
                }
            }
        }
        item {
            Text(
                "OpenTune ${BuildConfig.VERSION_NAME} • GNU GPL v3 • Lyrics from LRCLIB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(28.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun settingsSections(
    onOpenEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onSignIn: () -> Unit,
    onOpenDownloads: () -> Unit,
): List<Section> {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    var downloadQualityDialog by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by AppSettings.theme.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val lib by AppSettings.library.collectAsState()
    val metered = remember { AppSettings.onMeteredNetwork() }
    var qualityDialog by remember { mutableStateOf<Boolean?>(null) } // true = Wi-Fi, false = mobile
    var folderDialog by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { AudioCache.usedBytes(context) } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    val doc = buildJsonObject {
                        put("app", "OpenTune")
                        put("version", 1)
                        put("settings", AppSettings.exportJson())
                        put("history", History.exportJson())
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(doc.toString().toByteArray()) }
                }
                "Exported settings and history."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val doc = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { AppSettings.json.parseToJsonElement(it.readBytes().decodeToString()) }
                } as? JsonObject ?: error("not an OpenTune export")
                doc["settings"]?.let(AppSettings::importJson)
                doc["history"]?.let(History::importJson)
                "Imported settings and history."
            }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    qualityDialog?.let { wifi ->
        ChoiceDialog(
            title = if (wifi) "Quality on Wi-Fi" else "Quality on mobile data",
            options = AudioQuality.entries,
            selected = if (wifi) pb.wifiQuality else pb.mobileQuality,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { q -> AppSettings.updatePlayback { if (wifi) it.copy(wifiQuality = q) else it.copy(mobileQuality = q) } },
            onDismiss = { qualityDialog = null },
        )
    }
    if (downloadQualityDialog) {
        ChoiceDialog(
            title = "Download quality",
            options = AudioQuality.entries,
            selected = lib.downloadQuality,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { q -> AppSettings.updateLibrary { it.copy(downloadQuality = q) } },
            onDismiss = { downloadQualityDialog = false },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your likes and playlists on this device stay. YouTube Music stops seeing what you play here.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    AccountStore.signOut()
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    if (folderDialog) {
        var folders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
        LaunchedEffect(Unit) { folders = runCatching { LocalMusic.folders(context) }.getOrDefault(emptyList()) }
        ChoiceDialog(
            title = "Local music folder",
            options = listOf<String?>(null) + folders.map { it.first },
            selected = lib.localFolder,
            label = { f -> f?.let { name -> "$name (${folders.firstOrNull { it.first == name }?.second ?: 0})" } ?: "All audio folders" },
            onSelect = { f -> AppSettings.updateLibrary { it.copy(localFolder = f) } },
            onDismiss = { folderDialog = false },
        )
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, text = { Text(m) })
    }

    val done = downloads.values.filter { it.state == DownloadState.DONE }
    return listOf(
        Section(
            "Account",
            listOfNotNull(
                Entry("YouTube Music account", "sign in login google profile") {
                    if (signedIn) {
                        SettingRow(
                            account?.name ?: "Signed in",
                            summary = account?.email?.takeIf { it.isNotBlank() } ?: "YouTube Music",
                            trailing = {
                                Artwork(account?.thumbnailUrl, Modifier.size(40.dp), CircleShape)
                            },
                        )
                    } else {
                        NavRow("Sign in to YouTube Music", onSignIn, summary = "Your playlists, liked songs and recommendations", icon = Icons.Rounded.AccountCircle)
                    }
                },
                if (signedIn) {
                    Entry("Sign out", "logout account") {
                        SettingRow("Sign out", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { confirmSignOut = true })
                    }
                } else null,
            ),
        ),
        Section(
            "Audio quality",
            listOf(
                Entry("On Wi-Fi", "stream quality bitrate") {
                    NavRow("On Wi-Fi", { qualityDialog = true }, icon = Icons.Rounded.Wifi, value = pb.wifiQuality.label)
                },
                Entry("On mobile data", "stream quality bitrate cellular") {
                    SettingRow(
                        "On mobile data",
                        icon = Icons.Rounded.NetworkCell,
                        onClick = { qualityDialog = false },
                        summary = if (metered) "In use now" else null,
                        trailing = { Text(pb.mobileQuality.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                },
                Entry("Output precision", "16-bit 32-bit float sample rate") {
                    SettingRow("Output precision", summary = outputSummary(context, pb.floatOutput), icon = Icons.Rounded.GraphicEq, below = {
                        PillSegmented(listOf(false, true), pb.floatOutput, { if (it) "32-bit float" else "16-bit PCM" }, { v -> AppSettings.updatePlayback { it.copy(floatOutput = v) } })
                        Text(
                            "Float keeps hi-res local files in 32-bit to the output and plays them without effects. Takes effect after a restart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    })
                },
                Entry("Prefer USB DAC", "external dac usb audio") {
                    ToggleRow("Prefer USB DAC", pb.preferUsbDac, { v -> AppSettings.updatePlayback { it.copy(preferUsbDac = v) } }, summary = "Send audio to a USB DAC whenever one is plugged in", icon = Icons.Rounded.Usb)
                },
            ),
        ),
        Section(
            "Playback",
            listOf(
                Entry("Loudness normalization", "volume level") {
                    ToggleRow("Loudness normalization", pb.loudnessNormalization, { v -> AppSettings.updatePlayback { it.copy(loudnessNormalization = v) } }, summary = "Uses YouTube's loudness measurement to set one steady volume per song", icon = Icons.AutoMirrored.Rounded.VolumeUp)
                },
                Entry("Preload upcoming songs", "latency fast skip instant buffer") {
                    ToggleRow("Preload upcoming songs", pb.preloadUpcoming, { v -> AppSettings.updatePlayback { it.copy(preloadUpcoming = v) } }, summary = "Next and previous songs start almost instantly. Uses a little extra data.", icon = Icons.Rounded.Speed)
                },
                Entry("Upgrade quality while playing", "better stream premium 256 opus swap") {
                    ToggleRow("Upgrade quality while playing", pb.qualityUpgrade, { v -> AppSettings.updatePlayback { it.copy(qualityUpgrade = v) } }, summary = "If a clearly better stream turns up a few seconds in, switch to it without stopping", icon = Icons.Rounded.HighQuality)
                },
                Entry("Crossfade", "fade blend transition overlap") {
                    SettingRow(
                        "Crossfade",
                        summary = if (pb.crossfadeSeconds == 0) "Off: songs play back to back" else "Blend each song into the next over ${pb.crossfadeSeconds} s",
                        icon = Icons.Rounded.Animation,
                        below = {
                            Slider(
                                value = pb.crossfadeSeconds.toFloat(),
                                onValueChange = { v -> AppSettings.updatePlayback { it.copy(crossfadeSeconds = v.toInt()) } },
                                valueRange = 0f..12f,
                                steps = 11,
                            )
                        },
                    )
                },
                Entry("Skip silence", "gaps") {
                    ToggleRow("Skip silence", pb.skipSilence, { v -> AppSettings.updatePlayback { it.copy(skipSilence = v) } }, summary = "Trim gaps longer than a second", icon = Icons.Rounded.SpaceBar)
                },
                Entry("Spatial audio", "stereo widen immersive") {
                    ToggleRow("Spatial audio", pb.spatialAudio, { v -> AppSettings.updatePlayback { it.copy(spatialAudio = v) } }, summary = "Widens stereo tracks for a more open sound", icon = Icons.Rounded.SurroundSound)
                },
                Entry("Equalizer", "eq bands tone balance bass treble") {
                    NavRow("Equalizer", onOpenEqualizer, summary = "Seven bands, tone and balance", icon = Icons.Rounded.Equalizer)
                },
                Entry("Autoplay", "radio continue") {
                    ToggleRow("Autoplay", pb.autoplay, AppSettings::setAutoplay, summary = "Keep playing similar songs when the queue ends", icon = Icons.AutoMirrored.Rounded.PlaylistPlay)
                },
                Entry("Don't repeat songs in current session", "autoplay duplicates") {
                    ToggleRow("Don't repeat songs in current session", pb.noRepeatInSession, { v -> AppSettings.updatePlayback { it.copy(noRepeatInSession = v) } }, summary = "Autoplay won't add a song already played or queued this session", icon = Icons.Rounded.History)
                },
                Entry("Stop music on close from recents", "swipe away") {
                    ToggleRow("Stop music on close from recents", pb.stopOnTaskRemoved, { v -> AppSettings.updatePlayback { it.copy(stopOnTaskRemoved = v) } }, summary = "Stops playback when swiped away from recent apps", icon = Icons.Rounded.MusicOff)
                },
            ),
        ),
        Section(
            "Appearance",
            listOf(
                Entry("Theme", "dark light system") {
                    SettingRow("Theme", icon = Icons.Rounded.Palette, below = {
                        PillSegmented(ThemeMode.entries, theme.mode, { it.label }, { m -> AppSettings.updateTheme { it.copy(mode = m) } })
                    })
                },
                Entry("Color from artwork", "tint album cover") {
                    ToggleRow("Color from artwork", theme.colorFromArtwork, { v -> AppSettings.updateTheme { it.copy(colorFromArtwork = v) } }, summary = "Tint the app with the playing song's cover", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Material You", "wallpaper dynamic color") {
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ToggleRow(
                        "Material You",
                        theme.dynamicColor,
                        { v -> AppSettings.updateTheme { it.copy(dynamicColor = v) } },
                        summary = if (supported) "Use your wallpaper's colors when nothing is playing" else "Needs Android 12",
                        icon = Icons.Rounded.Wallpaper,
                        enabled = supported,
                    )
                },
                Entry("Accent color", "color") {
                    SettingRow("Accent color", icon = Icons.Rounded.Palette, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SEED_COLORS.forEach { argb ->
                                val selected = theme.seedColor == argb
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(Color(argb))
                                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { AppSettings.updateTheme { it.copy(seedColor = argb) } },
                                    contentAlignment = Alignment.Center,
                                ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            }
                        }
                    })
                },
                Entry("Palette style", "tonal vibrant expressive") {
                    SettingRow("Palette style", icon = Icons.Rounded.Palette, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaletteStyleOption.entries.forEach { s ->
                                FilterChip(theme.paletteStyle == s, { AppSettings.updateTheme { it.copy(paletteStyle = s) } }, label = { Text(s.label) })
                            }
                        }
                    })
                },
                Entry("Pure black", "oled amoled") {
                    ToggleRow("Pure black", theme.pureBlack, { v -> AppSettings.updateTheme { it.copy(pureBlack = v) } }, summary = "Black backgrounds with neutral gray cards in dark theme", icon = Icons.Rounded.Wallpaper)
                },
                Entry("Player background", "mesh gradient blur") {
                    SettingRow("Player background", icon = Icons.Rounded.Wallpaper, below = {
                        PillSegmented(PlayerBackground.entries, theme.playerBackground, { it.label }, { b -> AppSettings.updateTheme { it.copy(playerBackground = b) } })
                    })
                },
                Entry("Full-screen cover art", "artwork edge") {
                    ToggleRow("Full-screen cover art", ui.fullScreenCover, { v -> AppSettings.updateUi { it.copy(fullScreenCover = v) } }, summary = "Runs the cover to the edges of the player instead of a square sleeve", icon = Icons.Rounded.Fullscreen)
                },
                Entry("Reduce animation", "motion") {
                    ToggleRow("Reduce animation", ui.reduceAnimation, { v -> AppSettings.updateUi { it.copy(reduceAnimation = v) } }, summary = "Freezes the player's moving background", icon = Icons.Rounded.Animation)
                },
                Entry("Liquid Glass", "glass refraction apple lens") {
                    ToggleRow(
                        "Liquid Glass",
                        ui.liquidGlass && liquidGlassSupported,
                        { v -> AppSettings.updateUi { it.copy(liquidGlass = v) } },
                        summary = if (liquidGlassSupported) "Bends and lifts what's behind the floating bars, like Apple's glass"
                        else "Needs Android 13 or newer; frosted glass is used instead",
                        icon = Icons.Rounded.AutoAwesome,
                        enabled = liquidGlassSupported && !ui.reduceBlur,
                    )
                },
                Entry("Reduce dynamic blur", "glass frosted performance") {
                    ToggleRow("Reduce dynamic blur", ui.reduceBlur, { v -> AppSettings.updateUi { it.copy(reduceBlur = v) } }, summary = "Swaps frosted glass for solid fills across the app", icon = Icons.Rounded.BlurOff)
                },
            ),
        ),
        Section(
            "Lyrics",
            listOf(
                Entry("Synced lyrics", "karaoke words") {
                    ToggleRow("Synced lyrics", ui.syncedLyrics, { v -> AppSettings.updateUi { it.copy(syncedLyrics = v) } }, summary = "Lights up the words as they're sung", icon = Icons.Rounded.Lyrics)
                },
                Entry("Blur unfocused lyrics", "spotlight") {
                    ToggleRow("Blur unfocused lyrics", ui.blurLyrics, { v -> AppSettings.updateUi { it.copy(blurLyrics = v) } }, summary = "Keeps the spotlight on the current line", icon = Icons.Rounded.BlurOn)
                },
                Entry("Lyrics sources", "lrclib youtube") {
                    SettingRow("Lyrics sources", summary = "LRCLIB, then YouTube Music", icon = Icons.Rounded.TextFields)
                },
            ),
        ),
        Section(
            "Downloads",
            listOf(
                Entry("Downloaded songs", "offline saved") {
                    NavRow(
                        "Downloaded songs",
                        onOpenDownloads,
                        summary = "${done.size} songs · ${Formatter.formatShortFileSize(context, done.sumOf { it.bytes })}",
                        icon = Icons.Rounded.DownloadDone,
                    )
                },
                Entry("Download quality", "offline bitrate") {
                    NavRow("Download quality", { downloadQualityDialog = true }, icon = Icons.Rounded.Download, value = lib.downloadQuality.label)
                },
                Entry("Download on Wi-Fi only", "mobile data metered") {
                    ToggleRow("Download on Wi-Fi only", lib.downloadWifiOnly, { v -> AppSettings.updateLibrary { it.copy(downloadWifiOnly = v) } }, summary = "Waits for Wi-Fi before saving songs", icon = Icons.Rounded.Wifi)
                },
            ),
        ),
        Section(
            "Local music",
            listOf(
                Entry("Local music folder", "device files") {
                    NavRow("Local music folder", { folderDialog = true }, summary = lib.localFolder ?: "All audio folders", icon = Icons.Rounded.Folder)
                },
                Entry("Filter non-music audio", "recordings voice notes") {
                    ToggleRow("Filter non-music audio", lib.filterNonMusic, { v -> AppSettings.updateLibrary { it.copy(filterNonMusic = v) } }, summary = "Hides clips under 30 seconds, WAV files, voice notes, recordings and system sounds", icon = Icons.Rounded.FilterAlt)
                },
            ),
        ),
        Section(
            "Storage",
            listOf(
                Entry("Song cache limit", "disk offline seeking") {
                    var draft by remember(lib.songCacheMb) { mutableFloatStateOf(lib.songCacheMb.toFloat()) }
                    SettingRow(
                        "Song cache limit",
                        summary = "Keeps played audio on disk for instant seeking and replays. Takes effect after a restart.",
                        icon = Icons.Rounded.Storage,
                        trailing = { Text(formatMb(draft.toInt()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal) },
                        below = {
                            Slider(
                                value = draft,
                                onValueChange = { draft = (it / 128).toInt().coerceAtLeast(1) * 128f },
                                onValueChangeFinished = { AppSettings.updateLibrary { it.copy(songCacheMb = draft.toInt()) } },
                                valueRange = 128f..4096f,
                            )
                        },
                    )
                },
                Entry("Clear song cache", "free space") {
                    NavRow("Clear song cache", {
                        scope.launch {
                            withContext(Dispatchers.IO) { AudioCache.clear(context) }
                            cacheBytes = withContext(Dispatchers.IO) { AudioCache.usedBytes(context) }
                        }
                    }, summary = "Using ${Formatter.formatShortFileSize(context, cacheBytes)}", icon = Icons.Rounded.DeleteSweep)
                },
                Entry("Clear image cache", "artwork") {
                    NavRow("Clear image cache", {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        scope.launch { withContext(Dispatchers.IO) { loader.diskCache?.clear() } }
                        message = "Image cache cleared."
                    }, summary = "Frees space used by album artwork", icon = Icons.Rounded.DeleteSweep)
                },
            ),
        ),
        Section(
            "Your data",
            listOf(
                Entry("Replay", "stats top songs artists") {
                    NavRow("Replay", onOpenReplay, summary = "Your top songs, artists and albums", icon = Icons.Rounded.BarChart)
                },
                Entry("Export data", "backup json") {
                    NavRow("Export data", { exporter.launch("opentune-backup.json") }, summary = "Settings and listening history, as one JSON file", icon = Icons.Rounded.Upload)
                },
                Entry("Import data", "restore json") {
                    NavRow("Import data", { importer.launch(arrayOf("application/json")) }, summary = "Replaces the settings and history on this device", icon = Icons.Rounded.Download)
                },
                Entry("Clear listening history", "delete history") {
                    NavRow("Clear listening history", { History.clear(); message = "Listening history cleared." }, summary = "Removes Recents and Replay data", icon = Icons.Rounded.DeleteSweep)
                },
            ),
        ),
        Section(
            "Miscellaneous",
            listOf(
                Entry("Play next on swipe", "gesture queue") {
                    ToggleRow("Play next on swipe", pb.playNextOnSwipe, { v -> AppSettings.updatePlayback { it.copy(playNextOnSwipe = v) } }, summary = "When off, swiping a song adds it to the end of the queue", icon = Icons.AutoMirrored.Rounded.PlaylistPlay)
                },
                Entry("Hide volume bar", "player slider") {
                    ToggleRow("Hide volume bar", ui.hideVolumeBar, { v -> AppSettings.updateUi { it.copy(hideVolumeBar = v) } }, summary = "Removes the volume slider from the player", icon = Icons.AutoMirrored.Rounded.VolumeOff)
                },
                Entry("Hide song status", "playing from") {
                    ToggleRow("Hide song status", ui.hideSongStatus, { v -> AppSettings.updateUi { it.copy(hideSongStatus = v) } }, summary = "Hides the \"Playing from\" line in the player", icon = Icons.Rounded.VisibilityOff)
                },
            ),
        ),
        Section(
            "Advanced",
            listOf(
                Entry("Show stats for nerds", "codec bitrate sample rate debug") {
                    ToggleRow("Show stats for nerds", ui.statsForNerds, { v -> AppSettings.updateUi { it.copy(statsForNerds = v) } }, summary = "Codec, bitrate and sample rate under the seek bar", icon = Icons.Rounded.Info)
                },
            ),
        ),
    )
}

private fun outputSummary(context: Context, float: Boolean): String {
    val am = context.getSystemService(AudioManager::class.java)
    val rate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val khz = rate?.let { "%.1f kHz".format(it / 1000f) } ?: "unknown rate"
    return "AudioTrack · ${Build.MODEL} · $khz · ${if (float) "32-bit float" else "16-bit PCM"}"
}

private fun formatMb(mb: Int): String = if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(o); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = { onSelect(o); onDismiss() })
                        Text(label(o), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
