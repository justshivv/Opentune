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

/** Requests from the UI that the service carries out directly (same process). */
object PlaybackRequests {
    private val _upgrade = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val upgrade: kotlinx.coroutines.flow.SharedFlow<Unit> = _upgrade

    /** Look for a better stream for the current song now, and swap if one exists. */
    fun upgradeQuality() {
        _upgrade.tryEmit(Unit)
    }
}
