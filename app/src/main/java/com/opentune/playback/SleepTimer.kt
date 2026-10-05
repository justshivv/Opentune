package com.opentune.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When playback should stop: at a clock time, or when the current track
 * ends. Set from the player's menu; [PlaybackService] carries it out.
 */
object SleepTimer {
    sealed interface State {
        data object Off : State
        data class At(val endsAtMs: Long) : State
        data object EndOfTrack : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    fun startMinutes(minutes: Int) {
        _state.value = State.At(System.currentTimeMillis() + minutes * 60_000L)
    }

    fun endOfTrack() {
        _state.value = State.EndOfTrack
    }

    fun cancel() {
        _state.value = State.Off
    }
}
