package com.opentune.data.covers

import com.opentune.data.DebugLog
import com.opentune.data.Http
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Apple Music shows at the top of an artist's page: a hero photograph,
 * wider and sharper than YouTube's, and for some artists a looping video in
 * its place. [videoUrl] is an HLS playlist; [background] is Apple's colour
 * for the picture on screen (ARGB), so the page can match it at once.
 */
data class AppleArtistArt(
    val heroUrl: String,
    val heroAspect: Float,
    val videoUrl: String?,
    val background: Int,
)

/**
 * Finds [AppleArtistArt] by the artist's name, as BitChord does: the public
 * iTunes search turns the name into an Apple Music artist, and that artist's
 * music.apple.com page carries its header in the JSON it embeds to hydrate
 * itself. That JSON isn't an API Apple publishes, so any surprise reads as
 * "no art", and YouTube's photo stays.
 *
 * A search can return somebody else, so only an artist with the very name
 * asked for is taken. Answers, misses included, are remembered for the
 * session; a failed request isn't, so it's tried again next visit.
 */
object AppleArtistArtRepository {
    private const val TAG = "AppleArtistArt"
    private const val HERO_WIDTH = 1200
    private const val REMEMBERED = 64
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private class Entry(val art: AppleArtistArt?)

    private val cache = object : LinkedHashMap<String, Entry>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > REMEMBERED
    }

    /** What's already known for [artistName] without asking: null when never asked, or when Apple has nothing. */
    fun cached(artistName: String): AppleArtistArt? = synchronized(cache) { cache[normalized(artistName)] }?.art

    /** The header art for [artistName], or null when Apple has none or couldn't be asked. Never throws. */
    suspend fun artFor(artistName: String): AppleArtistArt? {
        val key = normalized(artistName)
        if (key.isEmpty()) return null
        synchronized(cache) { cache[key] }?.let { return it.art }
        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, key) }
                .onFailure { DebugLog.d(TAG, "Apple art lookup failed for $artistName: $it") }
                .getOrNull()
        } ?: return null
        synchronized(cache) { cache[key] = entry }
        return entry.art
    }

    private fun lookup(artistName: String, key: String): Entry? {
        val search = get("https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}&entity=musicArtist&limit=5") ?: return null
        val results = JSONObject(search).optJSONArray("results") ?: JSONArray()
        var page: String? = null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (normalized(r.optString("artistName")) == key) {
                page = r.optString("artistLinkUrl").substringBefore('?').takeIf { it.isNotBlank() }
                    ?: r.optLong("artistId").takeIf { it > 0 }?.let { "https://music.apple.com/us/artist/$it" }
                break
            }
        }
        if (page == null) return Entry(null)
        val html = get(page) ?: return null
        return Entry(parse(html))
    }

    internal fun parse(html: String): AppleArtistArt? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val header = findHeader(JSONObject("{\"d\":${html.substring(start, end)}}").get("d")) ?: return null
        val hero = (header.optJSONObject("artwork") ?: header.optJSONObject("colorBackdropArtwork"))?.optJSONObject("dictionary") ?: return null
        val heroW = hero.optInt("width").takeIf { it > 0 } ?: return null
        val heroH = hero.optInt("height").takeIf { it > 0 } ?: return null
        val heroUrl = fill(hero.optString("url"), HERO_WIDTH, (HERO_WIDTH.toLong() * heroH / heroW).toInt()) ?: return null
        val clip = header.optJSONObject("videoArtwork")?.optJSONObject("dictionary")?.optJSONObject("motionArtistSquare1x1")
        val video = clip?.optString("video")?.takeIf { it.startsWith("http") }
        // The clip is a different picture from the still, with its own colour.
        val colours = clip?.optJSONObject("previewFrame")?.takeIf { video != null } ?: hero
        val background = argb(colours.optString("bgColor")) ?: argb(hero.optString("bgColor")) ?: return null
        return AppleArtistArt(heroUrl, heroW.toFloat() / heroH, video, background)
    }

    /** The first object anywhere in [node] with an `artistLogo` key: the page's header. */
    private fun findHeader(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has("artistLogo")) return node
                val keys = node.keys()
                while (keys.hasNext()) findHeader(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findHeader(node.opt(i))?.let { return it }
        }
        return null
    }

    /** Apple's colours are six hex digits with no prefix. */
    private fun argb(s: String): Int? = s.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

    private fun fill(url: String, w: Int, h: Int): String? {
        if (url.isBlank() || "{w}" !in url) return null
        return url.replace("{w}", w.toString()).replace("{h}", h.toString()).replace("{c}", "bb").replace("{f}", "jpg")
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** Case, accents and punctuation aren't what makes two names different. */
    internal fun normalized(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }
}
