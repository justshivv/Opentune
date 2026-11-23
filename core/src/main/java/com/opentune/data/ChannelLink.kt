package com.opentune.data

import java.net.URI
import java.util.Locale

/**
 * Channel links as people share them: `youtube.com/@name`, `/c/name`, `/user/name`, with or
 * without a tab after the name (`/podcasts`, `/videos`) or tracking after a "?". A small
 * podcast that search doesn't surface can be reached by pasting its channel's link, or just
 * its `@handle`.
 */
object ChannelLink {
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val HANDLE = Regex("""@[A-Za-z0-9._-]{3,100}""")

    /** True when [text] is a channel link of that kind, or a bare `@handle`. */
    fun isChannel(text: String): Boolean = channelUrl(text) != null

    /**
     * [text] as a channel's own address (`https://www.youtube.com/@name`), for a channel link
     * or a bare `@handle`; null for anything else.
     */
    fun channelUrl(text: String): String? {
        val t = text.trim()
        if (HANDLE.matches(t)) return "https://www.youtube.com/$t"
        val uri = runCatching { URI(t) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || host !in HOSTS) return null
        val path = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val first = path.firstOrNull() ?: return null
        return when {
            HANDLE.matches(first) -> "https://www.youtube.com/$first"
            first == "c" || first == "user" -> path.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) }
                ?.let { "https://www.youtube.com/$first/$it" }
            else -> null
        }
    }

    /** [url] with a channel link cut back to the channel itself; anything else as it is. */
    fun base(url: String): String = channelUrl(url) ?: url.trim()
}
