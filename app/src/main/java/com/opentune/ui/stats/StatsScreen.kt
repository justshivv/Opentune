package com.opentune.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.ProjectStats
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.history.Wrapped
import com.opentune.data.library.LibraryStore
import com.opentune.data.recognize.Recognizer
import com.opentune.ui.components.Artwork
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The numbers: OpenTune's own from GitHub (downloads of every release,
 * updates included, stars and forks), then yours from this phone (plays,
 * hours, songs, artists, streaks, favourites and what's saved). Nothing
 * here is counted or sent by the app; the first half is what GitHub shows
 * anyone, the second never leaves the phone.
 */
@Composable
fun StatsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        ProjectStats.init(context)
        Recognizer.init(context)
    }
    val project by ProjectStats.numbers.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        failed = !ProjectStats.refresh()
        loading = false
    }
    val records by History.records.collectAsState()
    val mine = remember(records) { Wrapped.summarize(records, 0L) }
    val liked by LibraryStore.liked.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val found by Recognizer.history.collectAsState()
    val nf = remember { NumberFormat.getIntegerInstance() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    loading = true
                    scope.launch { failed = !ProjectStats.refresh(); loading = false }
                }, enabled = !loading) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
        }

        item(key = "everyone") {
            Section("OpenTune everywhere", "Read from GitHub. The app counts nothing and sends nothing.")
            val p = project
            Hero(
                value = p?.let { nf.format(it.downloads) } ?: if (loading) "…" else "–",
                label = "APK downloads, updates included",
            )
            Grid(
                listOf(
                    "Releases" to (p?.releases?.size?.let(nf::format) ?: "–"),
                    "Stars" to (p?.stars?.let(nf::format) ?: "–"),
                    "Forks" to (p?.forks?.let(nf::format) ?: "–"),
                    "Latest" to (p?.releases?.firstOrNull()?.tag ?: "–"),
                ),
            )
            if (p != null && p.releases.isNotEmpty()) {
                Caption("Downloads by version")
                Bars(p.releases.take(12).reversed().map { it.tag.removePrefix("v") to it.downloads.toLong() }, Modifier.padding(horizontal = 20.dp))
            }
            Caption(
                when {
                    loading -> "Asking GitHub…"
                    failed && p != null -> "Couldn't reach GitHub; these are the numbers from ${ago(p.fetchedAt)}."
                    failed -> "Couldn't reach GitHub just now."
                    else -> "Updated just now."
                },
                dim = true,
            )
        }

        item(key = "you") {
            Spacer(Modifier.height(12.dp))
            Section("Your listening", "Kept on this phone only.")
            Hero(value = nf.format(mine.plays), label = if (mine.plays == 1) "song played" else "songs played")
            Grid(
                listOf(
                    "Hours listened" to hours(mine.minutes),
                    "Different songs" to nf.format(mine.songs),
                    "Artists" to nf.format(mine.artists),
                    "Longest streak" to if (mine.streakDays == 1) "1 day" else "${mine.streakDays} days",
                    "Favourite hour" to if (mine.plays == 0) "–" else hourName(mine.peakHour),
                    "You're a" to if (mine.plays == 0) "–" else mine.clock.title,
                ),
            )
            mine.topSongs.firstOrNull()?.let { top -> Favourite("Most played song", top.title, "${top.subtitle} · ${nf.format(top.plays)} plays", top.thumbnailUrl) }
            mine.topArtists.firstOrNull()?.let { top -> Favourite("Most played artist", top.title, "${nf.format(top.plays)} plays · ${hours(top.minutes)} hours", top.thumbnailUrl) }
            if (mine.months.any { it.second > 0 }) {
                Caption("Minutes by month")
                Bars(mine.months, Modifier.padding(horizontal = 20.dp))
            }
        }

        item(key = "saved") {
            Spacer(Modifier.height(12.dp))
            Section("Saved", null)
            Grid(
                listOf(
                    "Liked songs" to nf.format(liked.size),
                    "Playlists" to nf.format(playlists.size),
                    "Downloaded" to nf.format(downloads.values.count { it.state == DownloadState.DONE }),
                    "Songs found by listening" to nf.format(found.size),
                ),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, note: String?) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Caption(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** The section's one big number, counting up as it appears. */
@Composable
private fun Hero(value: String, label: String) {
    val digits = value.filter(Char::isDigit).toLongOrNull()
    val count = remember(digits) { Animatable(0f) }
    LaunchedEffect(digits) { if (digits != null) count.animateTo(1f, tween(1_100, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
    val shown = if (digits == null) value else NumberFormat.getIntegerInstance().format((digits * count.value).toLong())
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.22f), scheme.tertiary.copy(alpha = 0.10f))))
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(shown, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
}

/** Small numbers two to a row. */
@Composable
private fun Grid(cells: List<Pair<String, String>>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Favourite(label: String, title: String, subtitle: String, art: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(52.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A row of bars that grow in, each named underneath. */
@Composable
private fun Bars(values: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(900, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
    val top = (values.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = values.size
            val slot = size.width / n
            val bar = slot * 0.62f
            values.forEachIndexed { i, (_, v) ->
                val h = (size.height - 16.dp.toPx()) * (v.toFloat() / top) * grow.value
                val x = slot * i + (slot - bar) / 2f
                drawRoundRect(scheme.surfaceContainerHighest, Offset(x, 0f), Size(bar, size.height), CornerRadius(bar / 3f))
                if (h > 0f) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(scheme.primary, scheme.tertiary), startY = size.height - h, endY = size.height),
                        Offset(x, size.height - h),
                        Size(bar, h),
                        CornerRadius(bar / 3f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            values.forEach { (name, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

private fun hours(minutes: Long): String = if (minutes < 60) "${minutes}m" else "%,.1f".format(minutes / 60.0).removeSuffix(".0")

private fun hourName(h: Int): String = when {
    h == 0 -> "12 am"
    h < 12 -> "$h am"
    h == 12 -> "12 pm"
    else -> "${h - 12} pm"
}

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 2 -> "a minute ago"
        m < 60 -> "$m minutes ago"
        m < 48 * 60 -> "${m / 60} hours ago"
        else -> "${m / 1440} days ago"
    }
}
