package com.opentune.ui.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opentune.data.podcasts.PodcastEpisode
import com.opentune.data.podcasts.PodcastFeeds
import com.opentune.data.podcasts.Podcasts
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.theme.AppIcons
import com.opentune.ui.theme.Icon
import com.opentune.ui.theme.Text
import kotlinx.coroutines.launch

/** The entry to [PodcastLibraryScreen] on the Podcasts page. */
@Composable
internal fun PodcastLibraryEntry(shows: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ImageVector.vectorResource(AppIcons.LibraryMusic), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Your podcast library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (shows == 0) "Your shows, new episodes and where you stopped" else com.opentune.i18n.Tr.f("{0} shows · new episodes and where you stopped", shows),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class Shelf(val label: String) { SHOWS("Shows"), NEW("New"), UNFINISHED("In progress"), PLAYED("Played") }

/**
 * Everything kept about podcasts in one place: the shows subscribed to (from YouTube Music,
 * Apple's directory or a feed added by its address), the newest episodes of each, episodes
 * started and not finished, and those played. All of it lives on the phone; no account needed.
 */
@Composable
fun PodcastLibraryScreen(contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenShow: (String) -> Unit) {
    val subscriptions by Podcasts.subscriptions.collectAsState()
    val progress by Podcasts.progress.collectAsState()
    var shelfName by rememberSaveable { mutableStateOf(Shelf.SHOWS.name) }
    val shelf = Shelf.valueOf(shelfName)
    val unfinished = remember(progress) { Podcasts.inProgress(progress) }
    val played = remember(progress) { progress.values.filter { it.finished }.sortedByDescending { it.updatedAt } }

    // The newest episodes of every show subscribed to, read when the New shelf is first opened.
    var latest by remember { mutableStateOf<List<PodcastEpisode>?>(null) }
    LaunchedEffect(shelf, subscriptions) {
        if (shelf == Shelf.NEW && latest == null) latest = Podcasts.latestFromSubscriptions(perShow = 5, shows = Int.MAX_VALUE)
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(com.opentune.ui.theme.tr("Your podcast library"), onBack = onBack) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Shelf.entries, key = { it.name }) { s ->
                    val count = when (s) {
                        Shelf.SHOWS -> subscriptions.size
                        Shelf.NEW -> null
                        Shelf.UNFINISHED -> unfinished.size
                        Shelf.PLAYED -> played.size
                    }
                    FilterChip(
                        selected = s == shelf,
                        onClick = { shelfName = s.name },
                        label = { Text(if (count != null && count > 0) "${com.opentune.i18n.Tr.t(s.label)} · $count" else com.opentune.i18n.Tr.t(s.label)) },
                    )
                }
            }
        }
        when (shelf) {
            Shelf.SHOWS -> {
                item(key = "add") { AddFeed(onAdded = onOpenShow) }
                if (subscriptions.isEmpty()) item(key = "no-shows") {
                    MessageState(
                        ImageVector.vectorResource(AppIcons.Podcasts), "No shows yet",
                        message = "Subscribe to a show from its page, or add one by its feed address above.",
                    )
                }
                items(subscriptions, key = { "show:${it.browseId}" }) { show ->
                    ShowRow(show) { onOpenShow(show.browseId) }
                }
            }
            Shelf.NEW -> {
                val list = latest
                when {
                    subscriptions.isEmpty() -> item(key = "no-new") { MessageState(ImageVector.vectorResource(AppIcons.NewReleases), "Nothing new yet", message = "New episodes of the shows you subscribe to show here.") }
                    list == null -> items(5) { SongRowPlaceholder() }
                    list.isEmpty() -> item(key = "none-new") { MessageState(ImageVector.vectorResource(AppIcons.NewReleases), "Couldn't load new episodes", message = "Check the connection and open this again.") }
                    else -> items(list, key = { "new:${it.videoId}" }) { ep ->
                        EpisodeRow(ep, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(list, list.indexOf(ep), ep.showTitle) }
                    }
                }
            }
            Shelf.UNFINISHED -> {
                if (unfinished.isEmpty()) item(key = "no-unfinished") { MessageState(ImageVector.vectorResource(AppIcons.History), "Nothing in progress", message = "Episodes you start and don't finish wait here.") }
                items(unfinished, key = { "un:${it.episode.videoId}" }) { p ->
                    EpisodeRow(p.episode, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(listOf(p.episode), 0, p.episode.showTitle) }
                }
            }
            Shelf.PLAYED -> {
                if (played.isEmpty()) item(key = "no-played") { MessageState(ImageVector.vectorResource(AppIcons.CheckCircle), "Nothing played yet", message = "Episodes you finish are listed here.") }
                items(played, key = { "pl:${it.episode.videoId}" }) { p ->
                    EpisodeRow(p.episode, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(listOf(p.episode), 0, p.episode.showTitle) }
                }
            }
        }
    }
}

/**
 * A show added by its RSS feed's address, for one that no directory lists: the feed is read,
 * and when it's a podcast the show is subscribed to and opened.
 */
@Composable
private fun AddFeed(onAdded: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun add() {
        val url = address.trim()
        if (url.isEmpty() || busy) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) { problem = com.opentune.i18n.Tr.t("Enter the feed's full address, starting with https://"); return }
        busy = true; problem = null
        scope.launch {
            runCatching { PodcastFeeds.load(PodcastFeeds.showId(url), fresh = true) }
                .onSuccess { page ->
                    Podcasts.setSubscribed(page.show, true)
                    address = ""
                    onAdded(page.show.browseId)
                }
                .onFailure { problem = com.opentune.i18n.Tr.t("That address isn't a podcast feed, or it couldn't be reached") }
            busy = false
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Add a show by its feed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "For a show no search finds: paste its RSS feed address, from the show's website or its host.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = address,
                onValueChange = { address = it; problem = null },
                singleLine = true,
                placeholder = { Text("https://…/feed.rss") },
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = ::add, enabled = address.isNotBlank() && !busy, modifier = Modifier.padding(start = 8.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Add")
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp, start = 8.dp)) }
    }
}
