package com.opentune.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.MusicRepository
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.durationMillis
import com.opentune.data.model.UiState
import com.opentune.data.settings.AppSettings
import com.opentune.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import com.opentune.data.innertube.StreamResolver
import kotlinx.coroutines.Dispatchers

/** Where Search looks: YouTube Music, your own library, or your audio add-ons. */
enum class SearchSource(val label: String, val placeholder: String) {
    YOUTUBE("YouTube", "Songs, albums, artists"),
    LIBRARY("Library", "Songs, artists and albums"),
    ADDONS("Add-ons", "Search your add-ons"),
}

/**
 * A track one of your add-ons found, with the add-on that has it, and the
 * same recording as other add-ons have it ([alternates]), shown as one row.
 */
class AddonHit(
    val track: com.opentune.data.addons.AddonTrack,
    val addon: com.opentune.data.addons.AudioAddon,
    val manifest: com.opentune.data.addons.AddonManifest,
    val alternates: List<AddonHit> = emptyList(),
) {
    val key get() = "${addon.key}:${track.id}"

    /** The same recording: title and lead artist alike, lengths within [SAME_RECORDING_MS]. */
    fun sameRecording(other: AddonHit): Boolean {
        val a = track
        val b = other.track
        if (norm(a.title) != norm(b.title) || norm(lead(a.artist)) != norm(lead(b.artist))) return false
        return a.durationMs <= 0 || b.durationMs <= 0 || kotlin.math.abs(a.durationMs - b.durationMs) <= SAME_RECORDING_MS
    }

    /**
     * Of YouTube Music's [songs] for this track, the same recording: title
     * (without bracketed extras) and lead artist alike, and lengths close
     * where both are known. Only the title has to agree when nothing else
     * does. With no title match at all, the top result with this track's
     * own title and artist, so the lyrics looked up are this song's rather
     * than whatever YouTube put first (sometimes the song just played).
     */
    fun bestMatch(songs: List<com.opentune.data.model.Song>): com.opentune.data.model.Song? {
        if (songs.isEmpty()) return null
        val title = titleKey(track.title)
        val artist = norm(lead(track.artist))
        fun titleOk(s: com.opentune.data.model.Song) = titleKey(s.title) == title
        fun artistOk(s: com.opentune.data.model.Song) = artist.isNotEmpty() && norm(s.artist).contains(artist)
        fun lengthOk(s: com.opentune.data.model.Song): Boolean {
            val ms = s.durationMillis()
            return track.durationMs <= 0 || ms <= 0 || kotlin.math.abs(track.durationMs - ms) <= MATCH_LENGTH_MS
        }
        return songs.firstOrNull { titleOk(it) && artistOk(it) && lengthOk(it) }
            ?: songs.firstOrNull { titleOk(it) && artistOk(it) }
            ?: songs.firstOrNull { titleOk(it) }
            ?: songs.first().copy(title = track.title, artist = track.artist)
    }

    companion object {
        const val SAME_RECORDING_MS = 2_000L
        /** A YouTube upload's length can differ from the add-on's file by a few seconds of silence. */
        const val MATCH_LENGTH_MS = 4_000L
        private fun titleKey(s: String) = norm(s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").substringBefore(" - "))
        private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        private fun lead(s: String) = s.substringBefore(",").substringBefore("&").substringBefore(" feat").trim()

        /** [hits] with each recording once, at the place of its first copy; the others become its alternates. */
        fun merged(hits: List<AddonHit>): List<AddonHit> {
            val kept = mutableListOf<Pair<AddonHit, MutableList<AddonHit>>>()
            for (h in hits) {
                val same = kept.firstOrNull { it.first.sameRecording(h) }
                if (same != null) same.second += h else kept += h to mutableListOf()
            }
            return kept.map { (h, more) -> AddonHit(h.track, h.addon, h.manifest, more) }
        }
    }
}

class SearchViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    private val _source = MutableStateFlow(SearchSource.YOUTUBE)
    val source = _source.asStateFlow()

    /** Library matches for the text so far; null while there's nothing to search for. */
    private val _library = MutableStateFlow<List<com.opentune.data.model.Song>?>(null)
    val library = _library.asStateFlow()

    /** Playlists in the library whose name matches the text so far. */
    private val _libraryLists = MutableStateFlow<List<LibraryList>>(emptyList())
    val libraryLists = _libraryLists.asStateFlow()

    /** Add-on matches for the text so far; null while there's nothing to search for. */
    private val _addons = MutableStateFlow<UiState<List<AddonHit>>?>(null)
    val addons = _addons.asStateFlow()

    /** The Local Music folder, read once a search first needs it. */
    private var localSongs: List<com.opentune.data.model.Song>? = null

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    /** The query the results belong to; null before the first search. */
    private val _submitted = MutableStateFlow<String?>(null)
    val submitted = _submitted.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter = _filter.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    /** What a search for the text so far finds, shown under the suggestions while typing. */
    private val _live = MutableStateFlow<List<SearchResult>>(emptyList())
    val live = _live.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>>(UiState.Loading)
    val results = _results.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch { watchSuggestions() }
        viewModelScope.launch { watchLive() }
        viewModelScope.launch { watchOtherSources() }
    }

    fun setSource(source: SearchSource) {
        _source.value = source
    }

    /** Library and add-on searches run as the words come; there's nothing to submit. */
    @OptIn(FlowPreview::class)
    private suspend fun watchOtherSources() {
        kotlinx.coroutines.flow.combine(_query.map { it.trim() }.distinctUntilChanged().debounce(OTHER_DEBOUNCE_MS), _source) { q, src -> q to src }
            .distinctUntilChanged().collectLatest { (q, src) ->
                when {
                    src == SearchSource.LIBRARY -> {
                        _libraryLists.value = if (q.isEmpty()) emptyList() else searchLists(q)
                        _library.value = if (q.isEmpty()) null else searchLibrary(q)
                    }
                    src == SearchSource.ADDONS && q.isEmpty() -> _addons.value = null
                    src == SearchSource.ADDONS -> {
                        _addons.value = UiState.Loading
                        _addons.value = searchAddons(q)
                    }
                }
            }
    }

    /**
     * Your playlists (made here or brought over from Spotify) and the albums and
     * playlists saved with their heart, every word of [q] in the name; names
     * that start with it first.
     */
    private fun searchLists(q: String): List<LibraryList> {
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(name: String) = name.lowercase().let { n -> words.all { it in n } }
        val store = com.opentune.data.library.LibraryStore
        val own = store.playlists.value.filter { matches(it.name) }.map { p ->
            LibraryList(
                "pl:${p.id}", p.name,
                listOfNotNull(if (p.spotifyId != null) "Spotify" else null, "${p.songs.size} songs").joinToString(" · "),
                p.coverUrl, p.songs.map { it.thumbnailUrl }, playlistId = p.id,
            )
        }
        val saved = store.savedLists.value.filter { matches(it.title) }.map { l ->
            LibraryList("saved:${l.browseId}", l.title, l.subtitle.ifBlank { "Saved" }, l.thumbnailUrl, emptyList(), browseId = l.browseId)
        }
        val start = q.lowercase().trim()
        return (own + saved).sortedBy { if (it.title.lowercase().startsWith(start)) 0 else 1 }.take(MAX_LIBRARY_LISTS)
    }

    /** Liked songs, your playlists, downloads and the Local Music folder, every word of [q] in the title, artist or album. */
    private suspend fun searchLibrary(q: String): List<com.opentune.data.model.Song> = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val local = localSongs ?: runCatching {
            com.opentune.data.local.LocalMusic.tracks(getApplication(), AppSettings.library.value)
        }.getOrDefault(emptyList()).also { localSongs = it }
        val store = com.opentune.data.library.LibraryStore
        val all = store.liked.value.map { it.toSong() } +
            store.playlists.value.flatMap { p -> p.songs.map { it.toSong() } } +
            com.opentune.data.download.Downloads.doneNow().map { it.song.toSong() } + local
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val title = q.lowercase()
        all.distinctBy { it.videoId }
            .filter { s -> "${s.title} ${s.artist} ${s.albumName.orEmpty()}".lowercase().let { text -> words.all { it in text } } }
            .sortedBy { s ->
                val t = s.title.lowercase()
                when {
                    t == title -> 0
                    t.startsWith(title) -> 1
                    title in t -> 2
                    else -> 3
                }
            }
            .take(MAX_LIBRARY_ROWS)
    }

    /** Every usable add-on at once; one that fails or is slow is left out rather than holding the rest. */
    private suspend fun searchAddons(q: String): UiState<List<AddonHit>> {
        val store = com.opentune.data.addons.AddonStore
        val usable = store.addons.value.filter(store::usable)
        if (usable.isEmpty()) return UiState.Error("Add an audio add-on in Settings › Add-ons to search it here.")
        val found = kotlinx.coroutines.coroutineScope {
            usable.map { addon ->
                async {
                    kotlinx.coroutines.withTimeoutOrNull(ADDON_SEARCH_MS) {
                        runCatching {
                            val client = com.opentune.data.addons.AddonClient.forBase(addon.base)
                            val manifest = client.manifest().let { if (addon.admin) it.copy(name = addon.manifest.name) else it }
                            client.search(q, manifest, "LOSSLESS").map { AddonHit(it, addon, manifest) }
                        }.getOrNull()
                    }
                }
            }.map { it.await() }
        }
        if (found.all { it == null }) return UiState.Error("Your add-ons didn't answer. Try again in a moment.")
        // Each add-on's best first, taken in turn, so one add-on doesn't fill the list;
        // a recording several add-ons have shows once.
        val lists = found.map { it.orEmpty() }
        val woven = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        val merged = AddonHit.merged(woven)
        prefetchMatches(merged.take(PREFETCH_MATCHES))
        return UiState.Success(merged)
    }

    /** The same song on YouTube Music for each hit, looked up ahead so a tap plays at once. */
    private val matches = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<com.opentune.data.model.Song?>>()

    private fun matchFor(hit: AddonHit): kotlinx.coroutines.Deferred<com.opentune.data.model.Song?> {
        if (matches.size > 200) matches.clear()
        return matches.getOrPut(hit.key) {
            viewModelScope.async(Dispatchers.IO) {
                val query = "${hit.track.title} ${hit.track.artist}".trim()
                val songs = runCatching { MusicRepository.search(query, SearchFilter.SONGS) }.getOrDefault(emptyList())
                    .mapNotNull { (it as? SearchResult.TopTrack)?.song ?: (it as? SearchResult.Track)?.song }
                hit.bestMatch(songs)
            }
        }
    }

    private fun prefetchMatches(hits: List<AddonHit>) = hits.forEach { matchFor(it) }

    /**
     * Plays [hit]: the same song is found on YouTube Music, for its lyrics,
     * radio and everything else, and the add-on's own stream for the track
     * picked, at the best quality it has, is handed to the player to start
     * on, whatever the quality setting. Null when no match was found.
     */
    suspend fun songFor(hit: AddonHit): com.opentune.data.model.Song? {
        // Every add-on that has the recording is asked for its stream at once.
        val streams = (listOf(hit) + hit.alternates).map { h ->
            viewModelScope.async(Dispatchers.IO) {
                runCatching {
                    // The add-on's best: hi-res where it has it, else lossless, else what it serves.
                    com.opentune.data.addons.AddonClient.forBase(h.addon.base).stream(h.track.id, h.manifest, BEST_TIER)
                        .copy(addonId = h.addon.key, provider = h.manifest.name)
                }.getOrNull()
            }
        }
        val match = matchFor(hit).await() ?: run { streams.forEach { it.cancel() }; return null }
        suspend fun best() = streams.filter { it.isCompleted }.mapNotNull { it.await() }
            .maxWithOrNull(compareBy<com.opentune.data.addons.AddonStream> { it.lossless }
                .thenBy { com.opentune.data.addons.AddonResolver.qualityScore(it.bits, it.sampleRate) })
        // A moment for the streams, so the song can start on one; then it starts anyway.
        kotlinx.coroutines.withTimeoutOrNull(STREAM_HEAD_START_MS) { streams.forEach { it.await() } }
        val first = best()
        if (first != null) com.opentune.data.lossless.ExternalStreams.offer(match.videoId, first)
        if (streams.any { !it.isCompleted }) viewModelScope.launch {
            // A better stream that answers after the song has started takes over.
            streams.forEach { runCatching { it.await() } }
            val later = best() ?: return@launch
            if (later.url != first?.url && (first == null || later.lossless && !first.lossless ||
                    com.opentune.data.addons.AddonResolver.qualityScore(later.bits, later.sampleRate) > com.opentune.data.addons.AddonResolver.qualityScore(first.bits, first.sampleRate))) {
                com.opentune.data.lossless.ExternalStreams.offer(match.videoId, later)
                com.opentune.playback.PlaybackRequests.switchToPicked(match.videoId)
            }
        }
        return match
    }

    /** Remembers [q] as a search in the tab it was made in. */
    fun remember(q: String) {
        val text = q.trim().takeIf { it.isNotEmpty() } ?: return
        when (_source.value) {
            SearchSource.ADDONS -> AppSettings.addRecentAddonSearch(text)
            SearchSource.LIBRARY -> Unit
            SearchSource.YOUTUBE -> AppSettings.addRecentSearch(text)
        }
    }

    /**
     * Searches as the words come, a moment after typing stops: the songs,
     * artists and lists found go under the suggestions, so what's wanted can
     * often be tapped without pressing search at all. The search is kept,
     * so pressing search then shows the full results at once.
     */
    @OptIn(FlowPreview::class)
    private suspend fun watchLive() {
        _query.map { it.trim() }.distinctUntilChanged().debounce(LIVE_DEBOUNCE_MS).collectLatest { q ->
            if (q.length < 2 || q == _submitted.value) {
                if (q.isEmpty()) _live.value = emptyList()
                return@collectLatest
            }
            val key = "${SearchFilter.ALL}:${q.lowercase()}"
            val rows = recentSearches[key] ?: runCatching { MusicRepository.search(q, SearchFilter.ALL) }.getOrNull()?.also { recentSearches[key] = it }
            if (rows != null) _live.value = rows.take(LIVE_ROWS)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchSuggestions() {
        _query.map { it.trim() }.distinctUntilChanged().debounce(220).collectLatest { q ->
            if (q.isEmpty() || q == _submitted.value) {
                _suggestions.value = emptyList()
                return@collectLatest
            }
            _suggestions.value = runCatching { MusicRepository.suggestions(q) }.getOrDefault(_suggestions.value)
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun submit(value: String) {
        val q = value.trim()
        if (q.isEmpty()) return
        _query.value = q
        _submitted.value = q
        AppSettings.addRecentSearch(q)
        run()
    }

    fun setFilter(filter: SearchFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        run()
    }

    fun retry() = run()

    private fun run() {
        val q = _submitted.value ?: return
        val f = _filter.value
        searchJob?.cancel()
        val key = "$f:${q.trim().lowercase()}"
        // A search made a minute ago comes back at once; it's refreshed anyway.
        _results.value = recentSearches[key]?.let { UiState.Success(it) } ?: UiState.Loading
        searchJob = viewModelScope.launch {
            val fresh = try {
                UiState.Success(MusicRepository.search(q, f))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_results.value is UiState.Success) null else UiState.Error(friendlyError(e))
            }
            fresh?.let { _results.value = it }
            (fresh as? UiState.Success)?.data?.let { rows ->
                recentSearches[key] = rows
                warmTopResult(rows)
            }
        }
    }

    /**
     * Finds the stream for the first song in the results before it's tapped,
     * since it's the one most likely to be. The stream URL is cached for 20
     * minutes, so the tap only has to fetch audio.
     */
    private fun warmTopResult(rows: List<SearchResult>) {
        val top = rows.firstNotNullOfOrNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                else -> null
            }
        } ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { StreamResolver.resolve(top.videoId) } }
    }

    private companion object {
        const val LIVE_DEBOUNCE_MS = 380L
        const val LIVE_ROWS = 12
        const val OTHER_DEBOUNCE_MS = 300L
        const val MAX_LIBRARY_ROWS = 100
        const val ADDON_SEARCH_MS = 10_000L
        const val BEST_TIER = "HI_RES_LOSSLESS"
        const val PREFETCH_MATCHES = 8
        const val STREAM_HEAD_START_MS = 1_200L
    }

    private val recentSearches = object : LinkedHashMap<String, List<SearchResult>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchResult>>?) = size > 60
    }
}

/** A playlist or saved album in the library that a search found: one of yours ([playlistId]) or one saved from YouTube Music ([browseId]). */
data class LibraryList(
    val key: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
    /** The songs' covers, for a playlist with no cover of its own. */
    val songCovers: List<String?>,
    val playlistId: String? = null,
    val browseId: String? = null,
)

private const val MAX_LIBRARY_LISTS = 8
