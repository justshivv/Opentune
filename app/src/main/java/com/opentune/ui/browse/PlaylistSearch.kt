package com.opentune.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
    var headerHeight by mutableIntStateOf(0)
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
                val outside = !search.bounds.contains(down.position + origin)
                var dragged = false
                var released = false
                var longPress = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.size > 1 || pointer == null ||
                        (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                    released = pointer != null && !pointer.pressed
                    if (pointer != null && pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) longPress = true
                } while (event.changes.any { it.pressed })
                // Observe the whole gesture without consuming it. A scroll must not close search,
                // and a real outside tap must still reach the row/button underneath it.
                if (outside && released && !dragged && !longPress) {
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
    androidx.activity.compose.BackHandler { search.close(); focus.clearFocus(); keyboard?.hide() }
    ListSearchField(search.query, { search.query = it }, {
        search.close(); focus.clearFocus(); keyboard?.hide()
    }, modifier.onGloballyPositioned { search.bounds = it.boundsInRoot() }, autoFocus = true)
}

/** The search field belongs to the fixed toolbar, never to the scrolling cover header. */
@Composable
internal fun PlaylistSearchHeader(search: PlaylistSearch, toolbar: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()
        .then(if (search.open) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
        .onSizeChanged { search.headerHeight = it.height }) {
        toolbar()
        PlaylistSearchField(search, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/** Constrain the results viewport above the IME, leaving the fixed search header visible. */
internal fun Modifier.playlistSearchResults(search: PlaylistSearch): Modifier = composed {
    if (search.open) padding(top = with(LocalDensity.current) { search.headerHeight.toDp() }).imePadding()
    else this
}

/**
 * Puts the list back where it was before a search, the moment the search closes. Searching
 * jumps the list to the top of the results, so on closing, for one frame, the list still read
 * as being at its top: the bar drew its see-through glass pill (as it does over the cover),
 * then the songs came back past the cover and the bar went solid again, a flash. The place is
 * put back in that same frame, and the result is true (keep the bar solid) for the few frames
 * the list takes to report it, when that place is past the cover.
 */
@Composable
internal fun rememberSearchReturn(
    search: PlaylistSearch,
    listState: androidx.compose.foundation.lazy.LazyListState,
    pastHero: (index: Int, offset: Int) -> Boolean,
): Boolean {
    val memo = remember { SearchReturn(search.open) }
    var hold by remember { mutableStateOf(false) }
    val open = search.open
    if (open != memo.wasOpen) {
        if (open) {
            memo.index = listState.firstVisibleItemIndex
            memo.offset = listState.firstVisibleItemScrollOffset
        } else {
            listState.requestScrollToItem(memo.index, memo.offset)
            hold = pastHero(memo.index, memo.offset)
        }
        memo.wasOpen = open
    }
    androidx.compose.runtime.LaunchedEffect(hold) {
        if (!hold) return@LaunchedEffect
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        hold = false
    }
    return hold
}

private class SearchReturn(var wasOpen: Boolean) {
    var index = 0
    var offset = 0
}
