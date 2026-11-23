package com.opentune.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.opentune.data.model.HomeShelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Quick picks made on a refresh, shown as soon as they're ready. Only the
 * latest refresh counts: pulled twice in a row, the first one's picks don't
 * land, and finishing first doesn't take the placeholder away while the
 * second is still being made.
 */
internal class FreshPicks(private val scope: CoroutineScope) {
    /** The newest refresh's picks, once made; null until then. */
    var shelf by mutableStateOf<HomeShelf?>(null)
        private set

    /** A refresh's picks are still being made. */
    var pending by mutableStateOf(false)
        private set

    private var turns = 0
    private var job: Job? = null

    fun refresh(picks: Deferred<List<HomeShelf>>) {
        val turn = ++turns
        job?.cancel()
        shelf = null
        pending = true
        job = scope.launch {
            try {
                picks.await().firstOrNull()?.let { if (turn == turns) shelf = it }
            } finally {
                if (turn == turns) pending = false
            }
        }
    }
}
