package com.opentune.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LyricsSource
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton

/**
 * Every lyrics source asked for this song at once, each row saying what it
 * found as the answers come in. Picking one keeps it for this song.
 */
@Composable
internal fun LyricsSourceDialog(song: Song, durationMs: Long, onDismiss: () -> Unit) {
    val settings by AppSettings.lyrics.collectAsState()
    val chosen = settings.chosen[song.videoId]
    val answers = remember(song.videoId) { mutableStateMapOf<LyricsSource, Result<Lyrics?>>() }
    LaunchedEffect(song.videoId, durationMs) {
        LyricsRepository.everySource(song.videoId, song.title, song.artist, durationMs).collect { (source, result) -> answers[source] = result }
    }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "What each source has for this song. Pick one to use it for this song from now on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SourceRow("Automatic", "Your order in Settings › Lyrics sources", picked = chosen == null, enabled = true, waiting = false) {
                    AppSettings.setLyricsSource(song.videoId, null)
                    onDismiss()
                }
                LyricsSource.entries.forEach { source ->
                    val answer = answers[source]
                    val found = answer?.getOrNull()
                    SourceRow(source.label, describe(answer, durationMs), picked = chosen == source, enabled = found != null, waiting = answer == null) {
                        AppSettings.setLyricsSource(song.videoId, source)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { SheetButton("Done", onClick = onDismiss, closes = true) },
    )
}

private fun describe(answer: Result<Lyrics?>?, durationMs: Long): String {
    if (answer == null) return "Looking…"
    if (answer.isFailure) return "Couldn't reach it just now"
    val l = answer.getOrNull() ?: return "Nothing for this song"
    val kind = when (l) {
        is Lyrics.Plain -> "Plain text, not synced"
        is Lyrics.Synced -> {
            val n = l.lines.size
            if (l.lines.any { it.wordSynced }) com.opentune.i18n.Tr.f("Word by word · {0} lines", n) else com.opentune.i18n.Tr.f("Line by line · {0} lines", n)
        }
    }
    // Why the automatic choice passes it over, so picking it is an informed choice.
    val problem = LyricsRepository.problemWith(l, durationMs) ?: return kind
    return com.opentune.i18n.Tr.f("{0} · Not used: {1}", kind, problem.replaceFirstChar { it.lowercase() })
}

@Composable
private fun SourceRow(title: String, detail: String, picked: Boolean, enabled: Boolean, waiting: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            waiting -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            picked -> Icon(Icons.Rounded.Check, "In use", Modifier.size(24.dp))
        }
    }
    HorizontalDivider()
}
