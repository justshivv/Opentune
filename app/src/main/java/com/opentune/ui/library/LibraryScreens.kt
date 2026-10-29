package com.opentune.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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

/** Ranked listening summaries; all figures come from recorded listens on this device. */
@Composable
fun ReplayScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenWrapped: () -> Unit = {}) {
    val records by History.records.collectAsState()
    val account by com.opentune.data.account.AccountStore.account.collectAsState()
    var range by rememberSaveable { mutableStateOf(ReplayRange.YEAR) }
    val summary = remember(records, range) { History.replay(records, range.since()) }
    val topSongs = remember(summary) { summary.topSongs.mapNotNull { it.song } }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var story by rememberSaveable { mutableStateOf(false) }
    if (sharing) ReplayShareSheet(summary, range.heading(), onDismiss = { sharing = false })
    if (story) ReplayStory(summary, range, onDismiss = { story = false })
    val name = account?.name ?: com.opentune.data.together.Together.savedName.takeIf { it.isNotBlank() } ?: "You"
    val since = remember(records) { memberSince(records.lastOrNull()?.playedAt) }
    // The page takes the colour of the card in front.
    var card by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tint by androidx.compose.animation.animateColorAsState(replayTint(card), androidx.compose.animation.core.tween(500), label = "replayTint")
    val background = MaterialTheme.colorScheme.background
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().background(
        androidx.compose.ui.graphics.Brush.verticalGradient(0f to tint.copy(alpha = 0.75f), 0.55f to background))) {
        item {
            PageHeader("Replay", onBack = onBack, actions = if (summary.totalPlays == 0) null else {
                { GlassIconButton(Icons.Rounded.Share, "Share my Replay", { sharing = true }) }
            })
            Text(range.heading(), Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            // Weekly, Monthly, Yearly, Lifetime, the chosen one on a light pill.
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)).padding(4.dp)) {
                ReplayRange.entries.forEach { r ->
                    val chosen = r == range
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(CircleShape)
                            .background(if (chosen) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { range = r; card = 0 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(r.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            color = if (chosen) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item(key = "cards:${range.name}") {
            ReplayCards(summary, range, name, since, onClick = { story = true }, onCard = { card = it })
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .clickable { story = true }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Text("Play your Replay", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (summary.totalPlays == 0) item {
            MessageState(Icons.Rounded.BarChart, "Nothing to replay yet", Modifier.padding(16.dp), message = "Listen for a while and your top songs and artists show up here.")
        }
        if (summary.topSongs.isNotEmpty()) item { SectionHeader("Top songs") }
        itemsIndexed(summary.topSongs, key = { i, entry -> "s:$i:${entry.key}" }) { i, entry ->
            val song = entry.song ?: return@itemsIndexed
            // "Artist · 9 min" under the title, the plays at the end.
            SongListItem(song.copy(durationText = "${TimeUnit.MILLISECONDS.toMinutes(entry.listenedMs)} min"),
                onClick = { actions.playAll(topSongs, i, false, "Replay") },
                isCurrent = song.videoId == actions.currentVideoId, isPlaying = actions.isPlaying,
                leading = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(28.dp), color = if (i == 0) androidx.compose.ui.graphics.Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    Artwork(song.thumbnailUrl, Modifier.size(52.dp))
                } }, onPlayNext = { actions.playNext(song) }, onAddToQueue = { actions.addToQueue(song) },
                trailing = { Text("${entry.plays}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) })
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
