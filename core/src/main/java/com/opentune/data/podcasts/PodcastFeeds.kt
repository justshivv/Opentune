package com.opentune.data.podcasts

import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Podcasts beyond YouTube Music: Apple's public podcast directory, which lists nearly every
 * public show, and each show's own RSS feed, where its episodes and their audio files are.
 * This is how podcast apps in general find and play shows; nothing here needs an account.
 *
 * A show from a feed has the id `rss:` + its feed address, and an episode `pod:` + its audio
 * file's address (both URL-safe base64), so either opens again from the id alone, as an
 * audiobook's chapter does.
 */
object PodcastFeeds {
    const val SHOW_PREFIX = "rss:"
    const val EPISODE_PREFIX = "pod:"
    private const val DIRECTORY = "https://itunes.apple.com/search"
    private const val MAX_FEED_BYTES = 20L * 1024 * 1024
    private const val MAX_EPISODES = 1000
    private const val FRESH_MS = 15 * 60_000L

    fun isFeedShow(id: String) = id.startsWith(SHOW_PREFIX)
    fun isFeedEpisode(id: String) = id.startsWith(EPISODE_PREFIX)

    fun showId(feedUrl: String) = SHOW_PREFIX + encode(feedUrl)
    fun feedUrlOf(showId: String): String = decode(showId.removePrefix(SHOW_PREFIX))
    fun episodeId(audioUrl: String) = EPISODE_PREFIX + encode(audioUrl)
    fun urlOf(episodeId: String): String = decode(episodeId.removePrefix(EPISODE_PREFIX))

    private fun encode(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun decode(s: String) = String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)

    /**
     * True when [url] reads like a podcast feed's address (`.rss`, `.xml`, `/feed`, `/rss`):
     * pasted into search, it opens as a show.
     */
    fun looksLikeFeed(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        val path = u.encodedPath.lowercase(Locale.ROOT).trimEnd('/')
        return path.endsWith(".rss") || path.endsWith(".xml") || path.endsWith("/rss") || path.endsWith("/feed") ||
            path.endsWith("/podcast") || u.host.startsWith("feeds.") || u.host.startsWith("rss.")
    }

    /** Shows in Apple's directory for [query], each with its feed; [country] as a two-letter code. */
    suspend fun search(query: String, country: String? = Innertube.currentRegion): List<PodcastShow> {
        val url = DIRECTORY.toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast").addQueryParameter("entity", "podcast")
            .addQueryParameter("limit", "30").addQueryParameter("term", query)
            .apply { country?.takeIf { it.length == 2 }?.let { addQueryParameter("country", it.lowercase(Locale.ROOT)) } }
            .build().toString()
        return parseDirectory(get(url, 2L * 1024 * 1024) ?: return emptyList())
    }

    /** The directory's answer: shows that have a feed, as feed shows. */
    internal fun parseDirectory(body: String): List<PodcastShow> {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val feed = r.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = r.optString("collectionName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PodcastShow(
                browseId = showId(feed),
                title = title,
                author = r.optString("artistName"),
                thumbnailUrl = r.optString("artworkUrl600").ifBlank { r.optString("artworkUrl100") }.ifBlank { null },
            )
        }.distinctBy { it.browseId }
    }

    private class Loaded(val page: PodcastShowPage, val at: Long)
    private val loaded = ConcurrentHashMap<String, Loaded>()

    /** A feed show's page: the show and all its episodes, newest first, read again after a quarter of an hour. */
    suspend fun load(showId: String, fresh: Boolean = false): PodcastShowPage {
        val now = System.currentTimeMillis()
        loaded[showId]?.takeIf { !fresh && now - it.at < FRESH_MS }?.let { return it.page }
        val url = feedUrlOf(showId)
        val body = get(url, MAX_FEED_BYTES) ?: throw java.io.IOException("Couldn't read the feed at $url")
        val page = parse(body, url) ?: throw java.io.IOException("$url isn't a podcast feed")
        loaded[showId] = Loaded(page, now)
        return page
    }

    /** A feed's show and episodes, or null when [xml] isn't an RSS podcast feed. */
    internal fun parse(xml: String, feedUrl: String): PodcastShowPage? {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var showTitle = ""
        var author = ""
        var showImage: String? = null
        var showText: String? = null
        val items = mutableListOf<Item>()
        var item: Item? = null
        var inChannel = false
        var inImage = false
        var sawRss = false
        runCatching {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name
                    when {
                        name == "rss" -> sawRss = true
                        name == "channel" -> inChannel = true
                        name == "item" -> item = Item()
                        name == "image" && item == null -> inImage = true
                        name == "enclosure" -> item?.let {
                            it.audio = parser.getAttributeValue(null, "url")?.trim()
                            it.type = parser.getAttributeValue(null, "type").orEmpty()
                        }
                        name == "itunes:image" -> parser.getAttributeValue(null, "href")?.trim()?.takeIf { it.startsWith("http") }?.let { href ->
                            if (item != null) item!!.image = href else if (showImage == null || !inImage) showImage = href
                        }
                        else -> {
                            val current = item
                            when {
                                current != null -> when (name) {
                                    "title" -> current.title = text(parser)
                                    "pubDate" -> current.published = text(parser)
                                    "itunes:duration" -> current.duration = text(parser)
                                    "description", "itunes:summary", "content:encoded" ->
                                        if (current.text.isNullOrBlank()) current.text = text(parser)
                                }
                                inImage && name == "url" -> { val u = text(parser); if (showImage == null && u.startsWith("http")) showImage = u }
                                inChannel -> when (name) {
                                    "title" -> if (showTitle.isBlank()) showTitle = text(parser)
                                    "itunes:author" -> if (author.isBlank()) author = text(parser)
                                    "description", "itunes:summary" -> if (showText.isNullOrBlank()) showText = text(parser)
                                }
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "item" -> { item?.let(items::add); item = null }
                        "image" -> inImage = false
                    }
                }
                event = parser.next()
            }
        }
        if (!sawRss || showTitle.isBlank()) return null
        val showId = showId(feedUrl)
        val show = PodcastShow(showId, showTitle.trim(), author.trim(), showImage, showText?.let(::plain))
        val episodes = items
            .filter { !it.audio.isNullOrBlank() && it.audio!!.startsWith("http") && !it.type.startsWith("image/") }
            .map { it to dateOf(it.published) }
            .let { list -> if (list.all { it.second != null }) list.sortedByDescending { it.second } else list }
            .take(MAX_EPISODES)
            .map { (it, date) ->
                PodcastEpisode(
                    videoId = episodeId(it.audio!!),
                    title = it.title?.trim()?.ifBlank { null } ?: showTitle,
                    showTitle = showTitle.trim(),
                    showBrowseId = showId,
                    thumbnailUrl = it.image ?: showImage,
                    description = it.text?.let(::plain),
                    published = date?.let { d -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d) },
                    durationText = durationText(it.duration),
                )
            }.distinctBy { it.videoId }
        return PodcastShowPage(show, episodes, null)
    }

    private class Item {
        var title: String? = null
        var audio: String? = null
        var type: String = ""
        var image: String? = null
        var published: String? = null
        var duration: String? = null
        var text: String? = null
    }

    private fun text(parser: XmlPullParser): String = runCatching { parser.nextText() }.getOrDefault("")

    /** A description as plain text: no tags, entities read, short enough for a page. */
    internal fun plain(html: String): String {
        val noTags = html.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]+>"), "")
        val decoded = noTags.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        return decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(1200)
    }

    private val DATE_FORMATS = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX")

    internal fun dateOf(raw: String?): java.util.Date? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DATE_FORMATS.firstNotNullOfOrNull { f -> runCatching { SimpleDateFormat(f, Locale.US).apply { isLenient = true }.parse(s) }.getOrNull() }
    }

    /** "3 hr 8 min" or "42 min" from `HH:MM:SS`, `MM:SS` or seconds, as YouTube Music writes it. */
    internal fun durationText(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = if (':' in s) s.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0L) { acc, v -> acc * 60 + v } else s.toDoubleOrNull()?.toLong() ?: return null
        if (seconds <= 0) return null
        val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
    }

    private suspend fun get(url: String, max: Long): String? = runInterruptible(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", "OpenTune/1.0 (Android; podcast and radio player)").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val source = r.body?.source() ?: return@use null
                source.request(max + 1)
                if (source.buffer.size > max) return@use null
                source.readUtf8()
            }
        }.getOrNull()
    }
}
