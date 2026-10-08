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
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder
import java.util.concurrent.TimeUnit

@Composable
internal fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) {
            GlassIconButton(Icons.Rounded.Shuffle, "Shuffle", onShuffle, size = 44.dp)
            GlassIconButton(Icons.Rounded.PlayArrow, "Play", onPlay, size = 44.dp)
        } else {
            Button(onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play")
            }
            FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Shuffle")
            }
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

private enum class ReplayPeriod(val label: String, val days: Long?) {
    MONTH("4 weeks", 28),
    HALF_YEAR("6 months", 182),
    YEAR("This year", 365),
    ALL("All time", null),
}

/** Top songs, artists and albums from this device's listening history. */
@Composable
fun ReplayScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenWrapped: () -> Unit = {}) {
    val records by History.records.collectAsState()
    var period by rememberSaveable { mutableStateOf(ReplayPeriod.MONTH) }
    val summary = remember(records, period) {
        val since = period.days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it) } ?: 0L
        History.replay(records, since)
    }
    val topSongs = remember(summary) { summary.topSongs.mapNotNull { it.song } }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Replay", onBack = onBack) }
        item {
            com.opentune.ui.wrapped.WrappedBanner(
                "Your Wrapped",
                "Your top artists, songs and listening habits as an animated story",
                onOpenWrapped,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReplayPeriod.entries) { p ->
                    FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("${TimeUnit.MILLISECONDS.toMinutes(summary.listenedMs)}", "minutes", Modifier.weight(1f))
                StatTile("${summary.totalPlays}", "plays", Modifier.weight(1f))
                StatTile("${summary.topArtists.size}", "artists", Modifier.weight(1f))
            }
        }
        if (summary.totalPlays == 0) {
            item {
                MessageState(Icons.Rounded.BarChart, "Nothing to replay yet", Modifier.padding(top = 16.dp), message = "Listen for a while and your top songs and artists show up here.")
            }
            return@LazyColumn
        }
        if (summary.topArtists.isNotEmpty()) {
            item { SectionHeader("Top artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(summary.topArtists.take(12)) { i, a ->
                        Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(a.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(112.dp), CircleShape)
                            Text("${i + 1}. ${a.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                            Text("${a.plays} plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Top songs", action = {
                PlayButtons(onPlay = { actions.playAll(topSongs, 0, false, "Replay") }, onShuffle = { actions.playAll(topSongs, 0, true, "Replay") }, compact = true)
            })
        }
        itemsIndexed(summary.topSongs, key = { i, e -> "s:$i:${e.key}" }) { i, entry ->
            val song = entry.song ?: return@itemsIndexed
            SongListItem(
                song = song.copy(durationText = "${entry.plays} plays"),
                onClick = { actions.playAll(topSongs, i, false, "Replay") },
                isCurrent = song.videoId == actions.currentVideoId,
                isPlaying = actions.isPlaying,
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                        Spacer(Modifier.width(8.dp))
                        Artwork(song.thumbnailUrl, Modifier.size(52.dp))
                    }
                },
                onPlayNext = { actions.playNext(song) },
                onAddToQueue = { actions.addToQueue(song) },
            )
        }
        if (summary.topAlbums.isNotEmpty()) {
            item { SectionHeader("Top albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(summary.topAlbums.take(12)) { a ->
                        Column(Modifier.width(140.dp)) {
                            Artwork(a.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(140.dp), MaterialTheme.shapes.medium)
                            Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text("${a.subtitle} • ${a.plays} plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
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
