package com.opentune.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.opentune.data.model.Song
import com.opentune.playback.PreloadPlanner
import kotlinx.coroutines.delay

/**
 * Tells [PreloadPlanner] which songs are on the screen under [key], likeliest first, once they've
 * stayed there a moment (scrolling or paging past doesn't count), and that they're gone when this
 * leaves the screen. The planner decides which of them to ready, and how much.
 * [immediate]: at once, for results the listener taps as soon as they show (search), where a
 * moment's wait would ready nothing before the tap.
 */
@Composable
fun PreloadCandidates(key: String, songs: List<Song>, immediate: Boolean = false) {
    val ids = songs.map { it.videoId }
    LaunchedEffect(key, ids) {
        if (!immediate) delay(SETTLE_MS)
        PreloadPlanner.onScreen(key, songs, now = immediate)
    }
    DisposableEffect(key) { onDispose { PreloadPlanner.offScreen(key) } }
}

private const val SETTLE_MS = 600L
