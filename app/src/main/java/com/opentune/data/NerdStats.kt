package com.opentune.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Figures for "Stats for nerds": the bitrate [StreamResolver][com.opentune.data.innertube.StreamResolver]
 * picked for the track playing, and how long the last track took from being
 * asked for to making sound.
 */
object NerdStats {
    private val _lastPicked = MutableStateFlow<Pair<String, Int>?>(null)
    val lastPicked = _lastPicked.asStateFlow()

    fun onStreamPicked(videoId: String, kbps: Int) {
        _lastPicked.value = videoId to kbps
    }

    /** The loudness-normalization gain on the current track, in dB, or null when none. */
    private val _loudnessGainDb = MutableStateFlow<Float?>(null)
    val loudnessGainDb = _loudnessGainDb.asStateFlow()

    fun onLoudnessGain(db: Float?) {
        _loudnessGainDb.value = db
    }

    private val _startupMs = MutableStateFlow<Long?>(null)
    val startupMs = _startupMs.asStateFlow()

    fun onStartup(ms: Long) {
        _startupMs.value = ms
    }
}
