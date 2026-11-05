package com.opentune.data.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Banners and featured playlists from the control dashboard that the
 * listener has closed. A closed banner stays closed; a new one (a new id)
 * shows. The featured playlists come back as soon as one is added that
 * wasn't there when they were closed.
 */
object HomeDismissals {
    private var prefs: SharedPreferences? = null
    private val _banners = MutableStateFlow<Set<String>>(emptySet())
    val banners: StateFlow<Set<String>> = _banners.asStateFlow()
    private val _playlists = MutableStateFlow<Set<String>>(emptySet())
    /** The featured playlists there were when the shelf was closed. */
    val playlists: StateFlow<Set<String>> = _playlists.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("home_dismissals", Context.MODE_PRIVATE)
        prefs = p
        _banners.value = p.getStringSet("banners", emptySet())!!.toSet()
        _playlists.value = p.getStringSet("playlists", emptySet())!!.toSet()
    }

    fun dismissBanner(id: String) {
        _banners.value = _banners.value + id
        prefs?.edit()?.putStringSet("banners", _banners.value)?.apply()
    }

    fun dismissPlaylists(ids: Collection<String>) {
        _playlists.value = ids.toSet()
        prefs?.edit()?.putStringSet("playlists", _playlists.value)?.apply()
    }

    /** Whether the featured shelf shows: something in it the listener hasn't closed. */
    fun showsPlaylists(ids: Collection<String>, dismissed: Set<String>): Boolean = ids.any { it !in dismissed }
}
