package com.opentune.data.addons

import com.opentune.data.lossless.CatalogIdentity
import com.opentune.data.lossless.LosslessSource
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object AddonResolver {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun failed(addon: String, song: String) { failures["$addon:$song"] = System.nanoTime() / 1_000_000 + 120_000 }
    fun retry() { failures.clear() }

    /**
     * Every usable add-on is asked at once, each given [PER_ADDON_MS]; of the
     * verified matches that come back, the best quality wins, and between
     * equals the one higher in the listener's order. One slow, failing or
     * rate-limited add-on no longer holds up the rest, as it did when they
     * were asked one after another. Playback never waits on this search.
     */
    suspend fun resolve(song: Song, durationMs: Long, download: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {},
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): AddonStream? = withContext(Dispatchers.IO) {
        if (durationMs <= 0) return@withContext null
        val identity = CatalogIdentity(song.title, song.artist, null, durationMs)
        // The best each add-on has: hi-res where it offers it, else lossless.
        val tier = "HI_RES_LOSSLESS"
        suspend fun attempt(addon: AudioAddon): AddonStream? {
            if (!addon.enabled) return null
            if ((failures["${addon.key}:${song.videoId}"] ?: 0) > System.nanoTime() / 1_000_000) { report("${addon.manifest.name}: recent playback failure; skipped"); return null }
            if (!AddonStore.usable(addon)) { report("${addon.manifest.name}: needs a supported lossless output"); return null }
            if (download && !addon.manifest.downloads) { report("${addon.manifest.name}: streaming only"); return null }
            val started = System.nanoTime()
            val answer = withTimeoutOrNull(PER_ADDON_MS) {
                try {
                    val client = clientFor(addon.base)
                    val manifest = client.manifest().let { if (addon.admin) it.copy(name = addon.manifest.name) else it }
                    AddonStore.refreshed(addon.key, manifest)
                    fun permitted(): Boolean = AddonStore.addons.value.firstOrNull { it.key == addon.key }?.let {
                        AddonStore.usable(it) && (!download || it.manifest.downloads)
                    } == true
                    if (!permitted()) return@withTimeoutOrNull null
                    val candidates = client.search("${song.title} ${song.artist}", manifest, tier)
                        .filter { identity.matches(it.title, listOf(it.artist), null, it.durationMs) }
                    if (candidates.isEmpty()) { report("${manifest.name}: no matching recording"); return@withTimeoutOrNull null }
                    for (track in candidates.take(3)) {
                        if (!permitted()) return@withTimeoutOrNull null
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
                catch (e: AddonHttp.Failure) { report("${addon.manifest.name}: HTTP ${e.status}${if (e.status == 429) " · rate limited" else ""}"); null }
                catch (_: Exception) { report("${addon.manifest.name}: unavailable or invalid response"); null }
            }
            if (answer == null && System.nanoTime() - started >= PER_ADDON_MS * 1_000_000) report("${addon.manifest.name}: no answer in time")
            return answer
        }
        val found = kotlinx.coroutines.coroutineScope {
            AddonStore.addons.value.map { addon -> async { attempt(addon) } }.map { it.await() }
        }
        pickBest(found)
    }

    /** The best of [found] (in the listener's order, nulls for add-ons with nothing): highest quality, the earlier one on a tie. */
    internal fun pickBest(found: List<AddonStream?>): AddonStream? {
        var best: AddonStream? = null
        for (s in found) {
            if (s == null) continue
            if (best == null || qualityScore(s.bits, s.sampleRate) > qualityScore(best.bits, best.sampleRate)) best = s
        }
        return best
    }

    private const val PER_ADDON_MS = 8_000L
    /** Unknown quality never outranks measured CD quality; ties preserve source priority. */
    fun qualityScore(bits: Int?, rate: Int?): Long = (bits ?: 0).toLong() * (rate ?: 0)
}
