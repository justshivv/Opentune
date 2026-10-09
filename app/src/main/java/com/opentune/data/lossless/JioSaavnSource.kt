package com.opentune.data.lossless

import com.opentune.data.model.Song
import com.opentune.data.reco.JioSaavnRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Public catalog/CDN protocol adapted from BitChord (GPLv3); verified 320 kbps AAC, not lossless. */
internal object JioSaavnSource {
    suspend fun resolve(song: Song, durationMs: Long, report: (String) -> Unit = {},
        read: suspend (Request, Int, Boolean) -> LosslessHttp.Data = { request, limit, prefix -> LosslessHttp.read(request, limit, prefix) },
    ): String? = withContext(Dispatchers.IO) {
        suspend fun json(url: String) = JSONObject(read(Request.Builder().url(url).build(), 1_048_576, false).bytes.toString(Charsets.UTF_8))
        var reported = false
        fun rejected(reason: String): String? { reported = true; report("JioSaavn: $reason"); return null }
        try {
            val result = withTimeoutOrNull(10_000) {
                val identity = CatalogIdentity(song.title, song.artist, song.albumName, durationMs)
                // Artist searches can fill the first page with compilations;
                // ask for the requested album first, without relaxing identity.
                var matched: String? = null
                val queries = listOfNotNull(song.albumName?.takeIf(String::isNotBlank)?.let { "${song.title} $it" }, "${song.title} ${song.artist}").distinct()
                for (query in queries) {
                    val root = json(api("search.getResults", "q" to query, "n" to "8", "p" to "1"))
                    matched = match(root, identity)
                    if (matched != null) break
                }
                val id = matched ?: return@withTimeoutOrNull rejected("no matching 320 kbps recording (title, artists, album and duration)")
                val details = json(api("song.getDetails", "pids" to id))
                val track = details.optJSONObject(id) ?: details.optJSONArray("songs")?.optJSONObject(0) ?: return@withTimeoutOrNull rejected("track details unavailable")
                if (track.optString("id") != id) return@withTimeoutOrNull rejected("track identity changed")
                val info = track.optJSONObject("more_info") ?: return@withTimeoutOrNull rejected("track details unavailable")
                val url = streamUrl(info) ?: return@withTimeoutOrNull rejected("320 kbps stream unavailable")
                val request = Request.Builder().url(url).header("Range", "bytes=0-63").build()
                val data = read(request, 64, true)
                if (!verified320(data.bytes, data.totalBytes, durationMs)) return@withTimeoutOrNull rejected("stream failed the audio/bitrate check")
                report("JioSaavn: verified 320 kbps stream")
                url
            }
            if (result == null && !reported) rejected("lookup timed out") else result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            rejected("request failed or service unavailable")
        }
    }

    internal fun match(root: JSONObject, identity: CatalogIdentity): String? {
        val tracks = root.optJSONArray("results") ?: return null
        for (i in 0 until minOf(8, tracks.length())) {
            val track = tracks.optJSONObject(i) ?: continue
            val info = track.optJSONObject("more_info") ?: continue
            if (!info.optString("320kbps").equals("true", true)) continue
            val artists = credits(info)
            if (identity.matches(JioSaavnRadio.unescape(track.optString("title")), artists, JioSaavnRadio.unescape(info.optString("album")), info.optLong("duration") * 1000)) {
                return track.optString("id").takeIf { Regex("[A-Za-z0-9_-]+").matches(it) }
            }
        }
        return null
    }

    private fun credits(info: JSONObject): List<String> {
        val map = info.optJSONObject("artistMap") ?: return emptyList()
        // YouTube often bills a soundtrack to the composer or lyricist;
        // Saavn's primary_artists can contain only the singer. Actors aren't
        // recording credits and must not make an otherwise unrelated match.
        val roles = setOf("music", "composer", "singer", "lyricist", "primary_artists", "featured_artists")
        return listOf("primary_artists", "featured_artists", "artists").flatMap { field ->
            val entries = map.optJSONArray(field)
            (0 until (entries?.length() ?: 0)).mapNotNull { i ->
                entries?.optJSONObject(i)?.takeIf { field != "artists" || it.optString("role") in roles }
                    ?.optString("name")?.let(JioSaavnRadio::unescape)
            }
        }.filter(String::isNotBlank).distinct()
    }

    internal fun streamUrl(info: JSONObject): String? {
        if (!info.optString("320kbps").equals("true", true)) return null
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        val decoded = String(cipher.doFinal(Base64.getDecoder().decode(info.getString("encrypted_media_url"))), Charsets.UTF_8).trim()
        return rendition320(decoded)
    }

    internal fun rendition320(decoded: String): String? {
        val url = httpsUrl(decoded) ?: return null
        val parsed = url.toHttpUrl()
        if (parsed.host != "saavncdn.com" && !parsed.host.endsWith(".saavncdn.com")) return null
        val pattern = Regex("_(48|96|160|320)\\.mp4$", RegexOption.IGNORE_CASE)
        if (!pattern.containsMatchIn(parsed.encodedPath)) return null
        return parsed.newBuilder().encodedPath(parsed.encodedPath.replace(pattern, "_320.mp4")).build().toString()
    }

    internal fun verified320(bytes: ByteArray, totalBytes: Long?, durationMs: Long): Boolean =
        bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            totalBytes != null && durationMs > 0 && totalBytes * 8 / durationMs in 256..380

    private fun api(method: String, vararg parameters: Pair<String, String>): String =
        "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", method).addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0").addQueryParameter("ctx", "android").addQueryParameter("api_version", "4")
            .apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()
}
