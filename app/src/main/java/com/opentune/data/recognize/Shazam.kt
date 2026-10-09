package com.opentune.data.recognize

import android.util.Base64
import com.opentune.data.Http
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A song recognised from what the microphone heard. */
data class Recognized(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val shazamUrl: String?,
    val foundAt: Long = System.currentTimeMillis(),
)

/**
 * Looks an encoded [Signature] up with Shazam, the way its own app does: the
 * fingerprint goes up, never the recording. The answer is the song, or
 * nothing when it doesn't know it.
 */
object Shazam {
    private const val BASE = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"

    suspend fun lookup(signature: ByteArray, durationMs: Long): Recognized? = withContext(Dispatchers.IO) {
        val uri = "data:audio/vnd.shazam.sig;base64," + Base64.encodeToString(signature, Base64.NO_WRAP)
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("timezone", TimeZone.getDefault().id)
            .put("signature", JSONObject().put("uri", uri).put("samplems", durationMs))
            .put("timestamp", now)
            .put("context", JSONObject())
            .put("geolocation", JSONObject())
        val url = "$BASE/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004)")
            .header("Content-Language", "en_US")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Shazam answered ${response.code}" }
            parse(response.body?.string().orEmpty())
        }
    }

    /** The song in a lookup's answer, if it found one. */
    fun parse(json: String): Recognized? {
        val root = JSONObject(json)
        val track = root.optJSONObject("track") ?: return null
        val title = track.optString("title").takeIf { it.isNotBlank() } ?: return null
        val images = track.optJSONObject("images")
        val album = track.optJSONArray("sections")?.let { sections ->
            (0 until sections.length()).asSequence()
                .mapNotNull { sections.optJSONObject(it)?.optJSONArray("metadata") }
                .flatMap { m -> (0 until m.length()).asSequence().mapNotNull { m.optJSONObject(it) } }
                .firstOrNull { it.optString("title").equals("Album", ignoreCase = true) }
                ?.optString("text")?.takeIf { it.isNotBlank() }
        }
        return Recognized(
            title = title,
            artist = track.optString("subtitle"),
            album = album,
            coverUrl = images?.optString("coverarthq")?.takeIf { it.isNotBlank() } ?: images?.optString("coverart")?.takeIf { it.isNotBlank() },
            shazamUrl = track.optString("url").takeIf { it.isNotBlank() },
        )
    }
}
