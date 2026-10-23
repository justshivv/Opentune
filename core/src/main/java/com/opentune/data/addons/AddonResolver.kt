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
    /**
     * Holds on an add-on, each for one song only ("addon:song"): the next song asks it
     * afresh. A file whose playback failed is left a short while, then asked for anew
     * (its link, not the cached one); an add-on that answered HTTP 429 waits out the
     * time it gave.
     */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val staleLinks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val limitedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun now() = System.nanoTime() / 1_000_000
    fun failed(addon: String, song: String) {
        if (failures.size > 256) failures.clear()
        failures["$addon:$song"] = now() + FAILED_HOLD_MS
        staleLinks += "$addon:$song"
    }
    fun retry() { failures.clear() }
    private fun limit(addon: String, song: String, e: AddonHttp.Failure) {
        if (limitedUntil.size > 256) limitedUntil.clear()
        limitedUntil["$addon:$song"] = now() + maxOf(e.retryAfterSeconds * 1_000, RATE_LIMIT_MS)
    }

    /**
     * A new link to the file [addon] verified for [song]: its stream asked for again by
     * [trackId], with no search and no new check of the recording. For a link that
     * stopped working (expired, say) while the song played. Null when it has none.
     */
    suspend fun freshLink(addon: String, trackId: String, tier: String, song: String,
        clientFor: (String) -> AddonClient = AddonClient::forBase): String? {
        val source = AddonStore.addons.value.firstOrNull { it.key == addon && it.enabled && AddonStore.usable(it) } ?: return null
        if ((limitedUntil["$addon:$song"] ?: 0) > now()) return null
        return try {
            clientFor(source.base).stream(trackId, source.manifest, tier.ifEmpty { "HI_RES_LOSSLESS" }, fresh = true)
                .takeIf { it.lossless }?.url
        } catch (e: CancellationException) { throw e }
        catch (e: AddonHttp.Failure) { if (e.status == 429) limit(addon, song, e); null }
        catch (_: Exception) { null }
    }

    /**
     * Every usable add-on is asked at once; of the verified matches that come
     * back, the best quality wins, and between equals the one higher in the
     * listener's order. Nothing is cut off for being slow: once one add-on
     * has a verified match, the others get [GRACE_MS] more and the best so
     * far is returned, while any still working carry on in the background
     * (up to [ADDON_LIMIT_MS], a guard against a hung connection). A later
     * answer of higher quality than the one returned goes to [late], so it
     * can still be switched to. With no match yet, it waits for them all.
     * [skip] names add-ons already heard from for this song, left out; each one
     * asked is told to [settled] once it's done, with whether it erred (an HTTP
     * error, no answer, a broken reply) rather than answered.
     */
    suspend fun resolve(song: Song, durationMs: Long, download: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {},
        late: (AddonStream) -> Unit = {},
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): AddonStream? =
        resolveAll(song, durationMs, download, bestAvailable, report, late, clientFor = clientFor, probe = probe).firstOrNull()

    /**
     * Every verified match [resolve] found, best first (ties in the
     * listener's order): when the best one's stream won't load, the next
     * can be tried rather than giving up on lossless.
     */
    suspend fun resolveAll(song: Song, durationMs: Long, download: Boolean = false, bestAvailable: Boolean = false,
        report: (String) -> Unit = {},
        late: (AddonStream) -> Unit = {},
        skip: Set<String> = emptySet(),
        settled: (addon: String, erred: Boolean) -> Unit = { _, _ -> },
        clientFor: (String) -> AddonClient = AddonClient::forBase,
        probe: suspend (String, Int, Boolean) -> ByteArray = AddonHttp::bytes): List<AddonStream> = withContext(Dispatchers.IO) {
        if (durationMs <= 0) return@withContext emptyList()
        val identity = CatalogIdentity(song.title, song.artist, null, durationMs)
        // The best each add-on has: hi-res where it offers it, else lossless.
        val tier = "HI_RES_LOSSLESS"
        /** [outcome]: asked over the network at all, and erred rather than answered. */
        suspend fun attempt(addon: AudioAddon, outcome: BooleanArray): AddonStream? {
            if (!addon.enabled) return null
            if ((failures["${addon.key}:${song.videoId}"] ?: 0) > now()) {
                // Not a no: asked again once the hold is over.
                outcome[0] = true; outcome[1] = true
                report("${addon.manifest.name}: its file failed moments ago; asked again shortly"); return null
            }
            if (!AddonStore.usable(addon)) { report("${addon.manifest.name}: only used with wired headphones, a USB DAC or HDMI, as its owner asks"); return null }
            if (download && !addon.manifest.downloads) { report("${addon.manifest.name}: streaming only"); return null }
            val wait = (limitedUntil["${addon.key}:${song.videoId}"] ?: 0) - now()
            if (wait > 0) {
                // Still an error to come back to, but nothing is sent until the add-on said it may be.
                outcome[0] = true; outcome[1] = true
                report("${addon.manifest.name}: rate limited; asked again in ${(wait + 999) / 1_000} s"); return null
            }
            var stage = "loading source manifest"
            val started = System.nanoTime()
            outcome[0] = true
            val answer = withTimeoutOrNull(ADDON_LIMIT_MS) {
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
                        .filter { identity.matches(it.title, listOf(it.artist), it.album, it.durationMs) }
                    if (candidates.isEmpty()) { report("${manifest.name}: no matching recording"); return@withTimeoutOrNull null }
                    // Its last link for this song broke: a new one, not the cached one.
                    val fresh = staleLinks.remove("${addon.key}:${song.videoId}")
                    for (track in candidates.take(3)) {
                        if (!permitted()) return@withTimeoutOrNull null
                        stage = "resolving audio"
                        var asked = tier
                        var stream = try { client.stream(track.id, manifest, tier, fresh) }
                        catch (e: AddonHttp.Failure) {
                            if (e.status != 404 || track.streamUrl == null) throw e
                            AddonProtocol.stream(org.json.JSONObject().put("url", track.streamUrl).put("format", track.format).toString())
                        }
                        // An add-on with no hi-res setting can take that ask as its first (lossy) quality:
                        // asked once more for plain lossless before it's passed over.
                        if (!stream.lossless && tier != "LOSSLESS") {
                            val plain = try { client.stream(track.id, manifest, "LOSSLESS", fresh) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (plain?.lossless == true) { stream = plain; asked = "LOSSLESS" }
                        }
                        if (!stream.lossless) { report("${manifest.name}: has this song only in compressed quality, not lossless"); continue }
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
                        return@withTimeoutOrNull checked.copy(addonId = addon.key, provider = manifest.name,
                            trackId = if (track.streamUrl != null && stream.url == track.streamUrl) "" else track.id, tier = asked)
                    }
                    null
                } catch (e: CancellationException) { throw e }
                catch (e: AddonHttp.Failure) {
                    outcome[1] = true
                    if (e.status == 429) limit(addon.key, song.videoId, e)
                    report("${addon.manifest.name}: ${failureDescription(stage, e)}"); null
                }
                catch (e: Exception) { outcome[1] = true; report("${addon.manifest.name}: ${failureDescription(stage, e)}"); null }
            }
            if (answer == null && System.nanoTime() - started >= ADDON_LIMIT_MS * 1_000_000) { outcome[1] = true; report("${addon.manifest.name}: timed out while $stage after ${ADDON_LIMIT_MS / 1_000} s") }
            return answer
        }
        val addons = AddonStore.addons.value.filterNot { it.key in skip }
        if (addons.isEmpty()) return@withContext emptyList()
        // The add-ons run here, apart from the caller, so returning early cancels none of them.
        val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val answers = kotlinx.coroutines.channels.Channel<Pair<Int, AddonStream?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        addons.forEachIndexed { i, addon ->
            work.launch {
                val outcome = BooleanArray(2)
                val stream = try { attempt(addon, outcome) } catch (e: CancellationException) { throw e } catch (_: Exception) { outcome[1] = outcome[0]; null }
                if (outcome[0]) settled(addon.key, outcome[1])
                answers.send(i to stream)
            }
        }
        val found = arrayOfNulls<AddonStream>(addons.size)
        var received = 0
        var firstMatch = 0L
        // Playing: the first verified file is used at once and the rest follow through [late],
        // so a song never waits on the slower add-ons to start sounding lossless. A download
        // gives them a moment, as it keeps whichever file it takes.
        val grace = if (download) GRACE_MS else FIRST_GRACE_MS
        try {
            while (received < addons.size) {
                val wait = if (firstMatch == 0L) Long.MAX_VALUE else grace - (System.nanoTime() - firstMatch) / 1_000_000
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
            // The slower ones finish on their own; every verified file among them is passed on,
            // to switch to when it's better and to fall back on when it isn't.
            work.launch {
                repeat(addons.size - received) {
                    val (_, stream) = answers.receive()
                    if (stream != null) late(stream)
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

    /** How long the other add-ons get once one has a verified match, before the best so far is used, for a download. */
    private const val GRACE_MS = 2_500L
    /** The same for playing: only enough to take answers that came in together. */
    private const val FIRST_GRACE_MS = 250L
    /** The least an add-on that answered HTTP 429 is left alone for, when it doesn't say. */
    private const val RATE_LIMIT_MS = 30_000L
    /** How long an add-on whose file failed in playback is left for that song before it's asked again. */
    private const val FAILED_HOLD_MS = 20_000L
    /** Only a guard against a connection that never answers; a slow add-on is waited for. */
    private const val ADDON_LIMIT_MS = 45_000L
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
