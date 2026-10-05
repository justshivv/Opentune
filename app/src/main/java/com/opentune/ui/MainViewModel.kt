package com.opentune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Success(val songs: List<Song>) : SearchState
    data class Error(val message: String) : SearchState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val player = PlayerConnection(application)

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState = _searchState.asStateFlow()

    val currentSong = player.currentSong
    val queue = player.queue
    val currentIndex = player.currentIndex
    val hasNext = player.hasNext
    val isPlaying = player.isPlaying
    val isBuffering = player.isBuffering
    val playbackError = player.error

    init {
        viewModelScope.launch { player.connect() }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        _searchState.value = SearchState.Loading
        viewModelScope.launch {
            try {
                // The "All" tab (no params) is what parseSearchSongs is built to
                // walk. The SONGS filter's own params blob currently comes back
                // "No results" for plenty of real tracks — verified live against
                // music.youtube.com's search endpoint, unfiltered doesn't.
                val response = Innertube.search(q)
                val songs = InnertubeParser.parseSearchSongs(response)
                _searchState.value = SearchState.Success(songs)
            } catch (e: Exception) {
                _searchState.value = SearchState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun play(song: Song) = player.play(song)

    fun playNext(song: Song) = player.playNext(song)

    fun addToQueue(song: Song) = player.addToQueue(song)

    fun togglePlayPause() = player.togglePlayPause()

    fun skipNext() = player.skipNext()

    fun skipPrevious() = player.skipPrevious()

    fun playQueueItem(index: Int) = player.playQueueItem(index)

    fun removeQueueItem(index: Int) = player.removeQueueItem(index)

    override fun onCleared() {
        player.disconnect()
        super.onCleared()
    }
}
