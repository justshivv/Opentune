package com.opentune.ui.settings

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.SingletonImageLoader
import com.opentune.BuildConfig
import com.opentune.data.LogExport
import com.opentune.data.UpdateCheck
import com.opentune.data.Updater
import com.opentune.data.account.AccountStore
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.lastfm.LastFm
import com.opentune.data.library.LibraryStore
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.local.LocalMusic
import com.opentune.data.releases.NewReleases
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.settings.ControlStyle
import com.opentune.data.settings.CoverChange
import com.opentune.data.settings.DockLens
import com.opentune.data.settings.DockMotion
import com.opentune.data.settings.GlassStyle
import com.opentune.data.settings.LyricsAlign
import com.opentune.data.settings.LyricsAnimation
import com.opentune.data.settings.PageTransition
import com.opentune.data.settings.PaletteStyleOption
import com.opentune.data.settings.PlayerBackground
import com.opentune.data.settings.PlayerMotion
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.settings.Recommender
import com.opentune.data.settings.SEED_COLORS
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.VolumeLevel
import com.opentune.data.sponsorblock.SponsorBlock
import com.opentune.data.subsonic.Subsonic
import com.opentune.playback.AudioCache
import com.opentune.playback.BitPerfectUsb
import com.opentune.ui.components.AppSwitch
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ChoiceSheet
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.GroupCard
import com.opentune.ui.components.GroupLabel
import com.opentune.ui.components.NavRow
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.PillSegmented
import com.opentune.ui.components.RowDivider
import com.opentune.ui.components.SettingRow
import com.opentune.ui.components.SheetButton
import com.opentune.ui.components.SheetTone
import com.opentune.ui.components.ToggleRow
import com.opentune.ui.components.liquidGlassSupported
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One searchable row: what it's called and the words it can be found by. */
private class Entry(val title: String, val summary: String = "", val content: @Composable () -> Unit)

private class Section(val title: String, val entries: List<Entry>)

/** A setting placed on its page: the category it's on and the subsection under it. */
private class Placed(val entry: Entry, val category: SettingsCategory, val group: String)

/**
 * Settings as a short list of categories, each opening its own page of
 * subsections (see [SETTINGS_CATEGORIES]). Searching from the main page looks
 * through every setting and shows where each one lives.
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenWrapped: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenSpotify: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val updatePending = com.opentune.ui.components.updatePending()
    val sections = settingsSections(onOpenEqualizer, onOpenReplay, onOpenWrapped, onSignIn, onOpenDownloads, onOpenSpotify, onOpenDevices)
    val placed = remember(sections) { place(sections) }
    val open = SETTINGS_CATEGORIES.firstOrNull { it.key == openKey }
    androidx.activity.compose.BackHandler(enabled = open != null) { openKey = null }
    val q = query.trim()
    // Kept out here, so coming back from a page finds the main list where it was left.
    val mainList = androidx.compose.foundation.lazy.rememberLazyListState()
    // Which folded subsections have been opened with "See more".
    var unfolded by rememberSaveable { mutableStateOf(emptySet<String>()) }

    androidx.compose.animation.AnimatedContent(
        open,
        transitionSpec = {
            val forward = targetState != null
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (forward) it / 8 else -it / 8 }) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
        },
        label = "settingsPage",
    ) { page ->
        LazyColumn(
            state = if (page == null) mainList else androidx.compose.foundation.lazy.rememberLazyListState(),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (page != null) {
                item { PageHeader(page.title, onBack = { openKey = null }) }
                placed.filter { it.category == page }.groupBy { it.group }.forEach { (group, rows) ->
                    item(key = "label:$group") { GroupLabel(group) }
                    item(key = "card:$group") {
                        val shown = FOLDED_GROUPS[group]?.takeIf { group !in unfolded && it < rows.size }
                        GroupCard(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))) {
                            rows.take(shown ?: rows.size).forEachIndexed { i, row ->
                                if (i > 0) RowDivider()
                                row.entry.content()
                            }
                            if (FOLDED_GROUPS[group]?.let { it < rows.size } == true) {
                                RowDivider()
                                SeeMoreRow(open = shown == null, hidden = rows.size - (FOLDED_GROUPS[group] ?: 0)) {
                                    unfolded = if (group in unfolded) unfolded - group else unfolded + group
                                }
                            }
                        }
                    }
                }
            } else {
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
                if (q.isNotEmpty()) {
                    val hits = placed.filter {
                        it.entry.title.contains(q, true) || it.entry.summary.contains(q, true) ||
                            it.group.contains(q, true) || it.category.title.contains(q, true)
                    }
                    if (hits.isEmpty()) {
                        item { Text("No settings match \"$q\".", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                    }
                    hits.groupBy { "${it.category.title} · ${it.group}" }.forEach { (where, rows) ->
                        item(key = "hit:$where") { GroupLabel(where) }
                        item(key = "hitcard:$where") {
                            GroupCard {
                                rows.forEachIndexed { i, row ->
                                    if (i > 0) RowDivider()
                                    row.entry.content()
                                }
                            }
                        }
                    }
                } else {
                    SETTINGS_HOME.forEach { (label, keys) ->
                        val cats = keys.mapNotNull { k -> SETTINGS_CATEGORIES.firstOrNull { it.key == k } }
                        item(key = "home:$label") { GroupLabel(label) }
                        item(key = "homecard:$label") {
                            GroupCard {
                                cats.forEachIndexed { i, c ->
                                    if (i > 0) RowDivider()
                                    val summary = if (c.key == "account") {
                                        if (signedIn) account?.name ?: "Signed in to YouTube Music" else "Not signed in"
                                    } else {
                                        c.summary
                                    }
                                    // The account row wears the signed-in Google photo in place of the icon.
                                    val avatar = account?.thumbnailUrl?.takeIf { c.key == "account" && signedIn }
                                    NavRow(
                                        c.title,
                                        { openKey = c.key },
                                        summary = summary,
                                        icon = if (avatar == null) c.icon else null,
                                        dot = c.key == "about" && updatePending,
                                        leading = avatar?.let { url -> { Artwork(url, Modifier.size(28.dp), CircleShape) } },
                                    )
                                }
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
    }
}

/** The last row of a folded subsection: "See more" with how many are tucked away, or "See less". */
@Composable
private fun SeeMoreRow(open: Boolean, hidden: Int, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "seeMore")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (open) "See less" else "See $hidden more",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Puts every setting on its category page, in the order [SETTINGS_CATEGORIES]
 * lists them; one it doesn't name goes under "More" on its old section's page.
 */
private fun place(sections: List<Section>): List<Placed> {
    val byTitle = sections.flatMap { s -> s.entries.map { it to s.title } }.associateBy { it.first.title }
    val used = mutableSetOf<String>()
    val out = mutableListOf<Placed>()
    // Every named setting first, so a page's "More" only gets what no page claims.
    SETTINGS_CATEGORIES.forEach { c ->
        c.groups.forEach { (group, titles) ->
            titles.forEach { t -> byTitle[t]?.let { (e, _) -> out += Placed(e, c, group); used += t } }
        }
    }
    SETTINGS_CATEGORIES.forEach { c ->
        byTitle.values.filter { (e, section) -> e.title !in used && SECTION_HOME[section] == c.key }
            .forEach { (e, _) -> out += Placed(e, c, "More"); used += e.title }
    }
    // Anything left (a section with no page) still shows, on About.
    val about = SETTINGS_CATEGORIES.last()
    byTitle.values.filter { (e, _) -> e.title !in used }.forEach { (e, _) -> out += Placed(e, about, "More") }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun settingsSections(
    onOpenEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onOpenWrapped: () -> Unit,
    onSignIn: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenDevices: () -> Unit,
): List<Section> {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val accounts by AccountStore.accounts.collectAsState()
    val done by Downloads.done.collectAsState(Downloads.doneNow())
    var downloadQualityDialog by remember { mutableStateOf(false) }
    var lyricsSourcesDialog by remember { mutableStateOf(false) }
    var lastFmDialog by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf(false) }
    var listenBrainzDialog by remember { mutableStateOf(false) }
    var sponsorBlockDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var supportDialog by remember { mutableStateOf(false) }
    val pendingUpdate by UpdateCheck.pending.collectAsState()
    var updateBytes by remember { mutableStateOf(0L) }
    var playerStyleDialog by remember { mutableStateOf(false) }
    var recommenderDialog by remember { mutableStateOf(false) }
    var followedDialog by remember { mutableStateOf(false) }
    var hiddenDialog by remember { mutableStateOf(false) }
    val hidden by LibraryStore.hidden.collectAsState()
    val followedArtists by NewReleases.followed.collectAsState()
    val listenBrainz by ListenBrainz.account.collectAsState()
    val listensWaiting by ListenBrainz.queued.collectAsState()
    val musicServer by Subsonic.server.collectAsState()
    var lyricsAnimationDialog by remember { mutableStateOf(false) }
    var dockMotionDialog by remember { mutableStateOf(false) }
    var dockLensDialog by remember { mutableStateOf(false) }
    var glassStyleDialog by remember { mutableStateOf(false) }
    var pageTransitionDialog by remember { mutableStateOf(false) }
    var playerMotionDialog by remember { mutableStateOf(false) }
    var coverChangeDialog by remember { mutableStateOf(false) }
    var controlStyleDialog by remember { mutableStateOf(false) }
    val lastFm by LastFm.account.collectAsState()
    val scrobblesWaiting by LastFm.queued.collectAsState()
    val lyricsSettings by AppSettings.lyrics.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(update) { updateBytes = withContext(Dispatchers.IO) { Updater.cachedBytes(context) } }
    val theme by AppSettings.theme.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val lib by AppSettings.library.collectAsState()
    val metered = remember { AppSettings.onMeteredNetwork() }
    val spotifyConnected by com.opentune.ui.spotify.SpotifyConnection.connected.collectAsState()
    val spotifyProblem by com.opentune.ui.spotify.SpotifyConnection.problem.collectAsState()
    val spotifySync by com.opentune.data.spotify.SpotifySync.status.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.opentune.ui.spotify.SpotifyConnection.refresh() } }
    var qualityDialog by remember { mutableStateOf<Boolean?>(null) } // true = Wi-Fi, false = mobile
    var audioAddons by remember { mutableStateOf(false) }
    if (audioAddons) AudioAddonsSheet { audioAddons = false }
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
                        put("library", LibraryStore.exportJson())
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(doc.toString().toByteArray()) }
                }
                "Exported settings, history, likes and playlists."
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
                doc["library"]?.let(LibraryStore::importJson)
                "Imported settings, history, likes and playlists."
            }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    qualityDialog?.let { wifi ->
        ChoiceSheet(
            title = if (wifi) "Quality on Wi-Fi" else "Quality on mobile data",
            options = AudioQuality.entries,
            selected = if (wifi) pb.wifiQuality else pb.mobileQuality,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { q -> AppSettings.updatePlayback { if (wifi) it.copy(wifiQuality = q) else it.copy(mobileQuality = q) } },
            onDismiss = { qualityDialog = null },
        )
    }
    if (lyricsSourcesDialog) LyricsSourcesDialog(onDismiss = { lyricsSourcesDialog = false })
    if (lastFmDialog) LastFmDialog(onDismiss = { lastFmDialog = false })
    if (serverDialog) ServerDialog(onDismiss = { serverDialog = false })
    if (listenBrainzDialog) ListenBrainzDialog(onDismiss = { listenBrainzDialog = false })
    if (sponsorBlockDialog) SponsorBlockDialog(onDismiss = { sponsorBlockDialog = false })
    update?.let { UpdateDialog(it, onDismiss = { update = null }) }
    if (showWhatsNew) com.opentune.ui.components.WhatsNewSheet(
        com.opentune.ui.components.WhatsNew.since(null, BuildConfig.VERSION_NAME, BuildConfig.CHANGELOG),
        BuildConfig.VERSION_NAME, onDismiss = { showWhatsNew = false },
    )
    if (supportDialog) com.opentune.ui.components.SupportDialog(onDismiss = { supportDialog = false })
    if (followedDialog) FollowedArtistsDialog(onDismiss = { followedDialog = false })
    if (hiddenDialog) HiddenDialog(onDismiss = { hiddenDialog = false })
    if (playerStyleDialog) {
        ChoiceSheet(
            title = "Player layout",
            options = PlayerStyle.entries,
            selected = ui.playerStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { v -> AppSettings.updateUi { it.copy(playerStyle = v) } },
            onDismiss = { playerStyleDialog = false },
        )
    }
    if (recommenderDialog) {
        ChoiceSheet(
            title = "Recommendations",
            options = Recommender.entries,
            selected = pb.recommender,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { r -> AppSettings.updatePlayback { it.copy(recommender = r) } },
            onDismiss = { recommenderDialog = false },
        )
    }
    if (controlStyleDialog) {
        ChoiceSheet(
            title = "Player buttons",
            options = ControlStyle.entries,
            selected = ui.controlStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { c -> AppSettings.updateUi { it.copy(controlStyle = c) } },
            onDismiss = { controlStyleDialog = false },
        )
    }
    if (dockMotionDialog) {
        ChoiceSheet(
            title = "Dock animation",
            options = DockMotion.entries,
            selected = ui.dockMotion,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(dockMotion = m) } },
            onDismiss = { dockMotionDialog = false },
        )
    }
    if (dockLensDialog) {
        ChoiceSheet(
            title = "Dock lens",
            options = DockLens.entries,
            selected = ui.dockLens,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { l -> AppSettings.updateUi { it.copy(dockLens = l) } },
            onDismiss = { dockLensDialog = false },
        )
    }
    if (glassStyleDialog) {
        ChoiceSheet(
            title = "Glass style",
            options = GlassStyle.entries,
            selected = ui.glassStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { g -> AppSettings.updateUi { it.copy(glassStyle = g) } },
            onDismiss = { glassStyleDialog = false },
        )
    }
    if (pageTransitionDialog) {
        ChoiceSheet(
            title = "Page transitions",
            options = PageTransition.entries,
            selected = ui.pageTransition,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { t -> AppSettings.updateUi { it.copy(pageTransition = t) } },
            onDismiss = { pageTransitionDialog = false },
        )
    }
    if (coverChangeDialog) {
        ChoiceSheet(
            title = "Song change",
            options = CoverChange.entries,
            selected = ui.coverChange,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { c -> AppSettings.updateUi { it.copy(coverChange = c) } },
            onDismiss = { coverChangeDialog = false },
        )
    }
    if (playerMotionDialog) {
        ChoiceSheet(
            title = "Player opening",
            options = PlayerMotion.entries,
            selected = ui.playerMotion,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(playerMotion = m) } },
            onDismiss = { playerMotionDialog = false },
        )
    }
    if (lyricsAnimationDialog) {
        ChoiceSheet(
            title = "Lyrics animation",
            options = LyricsAnimation.entries,
            selected = ui.lyricsAnimation,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { a -> AppSettings.updateUi { it.copy(lyricsAnimation = a) } },
            onDismiss = { lyricsAnimationDialog = false },
        )
    }
    if (downloadQualityDialog) {
        ChoiceSheet(
            title = "Download quality",
            options = AudioQuality.entries,
            selected = lib.downloadQuality,
            label = { "${it.label} · ${if (it == AudioQuality.MAX) "Lossless from add-ons when available; YouTube fallback" else it.summary}" },
            onSelect = { q -> AppSettings.updateLibrary { it.copy(downloadQuality = q) } },
            onDismiss = { downloadQualityDialog = false },
        )
    }
    if (confirmSignOut) {
        FloatingDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your likes and playlists on this device stay. YouTube Music stops seeing what you play here.") },
            confirmButton = {
                SheetButton("Sign out", tone = SheetTone.Danger, onClick = {
                    confirmSignOut = false
                    AccountStore.signOut()
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                })
            },
            dismissButton = { SheetButton("Cancel", onClick = { confirmSignOut = false }, closes = true) },
        )
    }
    if (folderDialog) {
        var folders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
        LaunchedEffect(Unit) { folders = runCatching { LocalMusic.folders(context) }.getOrDefault(emptyList()) }
        ChoiceSheet(
            title = "Local music folder",
            options = listOf<String?>(null) + folders.map { it.first },
            selected = lib.localFolder,
            label = { f ->
                f?.let { name ->
                    val n = folders.firstOrNull { it.first == name }?.second ?: 0
                    "$name · $n ${if (n == 1) "song" else "songs"}"
                } ?: "All audio folders · Every song on the phone"
            },
            onSelect = { f -> AppSettings.updateLibrary { it.copy(localFolder = f) } },
            onDismiss = { folderDialog = false },
        )
    }
    message?.let { m ->
        FloatingDialog(onDismissRequest = { message = null }, confirmButton = { SheetButton("OK", onClick = { message = null }, closes = true) }, text = { Text(m) })
    }

    return listOf(
        Section(
            "Account",
            listOfNotNull(
                Entry("YouTube Music account", "sign in login google profile switch add") {
                    if (signedIn) {
                        Column {
                            accounts.forEachIndexed { i, acc ->
                                if (i > 0) RowDivider()
                                SettingRow(
                                    acc.name.ifBlank { "Signed in" },
                                    summary = acc.email.ifBlank { "YouTube Music" },
                                    onClick = if (acc.active) null else ({ AccountStore.switchTo(acc.id) }),
                                    leading = { Artwork(acc.thumbnailUrl, Modifier.size(40.dp), CircleShape) },
                                    trailing = if (acc.active) ({ Icon(Icons.Rounded.Check, "Active account", tint = MaterialTheme.colorScheme.primary) }) else null,
                                )
                            }
                        }
                    } else {
                        NavRow("Sign in to YouTube Music", onSignIn, summary = "Your playlists, liked songs and recommendations", icon = Icons.Rounded.AccountCircle)
                    }
                },
                if (signedIn) {
                    Entry("Add another account", "add switch google account multiple") {
                        NavRow("Add another account", onSignIn, summary = "Sign in to a second YouTube Music account and switch between them", icon = Icons.Rounded.PersonAdd)
                    }
                } else null,
                Entry("Link a device", "tv television web browser computer cast speaker chromecast watch wear together qr pairing sign in with your phone platforms") {
                    NavRow("Link a device", onOpenDevices, summary = "Sign in your TV, the web player, speakers and more", icon = Icons.Rounded.Devices)
                },
                if (signedIn) {
                    Entry("Sign out", "logout account") {
                        SettingRow(if (accounts.size > 1) "Sign out of this account" else "Sign out", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { confirmSignOut = true })
                    }
                } else null,
            ),
        ),
        Section(
            "Audio quality",
            listOfNotNull(
                // The two look alike; the one for the network in use now says so.
                Entry("On Wi-Fi", "stream quality bitrate") {
                    NavRow("On Wi-Fi", { qualityDialog = true }, summary = if (!metered) "In use now" else null, icon = Icons.Rounded.Wifi, value = pb.wifiQuality.label)
                },
                Entry("On mobile data", "stream quality bitrate cellular") {
                    NavRow("On mobile data", { qualityDialog = false }, summary = if (metered) "In use now" else null, icon = Icons.Rounded.NetworkCell, value = pb.mobileQuality.label)
                },
                Entry("Output precision", "16-bit 32-bit float sample rate") {
                    SettingRow("Output precision", summary = outputSummary(context, pb.floatOutput), icon = Icons.Rounded.GraphicEq, beta = true, below = {
                        PillSegmented(listOf(false, true), pb.floatOutput, { if (it) "32-bit float" else "16-bit PCM" }, { v -> AppSettings.updatePlayback { it.copy(floatOutput = v) } })
                        Text(
                            "Float keeps hi-res files in 32-bit all the way to the output, effects included. 16-bit is dithered once, after the effects. Takes effect after a restart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    })
                },
                Entry("Lossless streaming", "flac lossless addons") {
                    ToggleRow("Lossless at other quality levels", pb.losslessStreaming, { v -> AppSettings.updatePlayback { it.copy(losslessStreaming = v) } }, summary = "Also check your audio add-ons after playback starts at other quality levels. Lossless checks them automatically in your chosen order.", icon = Icons.Rounded.HighQuality)
                },
                Entry("Unmetered networks only", "flac saavn mobile wifi data") {
                    ToggleRow("Unmetered networks only", pb.losslessUnmeteredOnly, { v -> AppSettings.updatePlayback { it.copy(losslessUnmeteredOnly = v) } }, summary = "When on, mobile data uses YouTube even with Lossless selected. Turn off to try FLAC and JioSaavn on mobile data", icon = Icons.Rounded.Wifi)
                },
                Entry("Audio add-ons", "source URL manifest order priority lossless flac high res") {
                    SettingRow("Audio add-ons", icon = Icons.Rounded.HighQuality, summary = "Add service URLs and choose source priority", onClick = { audioAddons = true })
                },
                Entry("Bit-perfect USB output", "dac exclusive passthrough hi-fi audiophile mixer bypass") {
                    ToggleRow(
                        "Bit-perfect USB output",
                        pb.bitPerfectUsb,
                        { v -> AppSettings.updatePlayback { it.copy(bitPerfectUsb = v) } },
                        summary = if (BitPerfectUsb.supported) "Sends the song unchanged to a USB DAC: no mixing, resampling, effects or crossfade. Volume is applied by the app; at full volume the signal is bit-exact."
                        else "Needs Android 14 or newer",
                        icon = Icons.Rounded.Usb,
                        enabled = BitPerfectUsb.supported,
                        beta = true,
                    )
                },
                Entry("System audio effects", "dolby atmos soundalive equalizer device effects") {
                    ToggleRow("System audio effects", pb.systemEffects, { v -> AppSettings.updatePlayback { it.copy(systemEffects = v) } }, summary = "Let the phone's own effects (Dolby, SoundAlive, system equalizer) process playback", icon = Icons.Rounded.SurroundSound)
                },
                Entry("Background playback", "battery optimization doze killed stops samsung") {
                    val ignoring = remember { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }
                    NavRow(
                        "Background playback",
                        { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        summary = if (ignoring) "Unrestricted: music keeps playing with the screen off" else "Restricted: if playback stops in the background, set OpenTune to Unrestricted",
                        icon = Icons.Rounded.BatteryChargingFull,
                    )
                },
                Entry("Prefer USB DAC", "external dac usb audio") {
                    ToggleRow("Prefer USB DAC", pb.preferUsbDac, { v -> AppSettings.updatePlayback { it.copy(preferUsbDac = v) } }, summary = "Send audio to a USB DAC whenever one is plugged in", icon = Icons.Rounded.Usb, beta = true)
                },
            ),
        ),
        Section(
            "Playback",
            listOfNotNull(
                Entry("Loudness normalization", "volume level") {
                    ToggleRow("Loudness normalization", pb.loudnessNormalization, { v -> AppSettings.updatePlayback { it.copy(loudnessNormalization = v) } }, summary = "Uses YouTube's loudness measurement to set one steady volume per song", icon = Icons.AutoMirrored.Rounded.VolumeUp)
                },
                if (pb.loudnessNormalization) Entry("Volume level", "loud louder quiet normal volume level loudness target boost") {
                    SettingRow("Volume level", summary = pb.volumeLevel.summary, icon = Icons.AutoMirrored.Rounded.VolumeUp, below = {
                        PillSegmented(VolumeLevel.entries, pb.volumeLevel, { it.label }, { v -> AppSettings.updatePlayback { it.copy(volumeLevel = v) } })
                    })
                } else null,
                if (pb.loudnessNormalization) Entry("Normalize on the phone speaker", "loudness volume speaker louder quiet normalization level") {
                    ToggleRow(
                        "Normalize on the phone speaker",
                        pb.normalizeOnSpeaker,
                        { v -> AppSettings.updatePlayback { it.copy(normalizeOnSpeaker = v) } },
                        summary = if (pb.normalizeOnSpeaker) "Songs are levelled on the speaker too, which turns loud ones down"
                        else "Off: the speaker never turns a song down, only lifts quiet ones; headphones and Bluetooth are fully levelled",
                        icon = Icons.Rounded.Speaker,
                    )
                } else null,
                Entry("Preload upcoming songs", "latency fast skip instant buffer") {
                    ToggleRow("Preload upcoming songs", pb.preloadUpcoming, { v -> AppSettings.updatePlayback { it.copy(preloadUpcoming = v) } }, summary = "Next and previous songs start almost instantly. Uses a little extra data.", icon = Icons.Rounded.Speed)
                },
                Entry("Upgrade quality while playing", "better stream premium 256 opus swap") {
                    ToggleRow("Upgrade quality while playing", pb.qualityUpgrade, { v -> AppSettings.updatePlayback { it.copy(qualityUpgrade = v) } }, summary = "If a clearly better stream turns up a few seconds in, switch to it without stopping", icon = Icons.Rounded.HighQuality, beta = true)
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
                Entry("Clarity", "studio master clarity presence air bass clear enhance") {
                    ToggleRow("Clarity", pb.clarity, { v -> AppSettings.updatePlayback { it.copy(clarity = v) } }, summary = "Firmer bass, less mud, more presence and air, at the same loudness. Off plays the stream untouched.", icon = Icons.Rounded.AutoAwesome, beta = true)
                },
                Entry("Spatial audio", "stereo widen immersive") {
                    ToggleRow("Spatial audio", pb.spatialAudio, { v -> AppSettings.updatePlayback { it.copy(spatialAudio = v) } }, summary = "Widens stereo tracks for a more open sound", icon = Icons.Rounded.SurroundSound, beta = true)
                },
                Entry("Equalizer", "eq bands tone balance bass treble") {
                    NavRow("Equalizer", onOpenEqualizer, summary = "Fifteen bands, tone and balance", icon = Icons.Rounded.Equalizer)
                },
                Entry("Autoplay", "radio continue") {
                    ToggleRow("Autoplay", pb.autoplay, AppSettings::setAutoplay, summary = "Keep playing similar songs when the queue ends", icon = Icons.AutoMirrored.Rounded.PlaylistPlay)
                },
                Entry("Recommendations", "recommendation engine spotify jiosaavn saavn youtube music radio suggestions similar songs") {
                    NavRow(
                        "Recommendations",
                        { recommenderDialog = true },
                        summary = "Whose picks autoplay adds and Home's \"Because you played\" shows. Songs still play from YouTube Music.",
                        icon = Icons.Rounded.AutoAwesome,
                        value = pb.recommender.label,
                    )
                },
                Entry("Don't repeat songs in current session", "autoplay duplicates") {
                    ToggleRow("Don't repeat songs in current session", pb.noRepeatInSession, { v -> AppSettings.updatePlayback { it.copy(noRepeatInSession = v) } }, summary = "Autoplay won't add a song already played or queued this session", icon = Icons.Rounded.History)
                },
                Entry("Smart shuffle", "shuffle similar songs mix recommendations playlist album sparkle") {
                    ToggleRow("Smart shuffle", pb.smartShuffle, { v -> AppSettings.updatePlayback { it.copy(smartShuffle = v) } }, summary = "Mixes a similar song in after every four. Tap the player's shuffle button twice, or long-press Shuffle on a playlist or album", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Hidden songs and artists", "not interested don't play hide block artist unhide suggestions") {
                    val count = hidden.songs.size + hidden.artists.size
                    NavRow(
                        "Hidden songs and artists",
                        { hiddenDialog = true },
                        summary = if (count == 0) "Kept out of autoplay, radio and Home. Nothing hidden yet"
                        else listOfNotNull(
                            hidden.songs.size.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
                            hidden.artists.size.takeIf { it > 0 }?.let { if (it == 1) "1 artist" else "$it artists" },
                        ).joinToString(" and ") + " kept out of suggestions",
                        icon = Icons.Rounded.VisibilityOff,
                    )
                },
                Entry("Resume when headphones connect", "bluetooth headset wired auto play resume connect car") {
                    ToggleRow("Resume when headphones connect", pb.resumeOnConnect, { v -> AppSettings.updatePlayback { it.copy(resumeOnConnect = v) } }, summary = "Carries on playing when headphones are plugged in or a Bluetooth device connects, while OpenTune is open or in the notification", icon = com.opentune.ui.player.PlayerIcons.Headphones)
                },
                Entry("Pause at zero volume", "mute volume down silent") {
                    ToggleRow("Pause at zero volume", pb.pauseAtZeroVolume, { v -> AppSettings.updatePlayback { it.copy(pauseAtZeroVolume = v) } }, summary = "Pauses when the volume is turned all the way down and plays again when it comes back up", icon = Icons.AutoMirrored.Rounded.VolumeOff)
                },
                Entry("SponsorBlock", "sponsorblock skip sponsor intro outro non-music talking music video segments") {
                    ToggleRow("Skip non-music parts", pb.sponsorBlock, { v -> AppSettings.updatePlayback { it.copy(sponsorBlock = v) } }, summary = "Jumps over talking, skits and sponsor reads in music videos, from SponsorBlock", icon = Icons.Rounded.FastForward)
                },
                if (pb.sponsorBlock) Entry("SponsorBlock categories", "sponsorblock skip categories") {
                    NavRow("What to skip", { sponsorBlockDialog = true }, summary = pb.sponsorBlockCategories.mapNotNull { SponsorBlock.CATEGORIES[it]?.label }.joinToString().ifEmpty { "Nothing" }, icon = Icons.Rounded.FastForward)
                } else null,
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
                Entry("Mini player takes the cover's colour", "mini player pill tint colour artwork plain") {
                    ToggleRow("Mini player takes the cover's colour", ui.miniPlayerTint, { v -> AppSettings.updateUi { it.copy(miniPlayerTint = v) } }, summary = "Off keeps the small player's glass plain, whatever is playing", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Artist pages take the photo's colour", "artist page tint colour photo background") {
                    ToggleRow("Artist pages take the photo's colour", ui.artistPageTint, { v -> AppSettings.updateUi { it.copy(artistPageTint = v) } }, summary = "Off keeps artist pages in the app's own colours", icon = Icons.Rounded.AutoAwesome)
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
                Entry("Player layout", "player style layout vinyl record lyrics minimal classic screen") {
                    NavRow("Player layout", { playerStyleDialog = true }, summary = ui.playerStyle.summary, icon = Icons.Rounded.Album, value = ui.playerStyle.label)
                },
                Entry("Player beside the page", "tablet foldable large screen side pane docked player split landscape") {
                    ToggleRow(
                        "Player beside the page",
                        ui.sidePlayer,
                        { v -> AppSettings.updateUi { it.copy(sidePlayer = v) } },
                        summary = "On large screens, the player stays open on the right while music plays",
                        icon = Icons.Rounded.VerticalSplit,
                        enabled = ui.wideLayout,
                    )
                },
                Entry("Player buttons", "play pause next previous skip controls buttons design morph disc squircle glass") {
                    NavRow("Player buttons", { controlStyleDialog = true }, summary = ui.controlStyle.summary, icon = Icons.Rounded.PlayCircle, value = ui.controlStyle.label)
                },
                Entry("Dock animation", "navigation bar dock fold hide scroll animation motion squash pop") {
                    NavRow("Dock animation", { dockMotionDialog = true }, summary = ui.dockMotion.summary, icon = Icons.Rounded.Dock, value = ui.dockMotion.label)
                },
                Entry("Dock lens", "navigation bar dock tab highlight selection pill indicator jelly stretch glide") {
                    NavRow("Dock lens", { dockLensDialog = true }, summary = ui.dockLens.summary, icon = Icons.Rounded.Lens, value = ui.dockLens.label)
                },
                Entry("Opening animation", "splash start launch intro logo reveal dive") {
                    ToggleRow(
                        "Opening animation",
                        ui.openingAnimation,
                        { v -> AppSettings.updateUi { it.copy(openingAnimation = v) } },
                        summary = "When OpenTune starts, the screen dives into the logo and the app comes through it",
                        icon = Icons.Rounded.AutoAwesome,
                    )
                },
                Entry("Page transitions", "navigation animation screen open back slide fade zoom rise") {
                    NavRow("Page transitions", { pageTransitionDialog = true }, summary = ui.pageTransition.summary, icon = Icons.Rounded.SwapHoriz, value = ui.pageTransition.label)
                },
                Entry("Glow under the cover", "glow shadow light cover colour bass pulse") {
                    ToggleRow("Glow under the cover", ui.coverGlow, { v -> AppSettings.updateUi { it.copy(coverGlow = v) } }, summary = "A soft light in the cover's colours under it, swelling a little with the bass", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Song change", "cover change next song skip animation flip carousel deck fade") {
                    NavRow("Song change", { coverChangeDialog = true }, summary = ui.coverChange.summary, icon = Icons.Rounded.SwapHoriz, value = ui.coverChange.label)
                },
                Entry("Player opening", "now playing sheet open close animation spring smooth bouncy snappy") {
                    NavRow("Player opening", { playerMotionDialog = true }, summary = ui.playerMotion.summary, icon = Icons.Rounded.UnfoldMore, value = ui.playerMotion.label)
                },
                Entry("Player background", "mesh gradient echo glow colours") {
                    SettingRow("Player background", icon = Icons.Rounded.Wallpaper, below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerBackground.entries.forEach { b ->
                                FilterChip(theme.playerBackground == b, { AppSettings.updateTheme { it.copy(playerBackground = b) } }, label = { Text(b.label) })
                            }
                        }
                    })
                },
                Entry("Moving cover art", "motion artwork canvas animated cover drift zoom") {
                    ToggleRow("Moving cover art", ui.movingCover, { v -> AppSettings.updateUi { it.copy(movingCover = v) } }, summary = "The cover drifts and zooms slowly in the player while a song plays", icon = Icons.Rounded.Animation)
                },
                Entry("Moving covers on Home", "motion artwork animated cover drift zoom home cards mixes") {
                    ToggleRow("Moving covers on Home", ui.homeMovingCovers, { v -> AppSettings.updateUi { it.copy(homeMovingCovers = v) } }, summary = "Song and album covers on Home drift and zoom slowly, one or two at a time, while you're not scrolling. Off in battery saver", icon = Icons.Rounded.Animation)
                },
                Entry("Full-screen cover art", "artwork edge") {
                    ToggleRow("Full-screen cover art", ui.fullScreenCover, { v -> AppSettings.updateUi { it.copy(fullScreenCover = v) } }, summary = "Runs the cover to the edges of the player instead of a square sleeve", icon = Icons.Rounded.Fullscreen)
                },
                Entry("Wavy seek bar", "wave progress slider") {
                    ToggleRow("Wavy seek bar", ui.wavySeekbar, { v -> AppSettings.updateUi { it.copy(wavySeekbar = v) } }, summary = "The played part of the bar ripples while music plays", icon = Icons.Rounded.Waves)
                },
                Entry("Lyric preview while seeking", "scrub seek bar lyric line preview bubble drag") {
                    ToggleRow("Lyric preview while seeking", ui.lyricScrubPreview, { v -> AppSettings.updateUi { it.copy(lyricScrubPreview = v) } }, summary = "Dragging the seek bar shows the line sung at that point above your finger", icon = Icons.Rounded.Lyrics)
                },
                Entry("Seek bar thickness", "progress bar seek slider thin thick height") {
                    SettingRow("Seek bar thickness", icon = Icons.Rounded.LineWeight, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarThickness.entries, ui.seekbarThickness, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarThickness = v) } })
                    })
                },
                Entry("Seek bar handle", "progress bar thumb knob dot line handle") {
                    SettingRow("Seek bar handle", icon = Icons.Rounded.RadioButtonChecked, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarThumb.entries, ui.seekbarThumb, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarThumb = v) } })
                    })
                },
                Entry("Seek bar colour", "progress bar color accent gradient") {
                    SettingRow("Seek bar colour", summary = "Accent and Gradient follow the cover when the player takes its colours", icon = Icons.Rounded.Palette, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarColor.entries, ui.seekbarColor, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarColor = v) } })
                    })
                },
                Entry("Seek bar time", "progress bar remaining total length duration time left") {
                    SettingRow("Time on the right", icon = Icons.Rounded.Schedule, below = {
                        PillSegmented(com.opentune.data.settings.SeekbarTime.entries, ui.seekbarTime, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarTime = v) } })
                    })
                },
                Entry("Song part marks", "seek bar chorus verse dots marks parts") {
                    ToggleRow("Song part marks", ui.seekbarMarks, { v -> AppSettings.updateUi { it.copy(seekbarMarks = v) } }, summary = "Small dots on the seek bar where the verse and chorus begin, when the lyrics mark them", icon = Icons.Rounded.MoreHoriz)
                },
                Entry("Skip to chorus", "chorus jump skip button song parts") {
                    ToggleRow("Skip to chorus button", ui.skipToChorus, { v -> AppSettings.updateUi { it.copy(skipToChorus = v) } }, summary = "A button under the title that jumps to the next chorus, when the lyrics mark one", icon = Icons.Rounded.FastForward)
                },
                Entry("Audio quality label", "audio source quality pill badge codec bitrate jiosaavn youtube") {
                    ToggleRow("Show the quality label", ui.sourcePill, { v -> AppSettings.updateUi { it.copy(sourcePill = v) } }, summary = "Shows quality and upgrade progress below the seek bar; tap for the full signal path", icon = Icons.Rounded.HighQuality)
                },
                Entry("Album covers", "artwork cover musicbrainz cover art archive video thumbnail") {
                    ToggleRow("Sharper album covers", ui.albumCovers, { v -> AppSettings.updateUi { it.copy(albumCovers = v) } }, summary = "The player shows the album's cover from Apple Music, Deezer or the Cover Art Archive, sharper than YouTube's and in place of a video frame", icon = Icons.Rounded.Album)
                },
                Entry("Animated covers", "apple music motion artwork video moving album cover animated") {
                    ToggleRow("Animated covers", ui.animatedCovers, { v -> AppSettings.updateUi { it.copy(animatedCovers = v) } }, summary = "When Apple Music has a moving cover for the album, it loops silently in the player's cover", icon = Icons.Rounded.Animation)
                },
                Entry("Spotify", "spotify canvas video loop background connect sign in playlists library sync") {
                    if (spotifyConnected) {
                        ToggleRow("Spotify Canvas", ui.spotifyCanvas, { v -> AppSettings.updateUi { it.copy(spotifyCanvas = v) } }, summary = "When a song has a Canvas on Spotify, it loops silently behind the player", icon = Icons.Rounded.Animation)
                        RowDivider()
                        SettingRow(
                            "Spotify playlists",
                            summary = when {
                                spotifySync.running && spotifySync.current != null -> "Bringing over ${spotifySync.current} (${spotifySync.index} of ${spotifySync.count})"
                                spotifySync.running -> "Reading your Spotify library…"
                                spotifySync.error != null -> "${spotifySync.error}. Tap to try again"
                                spotifySync.lastSynced > 0 -> "In Library, marked Spotify. Checked " +
                                    android.text.format.DateUtils.getRelativeTimeSpanString(spotifySync.lastSynced) + ". Tap to check now"
                                else -> "Tap to bring your Spotify playlists into Library"
                            },
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            enabled = !spotifySync.running,
                            onClick = { com.opentune.ui.spotify.SpotifyConnection.syncPlaylists(force = true) },
                        )
                        RowDivider()
                        SettingRow("Disconnect Spotify", summary = "Signs out of Spotify inside OpenTune. Playlists already brought over stay in Library", icon = Icons.Rounded.Close, onClick = { com.opentune.ui.spotify.SpotifyConnection.disconnect() })
                    } else {
                        NavRow(
                            "Connect Spotify",
                            { context.startActivity(android.content.Intent(context, com.opentune.ui.spotify.SpotifyConnectActivity::class.java)) },
                            summary = spotifyProblem?.let { "$it. Sign in again" } ?: "Sign in on Spotify's own page to bring your playlists into Library and show each song's Canvas behind the player",
                            icon = Icons.Rounded.Animation,
                        )
                    }
                },
                Entry("Animated covers on Wi-Fi only", "mobile data metered save data wifi animated covers canvas video") {
                    ToggleRow("Animated covers on Wi-Fi only", ui.animatedCoversWifiOnly, { v -> AppSettings.updateUi { it.copy(animatedCoversWifiOnly = v) } }, summary = "Animated covers and Canvas don't load on mobile data", icon = Icons.Rounded.Wifi, enabled = ui.animatedCovers || (spotifyConnected && ui.spotifyCanvas))
                },
                Entry("Reduce animation", "motion") {
                    ToggleRow("Reduce animation", ui.reduceAnimation, { v -> AppSettings.updateUi { it.copy(reduceAnimation = v) } }, summary = "Freezes decorative motion and simplifies player and lyric transitions", icon = Icons.Rounded.Animation)
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
                Entry("Glass style", "glass frosted blur clear heavy tinted smoke theme material") {
                    NavRow("Glass style", { glassStyleDialog = true }, summary = ui.glassStyle.summary, icon = Icons.Rounded.BlurOn, value = ui.glassStyle.label)
                },
                Entry("Frosted top edge", "status bar blur fade top scroll glass") {
                    ToggleRow("Frosted top edge", ui.frostedTopEdge, { v -> AppSettings.updateUi { it.copy(frostedTopEdge = v) } }, summary = "Pages blur and fade as they scroll under the status bar", icon = Icons.Rounded.Gradient)
                },
                Entry("High performance mode", "120hz 90hz smooth fps refresh rate frame rate scrolling performance battery unrestricted lag") {
                    HighPerformanceRows(ui)
                },
                Entry("Speed dial", "home most played quick grid shortcuts favourites top") {
                    ToggleRow("Speed dial", ui.speedDial, { v -> AppSettings.updateUi { it.copy(speedDial = v) } }, summary = "Your most-played songs, albums and artists at the top of Home", icon = Icons.Rounded.GridView)
                },
                Entry("Separate Explore tab", "explore tab dock search page navigation moods genres own") {
                    ToggleRow(
                        "Separate Explore tab",
                        ui.separateExplore,
                        { v -> AppSettings.updateUi { it.copy(separateExplore = v) } },
                        summary = if (ui.separateExplore) "Explore has a tab in the dock" else "Explore fills the Search page until you start a search",
                        icon = Icons.Rounded.Explore,
                    )
                },
                Entry("Wide-screen layout", "tablet foldable large screen landscape rail side navigation dock columns") {
                    ToggleRow(
                        "Wide-screen layout",
                        ui.wideLayout,
                        { v -> AppSettings.updateUi { it.copy(wideLayout = v) } },
                        summary = "On tablets, unfolded phones and in landscape, the tabs move to a rail on the side and pages keep a readable width",
                        icon = Icons.Rounded.TabletAndroid,
                    )
                },
                Entry("Reduce dynamic blur", "glass frosted performance") {
                    ToggleRow("Reduce dynamic blur", ui.reduceBlur, { v -> AppSettings.updateUi { it.copy(reduceBlur = v) } }, summary = "Swaps frosted glass for solid fills across the app", icon = Icons.Rounded.BlurOff)
                },
                Entry("Haptic feedback", "vibration haptics strength tap buzz intensity") {
                    val haptics = com.opentune.ui.components.rememberHaptics()
                    SettingRow(
                        "Haptic feedback",
                        summary = com.opentune.ui.components.Haptics.label(ui.hapticStrength) +
                            if (ui.hapticStrength > 0f) " · ${(ui.hapticStrength * 100).roundToInt()}%" else " · No taps or buzzes",
                        icon = Icons.Rounded.Vibration,
                        below = {
                            Slider(
                                value = ui.hapticStrength,
                                onValueChange = { v -> AppSettings.updateUi { it.copy(hapticStrength = (v * 10).roundToInt() / 10f) } },
                                // Let go and feel the new strength.
                                onValueChangeFinished = { haptics.press() },
                                valueRange = 0f..1f,
                                steps = 9,
                            )
                        },
                    )
                },
            ),
        ),
        Section(
            "Lyrics",
            listOf(
                Entry("Synced lyrics", "karaoke words") {
                    ToggleRow("Synced lyrics", ui.syncedLyrics, { v -> AppSettings.updateUi { it.copy(syncedLyrics = v) } }, summary = "Lights up the words as they're sung", icon = Icons.Rounded.Lyrics)
                },
                Entry("Lyrics animation", "style karaoke slide zoom fluid motion") {
                    NavRow("Lyrics animation", { lyricsAnimationDialog = true }, summary = ui.lyricsAnimation.summary, icon = Icons.Rounded.Animation, value = ui.lyricsAnimation.label)
                },
                Entry("Lyrics text size", "font bigger smaller") {
                    SettingRow(
                        "Lyrics text size",
                        summary = "${(ui.lyricsTextScale * 100).roundToInt()}%" + if (ui.lyricsTextScale == 1f) " · Standard" else "",
                        icon = Icons.Rounded.FormatSize,
                        below = {
                            Slider(
                                value = ui.lyricsTextScale,
                                onValueChange = { v -> AppSettings.updateUi { it.copy(lyricsTextScale = (v * 20).roundToInt() / 20f) } },
                                valueRange = 0.7f..1.5f,
                                steps = 15,
                            )
                        },
                    )
                },
                Entry("Lyrics alignment", "lyrics center centre left align") {
                    SettingRow("Lyrics alignment", icon = Icons.Rounded.FormatAlignCenter, below = {
                        PillSegmented(LyricsAlign.entries, ui.lyricsAlign, { it.label }, { a -> AppSettings.updateUi { it.copy(lyricsAlign = a) } })
                    })
                },
                Entry("Glow on the sung line", "lyrics glow shine highlight") {
                    ToggleRow("Glow on the sung line", ui.lyricsGlow, { v -> AppSettings.updateUi { it.copy(lyricsGlow = v) } }, summary = "A soft halo around the line being sung, with any animation", icon = Icons.Rounded.Flare)
                },
                Entry("Blur unfocused lyrics", "spotlight") {
                    ToggleRow("Blur unfocused lyrics", ui.blurLyrics, { v -> AppSettings.updateUi { it.copy(blurLyrics = v) } }, summary = "Keeps the spotlight on the current line", icon = Icons.Rounded.BlurOn)
                },
                Entry("Lyrics take the screen", "hide controls idle focus fullscreen lyrics auto") {
                    ToggleRow("Lyrics take the screen", ui.lyricsFocus, { v -> AppSettings.updateUi { it.copy(lyricsFocus = v) } }, summary = "On the lyrics view, the controls slide away after a few seconds untouched. Touch or swipe to bring them back.", icon = Icons.Rounded.Lyrics)
                },
                Entry("Lyrics sources", "lrclib youtube order provider") {
                    NavRow(
                        "Lyrics sources",
                        { lyricsSourcesDialog = true },
                        summary = lyricsSettings.ordered.filter { it.enabled }.joinToString(", then ") { it.source.label }.ifEmpty { "None: lyrics are off" },
                        icon = Icons.Rounded.TextFields,
                    )
                },
            ),
        ),
        Section(
            "New releases",
            listOf(
                Entry("New-release alerts", "new release album single notify follow artists notification") {
                    ToggleRow(
                        "New-release alerts",
                        lib.releaseAlerts,
                        { v ->
                            AppSettings.updateLibrary { it.copy(releaseAlerts = v) }
                            NewReleases.schedule(context)
                        },
                        summary = "A notification when an artist you follow puts out an album or single. Checked twice a day.",
                        icon = Icons.Rounded.NotificationsActive,
                    )
                },
                Entry("Followed artists", "follow artists unfollow") {
                    NavRow("Followed artists", { followedDialog = true }, summary = if (followedArtists.isEmpty()) "Follow from an artist's page" else "${followedArtists.size} artists", icon = Icons.Rounded.Person)
                },
            ),
        ),
        Section(
            "Content",
            listOf(
                Entry("Hide explicit content", "explicit clean kids family parental filter") {
                    ToggleRow(
                        "Hide explicit content",
                        lib.hideExplicit,
                        { v -> AppSettings.updateLibrary { it.copy(hideExplicit = v) } },
                        summary = "Leaves out songs and albums marked explicit on Home, in search, on album and artist pages and in autoplay",
                        icon = Icons.Rounded.FilterAlt,
                    )
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
                Entry("Stream downloads when online", "offline file internet play quality") {
                    val pbd by AppSettings.playback.collectAsState()
                    ToggleRow("Stream downloads when online", pbd.streamDownloadsOnline, { v -> AppSettings.updatePlayback { it.copy(streamDownloadsOnline = v) } }, summary = "Downloaded songs stream in their best quality with internet and play from the file offline", icon = Icons.Rounded.Wifi)
                },
                Entry("Export compatible downloads", "music folder other apps files save export mp4 aac copy") {
                    ToggleRow(
                        "Export compatible downloads",
                        lib.exportDownloads && com.opentune.data.download.DownloadExport.supported,
                        { v ->
                            AppSettings.updateLibrary { it.copy(exportDownloads = v) }
                            // Turned on: the songs already downloaded go into the Music folder too.
                            if (v) scope.launch {
                                val (copied, skipped) = com.opentune.data.download.Downloads.exportExisting(context.applicationContext)
                                val note = buildString {
                                    append(if (copied == 1) "1 song" else "$copied songs")
                                    append(" copied to Music/OpenTune")
                                    if (skipped > 0) append("; $skipped in a format other apps can't play, download them again to export")
                                }
                                if (copied > 0 || skipped > 0) com.opentune.data.Pills.done(note, context)
                            }
                        },
                        summary = if (com.opentune.data.download.DownloadExport.supported)
                            "Also saves each download to Music/OpenTune as AAC (M4A) or FLAC, so other music apps can play it"
                        else "Needs Android 10 or newer",
                        icon = Icons.Rounded.DownloadDone,
                        enabled = com.opentune.data.download.DownloadExport.supported,
                    )
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
                Entry("Delete downloaded updates", "apk update installer free space") {
                    NavRow("Delete downloaded updates", {
                        scope.launch {
                            withContext(Dispatchers.IO) { Updater.deleteCached(context) }
                            updateBytes = 0
                            message = "Downloaded updates deleted."
                        }
                    }, summary = if (updateBytes > 0) "Using ${Formatter.formatShortFileSize(context, updateBytes)}" else "None kept", icon = Icons.Rounded.DeleteSweep)
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
                Entry("Wrapped", "wrapped story year review stats recap top artists") {
                    NavRow("Wrapped", onOpenWrapped, summary = "Your listening as an animated story", icon = Icons.Rounded.AutoAwesome)
                },
                Entry("Export data", "backup json") {
                    NavRow("Export data", { exporter.launch("opentune-backup.json") }, summary = "Settings, history, likes and playlists, as one JSON file", icon = Icons.Rounded.Upload)
                },
                Entry("Import data", "restore json") {
                    NavRow("Import data", { importer.launch(arrayOf("application/json")) }, summary = "Replaces the settings, history, likes and playlists on this device", icon = Icons.Rounded.Download)
                },
                Entry("Clear listening history", "delete history") {
                    NavRow("Clear listening history", { History.clear(); com.opentune.data.history.ListenedCollections.clear(); message = "Listening history cleared." }, summary = "Removes Recents and Replay data", icon = Icons.Rounded.DeleteSweep)
                },
            ),
        ),
        Section(
            "Your music server",
            listOfNotNull(
                Entry("Music server", "subsonic navidrome gonic airsonic jellyfin flac lossless server") {
                    val connected = musicServer
                    if (connected == null) {
                        NavRow("Connect a server", { serverDialog = true }, summary = "Stream your own FLAC and hi-res files from any Subsonic server", icon = Icons.Rounded.Dns)
                    } else {
                        NavRow(
                            "${connected.user} on ${connected.url.substringAfter("://")}",
                            { serverDialog = true },
                            summary = "Streams stored files as they are. Tap to sign in again.",
                            icon = Icons.Rounded.Dns,
                        )
                    }
                },
                if (musicServer != null) Entry("Disconnect server", "sign out subsonic") {
                    SettingRow("Disconnect server", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { Subsonic.disconnect() })
                } else null,
            ),
        ),
        Section(
            "Last.fm (optional)",
            listOfNotNull(
                Entry("Last.fm scrobbling", "scrobble lastfm audioscrobbler history") {
                    val who = lastFm
                    if (who == null) {
                        NavRow("Connect Last.fm", { lastFmDialog = true }, summary = "Scrobble what you play with your own Last.fm API account", icon = Icons.Rounded.History)
                    } else {
                        SettingRow(
                            "Scrobbling as ${who.user}",
                            summary = if (scrobblesWaiting > 0) "$scrobblesWaiting scrobbles waiting to send" else "Songs are scrobbled after half their length or 4 minutes",
                            icon = Icons.Rounded.History,
                        )
                    }
                },
                if (lastFm != null) Entry("Disconnect Last.fm", "sign out lastfm") {
                    SettingRow("Disconnect Last.fm", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { LastFm.signOut() })
                } else null,
            ),
        ),
        Section(
            "ListenBrainz (optional)",
            listOfNotNull(
                Entry("ListenBrainz", "listenbrainz scrobble history recommendations metabrainz") {
                    val who = listenBrainz
                    if (who == null) {
                        NavRow("Connect ListenBrainz", { listenBrainzDialog = true }, summary = "Free, open listening history, and recommendations on Home", icon = Icons.Rounded.History)
                    } else {
                        SettingRow(
                            "Sending listens as ${who.user}",
                            summary = if (listensWaiting > 0) "$listensWaiting listens waiting to send" else "Recommendations from your listens show on Home",
                            icon = Icons.Rounded.History,
                        )
                    }
                },
                if (listenBrainz != null) Entry("Disconnect ListenBrainz", "sign out listenbrainz") {
                    SettingRow("Disconnect ListenBrainz", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { ListenBrainz.signOut() })
                } else null,
            ),
        ),
        Section(
            "Import",
            listOf(
                Entry("Import a playlist", "spotify youtube music import playlist album link csv exportify transfer migrate") {
                    NavRow("Import a playlist", onOpenSpotify, summary = "From a Spotify or YouTube Music link, or a CSV export", icon = Icons.AutoMirrored.Rounded.QueueMusic)
                },
            ),
        ),
        Section(
            "Miscellaneous",
            listOf(
                Entry("Swipe songs to queue them", "gesture swipe queue play next add to queue") {
                    ToggleRow("Swipe songs to queue them", pb.swipeToQueue, { v -> AppSettings.updatePlayback { it.copy(swipeToQueue = v) } }, summary = "Swipe a song right to play it next, or left to add it to the end of the queue", icon = Icons.AutoMirrored.Rounded.PlaylistPlay)
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
                Entry("What's new", "release notes changelog version highlights") {
                    NavRow("What's new", { showWhatsNew = true }, summary = "Highlights and improvements in ${BuildConfig.VERSION_NAME}", icon = Icons.Rounded.SystemUpdate)
                },
                Entry("Show the welcome again", "welcome onboarding first run start intro pick artists setup") {
                    NavRow("Show the welcome again", { com.opentune.data.Welcome.showAgain() }, summary = "Pick artists, choose a look and sign in, as on the first start", icon = Icons.Rounded.WavingHand)
                },
                Entry("Check for updates", "new version release download github") {
                    NavRow("Check for updates", {
                        // A release already found opens at once; Update still looks for a newer one first.
                        pendingUpdate?.let { update = it; return@NavRow }
                        scope.launch {
                            when (val r = UpdateCheck.checkNow(context)) {
                                is UpdateCheck.Result.Newer -> update = r.release
                                UpdateCheck.Result.UpToDate -> message = "You have the latest version (${BuildConfig.VERSION_NAME})."
                                UpdateCheck.Result.NoReleases -> message = "No releases are published yet. Builds are on the GitHub branch for now."
                                is UpdateCheck.Result.Failed -> message = "Couldn't check: ${r.reason}"
                            }
                        }
                    }, summary = pendingUpdate?.let { "OpenTune ${it.version} is ready to install. You have ${BuildConfig.VERSION_NAME}." } ?: "Current version: ${BuildConfig.VERSION_NAME}", icon = Icons.Rounded.SystemUpdate, dot = pendingUpdate != null)
                },
                Entry("Check for updates automatically", "update new version release auto") {
                    ToggleRow("Check for updates automatically", ui.checkForUpdates, { v -> AppSettings.updateUi { it.copy(checkForUpdates = v) }; com.opentune.data.UpdateCheck.schedule(context) }, summary = "Looks for a new release on GitHub every few hours, in the background too, and offers to install it", icon = Icons.Rounded.SystemUpdate)
                },
                Entry("Count this phone in usage numbers", "anonymous usage statistics analytics counter privacy telemetry") {
                    ToggleRow(
                        "Count this phone in usage numbers",
                        ui.countUsage,
                        { v -> AppSettings.updateUi { it.copy(countUsage = v) }; com.opentune.data.Usage.consentChanged(v) },
                        summary = "Shares a random installation ID, app version, active dates and daily play totals. No hardware ID, account or song names. Reinstalls get a new ID; updates keep it.",
                        icon = Icons.Rounded.Info,
                    )
                },
                Entry("OpenTune community", "github discord community addons developer") { CommunityBanner() },
                Entry("Support OpenTune", "donate upi pay money tip support developer") {
                    NavRow("Support OpenTune", { supportDialog = true }, summary = "A small thank-you helps keep OpenTune going", icon = Icons.Rounded.Favorite)
                },
                Entry("Star on GitHub", "star github rate like") {
                    NavRow("Star on GitHub", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, com.opentune.ui.components.Support.REPO.toUri())) }
                    }, summary = "Helps other people find OpenTune", icon = Icons.Rounded.Star)
                },
                Entry("Source code", "github open source license gpl") {
                    NavRow("Source code", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/justshivv/Opentune".toUri())) }
                    }, summary = "github.com/justshivv/Opentune · GPL v3", icon = Icons.Rounded.Code)
                },
                Entry("Export diagnostics", "log share troubleshooting bug report") {
                    NavRow("Export diagnostics", {
                        scope.launch {
                            val log = LogExport.recent()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "OpenTune ${BuildConfig.VERSION_NAME} log")
                                .putExtra(Intent.EXTRA_TEXT, log)
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        }
                    }, summary = "Share this app's recent log for troubleshooting", icon = Icons.Rounded.BugReport)
                },
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

