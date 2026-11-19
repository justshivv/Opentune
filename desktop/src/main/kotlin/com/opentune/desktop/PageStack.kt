package com.opentune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue

/**
 * Where the window has been, for Back: each page opened is an entry with a key of its own, and
 * what a page remembers with `rememberSaveable` (a tab, what's typed in a filter, grid or list,
 * how far it's scrolled) is kept under that key while the entry is on the stack, so going back
 * shows the page as it was left. An entry that leaves the stack for good (gone back from, or
 * the oldest past [limit]) has what it kept dropped.
 */
internal class PageStack<P>(start: P, private val limit: Int = 30) {
    data class Entry<P>(val page: P, val key: Int)

    private var nextKey = 1
    private val behind = mutableStateListOf<Entry<P>>()
    var current by mutableStateOf(Entry(start, 0))
        private set

    /** Told the key of each entry gone for good, so what it kept can go too. */
    var onDrop: (Int) -> Unit = {}

    val canGoBack: Boolean get() = behind.isNotEmpty()
    val page: P get() = current.page
    /** The pages behind this one, oldest first. */
    val history: List<P> get() = behind.map { it.page }

    /** Opens [to] over what's showing; nothing happens when it's showing already. */
    fun go(to: P): Boolean {
        if (to == current.page) return false
        behind.add(current)
        if (behind.size > limit) onDrop(behind.removeAt(0).key)
        current = Entry(to, nextKey++)
        return true
    }

    /** Back to the page before, as it was left. */
    fun back(): Boolean {
        val previous = behind.removeLastOrNull() ?: return false
        onDrop(current.key)
        current = previous
        return true
    }

    /** Whether showing [to] after [from] is going back. */
    fun isBack(from: Entry<P>, to: Entry<P>) = to.key < from.key
}

/**
 * What [load] gives, read once and kept with the page while it's on the stack: going back to it
 * shows it at once, so its list is there to scroll back to where it was. Null until it's read.
 */
@Composable
internal fun <T : Any> rememberKept(vararg keys: Any?, load: suspend () -> T): T? {
    val kept = rememberSaveable(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) { if (kept.value == null) kept.value = load() }
    return kept.value
}

/** A [PageStack] for the window, whose entries keep what their pages remember. */
@Composable
internal fun <P> rememberPageStack(start: P): Pair<PageStack<P>, SaveableStateHolder> {
    val holder = rememberSaveableStateHolder()
    val stack = remember(holder) { PageStack(start).also { s -> s.onDrop = { holder.removeState(it) } } }
    return stack to holder
}
