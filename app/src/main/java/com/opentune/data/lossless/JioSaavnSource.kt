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

/** Public catalog/CDN protocol adapted from BitChord (GPLv3); strictly opt-in and lossy. */
internal object JioSaavnSource {
    suspend fun resolve(song: Song, durationMs: Long): String? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(10_000) {
                val root = JSONObject(LosslessHttp.text(api("search.getResults", "q" to "${song.title} ${song.artist}", "n" to "8", "p" to "1")))
                val identity = CatalogIdentity(song.title, song.artist, song.albumName, durationMs)
                val id = match(root, identity) ?: return@withTimeoutOrNull null
                val details = JSONObject(LosslessHttp.text(api("song.getDetails", "pids" to id)))
                val track = details.optJSONObject(id) ?: details.optJSONArray("songs")?.optJSONObject(0) ?: return@withTimeoutOrNull null
                if (track.optString("id") != id) return@withTimeoutOrNull null
                val info = track.optJSONObject("more_info") ?: return@withTimeoutOrNull null
                val url = streamUrl(info) ?: return@withTimeoutOrNull null
                val request = Request.Builder().url(url).header("Range", "bytes=0-63").build()
                val data = LosslessHttp.read(request, 64, prefixOnly = true)
                if (!verified320(data.bytes, data.totalBytes, durationMs)) return@withTimeoutOrNull null
                url
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun match(root: JSONObject, identity: CatalogIdentity): String? {
        val tracks = root.optJSONArray("results") ?: return null
        for (i in 0 until minOf(8, tracks.length())) {
            val track = tracks.optJSONObject(i) ?: continue
            val info = track.optJSONObject("more_info") ?: continue
            if (!info.optString("320kbps").equals("true", true)) continue
            val artistList = info.optJSONObject("artistMap")?.optJSONArray("primary_artists") ?: continue
            val artists = (0 until artistList.length()).map { JioSaavnRadio.unescape(artistList.optJSONObject(it)?.optString("name").orEmpty()) }
            if (identity.matches(JioSaavnRadio.unescape(track.optString("title")), artists, JioSaavnRadio.unescape(info.optString("album")), info.optLong("duration") * 1000)) {
                return track.optString("id").takeIf { Regex("[A-Za-z0-9_-]+").matches(it) }
            }
        }
        return null
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
