package com.opentune.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Home works out from the play history (recents, Speed dial, the mixes, the daylist and
 * the rest), kept here rather than in the screen. Going to another tab and back throws Home's
 * screen away, and with it whatever it held in `remember`; working all of that out again from a
 * long history, on the main thread before the first frame, held up both the page and the dock
 * for seconds. Kept here, each value is shown at once, and worked out again only when what it's
 * made from has changed, off the main thread.
 */
internal object HomeDerived {
    private class Entry(val keys: List<Any?>, val value: Any?)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun entry(name: String): Pair<List<Any?>, Any?>? = entries[name]?.let { it.keys to it.value }

    fun put(name: String, keys: List<Any?>, value: Any?) {
        entries[name] = Entry(keys, value)
    }
}

/**
 * [compute] for [keys], from [HomeDerived]: at once when it's been worked out for these keys
 * before, else the last value while it's worked out again in the background, else [initial].
 * [compute] runs off the main thread, so it must only read what it's given.
 */
@Composable
@Suppress("UNCHECKED_CAST")
internal fun <T> rememberHomeDerived(name: String, vararg keys: Any?, initial: T, compute: () -> T): T {
    val keyList = keys.toList()
    val saved = HomeDerived.entry(name)
    val current = saved?.takeIf { it.first == keyList }
    val state = produceState(initialValue = (saved?.second ?: initial) as T, *keys) {
        if (current != null) {
            value = current.second as T
            return@produceState
        }
        val made = withContext(Dispatchers.Default) { compute() }
        HomeDerived.put(name, keyList, made)
        value = made
    }
    return state.value
}
