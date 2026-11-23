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
    /** A song on another service (Apple Music, Deezer, Tidal…), to find and play here. */
    data class Elsewhere(val url: String) : LinkTarget
    /**
     * A link only YouTube can say the page of: a channel by its handle (`@name`, `/c/name`), or
     * a playlist that may be a podcast show. Opens [fallback] when it can't be resolved.
     */
    data class Resolve(val url: String, val fallback: LinkTarget? = null) : LinkTarget
    /** A Spotify playlist, album or song shared to the app, to bring over. */
    data class SpotifyImport(val text: String) : LinkTarget
    /** An invite to a Listen together room. */
    data class Together(val code: String) : LinkTarget
    /** A link adding this phone to the person's devices (see Connect). */
    data class Connect(val secret: String) : LinkTarget
    /** From the widget or Quick Settings tile: start listening for a song nearby. */
    data object Recognize : LinkTarget
    /** Open the app with the player up. */
    data object Player : LinkTarget
}

/**
 * Reads music.youtube.com, youtube.com and youtu.be links: songs and videos,
 * playlists, albums and channels. Anything else is null.
 */
object Links {
    private val HOSTS = setOf("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be")
    private val ID = Regex("[A-Za-z0-9_-]+")

    /** Opens the app with the player up. */
    const val ACTION_OPEN_PLAYER = "com.opentune.action.OPEN_PLAYER"

    fun parse(text: String?): LinkTarget? {
        // A channel by its handle, as a link or just "@name": only YouTube knows which channel it is.
        text?.let { t -> Regex("""https?://\S+""").find(t)?.value ?: t.trim() }
            ?.let(com.opentune.data.ChannelLink::channelUrl)?.let { return LinkTarget.Resolve(it) }
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
            // A podcast's episodes are a playlist too: YouTube Music says whether it's a show.
            path.firstOrNull() == "playlist" -> valid(query["list"])?.let { LinkTarget.Resolve(raw, LinkTarget.Browse(playlistBrowseId(it))) }
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
        if (intent.action == com.opentune.widget.RecognizeLaunch.ACTION) {
            _incoming.value = LinkTarget.Recognize
            return
        }
        if (intent.action == ACTION_OPEN_PLAYER) {
            _incoming.value = LinkTarget.Player
            return
        }
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            _incoming.value = LinkTarget.PlaySearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        open(text)
    }

    /** What [text] links to, if the app can open it: the same links it takes when shared in. */
    fun targetOf(text: String?): LinkTarget? {
        com.opentune.data.connect.ConnectGroup.parse(text)?.let { return LinkTarget.Connect(it.encoded) }
        com.opentune.data.together.RoomCode.find(text)?.let { return LinkTarget.Together(it.raw) }
        if (text != null && com.opentune.data.spotify.Spotify.looksLikeSpotify(text)) return LinkTarget.SpotifyImport(text)
        parse(text)?.let { return it }
        com.opentune.data.links.MusicLinks.linkIn(text)?.let { return LinkTarget.Elsewhere(it) }
        // A podcast's own feed address opens as the show.
        val raw = text?.let { Regex("""https?://\S+""").find(it)?.value } ?: return null
        return if (com.opentune.data.podcasts.PodcastFeeds.looksLikeFeed(raw)) LinkTarget.Browse(com.opentune.data.podcasts.PodcastFeeds.showId(raw)) else null
    }

    /** Opens what [text] links to, as a shared link would be; false when it isn't a link the app knows. */
    fun open(text: String?): Boolean {
        val target = targetOf(text) ?: return false
        _incoming.value = target
        return true
    }

    fun consumed() {
        _incoming.value = null
    }
}
