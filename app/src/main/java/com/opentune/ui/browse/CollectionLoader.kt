package com.opentune.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.Collection
import com.opentune.data.ContentFilter
import com.opentune.data.MusicRepository
import com.opentune.data.library.LibraryCache
import com.opentune.data.model.UiState
import com.opentune.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Partial pages belong to the ViewModel, so rotating never loses an in-flight page. */
internal class CollectionLoader(
    private val browseId: String,
    private val owner: String,
    private val fetch: suspend ((Collection) -> Unit) -> Collection = { progress -> MusicRepository.collection(browseId, progress) },
    private val cached: suspend () -> Collection? = {
        withContext(Dispatchers.IO) { LibraryCache.collection(browseId, owner)?.let { it.copy(songs = ContentFilter.songs(it.songs)) } }
    },
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Collection>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _preview = MutableStateFlow<Collection?>(null)
    val preview = _preview.asStateFlow()
    private var job: Job? = null

    init { reload() }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                if (_preview.value == null) {
                    _preview.value = cached()
                }
                val complete = fetch { _preview.value = it }
                _preview.value = complete
                _state.value = UiState.Success(complete)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(friendlyError(e))
            }
        }
    }
}
