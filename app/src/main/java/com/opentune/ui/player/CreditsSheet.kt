package com.opentune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.metadata.Credit
import com.opentune.data.metadata.SongCredits
import com.opentune.data.metadata.SongCreditsRepository
import com.opentune.data.model.Song
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.FloatingCard
import com.opentune.ui.components.SheetButton
import com.opentune.ui.theme.Text
import kotlinx.coroutines.CancellationException

/**
 * Who made [song]: performing artists, composition & lyrics, and production & engineering,
 * from the credits its label sent to YouTube ([SongCreditsRepository]).
 */
@Composable
internal fun CreditsSheet(song: Song, onDismiss: () -> Unit) {
    var credits by remember(song.videoId) { mutableStateOf<SongCredits?>(null) }
    var loading by remember(song.videoId) { mutableStateOf(true) }
    var failed by remember(song.videoId) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(song.videoId, retry) {
        loading = true
        failed = false
        try {
            credits = SongCreditsRepository.lookup(song.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
        loading = false
    }
    val c = MaterialTheme.colorScheme
    FloatingCard(
        onDismiss = onDismiss,
        title = "Credits",
        expandable = true,
        fitContent = false,
        fullFraction = 1f,
        openFraction = 0.75f,
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumbnailUrl, Modifier.size(56.dp), shape = RoundedCornerShape(10.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp).padding(bottom = 24.dp)) {
            val data = credits
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
                failed -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't reach YouTube for the credits.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    SheetButton("Try again", onClick = { retry++ })
                }
                data == null || data.isEmpty -> Text(
                    "No credits for this song. Credits come with songs a label puts on YouTube Music; uploads, and some releases, have none.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 24.dp),
                )
                else -> {
                    Section("Performing Artists", data.performers)
                    Section("Composition & Lyrics", data.composition)
                    Section("Production & Engineering", data.production)
                    val footer = listOfNotNull(
                        data.released?.let { com.opentune.i18n.Tr.f("Released {0}", it) },
                        data.copyright,
                        data.label?.let { com.opentune.i18n.Tr.f("Credits as {0} sent them to YouTube", it) },
                    )
                    if (footer.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), color = c.outlineVariant.copy(alpha = 0.5f))
                        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            footer.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, people: List<Credit>) {
    if (people.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 14.dp).padding(top = 20.dp, bottom = 6.dp),
    )
    people.forEach { person ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(person.roles.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
