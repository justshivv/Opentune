package com.opentune.data.addons

import com.opentune.data.lossless.CatalogIdentity
import com.opentune.data.lossless.LosslessSource
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object AddonResolver {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun failed(addon: String, song: String) { failures["$addon:$song"] = System.nanoTime() / 1_000_000 + 120_000 }
    fun retry() { failures.clear() }

    /**
     * Every usable add-on is asked at once; of the verified matches that come
     * back, the best quality wins, and between equals the one higher in the
     * listener's order. Nothing is cut off for being slow: once one add-on
     * has a verified match, the others get [GRACE_MS] more and the best so
     * far is returned, while any still working carry on in the background
     * (up to [ADDON_LIMIT_MS], a guard against a hung connection). A later
     * answer of higher quality than the one returned goes to [late], so it
     * can still be switched to. With no match yet, it waits for them all.
     */
    suspend fun resolve(song: Song, durationMs: Long, download: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {},
        late: (AddonStream) -> Unit = {},
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): AddonStream? =
        resolveAll(song, durationMs, download, bestAvailable, report, late, clientFor, probe).firstOrNull()

    /**
     * Every verified match [resolve] found, best first (ties in the
     * listener's order): when the best one's stream won't load, the next
     * can be tried rather than giving up on lossless.
     */
    suspend fun resolveAll(song: Song, durationMs: Long, download: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {},
        late: (AddonStream) -> Unit = {},
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): List<AddonStream> = withContext(Dispatchers.IO) {
        if (durationMs <= 0) return@withContext emptyList()
        val identity = CatalogIdentity(song.title, song.artist, null, durationMs)
        // The best each add-on has: hi-res where it offers it, else lossless.
        val tier = "HI_RES_LOSSLESS"
        suspend fun attempt(addon: AudioAddon): AddonStream? {
            if (!addon.enabled) return null
            if ((failures["${addon.key}:${song.videoId}"] ?: 0) > System.nanoTime() / 1_000_000) { report("${addon.manifest.name}: recent playback failure; skipped"); return null }
            if (!AddonStore.usable(addon)) { report("${addon.manifest.name}: needs a supported lossless output"); return null }
            if (download && !addon.manifest.downloads) { report("${addon.manifest.name}: streaming only"); return null }
            val started = System.nanoTime()
            val answer = withTimeoutOrNull(ADDON_LIMIT_MS) {
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
            if (answer == null && System.nanoTime() - started >= ADDON_LIMIT_MS * 1_000_000) report("${addon.manifest.name}: no answer in ${ADDON_LIMIT_MS / 1_000} s")
            return answer
        }
        val addons = AddonStore.addons.value
        if (addons.isEmpty()) return@withContext emptyList()
        // The add-ons run here, apart from the caller, so returning early cancels none of them.
        val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val answers = kotlinx.coroutines.channels.Channel<Pair<Int, AddonStream?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        addons.forEachIndexed { i, addon ->
            work.launch {
                val stream = try { attempt(addon) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                answers.send(i to stream)
            }
        }
        val found = arrayOfNulls<AddonStream>(addons.size)
        var received = 0
        var firstMatch = 0L
        try {
            while (received < addons.size) {
                val wait = if (firstMatch == 0L) Long.MAX_VALUE else GRACE_MS - (System.nanoTime() - firstMatch) / 1_000_000
                if (wait <= 0) break
                val (i, stream) = withTimeoutOrNull(wait) { answers.receive() } ?: break
                received++
                found[i] = stream
                if (stream != null && firstMatch == 0L) firstMatch = System.nanoTime()
            }
        } catch (e: CancellationException) {
            work.cancel()
            throw e
        }
        val best = pickBest(found.toList())
        if (received == addons.size) {
            work.cancel()
        } else {
            // The slower ones finish on their own; a better match among them is passed on.
            work.launch {
                var top = best
                repeat(addons.size - received) {
                    val (i, stream) = answers.receive()
                    found[i] = stream
                    val now = pickBest(found.toList())
                    if (stream != null && now === stream && (top == null || qualityScore(stream.bits, stream.sampleRate) > qualityScore(top!!.bits, top!!.sampleRate))) {
                        top = stream
                        late(stream)
                    }
                }
                work.cancel()
            }
        }
        ranked(found.toList())
    }

    /** [found]'s streams, best quality first, the earlier in the listener's order on a tie. */
    internal fun ranked(found: List<AddonStream?>): List<AddonStream> =
        found.filterNotNull().sortedByDescending { qualityScore(it.bits, it.sampleRate) }

    /** The best of [found] (in the listener's order, nulls for add-ons with nothing): highest quality, the earlier one on a tie. */
    internal fun pickBest(found: List<AddonStream?>): AddonStream? {
        var best: AddonStream? = null
        for (s in found) {
            if (s == null) continue
            if (best == null || qualityScore(s.bits, s.sampleRate) > qualityScore(best.bits, best.sampleRate)) best = s
        }
        return best
    }

    /** How long the other add-ons get once one has a verified match, before the best so far is used. */
    private const val GRACE_MS = 2_500L
    /** Only a guard against a connection that never answers; a slow add-on is waited for. */
    private const val ADDON_LIMIT_MS = 45_000L
    /** Unknown quality never outranks measured CD quality; ties preserve source priority. */
    fun qualityScore(bits: Int?, rate: Int?): Long = (bits ?: 0).toLong() * (rate ?: 0)
}
