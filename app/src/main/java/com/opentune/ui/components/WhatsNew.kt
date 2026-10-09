package com.opentune.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What changed, shown once after an update: the notes for each version
 * since the one last opened, newest first. Not shown on a fresh install.
 */
object WhatsNew {
    data class Note(val icon: ImageVector, val title: String, val line: String)

    /** Newest first. Add the coming version's notes at the top when releasing. */
    val NOTES: List<Pair<String, List<Note>>> = listOf(
        "0.3.7" to listOf(
            Note(Icons.AutoMirrored.Rounded.QueueMusic, "Daily mixes", "New on Home every day, made from what you play: your top artists, this time of day, what's on repeat and old favourites."),
            Note(Icons.Rounded.Waves, "Waveform seek bar", "See the song's loud and quiet parts as you scrub. Settings › Player."),
            Note(Icons.Rounded.AutoAwesome, "Covers that fly", "Tap a song and its cover flies down into the now-playing card. Settings › Motion."),
            Note(Icons.Rounded.Favorite, "A burst of hearts", "Liking a song in the player sends hearts out in the cover's colours."),
            Note(Icons.Rounded.Lyrics, "Lyrics in colour", "The cover's colours drift slowly behind the lyrics."),
            Note(Icons.Rounded.Image, "Blurred headers", "Album, artist and playlist pages have their cover blurred behind the top."),
        ),
        "0.3.6" to listOf(
            Note(Icons.Rounded.Cast, "Cast to a TV or speaker", "Play on Chromecasts, smart TVs and network speakers on your Wi-Fi."),
            Note(Icons.Rounded.GraphicEq, "What's this song?", "From Home or the search bar, OpenTune names the song playing near you."),
            Note(Icons.Rounded.NewReleases, "More after one song", "A song started on its own now always carries on into similar songs."),
        ),
    )

    private const val PREFS = "whats_new"

    /**
     * The notes to show now, if any, and remembers the version as seen. A
     * fresh install has nothing new to tell, so it only remembers.
     */
    fun due(context: Context, version: String): List<Pair<String, List<Note>>> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getString("seen", null)
        if (seen == version) return emptyList()
        p.edit().putString("seen", version).apply()
        val fresh = seen == null && runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(true)
        if (fresh) return emptyList()
        return since(seen, version)
    }

    /** The notes for versions after [seen] up to [version], at most three versions. */
    internal fun since(seen: String?, version: String): List<Pair<String, List<Note>>> =
        NOTES.filter { (v, _) -> compare(v, version) <= 0 && (seen == null || compare(v, seen) > 0) }.take(3)

    internal fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/** The sheet itself: each note slides in a moment after the one above it. */
@Composable
fun WhatsNewSheet(notes: List<Pair<String, List<WhatsNew.Note>>>, onDismiss: () -> Unit) {
    FloatingCard(
        onDismiss = onDismiss,
        title = "What's new in ${notes.first().first}",
        icon = Icons.Rounded.NewReleases,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
            var index = 0
            notes.forEachIndexed { v, (version, list) ->
                if (v > 0) {
                    Text(
                        "Also in $version",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                list.forEach { note -> NoteRow(note, index++) }
            }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Text("Let's go") }
        }
    }
}

@Composable
private fun NoteRow(note: WhatsNew.Note, index: Int) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 70L)
        shown.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 260f))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown.value.coerceIn(0f, 1f)
                translationY = (1f - shown.value) * 24.dp.toPx()
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(note.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(note.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
