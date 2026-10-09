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
        data class At(val endsAtMs: Long, val startedAtMs: Long = System.currentTimeMillis()) : State
        data object EndOfTrack : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _windDown = MutableStateFlow(0f)
    /** How far the wind-down at the end has gone, 0 (not started) to 1 (silent); the player dims with it. */
    val windDown: StateFlow<Float> = _windDown.asStateFlow()

    internal fun setWindDown(v: Float) {
        _windDown.value = v
    }

    /** How long the fade before the end lasts for a timer of [totalMs]: three minutes, or a third of a short one. */
    fun fadeMsFor(totalMs: Long): Long = minOf(3 * 60_000L, totalMs / 3).coerceAtLeast(5_000L)

    /** The volume at a point of the fade, 1 down to 0: barely lower at first, halfway at the middle, gone at the end. */
    fun gainAt(fraction: Float): Float {
        val c = kotlin.math.cos(fraction.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
        return c * c
    }

    fun startMinutes(minutes: Int) {
        _state.value = State.At(System.currentTimeMillis() + minutes * 60_000L)
    }

    /** Pushes a running timer back by [minutes]. */
    fun addMinutes(minutes: Int) {
        val s = _state.value as? State.At ?: return
        _state.value = s.copy(endsAtMs = s.endsAtMs + minutes * 60_000L)
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
