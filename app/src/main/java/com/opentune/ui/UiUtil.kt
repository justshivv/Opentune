package com.opentune.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import io.ktor.client.plugins.ResponseException
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Something a person can read, rather than a stack of HTML from a refused request. */
fun friendlyError(e: Throwable): String = when (e) {
    is UnknownHostException -> "You're offline. Check your connection and try again."
    is ResponseException -> when (e.response.status.value) {
        403, 429 -> "YouTube Music turned the request away. Wait a moment and try again."
        in 500..599 -> "YouTube Music is having trouble right now."
        else -> "YouTube Music answered with an error (${e.response.status.value})."
    }
    is IOException -> "Network error. Check your connection and try again."
    else -> e.message?.take(160) ?: "Something went wrong."
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun ShelfItem.toSong(): Song? = videoId?.let {
    Song(videoId = it, title = title, artist = subtitle.substringBefore(" • "), thumbnailUrl = thumbnailUrl)
}

fun ShelfItem.type(): BrowseType? = browseId?.let(MusicRepository::typeOf)

/**
 * Loads one thing for a screen, survives rotation, and can be retried or
 * refreshed. [refreshing] is a reload that keeps the old content on screen.
 */
class LoaderViewModel<T>(private val load: suspend () -> T) : ViewModel() {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        reload()
    }

    fun reload(keepContent: Boolean = false) {
        viewModelScope.launch {
            if (keepContent && _state.value is UiState.Success) _refreshing.value = true else _state.value = UiState.Loading
            _state.value = try {
                UiState.Success(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_refreshing.value) _state.value else UiState.Error(friendlyError(e))
            }
            _refreshing.value = false
        }
    }
}

@Composable
fun <T> rememberLoader(key: String, load: suspend () -> T): LoaderViewModel<T> =
    viewModel(key = key) { LoaderViewModel(load) }
