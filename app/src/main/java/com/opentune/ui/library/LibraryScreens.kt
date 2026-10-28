package com.opentune.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opentune.data.history.History
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.browse.ShuffleButton
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.share.ReplayShareSheet
import java.util.concurrent.TimeUnit

@Composable
internal fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, onSmartShuffle: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) {
            ShuffleButton(onShuffle, onSmartShuffle, compact = true)
            GlassIconButton(Icons.Rounded.PlayArrow, "Play", onPlay, size = 44.dp)
        } else {
            Button(onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(com.opentune.ui.theme.tr("Play"))
            }
            ShuffleButton(onShuffle, onSmartShuffle, Modifier.weight(1f))
        }
    }
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/** Audio files on the device, after asking for permission to read them. */
@Composable
fun LocalMusicScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val library by AppSettings.library.collectAsState()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(granted, library.localFolder, library.filterNonMusic) {
        if (granted) songs = runCatching { LocalMusic.tracks(context, library) }.getOrDefault(emptyList())
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("On this device", onBack = onBack) }
        when {
            !granted -> item {
                MessageState(
                    Icons.Rounded.Folder,
                    "Play music saved on your phone",
                    Modifier.padding(top = 32.dp),
                    message = "OpenTune needs permission to read audio files. Nothing leaves your device.",
                    action = { Button(onClick = { launcher.launch(audioPermission) }) { Text("Allow access") } },
                )
            }
            songs == null -> items(8) { SongRowPlaceholder() }
            songs.isNullOrEmpty() -> item {
                MessageState(Icons.Rounded.Folder, "No music found", Modifier.padding(top = 32.dp), message = "Check the folder and filter in Settings › Local music.")
            }
            else -> {
                val list = songs.orEmpty()
                item {
                    Text(
                        "${list.size} songs" + (library.localFolder?.let { " in $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    PlayButtons(
                        onPlay = { actions.playAll(list, 0, false, "On this device") },
                        onShuffle = { actions.playAll(list, 0, true, "On this device") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                itemsIndexed(list, key = { _, s -> s.videoId }) { i, song ->
                    SongListItem(
                        song = song,
                        onClick = { actions.playAll(list, i, false, "On this device") },
                        isCurrent = song.videoId == actions.currentVideoId,
                        isPlaying = actions.isPlaying,
                        onPlayNext = { actions.playNext(song) },
                        onAddToQueue = { actions.addToQueue(song) },
                    )
                }
            }
        }
    }
}

/** [cardLabel] names the period on the shared Replay card. */
private enum class ReplayPeriod(val label: String, val days: Long?, val cardLabel: String) {
    WEEK("Weekly", 7, "Last 7 days"),
    MONTH("Monthly", 30, "Last 30 days"),
    YEAR("Yearly", null, "This year"),
    ALL("Lifetime", null, "All time"),
}

/** Ranked listening summaries; all figures come from recorded listens on this device. */
@Composable
fun ReplayScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenWrapped: () -> Unit = {}) {
    val records by History.records.collectAsState()
    val account by com.opentune.data.account.AccountStore.account.collectAsState()
    var period by rememberSaveable { mutableStateOf(ReplayPeriod.MONTH) }
    val summary = remember(records, period) {
        val since = when (period) {
            ReplayPeriod.YEAR -> java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            else -> period.days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it) } ?: 0L
        }
        History.replay(records, since)
    }
    val topSongs = remember(summary) { summary.topSongs.mapNotNull { it.song } }
    var sharing by rememberSaveable { mutableStateOf(false) }
    if (sharing) ReplayShareSheet(summary, period.cardLabel, onDismiss = { sharing = false })
    val background = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primaryContainer
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), background)))) {
        item {
            PageHeader("Replay", onBack = onBack, actions = if (summary.totalPlays == 0) null else {
                { GlassIconButton(Icons.Rounded.Share, "Share my Replay", { sharing = true }) }
            })
            Text("${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}", Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                ReplayPeriod.entries.forEach { p ->
                    androidx.compose.material3.TextButton(onClick = { period = p }, modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            containerColor = if (p == period) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (p == period) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                        Text(p.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item { ReplayExperienceCard("${TimeUnit.MILLISECONDS.toMinutes(summary.listenedMs)}", "MINUTES LISTENED", account?.name ?: "Your listening", "${summary.totalPlays} plays · ${period.cardLabel}") }
                summary.topSongs.firstOrNull()?.let { song -> item { ReplayExperienceCard(song.title, "TOP SONG", song.subtitle, "${song.plays} plays") } }
                summary.topArtists.firstOrNull()?.let { artist -> item { ReplayExperienceCard(artist.title, "TOP ARTIST", "${TimeUnit.MILLISECONDS.toMinutes(artist.listenedMs)} minutes", "${artist.plays} plays") } }
            }
        }
        item {
            androidx.compose.material3.FilledTonalButton(onClick = { actions.playAll(topSongs, 0, false, "Replay") },
                enabled = topSongs.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) { Text("Play your Replay") }
        }
        if (summary.totalPlays == 0) item {
            MessageState(Icons.Rounded.BarChart, "Nothing to replay yet", Modifier.padding(16.dp), message = "Listen for a while and your top songs and artists show up here.")
        }
        if (summary.topSongs.isNotEmpty()) item { SectionHeader("Top songs") }
        itemsIndexed(summary.topSongs, key = { i, entry -> "s:$i:${entry.key}" }) { i, entry ->
            val song = entry.song ?: return@itemsIndexed
            SongListItem(song,
                onClick = { actions.playAll(topSongs, i, false, "Replay") },
                isCurrent = song.videoId == actions.currentVideoId, isPlaying = actions.isPlaying,
                leading = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(32.dp), color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Artwork(song.thumbnailUrl, Modifier.size(56.dp))
                } }, onPlayNext = { actions.playNext(song) }, onAddToQueue = { actions.addToQueue(song) },
                trailing = { Text("${entry.plays} · ${TimeUnit.MILLISECONDS.toMinutes(entry.listenedMs)} min", style = MaterialTheme.typography.labelSmall) })
        }
        if (summary.topArtists.isNotEmpty()) item { SectionHeader("Top artists") }
        itemsIndexed(summary.topArtists, key = { i, entry -> "a:$i:${entry.key}" }) { i, entry -> ReplayRankRow(i, entry, true) }
        if (summary.topAlbums.isNotEmpty()) item { SectionHeader("Top albums") }
        itemsIndexed(summary.topAlbums, key = { i, entry -> "l:$i:${entry.key}" }) { i, entry -> ReplayRankRow(i, entry, false) }
        item {
            com.opentune.ui.wrapped.WrappedBanner("Your listening story", "Explore your listening habits as an animated story", onOpenWrapped,
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun ReplayExperienceCard(value: String, label: String, who: String, detail: String) {
    Column(Modifier.width(300.dp).height(220.dp).clip(RoundedCornerShape(28.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer)))
        .padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text("YOUR LISTENING EXPERIENCE", style = MaterialTheme.typography.labelSmall)
        Column {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        Column {
            Text(who, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReplayRankRow(index: Int, entry: com.opentune.data.history.TopEntry, round: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", Modifier.width(32.dp), color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Artwork(entry.thumbnailUrl, Modifier.size(56.dp), if (round) CircleShape else RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${TimeUnit.MILLISECONDS.toMinutes(entry.listenedMs)} min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${entry.plays}", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 18.dp, horizontal = 14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
