package com.opentune.ui

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something a YouTube or YouTube Music link points at. */
sealed interface LinkTarget {
    data class Song(val videoId: String) : LinkTarget
    data class Browse(val browseId: String) : LinkTarget
    /** "Play … on OpenTune" from the Assistant; blank means "play some music". */
    data class PlaySearch(val query: String) : LinkTarget
    /** A Spotify playlist, album or song shared to the app, to bring over. */
    data class SpotifyImport(val text: String) : LinkTarget
    /** An invite to a Listen together room. */
    data class Together(val code: String) : LinkTarget
}

/**
 * Reads music.youtube.com, youtube.com and youtu.be links: songs and videos,
 * playlists, albums and channels. Anything else is null.
 */
object Links {
    private val HOSTS = setOf("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be")
    private val ID = Regex("[A-Za-z0-9_-]+")

    fun parse(text: String?): LinkTarget? {
        val raw = text?.let { Regex("""https?://\S+""").find(it)?.value } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to v
        }.toMap()
        val path = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        fun valid(id: String?) = id?.takeIf { ID.matches(it) }
        return when {
            host == "youtu.be" -> valid(path.firstOrNull())?.let(LinkTarget::Song)
            path.firstOrNull() == "watch" -> valid(query["v"])?.let(LinkTarget::Song)
                ?: valid(query["list"])?.let { LinkTarget.Browse(playlistBrowseId(it)) }
            path.firstOrNull() == "shorts" -> valid(path.getOrNull(1))?.let(LinkTarget::Song)
            path.firstOrNull() == "playlist" -> valid(query["list"])?.let { LinkTarget.Browse(playlistBrowseId(it)) }
            path.firstOrNull() == "browse" -> valid(path.getOrNull(1))?.let(LinkTarget::Browse)
            path.firstOrNull() == "channel" -> valid(path.getOrNull(1))?.let(LinkTarget::Browse)
            else -> null
        }
    }

    /** Playlists are browsed with a "VL" in front of their id. */
    private fun playlistBrowseId(id: String) = if (id.startsWith("VL")) id else "VL$id"

    /** Links handed to the app from outside, waiting for the UI to open them. */
    private val _incoming = MutableStateFlow<LinkTarget?>(null)
    val incoming: StateFlow<LinkTarget?> = _incoming.asStateFlow()

    fun receive(intent: Intent?) {
        intent ?: return
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            _incoming.value = LinkTarget.PlaySearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        com.opentune.data.together.RoomCode.find(text)?.let {
            _incoming.value = LinkTarget.Together(it.raw)
            return
        }
        if (text != null && com.opentune.data.spotify.Spotify.looksLikeSpotify(text)) {
            _incoming.value = LinkTarget.SpotifyImport(text)
            return
        }
        parse(text)?.let { _incoming.value = it }
    }

    fun consumed() {
        _incoming.value = null
    }
}
