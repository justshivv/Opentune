package com.opentune.data.lossless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Adapted from Spotui's GPLv3 lossless registry; see THIRD_PARTY_NOTICES.md. */
internal object LosslessRegistry {
    const val API_KEY = "ak_8e3f1a7c2b5d9e4f0a6c3b8d1e5f2a9c7b4d0e6f" // Published community key.
    const val SESSION_BASE = "https://api.zarz.moe/v2"
    private const val GIST = "https://gist.githubusercontent.com/BartolomeoRusso9/ef9fdbbc894818aea89d25a8d99f8c77/raw"
    private val lock = Mutex()
    private var cached: JSONObject? = null
    private var nextRefresh = 0L

    suspend fun snapshot(): JSONObject? = lock.withLock {
        val now = System.nanoTime() / 1_000_000
        if (now < nextRefresh) return@withLock cached
        try {
            cached = decrypt(LosslessHttp.text(GIST))
            nextRefresh = now + 30 * 60_000
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Back off even on a cold-start failure; do not refetch for every provider.
            nextRefresh = now + 60_000
        }
        cached
    }

    fun endpoints(registry: JSONObject?, provider: String): List<String> {
        val entry = registry?.optJSONObject(provider)
        val remote = if (provider == "amazon") listOfNotNull(entry?.optString("antra")) else {
            val values = entry?.optJSONArray("stream")
            (0 until (values?.length() ?: 0)).map { values!!.optString(it) }
        }
        return (remote + "https://$provider.anandserver.cfd")
            .mapNotNull(::httpsUrl).map { it.trimEnd('/') }.distinct().take(3)
    }

    internal fun decrypt(text: String): JSONObject {
        val bytes = Base64.getDecoder().decode(text.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/'))
        require(bytes.size > 28)
        val key = MessageDigest.getInstance("SHA-256").digest("spotiflac:community:url:v1".toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("spotiflac|community|url|v1".toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
}
