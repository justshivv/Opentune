package com.opentune.ui.browse

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Shared by account, imported and local playlists. Closing also clears filtering. */
@Stable
internal class PlaylistSearch {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var bounds by mutableStateOf(Rect.Zero)
    fun close() { open = false; query = ""; bounds = Rect.Zero }
}

internal fun Modifier.dismissPlaylistSearch(search: PlaylistSearch): Modifier = composed {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(search, search.open) {
            if (search.open) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!search.bounds.contains(down.position + origin)) {
                    search.close()
                    focus.clearFocus()
                    keyboard?.hide()
                }
            }
        }
}

@Composable
internal fun PlaylistSearchField(search: PlaylistSearch, modifier: Modifier = Modifier) {
    if (!search.open) return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val intoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(search) {
        withFrameNanos { }
        intoView.bringIntoView()
    }
    ListSearchField(search.query, { search.query = it }, {
        search.close(); focus.clearFocus(); keyboard?.hide()
    }, modifier.bringIntoViewRequester(intoView).onGloballyPositioned { search.bounds = it.boundsInRoot() }, autoFocus = true)
}
