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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
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
    suspend fun resolve(song: Song, durationMs: Long, preferHiRes: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {}, timeoutMs: Long = 20_000): Track? = withContext(Dispatchers.IO) {
        if (!isYouTubeId(song.videoId)) return@withContext null
        val identity = CatalogIdentity(song.title, song.artist, song.albumName, durationMs)
        val linkedTidal = CompletableDeferred<String?>()
        searchSources(bestAvailable, timeoutMs = timeoutMs, providers = listOf(
            { record -> resolveCommunity(identity, linkedTidal, preferHiRes || bestAvailable, report)?.let(record) },
            { record -> QobuzCatalog.resolve(identity, preferHiRes || bestAvailable,
                AppSettings.playback.value.qobuzRelayUrl, bestAvailable = bestAvailable, onCandidate = record) },
            { record ->
                try { resolveMapped(song.videoId, durationMs, preferHiRes || bestAvailable,
                    bestAvailable = bestAvailable, onCandidate = record, report = report,
                    onTidalId = { linkedTidal.complete(it) }) }
                finally { linkedTidal.complete(null) }
            },
        ))
    }

    /** All routes get a chance; one slow route cannot discard a verified candidate. */
    internal suspend fun searchSources(bestAvailable: Boolean, timeoutMs: Long = 20_000,
        providers: List<suspend ((Track) -> Unit) -> Unit>): Track? {
        val best = AtomicReference<Track?>(null)
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val first = CompletableDeferred<Unit>()
                val record: (Track) -> Unit = { candidate ->
                    best.updateAndGet { better(it, candidate) }
                    first.complete(Unit)
                }
                val jobs = providers.map { provider -> launch {
                    try { provider(record) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* An unavailable route does not cancel the others. */ }
                } }
                if (bestAvailable) jobs.joinAll() else {
                    val completion = launch { jobs.joinAll(); first.complete(Unit) }
                    first.await()
                    jobs.forEach { it.cancel() }
                    completion.join()
                }
            }
        }
        return best.get()
    }

    private suspend fun resolveCommunity(identity: CatalogIdentity, linkedTidal: kotlinx.coroutines.Deferred<String?>,
        preferHiRes: Boolean, report: (String) -> Unit): Track? {
        if (SpotiflacSession.current() == null) {
            report(if (SpotiflacSession.needsVerification.value) "SpotiFLAC: verify again in Audio settings; the provider session needs renewing"
                else "SpotiFLAC: no verified provider session")
            return null
        }
        val registry = LosslessRegistry.snapshot()
        val endpoint = httpsUrl(registry?.optJSONObject("tidal")?.optString("community").orEmpty())
        if (endpoint == null) { report("SpotiFLAC: community endpoint unavailable"); return null }
        // Skip catalog work as well while the provider has asked clients to wait.
        try { SpotiflacGated.checkAvailable(endpoint) } catch (e: CommunityCooldowns.Paused) {
            report(communityFailureDetail(e)); return null
        }
        val id = try { TidalCatalog.withLinkedFallback(linkedTidal) { TidalCatalog.find(identity, registry) } }
            catch (_: TidalCatalog.Unavailable) {
                report("SpotiFLAC: catalog search incomplete because a provider was unavailable; no verified track ID found")
                return null
            }
        if (id == null) { report("SpotiFLAC: no exact catalog match for this recording"); return null }
        return try {
            var receivedUrl = false
            for (hiRes in if (preferHiRes) listOf(true, false) else listOf(false)) {
                val url = SpotiflacGated.tidalUrl(endpoint, id, hiRes) ?: continue
                receivedUrl = true
                val track = verify(url, "SpotiFLAC", identity.durationMs)
                if (track != null) return track
            }
            report(if (receivedUrl) "SpotiFLAC: returned audio was not a full matching FLAC stream"
                else "SpotiFLAC: provider returned no usable audio URL for this recording")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(communityFailureDetail(e))
            null
        }
    }

    /** Fixed messages deliberately exclude URLs, response bodies and session credentials. */
    internal fun communityFailureDetail(error: Exception): String = "SpotiFLAC: " + when (error) {
        is CommunityCooldowns.Paused -> {
            val seconds = (error.remainingMs + 999) / 1000
            val wait = if (seconds >= 60) "about ${(seconds + 59) / 60} min" else "$seconds sec"
            val reason = if (error.code == 429) "provider rate limit" else "provider temporarily unavailable"
            "$reason (HTTP ${error.code}); retry in $wait. Verification is still saved"
        }
        is LosslessHttp.HttpFailure -> if (error.code == 401 || error.code == 428) "session rejected; verify again in Audio settings"
            else "provider returned HTTP ${error.code}"
        is SpotiflacGated.InvalidResponse -> "provider returned an unreadable response"
        is java.net.UnknownHostException -> "provider hostname could not be resolved"
        is javax.net.ssl.SSLException -> "secure connection to the provider failed"
        is java.io.InterruptedIOException -> "provider request timed out"
        is java.io.IOException -> "network connection to the provider failed"
        else -> "provider response could not be processed"
    }

    internal fun better(current: Track?, candidate: Track): Track =
        if (current == null || compareValuesBy(candidate, current, { it.bits }, { it.sampleRate }) > 0) candidate else current

    private suspend fun resolveMapped(videoId: String, durationMs: Long, preferHiRes: Boolean,
        bestAvailable: Boolean = false, onCandidate: (Track) -> Unit = {}, report: (String) -> Unit = {}, onTidalId: (String?) -> Unit = {}): Track? =
        withContext(Dispatchers.IO) {
            if (!isYouTubeId(videoId)) return@withContext null
            withTimeoutOrNull(20_000) {
                val link = "https://api.song.link/v1-alpha.1/links".toHttpUrl().newBuilder()
                    .addQueryParameter("url", "https://music.youtube.com/watch?v=$videoId").build()
                val ids = attempt { providerIds(JSONObject(LosslessHttp.text(link.toString()))) }.orEmpty()
                onTidalId(ids["tidal"])
                if (ids.isEmpty()) {
                    report("Linked providers: song mapping unavailable; direct catalog searches still attempted")
                    return@withTimeoutOrNull null
                }
                val registry = LosslessRegistry.snapshot()
                suspend fun verified(url: String?, provider: String): Track? {
                    val track = verify(url, provider, durationMs) ?: return null
                    onCandidate(track)
                    return track.takeUnless { bestAvailable }
                }
                ids["tidal"]?.let { id ->
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

