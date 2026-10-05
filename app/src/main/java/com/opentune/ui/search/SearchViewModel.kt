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
import kotlinx.coroutines.launch

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
        _results.value = UiState.Loading
        searchJob = viewModelScope.launch {
            _results.value = try {
                UiState.Success(MusicRepository.search(q, f))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(friendlyError(e))
            }
        }
    }
}
