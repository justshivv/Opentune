package com.opentune.data.innertube

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks that were swapped to a better stream mid-play. They're queued on
 * their upgraded URI from then on, which keeps their better copy under its
 * own cache key, apart from the copy they started on.
 */
object UpgradedTracks {
    private const val KEY = "ids"
    private const val MAX = 2_000
    private var prefs: SharedPreferences? = null
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("upgraded_tracks", Context.MODE_PRIVATE)
        prefs = p
        p.getStringSet(KEY, null)?.let(ids::addAll)
    }

    fun contains(videoId: String): Boolean = videoId in ids

    fun add(videoId: String) {
        if (!ids.add(videoId)) return
        if (ids.size > MAX) ids.take(ids.size - MAX).forEach(ids::remove)
        prefs?.edit { putStringSet(KEY, HashSet(ids)) }
    }

    fun remove(videoId: String) {
        if (!ids.remove(videoId)) return
        prefs?.edit { putStringSet(KEY, HashSet(ids)) }
    }
}
