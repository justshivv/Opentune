package com.opentune.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long after coming back the pages still refuse focus they didn't ask for. */
private const val RETURN_MS = 800L

/**
 * For the box that holds the pages: leaving the app with a text field focused (Search, a
 * playlist's name), the field lets go and the keyboard closes. Android hands focus back to the
 * app's view straight away, and Compose would pass it on to the first field it finds, bringing
 * the keyboard up over the page when the app returns; while the app is away, and for a moment
 * after it comes back, focus that wasn't asked for doesn't go into the pages. Coming back with
 * another app's keyboard still up (typing in a chat, then switching here), it closes too. A tap
 * on a field, or a page asking for it (the Search button), focuses it and opens the keyboard as before.
 */
@Composable
fun rememberLeaveFocusGuard(): Modifier {
    val owner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var away by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        var back: Job? = null
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    back?.cancel()
                    away = true
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Another app's keyboard, open as the switch came, doesn't carry over (the
                    // window's stateAlwaysHidden does the same as it takes input focus).
                    keyboard?.hide()
                    if (away) {
                        back?.cancel()
                        back = scope.launch { delay(RETURN_MS); away = false }
                    }
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { back?.cancel(); owner.lifecycle.removeObserver(watcher) }
    }
    return Modifier.focusProperties { if (away) onEnter = { cancelFocusChange() } }.focusGroup()
}
