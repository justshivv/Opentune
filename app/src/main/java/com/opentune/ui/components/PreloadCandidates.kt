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
 */
@Composable
fun PreloadCandidates(key: String, songs: List<Song>) {
    val ids = songs.map { it.videoId }
    LaunchedEffect(key, ids) {
        delay(SETTLE_MS)
        PreloadPlanner.onScreen(key, songs)
    }
    DisposableEffect(key) { onDispose { PreloadPlanner.offScreen(key) } }
}

private const val SETTLE_MS = 600L
