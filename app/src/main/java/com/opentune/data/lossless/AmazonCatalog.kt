package com.opentune.data.lossless

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Public web catalogue requests follow the reviewed amazon 2.3.11 adapter. */
internal object AmazonCatalog {
    private const val HOME = "https://music.amazon.com"
    const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    suspend fun find(identity: CatalogIdentity): String? = match(searchPage(identity.title), identity)
    suspend fun search(query: String): List<LosslessTrack> = tracks(searchPage(query.trim().take(200)))
    private suspend fun searchPage(query: String): JSONObject {
        val config = JSONObject(post("$HOME/config.json?skipToken=false&clientApplication=skyfire", "", HOME))
        val csrf = config.optJSONObject("csrf") ?: error("Catalog credentials unavailable")
        val page = "$HOME/search/".toHttpUrl().newBuilder().addPathSegment(query).build().toString()
        val headers = JSONObject().apply {
            put("x-amzn-authentication", JSONObject().put("interface", "ClientAuthenticationInterface.v1_0.ClientTokenElement").put("accessToken", "").toString())
            put("x-amzn-csrf", JSONObject().put("interface", "CSRFInterface.v1_0.CSRFHeaderElement").put("token", csrf.optString("token"))
                .put("timestamp", csrf.optString("ts")).put("rndNonce", csrf.optString("rnd")).toString())
            put("x-amzn-device-model", "WEBPLAYER"); put("x-amzn-device-family", "WebPlayer")
            put("x-amzn-device-width", "1920"); put("x-amzn-device-height", "1080")
            put("x-amzn-device-id", config.optString("deviceId")); put("x-amzn-session-id", config.optString("sessionId"))
            put("x-amzn-request-id", UUID.randomUUID().toString()); put("x-amzn-user-agent", UA)
            put("x-amzn-device-language", config.optString("displayLanguage", "en_US"))
            put("x-amzn-currency-of-preference", "USD"); put("x-amzn-os-version", "1.0")
            put("x-amzn-application-version", config.optString("version", "1.0.9678.0"))
            put("x-amzn-device-time-zone", "UTC"); put("x-amzn-timestamp", System.currentTimeMillis().toString())
            put("x-amzn-music-domain", "music.amazon.com"); put("x-amzn-page-url", page)
            listOf("referer", "affiliate-tags", "ref-marker", "weblab-id-overrides", "video-player-token", "feature-flags", "has-profile-id", "age-band")
                .forEach { put("x-amzn-$it", "") }
        }
        val body = JSONObject().put("filter", "{\"IsLibrary\":[\"false\"]}")
            .put("keyword", JSONObject().put("interface", "Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation")
                .put("keyword", query).toString()).put("suggestedKeyword", query)
            .put("userHash", "{\"level\":\"LIBRARY_MEMBER\"}").put("headers", headers.toString())
        return JSONObject(post("https://na.mesk.skill.music.a2z.com/api/showSearch", body.toString(), page))
    }
    private suspend fun post(url: String, body: String, page: String): String = LosslessHttp.bytes(Request.Builder().url(url)
        .header("User-Agent", UA).header("Origin", HOME).header("Referer", page)
        .post(body.toRequestBody("text/plain;charset=UTF-8".toMediaType())).build(), 4_194_304,
        timeoutMs = 6_000, followRedirects = false).toString(Charsets.UTF_8)
    internal fun asin(link: String): String? {
        val url = HOME.toHttpUrl().resolve(link) ?: return null
        if (!url.isHttps || url.host != "music.amazon.com" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val segments = url.pathSegments
        val id = when (segments.firstOrNull()) {
            "albums" -> url.queryParameter("trackAsin")
            "track", "tracks" -> segments.getOrNull(1)
            else -> null
        }
        return id?.uppercase()?.takeIf { Regex("[A-Z0-9]{10}").matches(it) }
    }
    private fun text(value: Any?, depth: Int = 0): String = if (depth > 8) "" else when (value) {
        is String -> value
        is JSONObject -> value.optString("text").ifBlank { text(value.opt("defaultValue"), depth + 1) }
            .ifBlank { text(value.optJSONObject("observer")?.opt("defaultValue"), depth + 1) }
        else -> ""
    }
    internal fun tracks(root: JSONObject): List<LosslessTrack> {
        val found = linkedMapOf<String, LosslessTrack>()
        var nodes = 0
        fun walk(value: Any?, depth: Int) {
            if (++nodes > 30_000 || depth > 24 || found.size >= 50) return
            when (value) {
                is JSONObject -> {
                    val type = value.optString("interface")
                    if (type.endsWith("DescriptiveRowItemElement") || type.endsWith("SquareHorizontalItemElement")) {
                        val title = text(value.opt("primaryText"))
                        val artist = text(value.opt("secondaryText1")).ifBlank { text(value.opt("secondaryText")) }
                        val link = value.optJSONObject("primaryTextLink")?.optString("deeplink").orEmpty()
                            .ifBlank { value.optJSONObject("primaryLink")?.optString("deeplink").orEmpty() }
                        val id = asin(link)
                        val parts = text(value.opt("secondaryText3")).ifBlank { text(value.opt("duration")) }.split(':').mapNotNull { it.toLongOrNull() }
                        val duration = parts.takeIf { it.size in 2..3 }?.fold(0L) { total, n -> total * 60 + n }?.times(1000) ?: 0
                        val image = (value.opt("image") as? String).orEmpty().ifBlank { value.optJSONObject("image")?.optString("uri").orEmpty() }
                        if (id != null && title.isNotBlank() && artist.isNotBlank()) found.putIfAbsent(id,
                            LosslessTrack(id, title, artist, artwork = image.takeIf { it.startsWith("https://") }, durationMs = duration))
                    }
                    for (key in value.keys()) walk(value.opt(key), depth + 1)
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
            }
        }
        walk(root, 0)
        return found.values.toList()
    }
    internal fun match(root: JSONObject, identity: CatalogIdentity): String? {
        var nodes = 0
        fun walk(value: Any?, depth: Int): String? {
            if (++nodes > 30_000 || depth > 24) return null
            when (value) {
                is JSONObject -> {
                    val type = value.optString("interface")
                    if (type.endsWith("DescriptiveRowItemElement") || type.endsWith("SquareHorizontalItemElement")) {
                        val title = text(value.opt("primaryText"))
                        val artist = text(value.opt("secondaryText1")).ifBlank { text(value.opt("secondaryText")) }
                        val link = value.optJSONObject("primaryTextLink")?.optString("deeplink").orEmpty()
                            .ifBlank { value.optJSONObject("primaryLink")?.optString("deeplink").orEmpty() }
                        val duration = text(value.opt("secondaryText3")).ifBlank { text(value.opt("duration")) }
                            .split(':').mapNotNull { it.toLongOrNull() }.takeIf { it.size in 2..3 }?.fold(0L) { total, n -> total * 60 + n }?.times(1000)
                        // Missing search duration is checked against the actual FLAC before selection.
                        if (identity.copy(album = null).matches(title, listOf(artist), null, duration ?: identity.durationMs)) asin(link)?.let { return it }
                    }
                    for (key in value.keys()) walk(value.opt(key), depth + 1)?.let { return it }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), depth + 1)?.let { return it }
            }
            return null
        }
        return walk(root, 0)
    }
}
