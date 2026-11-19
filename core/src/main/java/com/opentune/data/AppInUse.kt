package com.opentune.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether anyone is using the app in this process: an activity has been on screen or the
 * player has started. A process Android starts only to run a background job (the add-on
 * check, say) leaves the syncs and the player warm-up alone; they run once it's in use.
 */
object AppInUse {
    val started = MutableStateFlow(false)
}
