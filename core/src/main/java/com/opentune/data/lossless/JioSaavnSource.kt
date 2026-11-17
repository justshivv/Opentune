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
                        onArtists: (String) -> Unit = {},
                        read: suspend (Request, Int, Boolean) -> LosslessHttp.Data = { request, limit, prefix -> LosslessHttp.read(request, limit, prefix) },
    ): String? = withContext(Dispatchers.IO) {
        suspend fun json(url: String) = JSONObject(read(Request.Builder().url(url).build(), 1_048_576, false).bytes.toString(Charsets.UTF_8))
        var reported = false
        fun rejected(reason: String): String? { reported = true; report("JioSaavn: $reason"); return null }
        try {
            // Only a guard against a hung connection; a slow but real answer is waited for.
            val result = withTimeoutOrNull(30_000) {
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
                displayLine(info, song.artist)?.let(onArtists)
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

    /**
     * The credit line worth showing for a matched recording: everyone Saavn bills for
     * performing it — the primary and featured artists, plus the singer, composer and
     * music credits — joined as one line. A name the artists array credits only with
     * words or a screen role is not a performer and never joins, even when Saavn also
     * files it under primary artists, as soundtracks bill the lyricist there. Only
     * offered when it names everyone the queue's byline already names, plus more: a
     * fuller credit may extend the byline, never rewrite it. The byline's own artists
     * lead, in their order; everyone else follows in the catalogue's order.
     */
    internal fun displayLine(info: JSONObject, byline: String): String? {
        val map = info.optJSONObject("artistMap") ?: return null
        val performing = setOf("singer", "composer", "music", "primary_artists", "featured_artists")
        val entries = map.optJSONArray("artists")
        val roles = mutableMapOf<String, MutableSet<String>>()
        for (i in 0 until (entries?.length() ?: 0)) {
            val entry = entries?.optJSONObject(i) ?: continue
            val name = entry.optString("name").let(JioSaavnRadio::unescape).trim()
            if (name.isNotEmpty()) roles.getOrPut(CatalogIdentity.key(name)) { mutableSetOf() } += entry.optString("role")
        }
        val notPerforming = roles.filterValues { rs -> rs.none { it in performing } }.keys
        val names = mutableListOf<String>()
        fun add(raw: String?, unlessBlocked: Boolean) {
            val name = raw?.let(JioSaavnRadio::unescape)?.trim().orEmpty()
            if (name.isEmpty() || (unlessBlocked && CatalogIdentity.key(name) in notPerforming)) return
            if (names.none { CatalogIdentity.key(it) == CatalogIdentity.key(name) }) names += name
        }
        for (field in listOf("primary_artists", "featured_artists")) {
            val list = map.optJSONArray(field)
            for (i in 0 until (list?.length() ?: 0)) add(list?.optJSONObject(i)?.optString("name"), unlessBlocked = true)
        }
        for (i in 0 until (entries?.length() ?: 0)) {
            val entry = entries?.optJSONObject(i)
            if (entry?.optString("role") in performing) add(entry?.optString("name"), unlessBlocked = false)
        }
        if (names.size < 2) return null
        val separators = Regex("\\s*(?:,|&|;|\\u2022)\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
        val billed = byline.split(separators).map { CatalogIdentity.key(it) }.filter(String::isNotEmpty).distinct()
        val have = names.map { CatalogIdentity.key(it) }.toSet()
        if (billed.isEmpty() || !billed.all { it in have } || names.size <= billed.size) return null
        val ordered = billed.map { b -> names.first { CatalogIdentity.key(it) == b } } +
                names.filter { CatalogIdentity.key(it) !in billed }
        return ordered.joinToString(", ")
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
