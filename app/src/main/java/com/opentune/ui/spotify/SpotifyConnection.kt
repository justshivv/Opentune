package com.opentune.ui.spotify

import android.webkit.CookieManager
import com.opentune.data.spotify.SpotifyWebPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Spotify is connected, for Canvas and the library's playlists
 * (see [com.opentune.data.spotify.SpotifySync]): signed in on Spotify's own page
 * in [SpotifyConnectActivity], which leaves its `sp_dc` cookie in the app's
 * WebView cookies. Nothing else is kept; disconnecting clears the cookies.
 */
object SpotifyConnection {
    private const val SITE = "https://open.spotify.com"
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Why the last connection stopped working, until the next sign-in. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** The sign-in cookie, or null when signed out (or the phone has no WebView). */
    fun cookie(): String? = runCatching { CookieManager.getInstance().getCookie(SITE) }.getOrNull()
        ?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("sp_dc=") }
        ?.substringAfter('=')?.takeIf { it.isNotBlank() }

    fun refresh() {
        _connected.value = cookie() != null
        if (_connected.value) _problem.value = null
    }

    /** Brings the Spotify library's playlists into Library; at most every few hours unless [force]. */
    fun syncPlaylists(force: Boolean = false) {
        val cookie = cookie() ?: return
        com.opentune.data.spotify.SpotifySync.sync(cookie, force, onSignedOut = ::lost)
    }

    /** A validated connection returned, or the app resumed: retry a failed read without signing out. */
    fun retryFailedSync() {
        val status = com.opentune.data.spotify.SpotifySync.status.value
        if (status.retryable && !status.running && connected.value) syncPlaylists()
    }

    /** Spotify no longer accepts the sign-in: show it as disconnected and say why. */
    fun lost(reason: String?) {
        com.opentune.data.spotify.SpotifySync.cancel()
        clearCookies()
        SpotifyWebPlayer.signedOut()
        _problem.value = reason ?: "Spotify signed you out"
        _connected.value = false
    }

    /** Signs out; the playlists already brought over stay in Library. */
    fun disconnect() {
        com.opentune.data.spotify.SpotifySync.cancel()
        clearCookies()
        SpotifyWebPlayer.signedOut()
        _problem.value = null
        _connected.value = false
    }

    /**
     * Runs [clear], which clears every cookie and calls back when done, then
     * puts Spotify's sign-in back, so signing out of YouTube Music doesn't
     * also disconnect Spotify.
     */
    fun keepingSpotify(clear: (android.webkit.ValueCallback<Boolean>) -> Unit) {
        val kept = cookie()
        clear { _ ->
            if (kept != null) runCatching {
                val cookies = CookieManager.getInstance()
                cookies.setCookie(SITE, "sp_dc=$kept; Domain=.spotify.com; Path=/; Max-Age=31536000; Secure; HttpOnly")
                cookies.flush()
            }
            refresh()
        }
    }

    private fun clearCookies() = runCatching {
        val cookies = CookieManager.getInstance()
        for (name in listOf("sp_dc", "sp_key", "sp_t", "sp_landing")) {
            for (host in listOf(SITE, "https://accounts.spotify.com")) {
                cookies.setCookie(host, "$name=; Domain=.spotify.com; Path=/; Max-Age=0; Secure")
                cookies.setCookie(host, "$name=; Path=/; Max-Age=0; Secure")
            }
        }
        cookies.flush()
    }
}
