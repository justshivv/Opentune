package com.opentune.ui.podcasts

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import com.opentune.ui.theme.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.data.podcasts.PodcastEpisode
import com.opentune.data.podcasts.PodcastShow
import com.opentune.data.podcasts.Podcasts
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.components.SongRowPlaceholder
import com.opentune.ui.components.pressable
import com.opentune.ui.rememberLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/** Plays [episodes] from [index] as a queue, each remembered so its place is kept. */
fun SongActions.playEpisodes(episodes: List<PodcastEpisode>, index: Int, source: String?) {
    episodes.forEach(Podcasts::starting)
    playAll(episodes.map { it.toSong() }, index, false, source)
}

private enum class SearchKind(val label: String) { SHOWS("Shows"), EPISODES("Episodes") }

/**
 * Podcasts: search shows and episodes, topics, what you're in the middle
 * of, your shows and their newest episodes, popular episodes and shows to
 * try, from YouTube Music.
 */
@Composable
fun PodcastsScreen(contentPadding: PaddingValues, actions: SongActions, onOpenShow: (String) -> Unit, onOpenSettings: (() -> Unit)? = null, onOpenAudiobooks: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(SearchKind.SHOWS) }
    val subscriptions by Podcasts.subscriptions.collectAsState()
    val progress by Podcasts.progress.collectAsState()
    // The suggestions follow what was listened to: another episode to draw from loads them anew.
    val seeds = remember(progress) { Podcasts.seeds(progress).map { it.videoId }.sorted().joinToString(",") }
    val loader = rememberLoader("podcasts:home:${com.opentune.data.innertube.Innertube.currentRegion}:$seeds") { Podcasts.home() }
    val state by loader.state.collectAsState()
    val inProgress = remember(progress) { Podcasts.inProgress(progress).take(10) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    LaunchedEffect(subscriptions.map { it.browseId }, refreshGeneration) {
        latest = runCatching { Podcasts.latestFromSubscriptions() }.getOrDefault(latest)
    }

    var shows by remember { mutableStateOf<List<PodcastShow>?>(null) }
    var episodes by remember { mutableStateOf<List<PodcastEpisode>?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // A chip that lists episodes instead of searching: a show listened to, or a quick filter.
    var pick by remember { mutableStateOf<ChipPick?>(null) }
    val topShows = remember(progress, subscriptions) { com.opentune.data.podcasts.PodcastInterests.topShows(progress.values, subscriptions) }
    val topicUse by Podcasts.topicUse.collectAsState()
    val topics = remember(topicUse, progress) { Podcasts.topicsInOrder(topicUse, progress.values) }
    val suggested = (state as? UiState.Success)?.data?.suggested.orEmpty()
    // Under half an hour, of what's new from the shows followed and what's suggested, not yet heard.
    val short = remember(latest, suggested, progress) {
        (latest + suggested).filter { ep ->
            val p = progress[ep.videoId]
            (p == null || !p.finished) && (com.opentune.data.podcasts.PodcastInterests.minutes(ep.durationText) ?: Int.MAX_VALUE) < 30
        }.distinctBy { it.videoId }
    }
    LaunchedEffect(query, kind, attempt, pick) {
        val q = query.trim()
        val chosen = pick
        shows = null; episodes = null; searchError = null
        if (chosen != null) {
            episodes = when (chosen) {
                is ChipPick.Show -> try { Podcasts.show(chosen.show.browseId).episodes } catch (e: CancellationException) { throw e } catch (e: Exception) { searchError = e.message ?: "The show didn't load"; null }
                ChipPick.Unfinished -> inProgress.map { it.episode }
                ChipPick.NewFromYourShows -> latest
                ChipPick.Short -> short
            }
            return@LaunchedEffect
        }
        if (q.isEmpty()) return@LaunchedEffect
        delay(350)
        try {
            if (kind == SearchKind.SHOWS) shows = Podcasts.searchShows(q) else episodes = Podcasts.searchEpisodes(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = e.message ?: "Search failed"
        }
    }

    val refreshing by loader.refreshing.collectAsState()
    com.opentune.ui.components.MarkRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshGeneration++; attempt++; loader.reload(keepContent = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { PageHeader(com.opentune.ui.theme.tr("Podcasts"), actions = onOpenSettings?.let { open -> { com.opentune.ui.components.AccountButton(open) } }) }
            if (onOpenAudiobooks != null) item(key = "audiobooks") { AudiobooksEntry(onOpenAudiobooks) }
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it; pick = null },
                    singleLine = true,
                    placeholder = { Text("Search shows and episodes") },
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
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The shows listened to most, then quick filters, then the topics, the listener's first.
                    fun choose(p: ChipPick) { pick = if (pick == p) null else p; query = "" }
                    items(topShows, key = { "show:${it.browseId}" }) { show ->
                        val p = ChipPick.Show(show)
                        FilterChip(pick == p, { choose(p) }, { Text(show.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.widthIn(max = 200.dp))
                    }
                    if (inProgress.isNotEmpty()) item(key = "unfinished") { FilterChip(pick == ChipPick.Unfinished, { choose(ChipPick.Unfinished) }, { Text("Unfinished") }) }
                    if (latest.isNotEmpty()) item(key = "new") { FilterChip(pick == ChipPick.NewFromYourShows, { choose(ChipPick.NewFromYourShows) }, { Text("New from your shows") }) }
                    if (short.isNotEmpty()) item(key = "short") { FilterChip(pick == ChipPick.Short, { choose(ChipPick.Short) }, { Text("Under 30 min") }) }
                    items(topics, key = { "topic:$it" }) { topic ->
                        FilterChip(query == topic, {
                            pick = null
                            if (query != topic) Podcasts.pickedTopic(topic)
                            query = if (query == topic) "" else topic; kind = SearchKind.SHOWS
                        }, { Text(topic) })
                    }
                }
            }

            if (query.isNotBlank() || pick != null) {
                // A show or a filter lists episodes; only a search has shows to choose from.
                if (pick == null) item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        SearchKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, SearchKind.entries.size)) { Text(k.label) }
                        }
                    }
                }
                when {
                    searchError != null -> item { ErrorState(searchError!!, onRetry = { attempt++ }) }
                    pick == null && kind == SearchKind.SHOWS -> {
                        val s = shows
                        if (s == null) items(6) { SongRowPlaceholder() }
                        else if (s.isEmpty()) item { MessageState(ImageVector.vectorResource(AppIcons.Podcasts), "No shows found") }
                        else items(s, key = { it.browseId }) { ShowRow(it) { onOpenShow(it.browseId) } }
                    }
                    else -> {
                        val e = episodes
                        if (e == null) items(6) { SongRowPlaceholder() }
                        else if (e.isEmpty()) item { MessageState(ImageVector.vectorResource(AppIcons.Podcasts), "No episodes found") }
                        else items(e, key = { it.videoId }) { ep ->
                            EpisodeRow(ep, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(listOf(ep), 0, ep.showTitle) }
                        }
                    }
                }
                return@LazyColumn
            }

            if (inProgress.isNotEmpty()) {
                item { SectionHeader("Continue listening") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(inProgress, key = { it.episode.videoId }) { p ->
                            ContinueCard(p) { actions.playEpisodes(listOf(p.episode), 0, p.episode.showTitle) }
                        }
                    }
                }
            }
            if (subscriptions.isNotEmpty()) {
                item { SectionHeader("Your shows") }
                item { ShowCarousel(subscriptions, onOpenShow) }
                if (latest.isNotEmpty()) {
                    item { SectionHeader("New from your shows") }
                    items(latest.take(8), key = { "new:${it.videoId}" }) { ep ->
                        EpisodeRow(ep, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(listOf(ep), 0, ep.showTitle) }
                    }
                }
            }
            when (val s = state) {
                is UiState.Loading -> {
                    item { ShelfPlaceholder() }
                    items(5) { SongRowPlaceholder() }
                }
                is UiState.Error -> item { ErrorState(s.message, onRetry = { loader.reload() }) }
                is UiState.Success -> {
                    val home = s.data
                    home.becauseOf.forEach { (show, list) ->
                        item(key = "because:$show") { SectionHeader(show, subtitle = "Because you listened to") }
                        item(key = "becauseRow:$show") { ShowCarousel(list, onOpenShow) }
                    }
                    if (home.suggested.isNotEmpty()) {
                        item(key = "suggestedHeader") { SectionHeader("Episodes you might like") }
                        items(home.suggested, key = { "sug:${it.videoId}" }) { ep ->
                            EpisodeRow(ep, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(listOf(ep), 0, ep.showTitle) }
                        }
                    }
                    if (home.shows.isNotEmpty()) {
                        item { SectionHeader("Shows to try") }
                        item { ShowCarousel(home.shows, onOpenShow) }
                    }
                    if (home.popular.isNotEmpty()) {
                        item { SectionHeader("Popular episodes") }
                        items(home.popular, key = { "pop:${it.videoId}" }) { ep ->
                            EpisodeRow(ep, actions, showShow = true, onOpenShow = onOpenShow) { actions.playEpisodes(listOf(ep), 0, "Popular episodes") }
                        }
                    }
                    home.more.forEach { (topic, list) ->
                        item(key = "topic:$topic") { SectionHeader(topic, subtitle = "Topic") }
                        item(key = "topicRow:$topic") { ShowCarousel(list, onOpenShow) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowCarousel(shows: List<PodcastShow>, onOpenShow: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(shows, key = { it.browseId }) { show ->
            Column(Modifier.width(140.dp).pressable({ onOpenShow(show.browseId) })) {
                Artwork(show.thumbnailUrl.artworkAt(360), Modifier.size(140.dp), RoundedCornerShape(18.dp), placeholder = ImageVector.vectorResource(AppIcons.Podcasts))
                Text(show.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (show.author.isNotBlank()) {
                    Text(show.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(p: Podcasts.Progress, onPlay: () -> Unit) {
    Column(Modifier.width(240.dp).pressable(onPlay)) {
        Box {
            Artwork(p.episode.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(16.dp), placeholder = ImageVector.vectorResource(AppIcons.Podcasts))
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(4.dp),
                trackColor = Color.White.copy(alpha = 0.35f),
            )
        }
        Text(p.episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(
            "${p.episode.showTitle} · ${remaining(p)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun remaining(p: Podcasts.Progress): String {
    if (p.durationMs <= 0) return "In progress"
    val minutes = ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) com.opentune.i18n.Tr.f("{0} hr {1} min left", minutes / 60, minutes % 60) else com.opentune.i18n.Tr.f("{0} min left", minutes)
}

@Composable
fun ShowRow(show: PodcastShow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(show.thumbnailUrl.artworkAt(200), Modifier.size(64.dp), RoundedCornerShape(14.dp), placeholder = ImageVector.vectorResource(AppIcons.Podcasts))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (show.author.isNotBlank()) Text(show.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One episode: its thumbnail, title, show, date and length, how far you
 * got, and the start of its description. Tap to play.
 */
@Composable
fun EpisodeRow(
    episode: PodcastEpisode,
    actions: SongActions,
    showShow: Boolean,
    onOpenShow: (String) -> Unit,
    onPlay: () -> Unit,
) {
    val progress by Podcasts.progress.collectAsState()
    val saved = progress[episode.videoId]
    val playing = actions.currentVideoId == episode.videoId
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 10.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            Artwork(episode.thumbnailUrl, Modifier.width(120.dp).aspectRatio(16f / 9f), RoundedCornerShape(12.dp), placeholder = ImageVector.vectorResource(AppIcons.Podcasts))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = listOfNotNull(
                    episode.showTitle.takeIf { showShow && it.isNotBlank() },
                    episode.published,
                    episode.durationText,
                ).joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).then(
                            if (showShow && episode.showBrowseId != null) Modifier.clickable { onOpenShow(episode.showBrowseId!!) } else Modifier,
                        ),
                    )
                }
                when {
                    saved?.finished == true -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(AppIcons.CheckCircle), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Played", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
                    }
                    saved != null && saved.fraction > 0.01f -> LinearProgressIndicator(
                        progress = { saved.fraction },
                        modifier = Modifier.padding(top = 8.dp).width(120.dp).height(3.dp),
                    )
                }
            }
        }
        episode.description?.let { d ->
            Text(
                d,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 12 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded },
            )
        }
        if (saved != null) {
            TextButton(onClick = { Podcasts.markPlayed(episode.videoId, saved.finished.not()) }, modifier = Modifier.padding(top = 2.dp)) {
                Text(if (saved.finished) "Mark as unplayed" else "Mark as played", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A podcast show: cover, name, who makes it, subscribe, play newest, and the episodes. */
@Composable
fun PodcastShowScreen(browseId: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onOpenShow: (String) -> Unit) {
    val loader = rememberLoader("podcast:$browseId") { Podcasts.show(browseId) }
    val state by loader.state.collectAsState()
    val subscriptions by Podcasts.subscriptions.collectAsState()
    var more by remember(browseId) { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    var token by remember(browseId) { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    // The next page didn't load: it waits for a tap on Retry. Asked for again at once, as the
    // last row coming back asked, a page that kept failing was asked for over and over.
    var moreFailed by remember(browseId) { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    val page = (state as? UiState.Success)?.data
    LaunchedEffect(page) { if (page != null && more.isEmpty()) token = page.continuation }
    var wantMore by remember { mutableIntStateOf(0) }
    LaunchedEffect(wantMore) {
        val t = token ?: return@LaunchedEffect
        val show = page?.show ?: return@LaunchedEffect
        if (wantMore == 0) return@LaunchedEffect
        loadingMore = true
        try {
            val (list, next) = Podcasts.moreEpisodes(show, t)
            more = (more + list).distinctBy { it.videoId }
            // The same token again would only bring the same page back.
            token = next?.takeIf { it != t }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            moreFailed = true
        } finally {
            loadingMore = false
        }
    }

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader(page?.show?.title ?: "Podcast", onBack = onBack) }
        when (val s = state) {
            is UiState.Loading -> {
                item { Placeholder(Modifier.padding(16.dp).size(180.dp), RoundedCornerShape(24.dp)) }
                items(5) { SongRowPlaceholder() }
            }
            is UiState.Error -> item { ErrorState(s.message, onRetry = { loader.reload() }) }
            is UiState.Success -> {
                val show = s.data.show
                val all = (s.data.episodes + more).distinctBy { it.videoId }
                val subscribed = subscriptions.any { it.browseId == show.browseId }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(show.thumbnailUrl.artworkAt(600), Modifier.size(140.dp), RoundedCornerShape(24.dp), placeholder = ImageVector.vectorResource(AppIcons.Podcasts))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(show.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (show.author.isNotBlank()) Text(show.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Button(
                            onClick = { if (all.isNotEmpty()) actions.playEpisodes(all, 0, show.title) },
                            enabled = all.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Play newest") }
                        androidx.compose.material3.FilledTonalButton(onClick = { Podcasts.setSubscribed(show, !subscribed) }, modifier = Modifier.weight(1f)) {
                            Text(if (subscribed) "Subscribed" else "Subscribe")
                        }
                    }
                }
                show.description?.let { d ->
                    item {
                        Text(
                            d,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (aboutOpen) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { aboutOpen = !aboutOpen }.padding(horizontal = 16.dp, vertical = 10.dp).animateContentSize(),
                        )
                    }
                }
                item { SectionHeader("Episodes", subtitle = com.opentune.i18n.Tr.f("{0}{1} episodes", all.size, if (token != null) "+" else "")) }
                items(all.size, key = { all[it].videoId }) { i ->
                    EpisodeRow(all[i], actions, showShow = false, onOpenShow = onOpenShow) { actions.playEpisodes(all, i, show.title) }
                    if (i == all.lastIndex && token != null && !loadingMore && !moreFailed) LaunchedEffect(all.size) { wantMore++ }
                }
                if (loadingMore) item { SongRowPlaceholder() }
                else if (moreFailed && token != null) item(key = "more-failed") {
                    // Back in view, the last row asks for the page again.
                    TextButton(onClick = { moreFailed = false }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Couldn't load more episodes. Retry")
                    }
                }
            }
        }
    }
}

/** A chip that lists episodes rather than searching: a show's own, or a quick filter of the listener's. */
private sealed interface ChipPick {
    data class Show(val show: PodcastShow) : ChipPick
    data object Unfinished : ChipPick
    data object NewFromYourShows : ChipPick
    data object Short : ChipPick
}
