package com.opentune.data.addons

import com.opentune.data.lossless.CatalogIdentity
import com.opentune.data.lossless.LosslessSource
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive

object AddonResolver {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun failed(addon: String, song: String) { failures["$addon:$song"] = System.nanoTime() / 1_000_000 + 120_000 }
    fun retry() { failures.clear() }

    /** First verified match wins, in the listener's order. Playback never waits on this search. */
    suspend fun resolve(song: Song, durationMs: Long, download: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {},
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): AddonStream? = withContext(Dispatchers.IO) {
        if (durationMs <= 0) return@withContext null
        val identity = CatalogIdentity(song.title, song.artist, null, durationMs)
        var best: AddonStream? = null
        val tier = if (bestAvailable) "HI_RES_LOSSLESS" else "LOSSLESS"
        for (addon in AddonStore.addons.value) {
            if (!addon.enabled) continue
            if ((failures["${addon.key}:${song.videoId}"] ?: 0) > System.nanoTime() / 1_000_000) { report("${addon.manifest.name}: recent playback failure; trying the next add-on"); continue }
            if (!AddonStore.usable(addon)) { report("${addon.manifest.name}: needs a supported lossless output"); continue }
            if (download && !addon.manifest.downloads) { report("${addon.manifest.name}: streaming only"); continue }
            var stage = "loading source manifest"
            var timedOut = true
            val result = withTimeoutOrNull(10_000) {
                try {
                    val client = clientFor(addon.base)
                    val manifest = client.manifest().let { if (addon.admin) it.copy(name = addon.manifest.name) else it }
                    AddonStore.refreshed(addon.key, manifest)
                    fun permitted(): Boolean = AddonStore.addons.value.firstOrNull { it.key == addon.key }?.let {
                        AddonStore.usable(it) && (!download || it.manifest.downloads)
                    } == true
                    if (!permitted()) return@withTimeoutOrNull null
                    stage = "searching catalogue"
                    val candidates = client.search("${song.title} ${song.artist}", manifest, tier)
                        .filter { identity.matches(it.title, listOf(it.artist), null, it.durationMs) }
                    if (candidates.isEmpty()) { report("${manifest.name}: no matching recording"); return@withTimeoutOrNull null }
                    for (track in candidates.take(3)) {
                        if (!permitted()) return@withTimeoutOrNull null
                        stage = "resolving audio"
                        val stream = try { client.stream(track.id, manifest, tier) }
                        catch (e: AddonHttp.Failure) {
                            if (e.status != 404 || track.streamUrl == null) throw e
                            AddonProtocol.stream(org.json.JSONObject().put("url", track.streamUrl).put("format", track.format).toString())
                        }
                        if (!stream.lossless) { report("${manifest.name}: returned compressed audio for a lossless request"); continue }
                        if (download && stream.transport != null) {
                            report("${manifest.name}: segmented stream; trying the next source for a downloadable file"); continue
                        }
                        if (!permitted()) return@withTimeoutOrNull null
                        stage = "checking audio header"
                        val checked = if (stream.transport == null && stream.codec == "flac") {
                            val info = LosslessSource.flacInfo(probe(stream.url, 65536, true))
                            if (info == null || !LosslessSource.durationMatches(durationMs, info.durationMs)) {
                                report("${manifest.name}: FLAC header or duration did not match"); continue
                            }
                            stream.copy(bits = info.bits, sampleRate = info.sampleRate)
                        } else {
                            val prefix = probe(stream.url, 65536, true)
                            if (stream.transport != null) {
                                val text = prefix.toString(Charsets.UTF_8)
                                val valid = if (stream.transport == "hls") text.trimStart().startsWith("#EXTM3U") && !Regex("#EXT-X-(?:SESSION-)?KEY:.*METHOD=(?!NONE)").containsMatchIn(text)
                                    else text.contains("<MPD") && !text.contains("ContentProtection", true)
                                if (!valid) { report("${manifest.name}: invalid or protected media manifest"); continue }
                            }
                            if (stream.transport == "hls") {
                                val text = prefix.toString(Charsets.UTF_8)
                                val initialization = Regex("#EXT-X-MAP:.*?URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                                val info = initialization?.let { relative ->
                                    val address = AddonProtocol.url(stream.url).resolve(relative)?.toString() ?: return@let null
                                    com.opentune.playback.LosslessAudioPolicy.mp4StreamInfo(probe(address, 65536, true))
                                }
                                if (info != null) stream.copy(bits = info.bits, sampleRate = info.rate)
                                else stream.copy(bits = null, sampleRate = null)
                            } else stream
                        }
                        report("${manifest.name}: ${if (stream.transport == null && stream.codec == "flac") "verified FLAC" else "lossless requested; decoder confirms quality during playback"}")
                        return@withTimeoutOrNull checked.copy(addonId = addon.key, provider = manifest.name)
                    }
                    null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report("${addon.manifest.name}: ${failureDescription(stage, e)}"); null }
                finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) timedOut = false }
            }
            if (timedOut) report("${addon.manifest.name}: timed out while $stage; try again")
            if (result != null) {
                if (!bestAvailable) return@withContext result
                if (best == null || qualityScore(result.bits, result.sampleRate) > qualityScore(best.bits, best.sampleRate)) best = result
            }
        }
        best
    }
    /** Unknown quality never outranks measured CD quality; ties preserve source priority. */
    fun qualityScore(bits: Int?, rate: Int?): Long = (bits ?: 0).toLong() * (rate ?: 0)
    /** Fixed diagnostics only: exceptions may contain private add-on URLs or tokens. */
    internal fun failureDescription(stage: String, error: Exception): String = when (error) {
        is FirstPartyAddonAuth.VerificationFailure -> error.message.orEmpty()
        is AddonHttp.Failure -> "$stage: HTTP ${error.status}" + when (error.status) {
            429 -> " · rate limited; retry in ${error.retryAfterSeconds.coerceAtLeast(1)}s"
            401, 403 -> " · access refused"
            else -> ""
        }
        is java.net.SocketTimeoutException -> "$stage: connection timed out"
        is java.net.UnknownHostException -> "$stage: could not resolve server; check DNS or connection"
        is javax.net.ssl.SSLException -> "$stage: secure connection failed"
        is org.json.JSONException -> "$stage: invalid JSON response"
        is java.io.IOException -> "$stage: network request failed"
        else -> "$stage: invalid source response"
    }
}
