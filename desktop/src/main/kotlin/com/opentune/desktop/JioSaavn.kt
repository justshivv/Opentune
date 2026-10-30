package com.opentune.desktop

import com.opentune.data.Http
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * JioSaavn's 320 kbps AAC for a song, as the phone and the web player find
 * it: the same title, a length within three seconds, a credited person in
 * common, and a file that really is AAC in MP4 at about 320 kbps. It's what
 * plays when no add-on has the song in FLAC, and JavaFX plays it as it is.
 */
internal object JioSaavn {
    /** A song to look for, by its YouTube Music names. */
    data class Wanted(val title: String, val artist: String, val album: String?, val durationSec: Double)

    private val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /** The verified stream's address, or null with why in [report]. */
    suspend fun find(wanted: Wanted, report: (String) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        try {
            val queries = listOfNotNull(wanted.album?.takeIf(String::isNotBlank)?.let { "${wanted.title} $it" }, "${wanted.title} ${wanted.artist}").distinct()
            var id: String? = null
            for (q in queries) {
                val found = json(api("search.getResults", "q" to q, "n" to "8", "p" to "1"))
                id = match(found.optJSONArray("results") ?: JSONArray(), wanted)
                if (id != null) break
            }
            if (id == null) return@withContext null.also { report("JioSaavn: no matching 320 kbps recording") }
            val details = json(api("song.getDetails", "pids" to id))
            val song = details.optJSONObject(id) ?: details.optJSONArray("songs")?.optJSONObject(0)
            if (song == null || song.optString("id") != id) return@withContext null.also { report("JioSaavn: track details unavailable") }
            val encrypted = song.optJSONObject("more_info")?.optString("encrypted_media_url").orEmpty()
            val url = encrypted.takeIf { it.isNotBlank() }?.let { rendition320(decrypt(it)) }
                ?: return@withContext null.also { report("JioSaavn: 320 kbps stream unavailable") }
            val (head, total) = probe(url)
            if (!verified320(head, total, wanted.durationSec)) return@withContext null.also { report("JioSaavn: stream failed the audio/bitrate check") }
            report("JioSaavn: verified 320 kbps stream")
            url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("JioSaavn: ${e.message ?: "request failed"}")
            null
        }
    }

    private fun api(call: String, vararg params: Pair<String, String>): String =
        "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", call).addQueryParameter("_format", "json").addQueryParameter("_marker", "0")
            .addQueryParameter("ctx", "android").addQueryParameter("api_version", "4")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()

    private fun json(url: String): JSONObject = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        check(r.isSuccessful) { "HTTP ${r.code}" }
        JSONObject(r.body!!.string())
    }

    /** The file's first bytes and its full length, without reading the rest. */
    private fun probe(url: String): Pair<ByteArray, Long> =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-63").build()).execute().use { r ->
            check(r.isSuccessful) { "its file server returned HTTP ${r.code}" }
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body?.contentLength() ?: -1
            r.body!!.byteStream().readNBytes(64) to total
        }

    private val ENTITIES = mapOf("&amp;" to "&", "&quot;" to "\"", "&#039;" to "'", "&#39;" to "'", "&lt;" to "<", "&gt;" to ">")
    fun unescape(text: String): String = Regex("&(?:amp|quot|#0?39|lt|gt);").replace(text) { ENTITIES[it.value] ?: it.value }

    /** A name compared on its words: no accents, case, brackets ("(From "Raees")") or trailing " - Remix" parts. */
    fun plain(text: String): String = Normalizer.normalize(unescape(text), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^\\]]*]"), " ").replace(Regex("\\s[-–]\\s.*$"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The kind of version a title names; a remix or a live take is never the studio recording. */
    private val VERSION = Regex("\\b(remix|mix|lo-?fi|slowed|reverb|unplugged|live|acoustic|instrumental|karaoke|cover|reprise|sped up|8d)\\b", RegexOption.IGNORE_CASE)
    fun versions(title: String): String = VERSION.findAll(unescape(title).lowercase()).map { it.groupValues[1].replace("-", "") }.toSortedSet().joinToString(",")

    private fun people(line: String): List<String> =
        line.split(Regex(",|&| x | feat\\.? | featuring | ft\\.? | and ", RegexOption.IGNORE_CASE)).map(::plain).filter(String::isNotEmpty)

    /** Singers, composers and lyricists a result credits, never its actors. */
    private fun credits(info: JSONObject): List<String> {
        val map = info.optJSONObject("artistMap") ?: return emptyList()
        val roles = setOf("music", "composer", "singer", "lyricist", "primary_artists", "featured_artists")
        return listOf("primary_artists", "featured_artists", "artists").flatMap { field ->
            val list = map.optJSONArray(field) ?: return@flatMap emptyList()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { field != "artists" || it.optString("role") in roles }
                .map { plain(it.optString("name")) }
        }.filter(String::isNotEmpty)
    }

    /** The search result that is the wanted recording, by the web player's rules (web/app/src/addons/jiosaavn.ts). */
    fun match(results: JSONArray, wanted: Wanted): String? {
        val title = plain(wanted.title)
        val asked = people(wanted.artist)
        for (i in 0 until minOf(results.length(), 8)) {
            val r = results.optJSONObject(i) ?: continue
            val info = r.optJSONObject("more_info") ?: JSONObject()
            val id = r.optString("id")
            if (!info.optString("320kbps").equals("true", true) || !id.matches(Regex("[A-Za-z0-9_-]+"))) continue
            val length = info.optString("duration").toDoubleOrNull() ?: continue
            if (kotlin.math.abs(length - wanted.durationSec) > 3) continue
            if (plain(r.optString("title")) != title || versions(r.optString("title")) != versions(wanted.title)) continue
            val credited = credits(info)
            if (asked.any { it in credited }) return id
        }
        return null
    }

    fun decrypt(encrypted: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        return String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8).trim()
    }

    /** The 320 kbps rendition of a decrypted media address, on Saavn's own CDN only. */
    fun rendition320(decoded: String): String? {
        val url = decoded.trim().toHttpUrlOrNull() ?: return null
        if (url.host != "saavncdn.com" && !url.host.endsWith(".saavncdn.com")) return null
        val pattern = Regex("_(48|96|160|320)\\.mp4$", RegexOption.IGNORE_CASE)
        if (!pattern.containsMatchIn(url.encodedPath)) return null
        return url.newBuilder().scheme("https").encodedPath(url.encodedPath.replace(pattern, "_320.mp4")).build().toString()
    }

    /** Whether the file really is AAC in MP4 at about 320 kbps, from its first bytes and its size. */
    fun verified320(head: ByteArray, totalBytes: Long, durationSec: Double): Boolean {
        if (head.size < 12 || String(head, 4, 4, Charsets.US_ASCII) != "ftyp" || durationSec <= 0 || totalBytes <= 0) return false
        val kbps = totalBytes * 8 / durationSec / 1000
        return kbps in 256.0..380.0
    }
}
