package com.opentune.ui.settings

import android.content.Context
import com.opentune.ui.components.launchSafely
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opentune.data.settings.bitPerfectMode
import androidx.compose.material3.FilterChip
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
import com.opentune.data.download.downloadedPlayback
import com.opentune.data.download.withDownloadedPlayback
import com.opentune.data.history.History
import com.opentune.data.lastfm.LastFm
import com.opentune.data.library.LibraryStore
import com.opentune.data.listenbrainz.ListenBrainz
import com.opentune.data.local.LocalMusic
import com.opentune.data.releases.NewReleases
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.settings.ChoiceMenuStyle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

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
    onOpenDiscord: () -> Unit = {},
    /** A page to open on (its key, "account" for Account & integrations); the main list when null. */
    startPage: String? = null,
) {
    val motion by AppSettings.ui.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf(startPage) }
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val updatePending = com.opentune.ui.components.updatePending()
    val sections = settingsSections(onOpenEqualizer, onOpenReplay, onOpenWrapped, onSignIn, onOpenDownloads, onOpenSpotify, onOpenDevices, onOpenDiscord)
    val placed = remember(sections) { place(sections) }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    if (sourcesOpen) AudioAddonsSheet { sourcesOpen = false }
    val open = SETTINGS_CATEGORIES.firstOrNull { it.key == openKey }
    androidx.activity.compose.BackHandler(enabled = open != null) { openKey = null }
    val q = query.trim()
    val languageContext = LocalContext.current
    // Kept out here, so coming back from a page finds the main list where it was left.
    val mainList = androidx.compose.foundation.lazy.rememberLazyListState()
    // Which folded subsections have been opened with "See more".
    var unfolded by rememberSaveable { mutableStateOf(emptySet<String>()) }

    androidx.compose.animation.AnimatedContent(
        open,
        modifier = Modifier.fillMaxSize(),
        contentKey = { it?.key ?: "settings-root" },
        transitionSpec = {
            val forward = targetState != null
            val enter = if (motion.reduceAnimation) androidx.compose.animation.EnterTransition.None else
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (forward) it / 8 else -it / 8 }
            val exit = if (motion.reduceAnimation) androidx.compose.animation.ExitTransition.None else
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            (enter togetherWith exit).using(null)
        },
        label = "settingsPage",
    ) { page ->
        LazyColumn(
            state = if (page == null) mainList else androidx.compose.foundation.lazy.rememberLazyListState(),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (page != null) {
                item { PageHeader(com.opentune.ui.theme.tr(page.title), onBack = { openKey = null }) }
                placed.filter { it.category == page && it.entry.title !in MAIN_PAGE_ONLY }.groupBy { it.group }.forEach { (group, rows) ->
                    item(key = "label:$group") {
                        when {
                            group in UNLABELLED_GROUPS -> androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                            // The side-by-side group wears its label in the middle, over the pair.
                            group in SIDE_BY_SIDE_GROUPS -> Text(
                                com.opentune.ui.theme.tr(group).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 10.dp),
                            )
                            else -> GroupLabel(group)
                        }
                    }
                    item(key = "card:$group") {
                        val shown = FOLDED_GROUPS[group]?.takeIf { group !in unfolded && it < rows.size }
                        if (group in SIDE_BY_SIDE_GROUPS) {
                            // No card behind this group: the pair sits on the page itself, together in the middle,
                            // in the layout's order on every screen — the first name sits on the left.
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                ) {
                                    rows.forEach { row -> row.entry.content() }
                                }
                            }
                        } else {
                            GroupCard(if (motion.reduceAnimation) Modifier else Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))) {
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
                    GROUP_NOTES[group]?.let { note ->
                        item(key = "note:$group") {
                            Text(
                                note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp),
                            )
                        }
                    }
                }
            } else {
                item { PageHeader(com.opentune.ui.theme.tr("Settings"), onBack = onBack) }
                item {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(com.opentune.ui.theme.tr("Search settings")) },
                        leadingIcon = { Icon(ImageVector.vectorResource(AppIcons.Search), null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(ImageVector.vectorResource(AppIcons.Close), "Clear") } },
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
                        // In English and in the app's language, so a search in either finds it.
                        listOf(it.entry.title, it.entry.summary, it.group, it.category.title)
                            .any { text -> text.contains(q, true) || com.opentune.i18n.Tr.t(text).contains(q, true) }
                    }
                    if (hits.isEmpty()) {
                        item { Text(com.opentune.i18n.Tr.f("No settings match \"{0}\".", q), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
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
                                    // Audio sources sits on the main page, just above the sound settings.
                                    if (c.key == "audio") {
                                        NavRow("Audio sources", { sourcesOpen = true }, summary = audioSourcesSummary(), icon = ImageVector.vectorResource(AppIcons.Extension))
                                        RowDivider()
                                    }
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
                                        icon = if (avatar == null) ImageVector.vectorResource(c.iconRes) else null,
                                        dot = c.key == "updates" && updatePending,
                                        badge = if (c.key == "updates") BuildConfig.VERSION_NAME else null,
                                        leading = avatar?.let { url -> { Artwork(url, Modifier.size(28.dp), CircleShape) } },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        com.opentune.i18n.Tr.f("OpenTune {0} • GNU GPL v3 • Lyrics from LRCLIB", BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                    )
                }
            }
        }
    }
}

/** What the Audio sources row says: how many lossless sources are on, or that there are none yet. */
@Composable
private fun audioSourcesSummary(): String {
    val addons by com.opentune.data.addons.AddonStore.addons.collectAsState()
    val on = addons.count { it.enabled && !it.ownerOff }
    return when {
        addons.isEmpty() -> com.opentune.i18n.Tr.t("Add lossless sources and choose their order")
        on == 1 -> com.opentune.i18n.Tr.t("1 lossless source on · choose the order")
        else -> com.opentune.i18n.Tr.f("{0} lossless sources on · choose the order", on)
    }
}

/** One developer on the About page: the photo, the name under it, and a GitHub badge on the photo's corner — the badge alone is the button, and it opens the profile. */
@Composable
private fun DeveloperCard(name: String, avatarUrl: String, profileUrl: String) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Box {
            Artwork(avatarUrl, Modifier.size(64.dp), CircleShape)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, profileUrl.toUri())) } },
            ) {
                Icon(githubLogo, "GitHub", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
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
            if (open) "See less" else com.opentune.i18n.Tr.f("See {0} more", hidden),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(ImageVector.vectorResource(AppIcons.ExpandMore), null, Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.primary)
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
    onOpenDiscord: () -> Unit,
): List<Section> {
    val discord by com.opentune.data.discord.DiscordRpc.account.collectAsState()
    val discordOn by com.opentune.data.discord.DiscordRpc.enabled.collectAsState()
    val discordStatus by com.opentune.data.discord.DiscordRpc.status.collectAsState()
    val discordProblem by com.opentune.data.discord.DiscordRpc.problem.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    val accounts by AccountStore.accounts.collectAsState()
    val done by Downloads.done.collectAsState(Downloads.doneNow())
    var downloadQualityDialog by remember { mutableStateOf(false) }
    var downloadedPlaybackDialog by remember { mutableStateOf(false) }
    var lyricsSourcesDialog by remember { mutableStateOf(false) }
    var bitPerfectSheet by remember { mutableStateOf(false) }
    var lastFmDialog by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf(false) }
    var listenBrainzDialog by remember { mutableStateOf(false) }
    var sponsorBlockDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var supportDialog by remember { mutableStateOf(false) }
    val pendingUpdate by UpdateCheck.pending.collectAsState()
    var updateBytes by remember { mutableStateOf(0L) }
    var playerStyleDialog by remember { mutableStateOf(false) }
    var automixPerformanceDialog by remember { mutableStateOf(false) }
    var profileDialog by remember { mutableStateOf(false) }
    var appIconDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var recommenderDialog by remember { mutableStateOf(false) }
    var historyTargetDialog by remember { mutableStateOf(false) }
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
    var sheetStyleDialog by remember { mutableStateOf(false) }
    var pageLayoutDialog by remember { mutableStateOf(false) }
    var choiceMenusDialog by remember { mutableStateOf(false) }
    var artistShadeDialog by remember { mutableStateOf(false) }
    var listBackgroundDialog by remember { mutableStateOf(false) }
    var pageButtonsDialog by remember { mutableStateOf(false) }
    var pageTransitionDialog by remember { mutableStateOf(false) }
    var coverChangeDialog by remember { mutableStateOf(false) }
    var controlStyleDialog by remember { mutableStateOf(false) }
    val lastFm by LastFm.account.collectAsState()
    val scrobblesWaiting by LastFm.queued.collectAsState()
    val lyricsSettings by AppSettings.lyrics.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    var listenAsOpen by remember { mutableStateOf(false) }
    if (listenAsOpen) ListenAsSheet(onDismiss = { listenAsOpen = false })
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(update) { updateBytes = withContext(Dispatchers.IO) { Updater.cachedBytes(context) } }
    val theme by AppSettings.theme.collectAsState()
    val pb by AppSettings.playback.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val lib by AppSettings.library.collectAsState()
    // What other settings decide, for the rows they make do nothing: those are shown greyed out,
    // with what turns them on, and keep what they're set to for when it does.
    val poster = ui.pageLayout == com.opentune.data.settings.PageLayout.POSTER
    val reduced = ui.reduceAnimation
    val appleStyle = ui.playerStyle == PlayerStyle.APPLE_MUSIC
    val amoledPlayer = theme.playerBackground == PlayerBackground.AMOLED
    // The tabs are in a rail down the side in this window (AppRoot picks it the same way), not the dock.
    val railShown = with(androidx.compose.ui.platform.LocalDensity.current) {
        val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        com.opentune.ui.appLayout(window.width.toDp().value.toInt(), window.height.toDp().value.toInt(), hasSong = false, wideLayout = ui.wideLayout).nav == com.opentune.ui.NavChrome.RAIL
    }
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
            }.getOrElse { com.opentune.i18n.Tr.f("Export failed: {0}", it.message) }
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
            }.getOrElse { com.opentune.i18n.Tr.f("Import failed: {0}", it.message) }
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
    if (appIconDialog) AppIconDialog { appIconDialog = false }
    if (fontDialog) com.opentune.ui.theme.FontSettingsSheet { fontDialog = false }
    if (profileDialog) {
        ChoiceSheet(
            title = "Performance profile",
            options = com.opentune.data.settings.PerformanceProfile.entries,
            selected = ui.performanceProfile,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { profile ->
                val (newUi, newPb) = com.opentune.data.settings.performanceApplied(AppSettings.ui.value, AppSettings.playback.value, profile)
                AppSettings.updateUi { newUi }
                AppSettings.updatePlayback { newPb }
                // The glass's costlier layers go with Battery saver, and come back with High performance.
                when (profile) {
                    com.opentune.data.settings.PerformanceProfile.BATTERY_SAVER -> com.opentune.ui.components.GlassPerformance.choose(true)
                    com.opentune.data.settings.PerformanceProfile.PERFORMANCE -> com.opentune.ui.components.GlassPerformance.choose(false)
                    else -> Unit
                }
            },
            onDismiss = { profileDialog = false },
        )
    }
    if (automixPerformanceDialog) {
        ChoiceSheet(
            title = "Automix performance",
            options = com.opentune.data.settings.AutomixPerformance.entries,
            selected = pb.automixPerformance,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { v -> AppSettings.updatePlayback { it.copy(automixPerformance = v) } },
            onDismiss = { automixPerformanceDialog = false },
        )
    }
    if (playerStyleDialog) {
        ChoiceSheet(
            title = "Player layout",
            options = PlayerStyle.entries,
            selected = ui.playerStyle,
            label = { "${it.label} · ${it.summary}" },
            // The Apple-inspired style is edge-to-edge artwork, so choosing it turns that on; it can be turned off after.
            onSelect = { v -> AppSettings.updateUi { it.copy(playerStyle = v, fullScreenCover = it.fullScreenCover || v == PlayerStyle.APPLE_MUSIC) } },
            onDismiss = { playerStyleDialog = false },
        )
    }
    if (languageDialog) {
        ChoiceSheet(
            title = com.opentune.ui.theme.tr("App language"),
            options = com.opentune.ui.theme.AppLanguage.entries,
            selected = com.opentune.ui.theme.AppLanguages.selected(context),
            label = { it.label },
            onSelect = { language -> languageDialog = false; com.opentune.ui.theme.AppLanguages.select(context, language) },
            onDismiss = { languageDialog = false },
        )
    }
    if (historyTargetDialog) {
        ChoiceSheet(
            title = "Sync listening history",
            options = com.opentune.data.history.ListeningHistoryTarget.entries,
            selected = pb.listeningHistoryTarget,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { target -> AppSettings.updatePlayback { it.copy(listeningHistoryTarget = target) } },
            onDismiss = { historyTargetDialog = false },
        )
    }
    if (recommenderDialog) {
        ChoiceSheet(
            title = "Recommendations",
            options = Recommender.entries,
            selected = pb.recommender,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { r ->
                com.opentune.data.MusicRepository.refreshHomeRecommendations()
                com.opentune.ui.ScreenCache.invalidate("home:${r.name}")
                AppSettings.updatePlayback { it.copy(recommender = r) }
            },
            onDismiss = { recommenderDialog = false },
        )
    }
    if (controlStyleDialog && ui.playerStyle != PlayerStyle.APPLE_MUSIC) {
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
    if (artistShadeDialog) {
        ChoiceSheet(
            title = "Artist page shade",
            options = com.opentune.data.settings.ArtistShade.entries,
            selected = ui.artistShade,
            label = { "${com.opentune.i18n.Tr.t(it.label)} · ${com.opentune.i18n.Tr.t(it.summary)}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(artistShade = m) } },
            onDismiss = { artistShadeDialog = false },
        )
    }
    if (listBackgroundDialog) {
        ChoiceSheet(
            title = "Playlist page background",
            options = com.opentune.data.settings.ListBackground.entries,
            selected = ui.listBackground,
            label = { "${com.opentune.i18n.Tr.t(it.label)} · ${com.opentune.i18n.Tr.t(it.summary)}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(listBackground = m) } },
            onDismiss = { listBackgroundDialog = false },
        )
    }
    if (pageButtonsDialog) {
        ChoiceSheet(
            title = "Page buttons",
            options = com.opentune.data.settings.PageButtons.entries,
            selected = ui.pageButtons,
            label = { "${com.opentune.i18n.Tr.t(it.label)} · ${com.opentune.i18n.Tr.t(it.summary)}" },
            onSelect = { b -> AppSettings.updateUi { it.copy(pageButtons = b) } },
            onDismiss = { pageButtonsDialog = false },
        )
    }
    if (choiceMenusDialog) {
        ChoiceSheet(
            title = "Choice menus",
            options = ChoiceMenuStyle.entries,
            selected = ui.choiceMenus,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { m -> AppSettings.updateUi { it.copy(choiceMenus = m) } },
            onDismiss = { choiceMenusDialog = false },
        )
    }
    if (pageLayoutDialog) {
        ChoiceSheet(
            title = "Page layout",
            options = com.opentune.data.settings.PageLayout.entries,
            selected = ui.pageLayout,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { l -> AppSettings.updateUi { it.copy(pageLayout = l) } },
            onDismiss = { pageLayoutDialog = false },
        )
    }
    if (sheetStyleDialog) {
        ChoiceSheet(
            title = "Sheet style",
            options = com.opentune.data.settings.SheetStyle.entries,
            selected = ui.sheetStyle,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { s -> AppSettings.updateUi { it.copy(sheetStyle = s) } },
            onDismiss = { sheetStyleDialog = false },
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
    if (downloadedPlaybackDialog) {
        val pb by AppSettings.playback.collectAsState()
        ChoiceSheet(
            title = "Stream downloaded songs",
            options = com.opentune.data.download.DownloadedPlayback.entries,
            selected = pb.downloadedPlayback,
            label = { "${it.label} · ${it.summary}" },
            onSelect = { c -> AppSettings.updatePlayback { it.withDownloadedPlayback(c) } },
            onDismiss = { downloadedPlaybackDialog = false },
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
                    // Every cookie goes, so Spotify's sign-in is put back afterwards.
                    com.opentune.ui.spotify.SpotifyConnection.keepingSpotify { android.webkit.CookieManager.getInstance().removeAllCookies(it) }
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
                Entry("YouTube Music account", "sign in login google profile switch add youtube") {
                    val active = accounts.firstOrNull { it.active }
                    if (signedIn && active != null) YouTubeMusicHero(active.name, active.email, active.thumbnailUrl, onSignIn)
                    else YouTubeMusicHero(null, null, null, onSignIn)
                },
                if (signedIn) {
                    Entry("Listen as", "listen as brand channel profile personal switch identity library") {
                        val listeningAs by AccountStore.listeningAs.collectAsState()
                        val own = accounts.firstOrNull { it.active }?.name.orEmpty()
                        NavRow("Listen as", { listenAsOpen = true }, summary = listeningAs ?: own.ifBlank { "Your own profile" }, icon = ImageVector.vectorResource(AppIcons.AccountCircle))
                    }
                } else null,
                if (signedIn && accounts.size > 1) {
                    Entry("Other accounts", "switch google account multiple other remove") {
                        Column {
                            accounts.filterNot { it.active }.forEachIndexed { i, acc ->
                                if (i > 0) RowDivider()
                                SettingRow(
                                    acc.name.ifBlank { "Signed in" },
                                    summary = acc.email.ifBlank { "Tap to switch" },
                                    onClick = { AccountStore.switchTo(acc.id) },
                                    leading = { Artwork(acc.thumbnailUrl, Modifier.size(32.dp), CircleShape) },
                                    trailing = {
                                        androidx.compose.material3.IconButton(onClick = { AccountStore.remove(acc.id) }) {
                                            Icon(ImageVector.vectorResource(AppIcons.Close), com.opentune.i18n.Tr.f("Remove {0}", acc.name.ifBlank { acc.email }))
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else null,
                if (signedIn) {
                    Entry("Add another account", "add switch google account multiple") {
                        NavRow("Add another account", onSignIn, summary = "Switch between YouTube Music accounts", icon = ImageVector.vectorResource(AppIcons.PersonAdd))
                    }
                } else null,
                Entry("Spotify account", "spotify connect sign in login account playlists library sync disconnect canvas video loop likes mixes") {
                    ServiceRow(
                        ServiceKind.Spotify, "Spotify",
                        connectedAs = if (spotifyConnected) "Connected" else null,
                        detail = when {
                            !spotifyConnected -> spotifyProblem?.let { com.opentune.i18n.Tr.f("{0}. Sign in again", it) } ?: com.opentune.i18n.Tr.t("Your playlists in Library, each song's Canvas, and mixes made for you")
                            spotifySync.running && spotifySync.error != null -> "Connection interrupted. Retrying…"
                            spotifySync.running && spotifySync.current != null -> com.opentune.i18n.Tr.f("Bringing over {0} ({1} of {2})", spotifySync.current, spotifySync.index, spotifySync.count)
                            spotifySync.running -> "Reading your Spotify library…"
                            spotifySync.error != null -> com.opentune.i18n.Tr.f("{0}. Tap Sync now to try again", spotifySync.error)
                            else -> "Playlists in Library. Playback uses YouTube Music"
                        },
                        onClick = {
                            if (spotifyConnected) { if (!spotifySync.running) com.opentune.ui.spotify.SpotifyConnection.checkPlaylists(withMixes = true) }
                            else context.startActivity(android.content.Intent(context, com.opentune.ui.spotify.SpotifyConnectActivity::class.java))
                        },
                        actions = listOf(
                            ServiceAction(if (spotifySync.running) "Syncing…" else "Sync now") { if (!spotifySync.running) com.opentune.ui.spotify.SpotifyConnection.checkPlaylists(withMixes = true) },
                            ServiceAction("Disconnect", danger = true) { com.opentune.ui.spotify.SpotifyConnection.disconnect() },
                        ),
                        chevron = false,
                    )
                },
                Entry("Link a device", "tv television web browser computer cast speaker chromecast watch wear together qr pairing sign in with your phone platforms") {
                    NavRow("Link a device", onOpenDevices, summary = "Sign in your TV, the web player, speakers and more", leading = { ServiceMark(ServiceKind.Devices) })
                },
                if (signedIn) {
                    Entry("Sign out", "logout account") {
                        CentredAction(if (accounts.size > 1) "Sign out of this account" else "Sign out", danger = true) { confirmSignOut = true }
                    }
                } else null,
            ),
        ),
        Section(
            "Audio quality",
            listOfNotNull(
                // The two look alike; the one for the network in use now says so.
                Entry("On Wi-Fi", "stream quality bitrate") {
                    NavRow("On Wi-Fi", { qualityDialog = true }, summary = if (!metered) "In use now" else null, icon = ImageVector.vectorResource(AppIcons.Wifi), value = pb.wifiQuality.label)
                },
                Entry("On mobile data", "stream quality bitrate cellular") {
                    NavRow("On mobile data", { qualityDialog = false }, summary = if (metered) "In use now" else null, icon = ImageVector.vectorResource(AppIcons.NetworkCell), value = pb.mobileQuality.label)
                },
                Entry("Output precision", "16-bit 32-bit float sample rate") {
                    SettingRow("Output precision", summary = outputSummary(context, pb.floatOutput), icon = ImageVector.vectorResource(AppIcons.GraphicEq), beta = true, below = {
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
                    ToggleRow("Lossless at other quality levels", pb.losslessStreaming, { v -> AppSettings.updatePlayback { it.copy(losslessStreaming = v) } }, summary = "Also check your audio add-ons after playback starts at other quality levels. Lossless checks them automatically in your chosen order.", icon = ImageVector.vectorResource(AppIcons.HighQuality))
                },
                Entry("Unmetered networks only", "flac saavn mobile wifi data") {
                    ToggleRow("Unmetered networks only", pb.losslessWifiOnly, { v -> AppSettings.updatePlayback { it.copy(losslessWifiOnly = v) } }, summary = "When on, mobile data uses YouTube even with Lossless selected. Turn off to try FLAC and JioSaavn on mobile data", icon = ImageVector.vectorResource(AppIcons.Wifi))
                },
                // On the main Settings page; here only so searching finds it.
                Entry("Audio sources", "audio add-ons addons source URL manifest order priority lossless flac high res") {
                    NavRow("Audio sources", { audioAddons = true }, summary = audioSourcesSummary(), icon = ImageVector.vectorResource(AppIcons.Extension))
                },
                Entry("Bit-perfect output", "dac exclusive mode usb audio player pro hiby passthrough hi-fi audiophile mixer bypass sample rate switching direct driver aaudio built-in") {
                    val usb by BitPerfectUsb.status.collectAsState()
                    val direct by com.opentune.playback.nativeout.UsbDirect.status.collectAsState()
                    val mode = pb.bitPerfectMode
                    NavRow(
                        "Bit-perfect output",
                        { bitPerfectSheet = true },
                        summary = when (val s = usb) {
                            is BitPerfectUsb.Status.Active -> com.opentune.i18n.Tr.f("{0}: {1} at {2} kHz, bit for bit", s.device, s.format.ifEmpty { "${s.bits}-bit" }, s.sampleRate / 1000.0)
                            is BitPerfectUsb.Status.NoMatch -> com.opentune.i18n.Tr.f("The DAC has no bit-perfect mode for {0} kHz, so this song goes through Android's mixer", s.sampleRate / 1000.0)
                            else -> when (val d = direct) {
                                is com.opentune.playback.nativeout.UsbDirect.Status.NeedsPermission -> com.opentune.i18n.Tr.f("Tap to allow {0}", d.device)
                                is com.opentune.playback.nativeout.UsbDirect.Status.Failed -> "${d.device}: ${d.why}"
                                com.opentune.playback.nativeout.UsbDirect.Status.NoDac -> "Plug in a USB DAC and the music moves to it"
                                else -> mode.summary
                            }
                        },
                        icon = ImageVector.vectorResource(AppIcons.Usb),
                        value = mode.label,
                    )
                    if (bitPerfectSheet) BitPerfectSheet(mode, onDismiss = { bitPerfectSheet = false })
                },
                Entry("System audio effects", "dolby atmos soundalive equalizer device effects") {
                    ToggleRow("System audio effects", pb.systemEffects, { v -> AppSettings.updatePlayback { it.copy(systemEffects = v) } }, summary = "Let the phone's own effects (Dolby, SoundAlive, system equalizer) process playback", icon = ImageVector.vectorResource(AppIcons.SurroundSound))
                },
                Entry("Background playback", "battery optimization doze killed stops samsung") {
                    val ignoring = remember { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }
                    NavRow(
                        "Background playback",
                        { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        summary = if (ignoring) "Unrestricted: music keeps playing with the screen off" else "Restricted: if playback stops in the background, set OpenTune to Unrestricted",
                        icon = ImageVector.vectorResource(AppIcons.BatteryChargingFull),
                    )
                },
                Entry("Prefer USB DAC", "external dac usb audio") {
                    ToggleRow("Prefer USB DAC", pb.preferUsbDac, { v -> AppSettings.updatePlayback { it.copy(preferUsbDac = v) } }, summary = "Send audio to a USB DAC whenever one is plugged in", icon = ImageVector.vectorResource(AppIcons.Usb), beta = true)
                },
            ),
        ),
        Section(
            "Playback",
            listOfNotNull(
                Entry("Loudness normalization", "volume level") {
                    ToggleRow("Loudness normalization", pb.loudnessNormalization, { v -> AppSettings.updatePlayback { it.copy(loudnessNormalization = v) } }, summary = "Uses YouTube's loudness measurement to set one steady volume per song", icon = ImageVector.vectorResource(AppIcons.VolumeUp))
                },
                if (pb.loudnessNormalization) Entry("Volume level", "loud louder quiet normal volume level loudness target boost") {
                    SettingRow("Volume level", summary = pb.volumeLevel.summary, icon = ImageVector.vectorResource(AppIcons.VolumeUp), below = {
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
                        icon = ImageVector.vectorResource(AppIcons.Speaker),
                    )
                } else null,
                Entry("Preload upcoming songs", "latency fast skip instant buffer") {
                    ToggleRow("Preload upcoming songs", pb.preloadUpcoming, { v -> AppSettings.updatePlayback { it.copy(preloadUpcoming = v) } }, summary = "Next and previous songs start almost instantly. Uses a little extra data.", icon = ImageVector.vectorResource(AppIcons.Speed))
                },
                if (pb.preloadUpcoming) Entry("Boost preloading on Wi-Fi", "preload wifi unmetered buffer performance speed") {
                    ToggleRow(
                        "Boost preloading on Wi-Fi",
                        pb.preloadBoostOnWifi,
                        { v -> AppSettings.updatePlayback { it.copy(preloadBoostOnWifi = v) } },
                        summary = "Pre-buffers 20 seconds ahead and resolves 3 songs early when connected to Wi-Fi",
                        icon = ImageVector.vectorResource(AppIcons.Wifi),
                    )
                } else null,
                Entry("Upgrade quality while playing", "better stream premium 256 opus swap") {
                    ToggleRow("Upgrade quality while playing", pb.qualityUpgrade, { v -> AppSettings.updatePlayback { it.copy(qualityUpgrade = v) } }, summary = "If a clearly better stream turns up a few seconds in, switch to it without stopping", icon = ImageVector.vectorResource(AppIcons.HighQuality), beta = true)
                },
                Entry("Automix", "auto mix dj blend beat transition seamless") {
                    ToggleRow(
                        "Automix",
                        pb.automix,
                        { v -> AppSettings.updatePlayback { it.copy(automix = v) } },
                        summary = if (pb.automix) "Each song blends into the next where it suits them: across a fade-out, on the beat, trading the bass over"
                        else "Off: songs end and start as set below",
                        icon = ImageVector.vectorResource(AppIcons.AutoAwesome),
                        beta = true,
                    )
                },
                if (pb.automix) Entry("Automix performance", "automix threads analysis speed battery heat efficient balanced performance") {
                    NavRow("Automix performance", { automixPerformanceDialog = true }, summary = pb.automixPerformance.summary, icon = ImageVector.vectorResource(AppIcons.Speed), value = pb.automixPerformance.label)
                } else null,
                Entry("Crossfade", "fade blend transition overlap") {
                    SettingRow(
                        "Crossfade",
                        summary = when {
                            pb.crossfadeSeconds == 0 -> "Off: songs play back to back"
                            else -> com.opentune.i18n.Tr.f("Blend each song into the next over {0} s", pb.crossfadeSeconds)
                        },
                        icon = ImageVector.vectorResource(AppIcons.Animation),
                        enabled = !pb.automix,
                        disabledReason = "Automix is choosing how long each blend lasts",
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
                    ToggleRow("Skip silence", pb.skipSilence, { v -> AppSettings.updatePlayback { it.copy(skipSilence = v) } }, summary = "Trim gaps longer than a second", icon = ImageVector.vectorResource(AppIcons.SpaceBar))
                },
                Entry("Clarity", "studio master clarity presence air bass clear enhance") {
                    ToggleRow("Clarity", pb.clarity, { v -> AppSettings.updatePlayback { it.copy(clarity = v) } }, summary = "Firmer bass, less mud, more presence and air, at the same loudness. Off plays the stream untouched.", icon = ImageVector.vectorResource(AppIcons.AutoAwesome), beta = true)
                },
                Entry("Spatial audio", "stereo widen immersive") {
                    ToggleRow("Spatial audio", pb.spatialAudio, { v -> AppSettings.updatePlayback { it.copy(spatialAudio = v) } }, summary = "Widens stereo tracks for a more open sound", icon = ImageVector.vectorResource(AppIcons.SurroundSound), beta = true)
                },
                Entry("Equalizer", "eq bands tone balance bass treble") {
                    NavRow("Equalizer", onOpenEqualizer, summary = "Fifteen bands, tone and balance", icon = ImageVector.vectorResource(AppIcons.Equalizer))
                },
                Entry("Autoplay", "radio continue") {
                    ToggleRow("Autoplay", pb.autoplay, AppSettings::setAutoplay, summary = "Keep playing similar songs when the queue ends", icon = ImageVector.vectorResource(AppIcons.PlaylistPlay))
                },
                Entry("Recommendations", "recommendation engine spotify jiosaavn saavn youtube music radio suggestions similar songs") {
                    NavRow(
                        "Recommendations",
                        { recommenderDialog = true },
                        summary = "Whose picks autoplay adds and Home's \"Because you played\" shows. Songs still play from YouTube Music.",
                        icon = ImageVector.vectorResource(AppIcons.AutoAwesome),
                        value = pb.recommender.label,
                    )
                },
                Entry("Sync listening history", "youtube account privacy sync spotify history") {
                    NavRow("Sync listening history", { historyTargetDialog = true },
                        summary = "YouTube Music needs a signed-in account. Spotify does not accept plays from other services; likes can sync separately. Connected scrobblers keep their own settings.",
                        icon = ImageVector.vectorResource(AppIcons.History), value = pb.listeningHistoryTarget.label)
                },
                Entry("Pause account sync", "private pause youtube spotify history recommendations") {
                    ToggleRow("Pause account sync", pb.pauseListeningHistory,
                        { v -> AppSettings.updatePlayback { it.copy(pauseListeningHistory = v) } },
                        summary = "Pause sending new listens to YouTube Music. Your local history, listening stats and personal recommendations keep updating. Paused listens are never sent later; likes and connected scrobblers keep their own settings.",
                        icon = ImageVector.vectorResource(AppIcons.History))
                },
                Entry("Listens from YouTube Music", "import sync history youtube music other apps outside recents") {
                    ToggleRow("Listens from YouTube Music", pb.importAccountHistory,
                        { v -> AppSettings.updatePlayback { it.copy(importAccountHistory = v) }; if (v) com.opentune.data.history.AccountHistory.syncSoon(force = true) },
                        summary = "What you play in YouTube Music or other apps on your account shows in your history, Recents and Replay here",
                        icon = ImageVector.vectorResource(AppIcons.History))
                },
                Entry("Don't repeat songs in current session", "autoplay duplicates") {
                    ToggleRow("Don't repeat songs in current session", pb.noRepeatInSession, { v -> AppSettings.updatePlayback { it.copy(noRepeatInSession = v) } }, summary = "Autoplay won't add a song already played or queued this session", icon = ImageVector.vectorResource(AppIcons.History))
                },
                Entry("Songs, not music videos", "audio only music video version omv atv song video switch") {
                    ToggleRow("Songs, not music videos", pb.songsOnly, { v -> AppSettings.updatePlayback { it.copy(songsOnly = v) } },
                        summary = "Radio and mixes play the song where YouTube Music also has its video, so the same song never comes twice in two versions",
                        icon = ImageVector.vectorResource(AppIcons.MusicNote))
                },
                Entry("Smart shuffle", "shuffle similar songs mix recommendations playlist album sparkle") {
                    ToggleRow("Smart shuffle", pb.smartShuffle, { v -> AppSettings.updatePlayback { it.copy(smartShuffle = v) } }, summary = "Mixes a similar song in after every four. Tap the player's shuffle button twice, or long-press Shuffle on a playlist or album", icon = ImageVector.vectorResource(AppIcons.AutoAwesome))
                },
                Entry("Hidden songs and artists", "not interested don't play hide block artist unhide suggestions") {
                    val count = hidden.songs.size + hidden.artists.size
                    NavRow(
                        "Hidden songs and artists",
                        { hiddenDialog = true },
                        summary = if (count == 0) "Kept out of autoplay, radio and Home. Nothing hidden yet"
                        else listOfNotNull(
                            hidden.songs.size.takeIf { it > 0 }?.let { if (it == 1) "1 song" else com.opentune.i18n.Tr.f("{0} songs", it) },
                            hidden.artists.size.takeIf { it > 0 }?.let { if (it == 1) "1 artist" else com.opentune.i18n.Tr.f("{0} artists", it) },
                        ).joinToString(" and ") + " kept out of suggestions",
                        icon = ImageVector.vectorResource(AppIcons.VisibilityOff),
                    )
                },
                Entry("Resume when headphones connect", "bluetooth headset wired auto play resume connect car") {
                    ToggleRow("Resume when headphones connect", pb.resumeOnConnect, { v -> AppSettings.updatePlayback { it.copy(resumeOnConnect = v) } }, summary = "Carries on playing when headphones are plugged in or a Bluetooth device connects, while OpenTune is open or in the notification", icon = com.opentune.ui.player.PlayerIcons.Headphones)
                },
                Entry("Pause at zero volume", "mute volume down silent") {
                    ToggleRow("Pause at zero volume", pb.pauseAtZeroVolume, { v -> AppSettings.updatePlayback { it.copy(pauseAtZeroVolume = v) } }, summary = "Pauses when the volume is turned all the way down and plays again when it comes back up", icon = ImageVector.vectorResource(AppIcons.VolumeOff))
                },
                Entry("SponsorBlock", "sponsorblock skip sponsor intro outro non-music talking music video segments") {
                    ToggleRow("Skip non-music parts", pb.sponsorBlock, { v -> AppSettings.updatePlayback { it.copy(sponsorBlock = v) } }, summary = "Jumps over talking, skits and sponsor reads in music videos, from SponsorBlock", icon = ImageVector.vectorResource(AppIcons.FastForward))
                },
                if (pb.sponsorBlock) Entry("SponsorBlock categories", "sponsorblock skip categories") {
                    NavRow("What to skip", { sponsorBlockDialog = true }, summary = pb.sponsorBlockCategories.mapNotNull { SponsorBlock.CATEGORIES[it]?.label }.joinToString().ifEmpty { "Nothing" }, icon = ImageVector.vectorResource(AppIcons.FastForward))
                } else null,
                Entry("Stop music on close from recents", "swipe away") {
                    ToggleRow("Stop music on close from recents", pb.stopOnTaskRemoved, { v -> AppSettings.updatePlayback { it.copy(stopOnTaskRemoved = v) } }, summary = "Stops playback when swiped away from recent apps", icon = ImageVector.vectorResource(AppIcons.MusicOff))
                },
            ),
        ),
        Section(
            "Appearance",
            listOfNotNull(
                Entry("Theme", "dark light system") {
                    SettingRow("Theme", icon = ImageVector.vectorResource(AppIcons.Palette), below = {
                        PillSegmented(ThemeMode.entries, theme.mode, { it.label }, { m -> AppSettings.updateTheme { it.copy(mode = m) } })
                    })
                },
                Entry("Color from artwork", "tint album cover") {
                    ToggleRow("Color from artwork", theme.colorFromArtwork, { v -> AppSettings.updateTheme { it.copy(colorFromArtwork = v) } }, summary = "Tint the app with the playing song's cover", icon = ImageVector.vectorResource(AppIcons.AutoAwesome))
                },
                Entry("Mini player takes the cover's colour", "mini player pill tint colour artwork plain") {
                    ToggleRow("Mini player takes the cover's colour", ui.miniPlayerTint, { v -> AppSettings.updateUi { it.copy(miniPlayerTint = v) } }, summary = "Off keeps the small player's glass plain, whatever is playing", icon = ImageVector.vectorResource(AppIcons.AutoAwesome))
                },
                Entry("Artist pages take the photo's colour", "artist page tint colour photo background") {
                    ToggleRow("Artist pages take the photo's colour", ui.artistPageTint, { v -> AppSettings.updateUi { it.copy(artistPageTint = v) } }, summary = "Off keeps artist pages in the app's own colours", icon = ImageVector.vectorResource(AppIcons.AutoAwesome))
                },
                Entry("Artist page shade", "artist page tint light dark deep soft shade colour photo background") {
                    // The shade is of the photo's colour, so with that off there's nothing to shade.
                    NavRow("Artist page shade", { artistShadeDialog = true }, summary = ui.artistShade.summary,
                        icon = ImageVector.vectorResource(AppIcons.AutoAwesome), value = ui.artistShade.label,
                        enabled = ui.artistPageTint, disabledReason = "Only when Artist pages take the photo's colour is on")
                },
                Entry("Playlist page background", "playlist album liked spotify page background cover colour accent blur fade plain dark light") {
                    NavRow("Playlist page background", { listBackgroundDialog = true }, summary = ui.listBackground.summary, icon = ImageVector.vectorResource(AppIcons.AutoAwesome), value = ui.listBackground.label)
                },
                Entry("Material You", "wallpaper dynamic color") {
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ToggleRow(
                        "Material You",
                        theme.dynamicColor,
                        { v -> AppSettings.updateTheme { it.copy(dynamicColor = v) } },
                        summary = if (supported) "Use your wallpaper's colors when nothing is playing" else "Needs Android 12",
                        icon = ImageVector.vectorResource(AppIcons.Wallpaper),
                        enabled = supported,
                    )
                },
                Entry("Accent color", "color") {
                    SettingRow("Accent color", icon = ImageVector.vectorResource(AppIcons.Palette), below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SEED_COLORS.forEach { argb ->
                                val selected = theme.seedColor == argb
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(Color(argb))
                                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { AppSettings.updateTheme { it.copy(seedColor = argb) } },
                                    contentAlignment = Alignment.Center,
                                ) { if (selected) Icon(ImageVector.vectorResource(AppIcons.Check), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            }
                        }
                    })
                },
                Entry("Palette style", "tonal vibrant expressive") {
                    SettingRow("Palette style", icon = ImageVector.vectorResource(AppIcons.Palette), below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaletteStyleOption.entries.forEach { s ->
                                FilterChip(theme.paletteStyle == s, { AppSettings.updateTheme { it.copy(paletteStyle = s) } }, label = { Text(s.label) })
                            }
                        }
                    })
                },
                Entry("App language", "language locale hindi punjabi english भाषा ਭਾਸ਼ਾ") {
                    NavRow("App language", { languageDialog = true }, icon = ImageVector.vectorResource(AppIcons.Language),
                        value = com.opentune.ui.theme.AppLanguages.selected(context).label)
                },
                Entry("App icon", "launcher icon design glass pearl midnight rose ocean") {
                    NavRow("App icon", { appIconDialog = true }, summary = "Choose the OpenTune icon on your home screen", icon = ImageVector.vectorResource(AppIcons.Palette))
                },
                Entry("Fonts & readability", "font typeface text weight bold thin ttf otf import custom") {
                    NavRow("Fonts & readability", { fontDialog = true }, summary = "Built-in fonts, text weight and your own font file", icon = ImageVector.vectorResource(AppIcons.TextFields), value = theme.font.label)
                },
                Entry("Pure black", "oled amoled") {
                    ToggleRow("Pure black", theme.pureBlack, { v -> AppSettings.updateTheme { it.copy(pureBlack = v) } }, summary = "Black backgrounds with neutral gray cards in dark theme", icon = ImageVector.vectorResource(AppIcons.Wallpaper))
                },
                Entry("Player layout", "player style layout vinyl record lyrics minimal classic screen") {
                    NavRow("Player layout", { playerStyleDialog = true }, summary = ui.playerStyle.summary, icon = ImageVector.vectorResource(AppIcons.Album), value = ui.playerStyle.label)
                },
                Entry("Player beside the page", "tablet foldable large screen side pane docked player split landscape") {
                    ToggleRow(
                        "Player beside the page",
                        ui.sidePlayer,
                        { v -> AppSettings.updateUi { it.copy(sidePlayer = v) } },
                        summary = "On large screens, the player stays open on the right while music plays",
                        icon = ImageVector.vectorResource(AppIcons.VerticalSplit),
                        enabled = ui.wideLayout,
                        disabledReason = "Only with Wide-screen layout on",
                    )
                },
                Entry("Player buttons", "play pause next previous skip controls buttons design morph disc squircle glass") {
                    // The Apple Music inspired player always draws its own buttons, so the design
                    // chosen here does nothing there: shown dimmed, and kept for the other layouts.
                    NavRow(
                        "Player buttons", { controlStyleDialog = true },
                        summary = ui.controlStyle.summary,
                        icon = ImageVector.vectorResource(AppIcons.PlayCircle), value = ui.controlStyle.label, enabled = !appleStyle,
                        disabledReason = "Not with the Apple Music inspired player, which has buttons of its own",
                    )
                },
                Entry("Dock animation", "navigation bar dock fold hide scroll animation motion squash pop") {
                    // The side rail has no dock to fold, and the Poster layout's dock folds its own way (BottomChrome).
                    NavRow("Dock animation", { dockMotionDialog = true }, summary = ui.dockMotion.summary, icon = ImageVector.vectorResource(AppIcons.Dock), value = ui.dockMotion.label,
                        enabled = !railShown && !poster,
                        disabledReason = if (railShown) "Not with the side rail this wide window uses" else "Only with the Classic page layout. The Poster dock folds its own way")
                },
                Entry("Dock lens", "navigation bar dock tab highlight selection pill indicator jelly stretch glide") {
                    // Reduced motion always glides; the Poster dock has a lens of its own (the rail still uses this one).
                    NavRow("Dock lens", { dockLensDialog = true }, summary = ui.dockLens.summary, icon = ImageVector.vectorResource(AppIcons.Lens), value = ui.dockLens.label,
                        enabled = !reduced && (railShown || !poster),
                        disabledReason = if (reduced) "Glides while Reduce animation is on" else "Only with the Classic page layout. The Poster dock has a lens of its own")
                },
                Entry("Dock slides away", "dock slides away hide navigation bar bottom pages liked playlist artist settings mini player") {
                    ToggleRow("Dock slides away", ui.dockSlidesAway, { v -> AppSettings.updateUi { it.copy(dockSlidesAway = v) } }, summary = "On Liked, playlists, artist pages, Settings and History, the dock and Search slide away and the mini player has the bottom to itself", icon = ImageVector.vectorResource(AppIcons.Dock),
                        enabled = !railShown, disabledReason = "Not with the side rail this wide window uses")
                },
                Entry("Opening animation", "splash start launch intro logo reveal dive") {
                    ToggleRow(
                        "Opening animation",
                        ui.openingAnimation,
                        { v -> AppSettings.updateUi { it.copy(openingAnimation = v) } },
                        summary = "When OpenTune starts, the screen dives into the logo and the app comes through it",
                        icon = ImageVector.vectorResource(AppIcons.AutoAwesome),
                        enabled = !reduced,
                        disabledReason = "Off while Reduce animation is on",
                    )
                },
                Entry("Page transitions", "navigation animation screen open back slide fade zoom rise") {
                    // Reduced motion is a short cross-fade whatever the choice (PageMotion).
                    NavRow("Page transitions", { pageTransitionDialog = true }, summary = ui.pageTransition.summary, icon = ImageVector.vectorResource(AppIcons.SwapHoriz), value = ui.pageTransition.label,
                        enabled = !reduced, disabledReason = "A short fade while Reduce animation is on")
                },
                Entry("Double-tap to skip 10 seconds", "double tap cover seek forward back rewind ten seconds gesture") {
                    ToggleRow("Double-tap to skip 10 seconds", ui.doubleTapSeek, { v -> AppSettings.updateUi { it.copy(doubleTapSeek = v) } }, summary = "Double-tap the left of the cover to go back 10 seconds, the right to go on", icon = ImageVector.vectorResource(AppIcons.TouchApp))
                },
                Entry("Glow under the cover", "glow shadow light cover colour bass pulse") {
                    // The glow is drawn only under the Classic and Minimal players' square cover
                    // (ArtworkPane), never under a full-screen one, and not over AMOLED black.
                    val glowWhyNot = when {
                        appleStyle -> "Not with the Apple Music inspired player"
                        ui.playerStyle != PlayerStyle.CLASSIC && ui.playerStyle != PlayerStyle.MINIMAL -> "Only with the Classic and Minimal players"
                        amoledPlayer -> "Not with the AMOLED black player background"
                        reduced -> "Off while Reduce animation is on"
                        ui.fullScreenCover -> "Not with Full-screen cover art"
                        poster -> "Only with the Classic page layout. The Poster player's cover is always full-screen"
                        else -> null
                    }
                    // Greyed with what it's set to showing, which comes back with the cover that draws it.
                    ToggleRow("Glow under the cover", ui.coverGlow, { v -> AppSettings.updateUi { it.copy(coverGlow = v) } },
                        summary = "A soft light in the cover's colours under it, swelling a little with the bass",
                        icon = ImageVector.vectorResource(AppIcons.AutoAwesome), enabled = glowWhyNot == null, disabledReason = glowWhyNot)
                },
                Entry("Song change", "cover change next song skip animation flip carousel deck fade") {
                    NavRow("Song change", { coverChangeDialog = true }, summary = ui.coverChange.summary, icon = ImageVector.vectorResource(AppIcons.SwapHoriz), value = ui.coverChange.label,
                        enabled = !reduced, disabledReason = "Always a fade while Reduce animation is on")
                },
                Entry("Player background", "cover blur blurred mesh gradient echo glow colours pure black oled amoled") {
                    SettingRow("Player background", icon = ImageVector.vectorResource(AppIcons.Wallpaper), below = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerBackground.entries.forEach { b ->
                                FilterChip(theme.playerBackground == b, { AppSettings.updateTheme { it.copy(playerBackground = b) } }, label = { Text(b.label) })
                            }
                        }
                    })
                },
                Entry("Moving cover art", "motion artwork canvas animated cover drift zoom") {
                    ToggleRow("Moving cover art", ui.movingCover, { v -> AppSettings.updateUi { it.copy(movingCover = v) } }, summary = "The cover drifts and zooms slowly in the player while a song plays", icon = ImageVector.vectorResource(AppIcons.Animation),
                        enabled = !reduced, disabledReason = "Off while Reduce animation is on")
                },
                Entry("Moving covers on Home", "motion artwork animated cover drift zoom home cards mixes") {
                    ToggleRow("Moving covers on Home", ui.homeMovingCovers, { v -> AppSettings.updateUi { it.copy(homeMovingCovers = v) } }, summary = "Song and album covers on Home drift and zoom slowly, one or two at a time, while you're not scrolling. Off in battery saver", icon = ImageVector.vectorResource(AppIcons.Animation),
                        enabled = !reduced, disabledReason = "Off while Reduce animation is on")
                },
                Entry("Page layout", "layout poster classic album playlist artist page design look header") {
                    NavRow("Page layout", { pageLayoutDialog = true }, summary = ui.pageLayout.summary, icon = ImageVector.vectorResource(AppIcons.GridView), value = ui.pageLayout.label)
                },
                Entry("Page buttons", "play shuffle save follow header buttons glass liquid frosted accent colour color solid fill album playlist liked artist page") {
                    NavRow("Page buttons", { pageButtonsDialog = true }, summary = ui.pageButtons.summary, icon = ImageVector.vectorResource(AppIcons.AutoAwesome), value = ui.pageButtons.label)
                },
                Entry("Full-screen playlist covers", "playlist album cover art edge full screen header") {
                    // Poster pages always open with their cover edge to edge (fullListCover), so the switch is the Classic layout's.
                    ToggleRow("Full-screen playlist covers", ui.fullScreenListCover, { v -> AppSettings.updateUi { it.copy(fullScreenListCover = v) } }, summary = "Playlists and albums open with their cover across the top of the page, edge to edge", icon = ImageVector.vectorResource(AppIcons.Fullscreen),
                        enabled = !poster, disabledReason = "Only with the Classic page layout. Poster pages always open edge to edge")
                },
                Entry("Liked Music cover", "liked music cover heart newest latest song artwork") {
                    ToggleRow("Liked Music cover", ui.likedCoverFromLatest, { v -> AppSettings.updateUi { it.copy(likedCoverFromLatest = v) } }, summary = "The newest song you liked becomes the cover, instead of the heart cover", icon = ImageVector.vectorResource(AppIcons.Favorite))
                },
                Entry("Full-screen cover art", "artwork edge") {
                    // The Poster layout always gives the player its cover edge to edge, whatever this says (PlayerScreen).
                    ToggleRow("Full-screen cover art", ui.fullScreenCover, { v -> AppSettings.updateUi { it.copy(fullScreenCover = v) } }, summary = "Runs the cover to the edges of the player instead of a square sleeve", icon = ImageVector.vectorResource(AppIcons.Fullscreen),
                        enabled = !poster, disabledReason = "Only with the Classic page layout. The Poster player's cover is always full-screen")
                },
                Entry("Cover corners", "artwork corner radius round square sharp shape thumbnail") {
                    SettingRow(
                        "Cover corners",
                        summary = when {
                            ui.coverCornerDp == 0 -> "Square, with sharp corners"
                            else -> com.opentune.i18n.Tr.f("Rounded by {0} dp", ui.coverCornerDp)
                        },
                        icon = ImageVector.vectorResource(AppIcons.RoundedCorner),
                        enabled = !ui.fullScreenCover,
                        disabledReason = "Turn off Full-screen cover art to shape the cover",
                        below = {
                            Slider(
                                value = ui.coverCornerDp.toFloat(),
                                onValueChange = { v -> AppSettings.updateUi { it.copy(coverCornerDp = (v / 4).roundToInt() * 4) } },
                                valueRange = 0f..40f,
                                steps = 9,
                            )
                        },
                    )
                },
                Entry("Match background to cover", "background colour seamless") {
                    // Only a full-screen cover is matched (PlayerBackdrop), and only the plain-cover players have one.
                    val matchWhyNot = when {
                        !appleStyle && ui.playerStyle != PlayerStyle.CLASSIC && ui.playerStyle != PlayerStyle.MINIMAL -> "Only with the Apple Music inspired, Classic and Minimal players"
                        amoledPlayer -> "Not with the AMOLED black player background"
                        !ui.fullScreenCover && !poster -> "Only with Full-screen cover art on"
                        else -> null
                    }
                    ToggleRow("Match background to cover", ui.coverColorBackground, { v -> AppSettings.updateUi { it.copy(coverColorBackground = v) } }, summary = "Paints the player's background in the cover's own colour, so the full-screen cover blends into it", icon = ImageVector.vectorResource(AppIcons.Palette),
                        enabled = matchWhyNot == null, disabledReason = matchWhyNot)
                },
                Entry("Wavy seek bar", "wave progress slider") {
                    // The Apple Music inspired player keeps its bar plain, and reduced motion keeps every bar still.
                    ToggleRow("Wavy seek bar", ui.wavySeekbar, { v -> AppSettings.updateUi { it.copy(wavySeekbar = v) } },
                        summary = "The played part of the bar ripples while music plays",
                        icon = ImageVector.vectorResource(AppIcons.Waves), enabled = !appleStyle && !reduced,
                        disabledReason = if (appleStyle) "Not with the Apple Music inspired player, whose bar stays plain" else "Off while Reduce animation is on")
                },
                Entry("Lyric preview while seeking", "scrub seek bar lyric line preview bubble drag") {
                    ToggleRow("Lyric preview while seeking", ui.lyricScrubPreview, { v -> AppSettings.updateUi { it.copy(lyricScrubPreview = v) } }, summary = "Dragging the seek bar shows the line sung at that point above your finger", icon = ImageVector.vectorResource(AppIcons.Lyrics))
                },
                Entry("Seek bar thickness", "progress bar seek slider thin thick height") {
                    SettingRow("Seek bar thickness", icon = ImageVector.vectorResource(AppIcons.LineWeight), below = {
                        PillSegmented(com.opentune.data.settings.SeekbarThickness.entries, ui.seekbarThickness, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarThickness = v) } })
                    })
                },
                Entry("Seek bar handle", "progress bar thumb knob dot line handle") {
                    SettingRow("Seek bar handle", icon = ImageVector.vectorResource(AppIcons.RadioButtonChecked), below = {
                        PillSegmented(com.opentune.data.settings.SeekbarThumb.entries, ui.seekbarThumb, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarThumb = v) } })
                    })
                },
                Entry("Seek bar colour", "progress bar color accent gradient") {
                    SettingRow("Seek bar colour", summary = "Accent and Gradient follow the cover when the player takes its colours", icon = ImageVector.vectorResource(AppIcons.Palette), below = {
                        PillSegmented(com.opentune.data.settings.SeekbarColor.entries, ui.seekbarColor, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarColor = v) } })
                    })
                },
                Entry("Seek bar time", "progress bar remaining total length duration time left") {
                    SettingRow("Time on the right", icon = ImageVector.vectorResource(AppIcons.Schedule), below = {
                        PillSegmented(com.opentune.data.settings.SeekbarTime.entries, ui.seekbarTime, { it.label }, { v -> AppSettings.updateUi { it.copy(seekbarTime = v) } })
                    })
                },
                Entry("Audio details page", "signal path pipeline format details bit perfect output routing codec resolution") {
                    SettingRow("Audio details page", summary = "What the quality label opens: cards of format, output and source, or the live pipeline", icon = ImageVector.vectorResource(AppIcons.GraphicEq), below = {
                        PillSegmented(com.opentune.data.settings.SignalPathStyle.entries, ui.signalPathStyle, { it.label }, { v -> AppSettings.updateUi { it.copy(signalPathStyle = v) } })
                    })
                },
                Entry("Song part marks", "seek bar chorus verse dots marks parts") {
                    ToggleRow("Song part marks", ui.seekbarMarks, { v -> AppSettings.updateUi { it.copy(seekbarMarks = v) } }, summary = "Small dots on the seek bar where the verse and chorus begin, when the lyrics mark them", icon = ImageVector.vectorResource(AppIcons.MoreHoriz))
                },
                Entry("Skip to chorus", "chorus jump skip button song parts") {
                    ToggleRow("Skip to chorus button", ui.skipToChorus, { v -> AppSettings.updateUi { it.copy(skipToChorus = v) } }, summary = "A button under the title that jumps to the next chorus, when the lyrics mark one", icon = ImageVector.vectorResource(AppIcons.FastForward))
                },
                Entry("Audio quality label", "audio source quality pill badge codec bitrate jiosaavn youtube") {
                    ToggleRow("Show the quality label", ui.sourcePill, { v -> AppSettings.updateUi { it.copy(sourcePill = v) } }, summary = "Shows quality and upgrade progress below the seek bar; tap for the full signal path", icon = ImageVector.vectorResource(AppIcons.HighQuality))
                },
                Entry("Album covers", "artwork cover musicbrainz cover art archive video thumbnail") {
                    ToggleRow("Sharper album covers", ui.albumCovers, { v -> AppSettings.updateUi { it.copy(albumCovers = v) } }, summary = "The player shows the album's cover from Apple Music, Deezer or the Cover Art Archive, sharper than YouTube's and in place of a video frame", icon = ImageVector.vectorResource(AppIcons.Album))
                },
                Entry("Artist signatures", "artist signature handwritten autograph name artist page header") {
                    com.opentune.ui.browse.ArtistSignatureSettings()
                },
                Entry("Animated covers", "apple music motion artwork video moving album cover animated") {
                    // With motion reduced no clip is fetched or played (PlayerViewModel), so these do nothing then.
                    ToggleRow("Animated covers", ui.animatedCovers, { v -> AppSettings.updateUi { it.copy(animatedCovers = v) } }, summary = "When Apple Music has a moving cover for the album, it loops silently in the player's cover", icon = ImageVector.vectorResource(AppIcons.Animation),
                        enabled = !reduced, disabledReason = "Off while Reduce animation is on")
                    if (ui.animatedCovers) {
                        RowDivider()
                        SettingRow("Animated cover quality", summary = when (ui.motionQuality) {
                            com.opentune.data.settings.MotionQuality.LOW -> "Up to 768 px, about 1.5 Mbps. Lightest on data"
                            com.opentune.data.settings.MotionQuality.MEDIUM -> "Up to 1080 px, about 4 Mbps"
                            com.opentune.data.settings.MotionQuality.HIGH -> "Up to 2160 px square, about 15 Mbps. Sharpest, uses the most data"
                        }, icon = ImageVector.vectorResource(AppIcons.HighQuality), enabled = !reduced, disabledReason = "Off while Reduce animation is on", below = {
                            PillSegmented(com.opentune.data.settings.MotionQuality.entries, ui.motionQuality, { it.label }, { v -> AppSettings.updateUi { it.copy(motionQuality = v) } })
                        })
                    }
                },
                // Shown once Spotify is connected; before that the Spotify row above says what it adds.
                if (spotifyConnected) Entry("Spotify", "spotify canvas video loop background likes mixes home") {
                    ToggleRow("Spotify Canvas", ui.spotifyCanvas, { v -> AppSettings.updateUi { it.copy(spotifyCanvas = v) } }, summary = "When a song has a Canvas on Spotify, it loops silently behind the player", icon = ImageVector.vectorResource(AppIcons.Animation),
                        enabled = !reduced, disabledReason = "Off while Reduce animation is on")
                    RowDivider()
                    // How a Canvas is shaded, so only while one can play.
                    SettingRow("Behind the title and controls", summary = ui.canvasLook.summary, icon = ImageVector.vectorResource(AppIcons.BlurOn),
                        enabled = ui.spotifyCanvas && !reduced, disabledReason = if (reduced) "Off while Reduce animation is on" else "Only with Spotify Canvas on", below = {
                        PillSegmented(com.opentune.data.settings.CanvasLook.entries, ui.canvasLook, { it.label }, { v -> AppSettings.updateUi { it.copy(canvasLook = v) } })
                    })
                    RowDivider()
                    ToggleRow("Save likes to Spotify", ui.spotifySaveLikes, { v -> AppSettings.updateUi { it.copy(spotifySaveLikes = v) } }, summary = "Likes are saved to Spotify. Listening here does not add plays to Spotify history", icon = ImageVector.vectorResource(AppIcons.Favorite))
                    RowDivider()
                    ToggleRow("Spotify mixes on Home", ui.spotifyMixes, { v -> AppSettings.updateUi { it.copy(spotifyMixes = v) } }, summary = "Your Daily Mixes, Discover Weekly and Release Radar from Spotify, found on YouTube Music", icon = ImageVector.vectorResource(AppIcons.Album))
                } else null,
                Entry("Animated covers on Wi-Fi only", "mobile data metered save data wifi animated covers canvas video") {
                    ToggleRow("Animated covers on Wi-Fi only", ui.animatedCoversWifiOnly, { v -> AppSettings.updateUi { it.copy(animatedCoversWifiOnly = v) } }, summary = "Animated covers and Canvas don't load on mobile data", icon = ImageVector.vectorResource(AppIcons.Wifi),
                        enabled = !reduced && (ui.animatedCovers || (spotifyConnected && ui.spotifyCanvas)),
                        disabledReason = if (reduced) "Off while Reduce animation is on" else "Only with Animated covers or Spotify Canvas on")
                },
                Entry("Reduce animation", "motion") {
                    ToggleRow("Reduce animation", ui.reduceAnimation, { v -> AppSettings.updateUi { it.copy(reduceAnimation = v) } }, summary = "Freezes decorative motion and simplifies player and lyric transitions", icon = ImageVector.vectorResource(AppIcons.Animation))
                },
                Entry("Lite three-dot menu", "song menu page animation performance low end") {
                    ToggleRow("Lite three-dot menu", ui.liteThreeDotMenu, { v -> AppSettings.updateUi { it.copy(liteThreeDotMenu = v) } }, summary = "The song menu opens as a plain page with no animation, lighter on low-end phones", icon = ImageVector.vectorResource(AppIcons.Speed))
                },
                Entry("Glass style", "glass liquid glass refraction apple lens web frosted blur theme material poster classic") {
                    // Both styles are Liquid Glass, in both page layouts (glass, posterGlass): without a
                    // lens (before Android 13) they're the same frost, and with blur reduced the same fill.
                    NavRow("Glass style", { glassStyleDialog = true }, summary = ui.glassStyle.summary, icon = ImageVector.vectorResource(AppIcons.BlurOn), value = ui.glassStyle.label,
                        enabled = liquidGlassSupported && !ui.reduceBlur,
                        disabledReason = if (ui.reduceBlur) "Solid fills while Reduce dynamic blur is on" else "Needs Android 13 or newer; frosted glass is used instead")
                },
                Entry("Choice menus", "popup sheet screen page list select choose menu dialog") {
                    NavRow("Choice menus", { choiceMenusDialog = true }, summary = ui.choiceMenus.summary, icon = ImageVector.vectorResource(AppIcons.Tune), value = ui.choiceMenus.label)
                },
                Entry("Frosted top edge", "status bar blur fade top scroll glass") {
                    ToggleRow("Frosted top edge", ui.frostedTopEdge, { v -> AppSettings.updateUi { it.copy(frostedTopEdge = v) } }, summary = "Pages blur and fade as they scroll under the status bar", icon = ImageVector.vectorResource(AppIcons.Gradient))
                },
                Entry("Performance profile", "battery saver drain power performance profile balanced smooth heat") {
                    NavRow("Performance profile", { profileDialog = true }, summary = ui.performanceProfile.summary, icon = ImageVector.vectorResource(AppIcons.Speed), value = ui.performanceProfile.label)
                },
                Entry("High performance mode", "120hz 90hz smooth fps refresh rate frame rate scrolling performance battery unrestricted lag") {
                    HighPerformanceRows(ui)
                },
                Entry("Streaks on Home", "streak badges milestones hours artists new week home") {
                    ToggleRow("Streaks on Home", ui.homeStreaks, { v -> AppSettings.updateUi { it.copy(homeStreaks = v) } },
                        summary = "Days in a row you've listened, new artists this week and hours with an artist, under the greeting", icon = ImageVector.vectorResource(AppIcons.LocalFireDepartment))
                },
                Entry("Speed dial", "home most played quick grid shortcuts favourites top") {
                    ToggleRow("Speed dial", ui.speedDial, { v -> AppSettings.updateUi { it.copy(speedDial = v) } }, summary = "Your most-played songs, albums and artists at the top of Home", icon = ImageVector.vectorResource(AppIcons.GridView))
                },
                Entry("Separate Explore tab", "explore tab dock search page navigation moods genres own") {
                    ToggleRow(
                        "Separate Explore tab",
                        ui.separateExplore,
                        { v -> AppSettings.updateUi { it.copy(separateExplore = v) } },
                        summary = if (ui.separateExplore) "Explore has a tab in the dock" else "Explore fills the Search page until you start a search",
                        icon = ImageVector.vectorResource(AppIcons.Explore),
                    )
                },
                Entry("Wide-screen layout", "tablet foldable large screen landscape rail side navigation dock columns") {
                    ToggleRow(
                        "Wide-screen layout",
                        ui.wideLayout,
                        { v -> AppSettings.updateUi { it.copy(wideLayout = v) } },
                        summary = "On tablets, unfolded phones and in landscape, the tabs move to a rail on the side and pages keep a readable width",
                        icon = ImageVector.vectorResource(AppIcons.TabletAndroid),
                    )
                },
                Entry("Reduce dynamic blur", "glass frosted performance") {
                    ToggleRow("Reduce dynamic blur", ui.reduceBlur, { v -> AppSettings.updateUi { it.copy(reduceBlur = v) } }, summary = "Swaps frosted glass for solid fills across the app", icon = ImageVector.vectorResource(AppIcons.BlurOff))
                },
                Entry("Sheet style", "sheets blur theme dynamic frosted bottom sheet") {
                    NavRow("Sheet style", { sheetStyleDialog = true }, summary = ui.sheetStyle.summary, icon = ImageVector.vectorResource(AppIcons.BlurOn), value = ui.sheetStyle.label)
                },
                Entry("Lite mode", "performance tv old phone lag smooth light simple fast low end") {
                    ToggleRow(
                        "Lite mode",
                        ui.liteMode,
                        { v -> AppSettings.updateUi { com.opentune.data.settings.liteModeApplied(it, v) } },
                        summary = "The same app without glass, blur, glows, moving and animated covers, and fewer shelves on Home. On by itself on TVs and older phones",
                        icon = ImageVector.vectorResource(AppIcons.Speed),
                    )
                },
                Entry("Lighter Liquid Glass", "glass performance lag smooth slow phone frame") {
                    ToggleRow(
                        "Lighter Liquid Glass",
                        com.opentune.ui.components.GlassPerformance.lite,
                        { v -> com.opentune.ui.components.GlassPerformance.choose(v) },
                        summary = "Leaves out the colour fringe and inner shade, which slower phones can't draw every frame. Turns on by itself when frames run late",
                        icon = ImageVector.vectorResource(AppIcons.Speed),
                    )
                },
                Entry("Haptic feedback", "vibration haptics strength tap buzz intensity") {
                    val haptics = com.opentune.ui.components.rememberHaptics()
                    SettingRow(
                        "Haptic feedback",
                        summary = com.opentune.ui.components.Haptics.label(ui.hapticStrength) +
                                if (ui.hapticStrength > 0f) " · ${(ui.hapticStrength * 100).roundToInt()}%" else " · No taps or buzzes",
                        icon = ImageVector.vectorResource(AppIcons.Vibration),
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
                    ToggleRow("Synced lyrics", ui.syncedLyrics, { v -> AppSettings.updateUi { it.copy(syncedLyrics = v) } }, summary = "Lights up the words as they're sung", icon = ImageVector.vectorResource(AppIcons.Lyrics))
                },
                // Animation, glow and blur are how synced lines move (SyncedLyrics); unsynced lyrics are plain text.
                Entry("Lyrics animation", "style karaoke slide zoom fluid motion") {
                    NavRow("Lyrics animation", { lyricsAnimationDialog = true }, summary = ui.lyricsAnimation.summary, icon = ImageVector.vectorResource(AppIcons.Animation), value = ui.lyricsAnimation.label,
                        enabled = ui.syncedLyrics && !reduced,
                        disabledReason = if (!ui.syncedLyrics) "Only with Synced lyrics on" else "Minimal while Reduce animation is on")
                },
                Entry("Lyrics text size", "font bigger smaller") {
                    SettingRow(
                        "Lyrics text size",
                        summary = "${(ui.lyricsTextScale * 100).roundToInt()}%" + if (ui.lyricsTextScale == 1f) " · Standard" else "",
                        icon = ImageVector.vectorResource(AppIcons.FormatSize),
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
                    SettingRow("Lyrics alignment", icon = ImageVector.vectorResource(AppIcons.FormatAlignCenter), below = {
                        PillSegmented(LyricsAlign.entries, ui.lyricsAlign, { it.label }, { a -> AppSettings.updateUi { it.copy(lyricsAlign = a) } })
                    })
                },
                Entry("Glow on the sung line", "lyrics glow shine highlight") {
                    // Karaoke has the glow built in, whatever this says (motionOf in LyricsView).
                    val karaoke = ui.lyricsAnimation == LyricsAnimation.KARAOKE && !reduced
                    ToggleRow("Glow on the sung line", ui.lyricsGlow, { v -> AppSettings.updateUi { it.copy(lyricsGlow = v) } }, summary = "A soft halo around the line being sung, with any animation", icon = ImageVector.vectorResource(AppIcons.Flare),
                        enabled = ui.syncedLyrics && !karaoke,
                        disabledReason = if (!ui.syncedLyrics) "Only with Synced lyrics on" else "The Karaoke animation always glows")
                },
                Entry("Keep the screen on for lyrics", "keep screen awake on wake lock sleep dim lyrics") {
                    ToggleRow("Keep the screen on for lyrics", ui.keepScreenOnLyrics, { v -> AppSettings.updateUi { it.copy(keepScreenOnLyrics = v) } }, summary = "While lyrics are showing, the screen doesn't dim or lock. It goes back to normal when you leave them.", icon = ImageVector.vectorResource(AppIcons.LightMode))
                },
                Entry("Hide the navigation bar on the lyrics page", "navigation bar hide gesture bottom bar immersive lyrics") {
                    ToggleRow("Hide the navigation bar on the lyrics page", ui.hideNavigationBarOnLyrics, { v -> AppSettings.updateUi { it.copy(hideNavigationBarOnLyrics = v) } }, summary = "The device's navigation bar is hidden on the lyrics page and its space left empty, so nothing crowds the bottom edge. Swipe up from the edge to bring it back for a moment.", icon = ImageVector.vectorResource(AppIcons.BorderBottom))
                },
                Entry("Hide the status bar on the lyrics page", "status bar hide top immersive lyrics") {
                    ToggleRow("Hide the status bar on the lyrics page", ui.hideStatusBarOnLyrics, { v -> AppSettings.updateUi { it.copy(hideStatusBarOnLyrics = v) } }, summary = "The phone's status bar is hidden on the lyrics page and its space left empty, so nothing crowds the top edge. Swipe down from the top edge to bring it back for a moment.", icon = ImageVector.vectorResource(AppIcons.BorderTop))
                },
                Entry("Blur unfocused lyrics", "spotlight") {
                    // Karaoke and Minimal blur no line (and reduced motion plays Minimal), so there's nothing to turn off.
                    val unblurred = reduced || ui.lyricsAnimation == LyricsAnimation.KARAOKE || ui.lyricsAnimation == LyricsAnimation.MINIMAL
                    ToggleRow("Blur unfocused lyrics", ui.blurLyrics, { v -> AppSettings.updateUi { it.copy(blurLyrics = v) } }, summary = "Keeps the spotlight on the current line", icon = ImageVector.vectorResource(AppIcons.BlurOn),
                        enabled = ui.syncedLyrics && !unblurred,
                        disabledReason = when {
                            !ui.syncedLyrics -> "Only with Synced lyrics on"
                            reduced -> "Off while Reduce animation is on"
                            else -> "Only with the Fluid and Focus zoom lyrics animations"
                        })
                },
                Entry("Lyrics take the screen", "hide controls idle focus fullscreen lyrics auto") {
                    ToggleRow("Lyrics take the screen", ui.lyricsFocus, { v -> AppSettings.updateUi { it.copy(lyricsFocus = v) } }, summary = "On the lyrics view, the controls slide away after a few seconds untouched. Touch or swipe to bring them back.", icon = ImageVector.vectorResource(AppIcons.Lyrics))
                },
                Entry("Lyrics sources", "lrclib youtube order provider") {
                    NavRow(
                        "Lyrics sources",
                        { lyricsSourcesDialog = true },
                        summary = lyricsSettings.ordered.filter { it.enabled }.joinToString(", then ") { it.source.label }.ifEmpty { "None: lyrics are off" },
                        icon = ImageVector.vectorResource(AppIcons.TextFields),
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
                        icon = ImageVector.vectorResource(AppIcons.NotificationsActive),
                    )
                },
                Entry("Followed artists", "follow artists unfollow") {
                    NavRow("Followed artists", { followedDialog = true }, summary = if (followedArtists.isEmpty()) "Follow from an artist's page" else com.opentune.i18n.Tr.f("{0} artists", followedArtists.size), icon = ImageVector.vectorResource(AppIcons.Person))
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
                        icon = ImageVector.vectorResource(AppIcons.FilterAlt),
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
                        summary = com.opentune.i18n.Tr.f("{0} songs · {1}", done.size, Formatter.formatShortFileSize(context, done.sumOf { it.bytes })),
                        icon = ImageVector.vectorResource(AppIcons.DownloadDone),
                    )
                },
                Entry("Smart downloads", "automatic offline favorite wifi battery") {
                    ToggleRow("Smart downloads", lib.smartDownloads,
                        { enabled -> AppSettings.updateLibrary { it.copy(smartDownloads = enabled) } },
                        summary = "Save your liked and most-listened music on unmetered Wi-Fi with sufficient battery. Manual downloads are kept separately.",
                        icon = ImageVector.vectorResource(AppIcons.DownloadDone))
                },
                Entry("Keep today's mixes offline", "smart download mixes daylist discover genre offline automatic") {
                    ToggleRow("Keep today's mixes offline", lib.smartDownloadMixes,
                        { v -> AppSettings.updateLibrary { it.copy(smartDownloadMixes = v) } },
                        summary = "Smart downloads also save the first songs of each mix on Home: your daylist, daily, genre and Discover mixes",
                        icon = ImageVector.vectorResource(AppIcons.AutoAwesome), enabled = lib.smartDownloads, disabledReason = "Only with Smart downloads on")
                },
                Entry("Smart download limit", "automatic offline songs storage threshold") {
                    var limit by remember(lib.smartDownloadLimit) { mutableStateOf(lib.smartDownloadLimit.coerceIn(10, 500).toFloat()) }
                    SettingRow("Smart download limit", icon = ImageVector.vectorResource(AppIcons.Storage),
                        summary = "${limit.toInt()} songs · Space used depends on download quality",
                        enabled = lib.smartDownloads, disabledReason = "Only with Smart downloads on", below = {
                            Slider(value = limit, onValueChange = { limit = (kotlin.math.round(it / 10f) * 10f).coerceIn(10f, 500f) },
                                onValueChangeFinished = { AppSettings.updateLibrary { it.copy(smartDownloadLimit = limit.toInt()) } },
                                valueRange = 10f..500f, steps = 48)
                        })
                },
                Entry("Smart download battery", "automatic offline battery threshold charging") {
                    var minimum by remember(lib.smartDownloadMinBattery) { mutableStateOf(lib.smartDownloadMinBattery.coerceIn(15, 80).toFloat()) }
                    SettingRow("Smart download battery", icon = ImageVector.vectorResource(AppIcons.BatteryFull),
                        summary = "At least ${minimum.toInt()}% battery, or while charging",
                        enabled = lib.smartDownloads, disabledReason = "Only with Smart downloads on", below = {
                            Slider(value = minimum, onValueChange = { minimum = (kotlin.math.round(it / 5f) * 5f).coerceIn(15f, 80f) },
                                onValueChangeFinished = { AppSettings.updateLibrary { it.copy(smartDownloadMinBattery = minimum.toInt()) } },
                                valueRange = 15f..80f, steps = 12)
                        })
                },
                Entry("Download quality", "offline bitrate") {
                    NavRow("Download quality", { downloadQualityDialog = true }, icon = ImageVector.vectorResource(AppIcons.Download), value = lib.downloadQuality.label)
                },
                Entry("Download on Wi-Fi only", "mobile data metered") {
                    ToggleRow("Download on Wi-Fi only", lib.downloadWifiOnly, { v -> AppSettings.updateLibrary { it.copy(downloadWifiOnly = v) }; com.opentune.data.download.Downloads.requeueWaiting(context) }, summary = "Waits for Wi-Fi before saving songs", icon = ImageVector.vectorResource(AppIcons.Wifi))
                },
                Entry("Stream downloaded songs", "downloaded songs play from downloads when online offline file internet mobile data wifi quality") {
                    val pbd by AppSettings.playback.collectAsState()
                    NavRow("Stream downloaded songs", { downloadedPlaybackDialog = true }, icon = ImageVector.vectorResource(AppIcons.Wifi),
                        summary = "${com.opentune.i18n.Tr.t(pbd.downloadedPlayback.label)} · ${com.opentune.i18n.Tr.t(pbd.downloadedPlayback.summary)}")
                },
                Entry("Prefer the download when it's as good", "downloaded songs downloads file quality lossless data stream only when better") {
                    val pbd by AppSettings.playback.collectAsState()
                    ToggleRow("Prefer the download when it's as good", pbd.streamDownloadsOnlyIfBetter,
                        { v -> AppSettings.updatePlayback { it.copy(streamDownloadsOnlyIfBetter = v) } },
                        summary = "Plays the saved file instead of streaming when no stream would sound better, such as a lossless download. Saves data.",
                        icon = ImageVector.vectorResource(AppIcons.HighQuality), enabled = pbd.downloadedPlayback != com.opentune.data.download.DownloadedPlayback.FILE,
                        disabledReason = "Downloaded songs always play from the file while Stream downloaded songs is Never")
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
                                    append(if (copied == 1) "1 song" else com.opentune.i18n.Tr.f("{0} songs", copied))
                                    append(" copied to Music/OpenTune")
                                    if (skipped > 0) append(com.opentune.i18n.Tr.f("; {0} in a format other apps can't play, download them again to export", skipped))
                                }
                                if (copied > 0 || skipped > 0) com.opentune.data.Pills.done(note, context)
                            }
                        },
                        summary = if (com.opentune.data.download.DownloadExport.supported)
                            "Also saves each download to Music/OpenTune as AAC (M4A) or FLAC, so other music apps can play it"
                        else "Needs Android 10 or newer",
                        icon = ImageVector.vectorResource(AppIcons.DownloadDone),
                        enabled = com.opentune.data.download.DownloadExport.supported,
                    )
                },
            ),
        ),
        Section(
            "Local music",
            listOf(
                Entry("Local music folder", "device files") {
                    NavRow("Local music folder", { folderDialog = true }, summary = lib.localFolder ?: "All audio folders", icon = ImageVector.vectorResource(AppIcons.Folder))
                },
                Entry("Filter non-music audio", "recordings voice notes") {
                    ToggleRow("Filter non-music audio", lib.filterNonMusic, { v -> AppSettings.updateLibrary { it.copy(filterNonMusic = v) } }, summary = "Hides clips under 30 seconds, WAV files, voice notes, recordings and system sounds", icon = ImageVector.vectorResource(AppIcons.FilterAlt))
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
                        icon = ImageVector.vectorResource(AppIcons.Storage),
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
                    }, summary = com.opentune.i18n.Tr.f("Using {0}", Formatter.formatShortFileSize(context, cacheBytes)), icon = ImageVector.vectorResource(AppIcons.DeleteSweep))
                },
                Entry("Delete downloaded updates", "apk update installer free space") {
                    NavRow("Delete downloaded updates", {
                        scope.launch {
                            withContext(Dispatchers.IO) { Updater.deleteCached(context) }
                            updateBytes = 0
                            message = "Downloaded updates deleted."
                        }
                    }, summary = if (updateBytes > 0) com.opentune.i18n.Tr.f("Using {0}", Formatter.formatShortFileSize(context, updateBytes)) else "None kept", icon = ImageVector.vectorResource(AppIcons.DeleteSweep))
                },
                Entry("Clear image cache", "artwork") {
                    NavRow("Clear image cache", {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        scope.launch { withContext(Dispatchers.IO) { loader.diskCache?.clear() } }
                        message = "Image cache cleared."
                    }, summary = "Frees space used by album artwork", icon = ImageVector.vectorResource(AppIcons.DeleteSweep))
                },
            ),
        ),
        Section(
            "Your data",
            listOf(
                Entry("Replay", "stats top songs artists") {
                    NavRow("Replay", onOpenReplay, summary = "Your top songs, artists and albums", icon = ImageVector.vectorResource(AppIcons.BarChart))
                },
                Entry("Wrapped", "wrapped story year review stats recap top artists") {
                    NavRow("Wrapped", onOpenWrapped, summary = "Your listening as an animated story", icon = ImageVector.vectorResource(AppIcons.AutoAwesome))
                },
                Entry("Export data", "backup json") {
                    NavRow("Export data", { exporter.launchSafely(context, "opentune-backup.json") }, summary = "Settings, history, likes and playlists, as one JSON file", icon = ImageVector.vectorResource(AppIcons.Upload))
                },
                Entry("Import data", "restore json") {
                    NavRow("Import data", { importer.launchSafely(context, arrayOf("application/json")) }, summary = "Replaces the settings, history, likes and playlists on this device", icon = ImageVector.vectorResource(AppIcons.Download))
                },
                Entry("Clear listening history", "delete history") {
                    NavRow("Clear listening history", { History.clear(); com.opentune.data.history.ListenedCollections.clear(); message = "Listening history cleared." }, summary = "Removes Recents and Replay data", icon = ImageVector.vectorResource(AppIcons.DeleteSweep))
                },
            ),
        ),
        Section(
            "Your music server",
            listOfNotNull(
                Entry("Music server", "subsonic navidrome gonic airsonic jellyfin flac lossless server sign out disconnect") {
                    val connected = musicServer
                    ServiceRow(
                        ServiceKind.Server, "Music server",
                        connectedAs = connected?.let { com.opentune.i18n.Tr.f("{0} on {1}", it.user, it.url.substringAfter("://")) },
                        detail = if (connected == null) "Stream your own FLAC and hi-res files from any Subsonic server" else "Streams stored files as they are",
                        onClick = { serverDialog = true },
                        actions = listOf(
                            ServiceAction("Sign in again") { serverDialog = true },
                            ServiceAction("Disconnect", danger = true) { Subsonic.disconnect() },
                        ),
                        chevron = false,
                    )
                },
            ),
        ),
        Section(
            "Discord",
            listOfNotNull(
                Entry("Show on Discord", "discord rich presence status listening activity profile sign out disconnect") {
                    val who = discord
                    ServiceRow(
                        ServiceKind.Discord, "Discord",
                        connectedAs = who?.let { com.opentune.i18n.Tr.f("Connected as {0}", it.name) },
                        detail = when {
                            who == null -> "Show the song you play on your Discord profile"
                            !discordOn -> "Off: nothing shows on your profile"
                            discordProblem != null -> discordProblem!!
                            discordStatus == com.opentune.data.discord.DiscordRpc.Status.Ready -> "Showing what you play on your profile"
                            else -> "Shows what you play on your profile while music plays"
                        },
                        onClick = { if (who == null) onOpenDiscord() else com.opentune.data.discord.DiscordRpc.setEnabled(!discordOn) },
                        actions = listOf(ServiceAction("Disconnect", danger = true) { com.opentune.data.discord.DiscordRpc.signOut() }),
                        trailing = if (who != null) { { AppSwitch(discordOn, { v -> com.opentune.data.discord.DiscordRpc.setEnabled(v) }) } } else null,
                    )
                },
            ),
        ),
        Section(
            "Last.fm (optional)",
            listOfNotNull(
                Entry("Last.fm scrobbling", "scrobble lastfm audioscrobbler history sign out disconnect") {
                    val who = lastFm
                    ServiceRow(
                        ServiceKind.LastFm, "Last.fm",
                        connectedAs = who?.let { com.opentune.i18n.Tr.f("Scrobbling as {0}", it.user) },
                        detail = when {
                            who == null -> "Scrobble what you play with your own Last.fm API account"
                            scrobblesWaiting > 0 -> com.opentune.i18n.Tr.f("{0} scrobbles waiting to send", scrobblesWaiting)
                            else -> "Songs count after half their length or 4 minutes"
                        },
                        onClick = { if (who == null) lastFmDialog = true },
                        actions = listOf(ServiceAction("Disconnect", danger = true) { LastFm.signOut() }),
                        chevron = false,
                    )
                },
            ),
        ),
        Section(
            "ListenBrainz (optional)",
            listOfNotNull(
                Entry("ListenBrainz", "listenbrainz scrobble history recommendations metabrainz sign out disconnect") {
                    val who = listenBrainz
                    ServiceRow(
                        ServiceKind.ListenBrainz, "ListenBrainz",
                        connectedAs = who?.let { com.opentune.i18n.Tr.f("Sending listens as {0}", it.user) },
                        detail = when {
                            who == null -> "Free, open listening history, and recommendations on Home"
                            listensWaiting > 0 -> com.opentune.i18n.Tr.f("{0} listens waiting to send", listensWaiting)
                            else -> "Recommendations from your listens show on Home"
                        },
                        onClick = { if (who == null) listenBrainzDialog = true },
                        actions = listOf(ServiceAction("Disconnect", danger = true) { ListenBrainz.signOut() }),
                        chevron = false,
                    )
                },
            ),
        ),
        Section(
            "Import",
            listOf(
                Entry("Import a playlist", "spotify youtube music import playlist album link csv exportify transfer migrate") {
                    NavRow("Import a playlist", onOpenSpotify, summary = "From a Spotify or YouTube Music link, or a CSV export", icon = ImageVector.vectorResource(AppIcons.QueueMusic))
                },
            ),
        ),
        Section(
            "Miscellaneous",
            listOf(
                Entry("Playlists in Library", "playlists library tiles list grid view rows layout show all view all") {
                    SettingRow("Playlists in Library", summary = "Tiles in a sideways row, or a list down the page by where they're from, each with View all", icon = ImageVector.vectorResource(AppIcons.QueueMusic), below = {
                        PillSegmented(listOf(false, true), ui.playlistList, { if (it) "List" else "Tiles" }, { v -> AppSettings.updateUi { it.copy(playlistList = v) } })
                    })
                },
                Entry("Swipe songs to queue them", "gesture swipe queue play next add to queue") {
                    ToggleRow("Swipe songs to queue them", pb.swipeToQueue, { v -> AppSettings.updatePlayback { it.copy(swipeToQueue = v) } }, summary = "Swipe a song right to play it next, or left to add it to the end of the queue", icon = ImageVector.vectorResource(AppIcons.PlaylistPlay))
                },
                Entry("Hide volume bar", "player slider") {
                    ToggleRow("Hide volume bar", ui.hideVolumeBar, { v -> AppSettings.updateUi { it.copy(hideVolumeBar = v) } }, summary = "Removes the volume slider from the player", icon = ImageVector.vectorResource(AppIcons.VolumeOff))
                },
                Entry("Hide device volume bar", "system volume panel keys") {
                    ToggleRow("Hide device volume bar", ui.hideDeviceVolumeBar, { v -> AppSettings.updateUi { it.copy(hideDeviceVolumeBar = v) } }, summary = "Volume keys change the volume with no Android panel over the player; needs the volume slider shown", icon = ImageVector.vectorResource(AppIcons.VolumeUp), enabled = !ui.hideVolumeBar, disabledReason = "Only while the player's volume bar is shown")
                },
                Entry("Lyrics preview", "player lyrics line apple music classic theme") {
                    ToggleRow("Lyrics preview", ui.lyricsPreview, { v -> AppSettings.updateUi { it.copy(lyricsPreview = v) } },
                        summary = "Show the current lyric on the player in every theme", icon = ImageVector.vectorResource(AppIcons.Lyrics))
                },
                Entry("Comments button", "comments youtube button pill remove hide") {
                    ToggleRow("Comments button", ui.commentsButton, { v -> AppSettings.updateUi { it.copy(commentsButton = v) } }, summary = "A button in the player's pill for the song's comments from YouTube. Off, they're still in the player's menu", icon = ImageVector.vectorResource(AppIcons.ChatBubbleOutline))
                },
                Entry("Hide song status", "playing from") {
                    ToggleRow("Hide song status", ui.hideSongStatus, { v -> AppSettings.updateUi { it.copy(hideSongStatus = v) } }, summary = "Hides the \"Playing from\" line in the player", icon = ImageVector.vectorResource(AppIcons.VisibilityOff))
                },
                Entry("Hide output device", "output device name speaker bluetooth headphones") {
                    ToggleRow("Hide output device", ui.hideOutputDevice, { v -> AppSettings.updateUi { it.copy(hideOutputDevice = v) } }, summary = "Hides the output device's name at the bottom of the player", icon = ImageVector.vectorResource(AppIcons.VisibilityOff))
                },
            ),
        ),
        Section(
            "Advanced",
            listOf(
                Entry("What's new", "release notes changelog version highlights") {
                    NavRow("What's new", { showWhatsNew = true }, summary = com.opentune.i18n.Tr.f("Highlights and improvements in {0}", BuildConfig.VERSION_NAME), icon = ImageVector.vectorResource(AppIcons.FiberNew))
                },
                Entry("Show the welcome again", "welcome onboarding first run start intro pick artists setup") {
                    NavRow("Show the welcome again", { com.opentune.data.Welcome.showAgain() }, summary = "Pick artists, choose a look and sign in, as on the first start", icon = ImageVector.vectorResource(AppIcons.WavingHand))
                },
                Entry("Check for updates", "new version release download github") {
                    NavRow("Check for updates", {
                        // A release already found opens at once; Update still looks for a newer one first.
                        pendingUpdate?.let { update = it; return@NavRow }
                        checkingUpdates = true
                        scope.launch {
                            try {
                                when (val r = UpdateCheck.checkNow(context)) {
                                    is UpdateCheck.Result.Newer -> update = r.release
                                    UpdateCheck.Result.UpToDate -> message = com.opentune.i18n.Tr.f("You have the latest version ({0}).", BuildConfig.VERSION_NAME)
                                    UpdateCheck.Result.NoReleases -> message = "No releases are published yet. Builds are on the GitHub branch for now."
                                    is UpdateCheck.Result.Failed -> message = com.opentune.i18n.Tr.f("Couldn't check: {0}", r.reason)
                                }
                            } finally {
                                checkingUpdates = false
                            }
                        }
                    }, summary = pendingUpdate?.let { com.opentune.i18n.Tr.f("OpenTune {0} is ready to install. You have {1}.", it.version, BuildConfig.VERSION_NAME) } ?: com.opentune.i18n.Tr.f("Current version: {0}", BuildConfig.VERSION_NAME), icon = ImageVector.vectorResource(AppIcons.Update), dot = pendingUpdate != null, busy = checkingUpdates)
                },
                Entry("Check for updates automatically", "update new version release auto") {
                    ToggleRow("Check for updates automatically", ui.checkForUpdates, { v -> AppSettings.updateUi { it.copy(checkForUpdates = v) }; com.opentune.data.UpdateCheck.schedule(context) }, summary = "Looks for a new release on GitHub every few hours, in the background too, and offers to install it", icon = ImageVector.vectorResource(AppIcons.Autorenew))
                },
                Entry("Count this phone in usage numbers", "anonymous usage statistics analytics counter privacy telemetry") {
                    ToggleRow(
                        "Count this phone in usage numbers",
                        ui.countUsage,
                        { v -> AppSettings.updateUi { it.copy(countUsage = v) }; com.opentune.data.Usage.consentChanged(v) },
                        summary = "Shares a random installation ID, app version, active dates and daily play totals. No hardware ID, account or song names. Reinstalls get a new ID; updates keep it.",
                        icon = ImageVector.vectorResource(AppIcons.Info),
                    )
                },
                Entry("OpenTune community", "github discord community addons developer") { CommunityBanner() },
                Entry("Support OpenTune", "donate upi pay money tip support developer") {
                    NavRow("Support OpenTune", { supportDialog = true }, summary = "A small thank-you helps keep OpenTune going", icon = ImageVector.vectorResource(AppIcons.Favorite))
                },
                Entry("Star on GitHub", "star github rate like") {
                    NavRow("Star on GitHub", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, com.opentune.ui.components.Support.REPO.toUri())) }
                    }, summary = "Helps other people find OpenTune", icon = ImageVector.vectorResource(AppIcons.Star))
                },
                Entry("Source code", "github open source license gpl") {
                    NavRow("Source code", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/justshivv/Opentune".toUri())) }
                    }, summary = "github.com/justshivv/Opentune · GPL v3", icon = ImageVector.vectorResource(AppIcons.Code))
                },
                Entry("justshivv", "github developer creator justshivv") {
                    DeveloperCard("justshivv", "https://avatars.githubusercontent.com/u/78946189?v=4", "https://github.com/justshivv")
                },
                Entry("aerluc", "github developer aerluc") {
                    DeveloperCard("aerluc", "https://avatars.githubusercontent.com/u/122277259?v=4", "https://github.com/aerluc")
                },
                Entry("Export diagnostics", "log share troubleshooting bug report") {
                    NavRow("Export diagnostics", {
                        scope.launch {
                            val log = LogExport.recent()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, com.opentune.i18n.Tr.f("OpenTune {0} log", BuildConfig.VERSION_NAME))
                                .putExtra(Intent.EXTRA_TEXT, log)
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        }
                    }, summary = "Share this app's recent log for troubleshooting", icon = ImageVector.vectorResource(AppIcons.BugReport))
                },
                Entry("Show stats for nerds", "codec bitrate sample rate debug") {
                    ToggleRow("Show stats for nerds", ui.statsForNerds, { v -> AppSettings.updateUi { it.copy(statsForNerds = v) } }, summary = "Codec, bitrate and sample rate under the seek bar", icon = ImageVector.vectorResource(AppIcons.Info))
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

private fun formatMb(mb: Int): String = if (mb >= 1024) "%.1f GB".format(mb / 1024f) else com.opentune.i18n.Tr.f("{0} MB", mb)

/** Spotify's green disc, for its rows in Account. */
/**
 * The four bit-perfect paths, each with what it does, and USB access for the direct driver.
 * A choice like any other in Settings: a sheet or a page of its own, as Choice menus says.
 * The Android-14 path shows dimmed where it isn't there; picking the direct driver stays
 * open until USB access is given, with the button to ask for it again.
 */
@Composable
private fun BitPerfectSheet(mode: com.opentune.data.settings.BitPerfectOutput, onDismiss: () -> Unit) {
    val direct by com.opentune.playback.nativeout.UsbDirect.status.collectAsState()
    val needsUsb = { o: com.opentune.data.settings.BitPerfectOutput ->
        o == com.opentune.data.settings.BitPerfectOutput.USB_DIRECT &&
                com.opentune.playback.nativeout.UsbDirect.status.value is com.opentune.playback.nativeout.UsbDirect.Status.NeedsPermission
    }
    val available = { o: com.opentune.data.settings.BitPerfectOutput ->
        o != com.opentune.data.settings.BitPerfectOutput.USB_ANDROID || BitPerfectUsb.supported
    }
    com.opentune.ui.components.ChoiceSheet(
        title = "Bit-perfect output",
        options = com.opentune.data.settings.BitPerfectOutput.entries,
        selected = mode,
        label = { o ->
            com.opentune.i18n.Tr.t(o.label) + " · " +
                    com.opentune.i18n.Tr.t(if (available(o)) o.summary else "Needs Android 14 or newer")
        },
        onSelect = { option ->
            AppSettings.updatePlayback {
                it.copy(bitPerfectOutput = option, bitPerfectUsb = option == com.opentune.data.settings.BitPerfectOutput.USB_ANDROID)
            }
            if (option == com.opentune.data.settings.BitPerfectOutput.USB_DIRECT) com.opentune.playback.nativeout.UsbDirect.requestPermission()
        },
        onDismiss = onDismiss,
        enabled = available,
        keepOpen = needsUsb,
        footer = {
            Column {
                if (mode == com.opentune.data.settings.BitPerfectOutput.USB_DIRECT && direct is com.opentune.playback.nativeout.UsbDirect.Status.NeedsPermission) {
                    androidx.compose.material3.Button(
                        onClick = { com.opentune.playback.nativeout.UsbDirect.requestPermission() },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    ) { Text("Allow USB access") }
                }
                Text(
                    "Sends the track to the DAC unchanged. The equalizer, loudness normalization, crossfade, Automix and playback speed are bypassed; the volume is set by the app.",
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** A card's own action, as plain centred text across the row: Sign out, Disconnect. */
@Composable
private fun CentredAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

/**
 * The profiles the signed-in Google account can listen as, its own and any Brand channel it
 * owns, read from YouTube when opened; the one chosen is remembered for the account.
 */
@Composable
private fun ListenAsSheet(onDismiss: () -> Unit) {
    val listeningAs by AccountStore.listeningAs.collectAsState()
    val profiles by androidx.compose.runtime.produceState<List<com.opentune.data.model.AccountChannel>?>(null) {
        value = withContext(Dispatchers.IO) { AccountStore.profiles() }
    }
    com.opentune.ui.components.FloatingCard(onDismiss = onDismiss, title = "Listen as", expandable = true, fullFraction = 1f) {
        Column(Modifier.padding(bottom = 12.dp)) {
            val list = profiles
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
                list.isEmpty() -> Text(
                    "YouTube didn't list any other profiles for this account.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                else -> list.forEachIndexed { i, p ->
                    // The account's own profile is the one that isn't a delegated page.
                    val own = p.pageId == null
                    val chosen = if (listeningAs == null) own else p.name == listeningAs
                    SettingRow(
                        p.name,
                        summary = p.subtitle.ifBlank { if (own) "Personal" else "Brand" },
                        leading = { Artwork(p.thumbnailUrl, Modifier.size(40.dp), CircleShape) },
                        trailing = if (chosen) ({ Icon(ImageVector.vectorResource(AppIcons.Check), "In use", tint = MaterialTheme.colorScheme.primary) }) else null,
                        onClick = { if (!chosen) AccountStore.listenAs(p, own); onDismiss() },
                    )
                }
            }
        }
    }
}
