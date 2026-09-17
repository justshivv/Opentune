package com.opentune.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bitrate [StreamResolver][com.opentune.data.innertube.StreamResolver]
 * actually picked for the track currently playing, for a future debug
 * overlay. Nothing reads this yet in the MVP UI.
 */
object NerdStats {
    private val _lastPicked = MutableStateFlow<Pair<String, Int>?>(null)
    val lastPicked = _lastPicked.asStateFlow()

    fun onStreamPicked(videoId: String, kbps: Int) {
        _lastPicked.value = videoId to kbps
    }
}
