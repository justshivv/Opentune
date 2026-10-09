package com.opentune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.opentune.data.metadata.TrackMetadata
import com.opentune.data.metadata.TrackMetadataRepository
import com.opentune.data.model.Song
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import kotlinx.coroutines.CancellationException

@Composable
internal fun TrackDetailsDialog(song: Song, durationMs: Long, onDismiss: () -> Unit) {
    var metadata by remember(song.videoId) { mutableStateOf<TrackMetadata?>(null) }
    var message by remember(song.videoId) { mutableStateOf("Matching recording…") }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember(song.videoId) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(song.videoId, durationMs, retry) {
        metadata = null
        failed = false
        message = "Matching recording…"
        try {
            metadata = TrackMetadataRepository.lookup(song, durationMs)
            message = if (metadata == null) "No unambiguous recording match. Original track information is shown above." else "Recording metadata from MusicBrainz"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; message = "Could not reach MusicBrainz. Your track can keep playing." }
    }
    FloatingDialog(onDismissRequest = onDismiss, title = { Text("Track details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Detail("Title", song.title)
            Detail("Artist", song.artist)
            song.albumName?.let { Detail("Album", it) }
            if (durationMs > 0) Detail("Duration", com.opentune.ui.formatTime(durationMs))
            metadata?.let { data ->
                Detail("Recording credits", data.credits)
                data.album?.let { Detail("Matched release", it) }
                data.releaseDate?.let { Detail("Release date", it) }
                Detail("ISRC", data.isrcs.joinToString(", ").ifEmpty { "Not listed" })
                SheetButton("View MusicBrainz recording", onClick = { uri.openUri("https://musicbrainz.org/recording/${data.recordingId}") })
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (failed) SheetButton("Retry", onClick = { retry++ })
        }
    }, confirmButton = { SheetButton("Close", onClick = onDismiss, closes = true) })
}

@Composable private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
