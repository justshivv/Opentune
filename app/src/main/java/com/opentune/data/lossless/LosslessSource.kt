package com.opentune.data.lossless

import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64
import kotlin.math.abs

/**
 * Spotui's Tidal → Amazon → Qobuz chain adapted for YouTube Music identities.
 * Catalog matching also works when Odesli's public API is unavailable.
 * The FLAC header, precision and duration are checked before playback switches.
 */
object LosslessSource {
    data class Track(val url: String, val provider: String, val bits: Int, val sampleRate: Int)
    internal data class FlacInfo(val bits: Int, val sampleRate: Int, val durationMs: Long)
    private val keyHeader = mapOf("X-API-Key" to LosslessRegistry.API_KEY)
    @Volatile private var mappingRetryAt = 0L

    suspend fun resolve(song: Song, durationMs: Long, preferHiRes: Boolean = false, bestAvailable: Boolean = false): Track? =
        withContext(Dispatchers.IO) {
            if (!isYouTubeId(song.videoId)) return@withContext null
            val identity = CatalogIdentity(song.title, song.artist, song.albumName, durationMs)
            if (!bestAvailable) return@withContext withTimeoutOrNull(20_000) {
                QobuzCatalog.resolve(identity, preferHiRes, AppSettings.playback.value.qobuzRelayUrl)
                    ?: resolveMapped(song.videoId, durationMs, preferHiRes)
            }
            val best = AtomicReference<Track?>(null)
            val record: (Track) -> Unit = { candidate -> best.updateAndGet { better(it, candidate) } }
            // Keep an already verified candidate even if another provider exhausts the budget.
            withTimeoutOrNull(12_000) {
                coroutineScope {
                    launch { QobuzCatalog.resolve(identity, true, AppSettings.playback.value.qobuzRelayUrl, bestAvailable = true, onCandidate = record) }
                    launch { resolveMapped(song.videoId, durationMs, true, bestAvailable = true, onCandidate = record) }
                }
            }
            best.get()
        }

    internal fun better(current: Track?, candidate: Track): Track =
        if (current == null || compareValuesBy(candidate, current, { it.bits }, { it.sampleRate }) > 0) candidate else current

    private suspend fun resolveMapped(videoId: String, durationMs: Long, preferHiRes: Boolean,
        bestAvailable: Boolean = false, onCandidate: (Track) -> Unit = {}): Track? =
        withContext(Dispatchers.IO) {
            if (!isYouTubeId(videoId)) return@withContext null
            withTimeoutOrNull(20_000) {
                val link = "https://api.song.link/v1-alpha.1/links".toHttpUrl().newBuilder()
                    .addQueryParameter("url", "https://music.youtube.com/watch?v=$videoId").build()
                val ids = if (System.nanoTime() / 1_000_000 < mappingRetryAt) emptyMap() else {
                    attempt { providerIds(JSONObject(LosslessHttp.text(link.toString()))) }
                        ?: emptyMap<String, String>().also { mappingRetryAt = System.nanoTime() / 1_000_000 + 30 * 60_000 }
                }
                if (ids.isEmpty()) return@withTimeoutOrNull null
                val registry = LosslessRegistry.snapshot()
                suspend fun verified(url: String?, provider: String): Track? {
                    val track = verify(url, provider, durationMs) ?: return null
                    onCandidate(track)
                    return track.takeUnless { bestAvailable }
                }
                ids["tidal"]?.let { id ->
                    val gated = httpsUrl(registry?.optJSONObject("tidal")?.optString("community").orEmpty())
                    if (gated != null) {
                        val url = attempt { SpotiflacGated.tidalUrl(gated, id) }
                        verified(url, "SpotiFLAC")?.let { return@withTimeoutOrNull it }
                    }
                    for (endpoint in LosslessRegistry.endpoints(registry, "tidal")) {
                        val url = attempt {
                            tidalUrl(JSONObject(LosslessHttp.text("$endpoint/track/?id=$id&quality=LOSSLESS", keyHeader)))
                        }
                        verified(url, "Tidal")?.let { return@withTimeoutOrNull it }
                    }
                }
                ids["spotify"]?.let { id ->
                    for (endpoint in LosslessRegistry.endpoints(registry, "amazon")) {
                        val url = attempt {
                            directUrl(JSONObject(LosslessHttp.text("$endpoint/api/resolve/spotify/$id", keyHeader)))
                        }
                        verified(url, "Amazon")?.let { return@withTimeoutOrNull it }
                    }
                }
                ids["qobuz"]?.let { id ->
                    for (endpoint in LosslessRegistry.endpoints(registry, "qobuz")) {
                        for (quality in if (preferHiRes) listOf(27, 7, 6) else listOf(7, 6)) {
                            val url = attempt {
                                directUrl(JSONObject(LosslessHttp.text("$endpoint/api/track/$id?quality=$quality", keyHeader)))
                            }
                            verified(url, "Qobuz")?.let { return@withTimeoutOrNull it }
                        }
                    }
                }
                null
            }
        }

    internal suspend fun verify(url: String?, provider: String, durationMs: Long): Track? = attempt {
        val safeUrl = url?.let(::httpsUrl) ?: return@attempt null
        val request = Request.Builder().url(safeUrl).header("Range", "bytes=0-41").build()
        val info = flacInfo(LosslessHttp.bytes(request, 42, prefixOnly = true)) ?: return@attempt null
        if (!durationMatches(durationMs, info.durationMs)) return@attempt null
        Track(safeUrl, provider, info.bits, info.sampleRate)
    }

    internal fun providerIds(json: JSONObject): Map<String, String> {
        val links = json.optJSONObject("linksByPlatform") ?: return emptyMap()
        val entities = json.optJSONObject("entitiesByUniqueId")
        return listOf("tidal", "qobuz", "spotify").mapNotNull { provider ->
            val key = links.optJSONObject(provider)?.optString("entityUniqueId") ?: return@mapNotNull null
            val id = entities?.optJSONObject(key)?.optString("id")?.takeIf { it.isNotBlank() }
                ?: key.substringAfter("::", "")
            val pattern = if (provider == "spotify") Regex("[A-Za-z0-9]{22}") else Regex("[0-9]+")
            if (pattern.matches(id)) provider to id else null
        }.toMap()
    }

    internal fun tidalUrl(root: JSONObject): String? {
        val data = root.optJSONObject("data") ?: root
        if (encrypted(root) || encrypted(data)) return null
        val manifest = JSONObject(String(Base64.getDecoder().decode(data.optString("manifest")), Charsets.UTF_8))
        if (encrypted(manifest)) return null
        val encryption = manifest.optString("encryptionType")
        if (encryption.isNotBlank() && !encryption.equals("NONE", true)) return null
        val urls = manifest.optJSONArray("urls") ?: return null
        if (urls.length() != 1) return null // Segmented/DASH manifests need a different data source.
        return httpsUrl(urls.optString(0))
    }

    internal fun directUrl(root: JSONObject): String? {
        val data = root.optJSONObject("data") ?: root
        if (encrypted(root) || encrypted(data)) return null
        return listOf("url", "stream_url", "download_url", "streamUrl", "streaming_url")
            .firstNotNullOfOrNull { httpsUrl(data.optString(it)) }
    }

    private fun encrypted(json: JSONObject): Boolean =
        listOf("decryption_key", "decryptionKey", "keyId").any { json.has(it) && !json.isNull(it) } ||
            json.optBoolean("encrypted", false)

    /** STREAMINFO is the mandatory first FLAC metadata block (34 bytes). */
    internal fun flacInfo(bytes: ByteArray): FlacInfo? {
        if (bytes.size < 42 || String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        if (bytes[4].toInt() and 0x7f != 0 || bytes[5] != 0.toByte() || bytes[6] != 0.toByte() || bytes[7] != 34.toByte()) return null
        var packed = 0L
        for (i in 18..25) packed = (packed shl 8) or (bytes[i].toLong() and 0xff)
        val rate = (packed ushr 44).toInt()
        val bits = ((packed ushr 36) and 31).toInt() + 1
        val samples = packed and 0xfffffffffL
        // Lossless voice-rate audio is not a music quality upgrade.
        if (rate !in 44_100..384_000 || bits !in 16..32 || samples == 0L) return null
        return FlacInfo(bits, rate, samples * 1000 / rate)
    }

    internal fun durationMatches(expectedMs: Long, actualMs: Long): Boolean =
        expectedMs > 0 && abs(expectedMs - actualMs) <= 3_000

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

internal fun httpsUrl(value: String): String? = value.toHttpUrlOrNull()
    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
