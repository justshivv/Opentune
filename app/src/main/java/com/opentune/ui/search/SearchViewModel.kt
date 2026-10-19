package com.opentune.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.MusicRepository
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.opentune.data.addons.Addons
import com.opentune.data.innertube.StreamResolver
import kotlinx.coroutines.Dispatchers

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    /** The query the results belong to; null before the first search. */
    private val _submitted = MutableStateFlow<String?>(null)
    val submitted = _submitted.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter = _filter.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>>(UiState.Loading)
    val results = _results.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch { watchSuggestions() }
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
            // Addons answer alongside YouTube, and their songs follow its results.
            val fromAddons = if (f == SearchFilter.ALL || f == SearchFilter.SONGS) async { Addons.search(q) } else null
            val fresh = try {
                UiState.Success(MusicRepository.search(q, f))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_results.value is UiState.Success) null else UiState.Error(friendlyError(e))
            }
            // Addon songs from the last time stay in place until their new answers come.
            val earlier = recentSearches[key].orEmpty().filter(::fromAddon)
            fresh?.let { _results.value = if (it is UiState.Success) UiState.Success(it.data + earlier) else it }
            (fresh as? UiState.Success)?.data?.let { rows ->
                recentSearches[key] = rows + earlier
                warmTopResult(rows)
            }
            if (fromAddons == null) return@launch
            val extra = fromAddons.await().flatMap { (_, songs) -> songs.map { SearchResult.Track(it) } }
            val shown = (_results.value as? UiState.Success)?.data
            if (shown == null && extra.isEmpty()) return@launch
            val rows = shown.orEmpty().filterNot(::fromAddon) + extra
            _results.value = UiState.Success(rows)
            recentSearches[key] = rows
        }
    }

    private fun fromAddon(r: SearchResult) = r is SearchResult.Track && Addons.isAddon(r.song.videoId)

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

    private val recentSearches = object : LinkedHashMap<String, List<SearchResult>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SearchResult>>?) = size > 60
    }
}
