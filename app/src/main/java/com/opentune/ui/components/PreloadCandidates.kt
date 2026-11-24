package com.opentune.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * Readies [song] the moment a finger rests on it, before the tap lands (see
 * [PreloadPlanner.pressed]). A touch that starts a scroll doesn't count: the finger has to stay
 * put for [PRESS_MS], or lift before then. It only watches, so the row's own click and the list's
 * scrolling work as before. Nothing when [song] is null.
 */
fun Modifier.readyOnPress(song: Song?): Modifier =
    if (song == null) this else this.pointerInput(song.videoId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // Null: still held, unmoved, when the wait ran out: a press.
            val tap = withTimeoutOrNull(PRESS_MS) { stillTapping(down.id, down.position, slop) } ?: true
            if (tap) PreloadPlanner.pressed(song)
        }
    }

/** Until the finger lifts (true) or moves off as a scroll does (false). */
private suspend fun AwaitPointerEventScope.stillTapping(id: PointerId, from: Offset, slop: Float): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) return true
        if ((change.position - from).getDistance() > slop) return false
    }
}

/** Long enough that a scroll has moved off by then, short against a tap's ~100 ms. */
private const val PRESS_MS = 40L
