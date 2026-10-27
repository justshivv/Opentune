package com.opentune.data.lossless

import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.model.Song
import com.opentune.data.innertube.StreamResolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async

/** Pins each external rendition to a unique cache key; expired URLs never become YouTube bytes. */
object ExternalStreams {
    data class Rendition(val key: String, val url: String, val label: String, val lossless: Boolean, val at: Long,
        val provider: String = label, val bits: Int? = null, val sampleRate: Int? = null, val kbps: Int? = null,
        val maximumSearch: Boolean = false, val sessionRevision: Long = 0,
        val addonId: String? = null, val mimeType: String? = null,
        /** Picked by hand in Search: played whatever the quality settings say. */
        val picked: Boolean = false,
        /** The add-on's track id and tier, to ask it for a new link to this same file (see [refreshLink]). */
        val trackId: String? = null, val tier: String? = null)
    private val renditions = ConcurrentHashMap<String, Rendition>()
    private val pinned = ConcurrentHashMap<String, Pair<String, Rendition>>()
    /**
     * Songs the FLAC search, or JioSaavn, recently came back from with a
     * definite no (no matching recording), each kept apart: a FLAC miss
     * never stops JioSaavn being asked, nor the other way round. A search
     * that missed only because servers erred (an HTTP 404 or 502, no answer)
     * isn't remembered at all, and goes in [transientMiss] to be tried again.
     */
    private val losslessMissUntil = ConcurrentHashMap<String, Long>()
    private val saavnMissUntil = ConcurrentHashMap<String, Long>()
    private val transientMiss = ConcurrentHashMap.newKeySet<String>()
    private val reports = ConcurrentHashMap<String, String>()
    /** Songs whose FLAC search ran to the end since the add-ons last changed, found or not. */
    private val losslessSearched = ConcurrentHashMap.newKeySet<String>()
    private const val TTL = 5 * 60_000L

    /**
     * JioSaavn's own answer from its quick lookup at song start, kept apart from
     * [reports]: the FLAC search and that lookup run side by side, and one
     * writing over the other left the pipeline showing only JioSaavn's line.
     */
    private val saavnReports = ConcurrentHashMap<String, String>()

    /** What the add-ons and JioSaavn each said for [videoId], add-ons first, each line once. */
    fun selectionDetail(videoId: String): String? =
        ((reports[videoId]?.lines().orEmpty()) + (saavnReports[videoId]?.lines().orEmpty()))
            .distinct().joinToString("\n").ifEmpty { null }

    /** Instant startup lookup only; a miss must never start network work. */
    fun cached(videoId: String): Rendition? {
        val now = now()
        return renditions[videoId]?.takeIf {
            now - it.at < TTL && (it.picked || allowed(it.lossless)) &&
                (it.addonId == null || (it.sessionRevision == com.opentune.data.addons.AddonStore.revision.value && addonAllowed(it)))
        }
    }

    /** JioSaavn has its own fast path; never wait for a FLAC search before it. */
    /** Fires with a video id when a better stream for it turned up after the search had already answered. */
    val improved: kotlinx.coroutines.flow.SharedFlow<String> get() = _improved
    private val _improved = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Lookups that outlive whoever asked: a caller that stops waiting doesn't throw the answer away. */
    internal val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val saavnInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Rendition?>>()

    /**
     * JioSaavn's stream for [song]. One lookup per song runs at a time, apart
     * from the callers: song start waits for it only briefly, and when it
     * answers later the result is kept for the upgrade, which then gets it
     * at once instead of asking again.
     */
    suspend fun findJioSaavn(song: Song, durationMs: Long): Rendition? {
        if (!allowed(false) || (saavnMissUntil[song.videoId] ?: 0) > now()) return null
        cached(song.videoId)?.takeIf { it.mimeType == null }?.let { return it }
        val lookup = saavnInFlight.computeIfAbsent(song.videoId) {
            background.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { lookupJioSaavn(song, durationMs) }
        }
        lookup.start()
        return try { lookup.await() } finally { if (lookup.isCompleted) saavnInFlight.remove(song.videoId, lookup) }
    }

    private suspend fun lookupJioSaavn(song: Song, durationMs: Long): Rendition? {
        val duration = CatalogMetadata.duration(song, durationMs)
        if (duration <= 0) return null
        val url = JioSaavnSource.resolve(song, duration, report = { reportSaavn(song.videoId, it) }) ?: return null
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), url, "JioSaavn · 320 kbps (lossy)", false, now(),
            provider = "JioSaavn", kbps = 320, sessionRevision = com.opentune.data.addons.AddonStore.revision.value)
        // Keep this lossy fallback address, but never overwrite a FLAC which
        // answered while JioSaavn was still searching. Decide atomically.
        remember(song.videoId, rendition, preserveLossless = true)
        return rendition
    }

    internal fun reportSaavn(videoId: String, detail: String) {
        if (saavnReports.size > 256) saavnReports.clear()
        if (saavnReports.put(videoId, detail) != detail) com.opentune.data.DebugLog.d("ExternalStreams", "$videoId: ${detail.replace("\n", " | ")}")
    }

    internal fun report(videoId: String, detail: String) {
        if (reports.size > 256) reports.clear()
        // Into the exported log too, so a shared log says what each source answered.
        if (reports.put(videoId, detail) != detail) com.opentune.data.DebugLog.d("ExternalStreams", "$videoId: ${detail.replace("\n", " | ")}")
    }

    /**
     * The picked stream was found but playback couldn't switch to it: said so
     * under what was picked, so the pipeline doesn't read as if it's playing.
     */
    fun swapFailed(videoId: String, why: List<String> = emptyList()) {
        val picked = reports[videoId]?.substringBefore("\nCouldn't switch") ?: return
        val reasons = if (why.isEmpty()) "" else why.joinToString("\n", prefix = "\n")
        report(videoId, "$picked\nCouldn't switch to it: its stream didn't load after a few tries.$reasons\nThe current stream keeps playing; Upgrade quality tries again.")
    }

    /** The switch went through after all (a later try, or a better file): the note that it hadn't is taken off. */
    fun swapSucceeded(videoId: String) {
        val detail = reports[videoId] ?: return
        if ("\nCouldn't switch" in detail) report(videoId, detail.substringBefore("\nCouldn't switch"))
    }

    fun allowed(lossless: Boolean? = null): Boolean = AppSettings.playback.value.let {
        val maximum = AppSettings.effectiveAudioQuality == AudioQuality.MAX
        val enabled = when (lossless) { true -> maximum || it.losslessStreaming; false -> it.jioSaavnQuality; null -> maximum || it.losslessStreaming || it.jioSaavnQuality }
        enabled && (lossless != false || AppSettings.effectiveAudioQuality.maxKbps >= 320) &&
            (!it.losslessUnmeteredOnly || !AppSettings.onMeteredNetwork())
    }

    private val losslessInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Rendition?>>()

    /**
     * The best external stream for [song]. A FLAC search already running for it (one readying
     * the song ahead, say) is joined rather than started again, so a song is never searched
     * twice at once and the add-ons aren't asked the same thing over and over.
     */
    suspend fun find(song: Song, durationMs: Long, beforePlayback: Boolean = false, force: Boolean = false, losslessOnly: Boolean = false): Rendition? {
        // A verified candidate can play now even if a background retry is still
        // looking for better sources. That lookup continues and publishes upgrades.
        if (losslessOnly && !force) cached(song.videoId)?.takeIf { it.lossless }?.let { return it }
        if (!losslessOnly || force) return findNow(song, durationMs, beforePlayback, force, losslessOnly)
        return joined(song) { findNow(song, durationMs, beforePlayback, false, true) }
    }

    /**
     * Asks again only the add-ons that erred in [song]'s FLAC search (an HTTP 502, no
     * answer), leaving alone those that answered. Whatever they verify is kept to fall
     * back on and, when better than the file chosen, made the choice and announced on
     * [improved], so playback moves onto it in the background. False when none erred.
     */
    suspend fun retryErred(song: Song, durationMs: Long): Boolean {
        if (!hasErred(song.videoId)) return false
        joined(song) { findNow(song, durationMs, beforePlayback = false, force = false, losslessOnly = true, erredOnly = true) }
        return true
    }

    /** Whether [rendition] was verified (its file's start read and checked) moments ago, so its server is known to answer. */
    fun verifiedRecently(rendition: Rendition): Boolean = now() - rendition.at < VERIFIED_FRESH_MS

    /** Whether some add-on erred, rather than answered, in [videoId]'s FLAC search, and so is worth asking again. */
    fun hasErred(videoId: String): Boolean = heardFor(videoId)?.erred?.isNotEmpty() == true

    private suspend fun joined(song: Song, search: suspend () -> Rendition?): Rendition? {
        val lookup = losslessInFlight.computeIfAbsent(song.videoId) {
            background.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { search() }
                // Gone once it's answered, whoever was waiting: a finished search is never handed out again.
                .also { d -> d.invokeOnCompletion { losslessInFlight.remove(song.videoId, d) } }
        }
        lookup.start()
        return lookup.await()
    }

    /**
     * What each add-on said in a song's FLAC search, so a later search for it asks only
     * the ones that erred: [settled] answered (a verified file, kept in [alternatives], or
     * a definite no), [erred] didn't. [lines] are their lines for the pipeline report.
     */
    private class Heard(val revision: Long, val at: Long, val settled: Set<String>, val erred: Set<String>, val lines: List<String>)
    private val heard = ConcurrentHashMap<String, Heard>()

    private fun heardFor(videoId: String): Heard? = heard[videoId]?.takeIf {
        it.revision == com.opentune.data.addons.AddonStore.revision.value && now() - it.at < TTL
    }

    /** The report lines that are an add-on's own ("Name: verified FLAC"), not the search's summary. */
    private fun addonLines(lines: List<String>): List<String> {
        val names = com.opentune.data.addons.AddonStore.addons.value.map { "${it.manifest.name}:" }
        return lines.filter { line -> names.any { line.startsWith(it) } }
    }

    private suspend fun findNow(song: Song, durationMs: Long, beforePlayback: Boolean, force: Boolean, losslessOnly: Boolean,
        erredOnly: Boolean = false): Rendition? {
        val videoId = song.videoId
        if (!AppSettings.isOnline()) {
            report(videoId, "Offline: source lookup resumes when a connection is available.")
            return null
        }
        if (!allowed()) {
            report(videoId, if (AppSettings.playback.value.losslessUnmeteredOnly && AppSettings.onMeteredNetwork())
                "External sources skipped: Unmetered networks only is on. Turn it off in Settings to try FLAC and JioSaavn on mobile data."
                else "External sources disabled for the current quality settings.")
            return null
        }
        val now = now()
        if (force) { losslessMissUntil.remove(videoId); saavnMissUntil.remove(videoId) }
        val losslessMissed = (losslessMissUntil[videoId] ?: 0) > now
        val saavnMissed = (saavnMissUntil[videoId] ?: 0) > now
        if (losslessMissed && (losslessOnly || saavnMissed)) return null
        renditions[videoId]?.takeIf {
            !force && !erredOnly && (!losslessOnly || it.lossless) && now - it.at < TTL && (it.picked || allowed(it.lossless)) &&
                (it.addonId == null || (it.sessionRevision == com.opentune.data.addons.AddonStore.revision.value && addonAllowed(it))) && (!beforePlayback || it.maximumSearch) &&
                (it.lossless || beforePlayback || StreamResolver.worthExternalUpgrade(videoId, 320))
        }?.let { return it }
        val duration = CatalogMetadata.duration(song, durationMs)
        if (duration <= 0) {
            report(videoId, "External sources skipped: this track's duration could not be verified.")
            return null
        }
        val detail = java.util.concurrent.CopyOnWriteArrayList<String>()
        // The add-ons already heard from for this song aren't asked again: their verified files
        // are still on record, and a "no matching recording" doesn't change in a few minutes.
        val prior = if (force) null else heardFor(videoId)
        if (erredOnly && prior?.erred.isNullOrEmpty()) return null
        val revision = com.opentune.data.addons.AddonStore.revision.value
        val skip = prior?.settled.orEmpty()
        val settledNow = ConcurrentHashMap.newKeySet<String>().apply { addAll(skip) }
        val erredNow = ConcurrentHashMap.newKeySet<String>()
        if (prior != null) {
            val asked = com.opentune.data.addons.AddonStore.addons.value.filter { it.key !in skip }.map { "${it.manifest.name}:" }
            detail += prior.lines.filterNot { line -> asked.any { line.startsWith(it) } }
        }
        // Dated from the song's first search, so it never outlasts the files it vouches for.
        val heardAt = prior?.at ?: now()
        fun noteHeard(addon: String, erred: Boolean) = synchronized(settledNow) {
            if (erred) { erredNow += addon; settledNow -= addon } else { settledNow += addon; erredNow -= addon }
            if (heard.size > 256) heard.clear()
            heard[videoId] = Heard(revision, heardAt, settledNow.toSet(), erredNow.toSet(), addonLines(detail))
        }
        fun losslessRendition(track: LosslessSource.Track) = Rendition(java.util.UUID.randomUUID().toString(), track.url,
            "${track.provider} · lossless requested", true, now(), provider = track.provider, bits = track.bits, sampleRate = track.sampleRate,
            maximumSearch = beforePlayback, sessionRevision = com.opentune.data.addons.AddonStore.revision.value, addonId = track.addonId, mimeType = track.mimeType,
            trackId = track.trackId.ifEmpty { null }, tier = track.tier.ifEmpty { null })
        val tracks = if (allowed(true) && !losslessMissed) LosslessSource.resolveAll(song, duration,
            beforePlayback || AppSettings.playback.value.losslessHiRes, bestAvailable = beforePlayback, report = { line ->
                synchronized(detail) {
                    val prefix = line.substringBefore(":", "")
                    if (prefix.isNotEmpty()) detail.removeAll { it.startsWith("$prefix:") }
                    detail.addIfAbsent(line)
                    report(videoId, detail.joinToString("\n"))
                }
            },
            skip = skip, settled = ::noteHeard,
            late = { later ->
                // A slower add-on's verified file: kept to fall back on, and, when it's better than
                // the one chosen (or nothing was), made the choice and said, so playback switches to it.
                if (allowed(true)) {
                    val r = losslessRendition(later)
                    addAlternative(videoId, r)
                    losslessMissUntil.remove(videoId)
                    transientMiss.remove(videoId)
                    val chosen = renditions[videoId]?.takeIf { it.lossless }
                    fun score(x: Rendition) = com.opentune.data.addons.AddonResolver.qualityScore(x.bits, x.sampleRate)
                    if (chosen == null || score(r) > score(chosen)) {
                        remember(videoId, r)
                        report(videoId, (reports[videoId]?.let { "$it\n" } ?: "") + "${later.provider}: answered later with higher quality")
                        _improved.tryEmit(videoId)
                    }
                }
            }) else emptyList()
        fun score(x: Rendition) = com.opentune.data.addons.AddonResolver.qualityScore(x.bits, x.sampleRate)
        // Files verified earlier in this song's search, from the add-ons not asked this time; or,
        // when everyone was asked again, the verified file already held for it, which an add-on
        // erring or rate limiting this time says nothing against.
        val held = renditions[videoId]?.takeIf { it.lossless && now() - it.at < TTL && allowed(true) && addonAllowed(it) }
        val known = (if (prior != null) alternatives(videoId) else emptyList()).ifEmpty { listOfNotNull(held) }
        val fresh = tracks.firstOrNull()
        val earlier = known.firstOrNull()?.takeIf { e -> fresh == null || score(e) >= com.opentune.data.addons.AddonResolver.qualityScore(fresh.bits, fresh.sampleRate) }
        val track = fresh.takeIf { earlier == null }
        if (allowed(true) && !losslessMissed) {
            if (losslessSearched.size > 512) losslessSearched.clear()
            losslessSearched += videoId
        }
        if (earlier != null && earlier === held && fresh == null) detail += "${earlier.provider}: verified FLAC, found earlier and available for playback"
        if (allowed(true)) detail += if (track == null && earlier == null) "FLAC: no verified matching stream available" else "Verified FLAC available; the track info above shows the source currently playing"
        val askedSaavn = !losslessOnly && track == null && earlier == null && allowed(false) && !saavnMissed && (beforePlayback || StreamResolver.worthExternalUpgrade(videoId, 320))
        val saavn = if (askedSaavn) JioSaavnSource.resolve(song, duration, report = { detail += it }) else null
        report(videoId, detail.joinToString("\n").ifEmpty { "No external source eligible for this quality setting." })
        if (track == null && earlier == null && allowed(true) && !losslessMissed) {
            // Servers that erred or didn't answer say nothing about whether the song is there.
            val erred = erredNow.isNotEmpty() || detail.any { line -> TRANSIENT.any { it in line } }
            if (erred) transientMiss += videoId
            else { transientMiss.remove(videoId); remember(losslessMissUntil, videoId) }
        } else if (track != null || earlier != null) transientMiss.remove(videoId)
        if (askedSaavn && saavn == null) remember(saavnMissUntil, videoId)
        // Only re-asked add-ons came back, and what they had is no better than the file on record.
        if (earlier != null) {
            tracks.forEach { addAlternative(videoId, losslessRendition(it)) }
            if (renditions[videoId]?.lossless != true) remember(videoId, earlier)
            return renditions[videoId]?.takeIf { it.lossless } ?: earlier
        }
        if (track == null && saavn == null) return null
        // Even two FLACs of the same recording can have different byte offsets.
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), track?.url ?: saavn!!,
            track?.let { "${it.provider} · lossless requested" } ?: "JioSaavn · 320 kbps (lossy)", track != null, now(),
            provider = track?.provider ?: "JioSaavn", bits = track?.bits, sampleRate = track?.sampleRate,
            kbps = if (track == null) 320 else null, maximumSearch = beforePlayback, sessionRevision = com.opentune.data.addons.AddonStore.revision.value, addonId = track?.addonId, mimeType = track?.mimeType,
            trackId = track?.trackId?.ifEmpty { null }, tier = track?.tier?.ifEmpty { null })
        remember(videoId, rendition)
        // The other verified files of this recording, to fall back on if this one won't load.
        if (track != null) {
            if (prior == null) alternatives[videoId] = listOf(rendition) else addAlternative(videoId, rendition)
            tracks.drop(1).forEach { addAlternative(videoId, losslessRendition(it)) }
            // A re-asked add-on had better than anything on record: playback moves onto it.
            if (erredOnly) _improved.tryEmit(videoId)
        }
        return rendition
    }

    private val alternatives = ConcurrentHashMap<String, List<Rendition>>()

    private fun addAlternative(videoId: String, rendition: Rendition) {
        if (alternatives.size > 256) alternatives.clear()
        pinned[rendition.key] = videoId to rendition
        alternatives.compute(videoId) { _, list ->
            (list.orEmpty() + rendition).sortedByDescending { com.opentune.data.addons.AddonResolver.qualityScore(it.bits, it.sampleRate) }
        }
    }

    /**
     * Every verified lossless file found for [videoId] that may still be
     * played, best quality first: when one won't load, playback tries the
     * next (see PlaybackService).
     */
    fun alternatives(videoId: String): List<Rendition> {
        val now = now()
        return alternatives[videoId].orEmpty().filter {
            now - it.at < TTL && allowed(true) &&
                (it.addonId == null || (it.sessionRevision == com.opentune.data.addons.AddonStore.revision.value && addonAllowed(it)))
        }
    }

    /**
     * A stream picked by hand from an add-on's search results, to start
     * [videoId] on: the listener chose this recording, so it isn't searched
     * for or compared again. Startup takes it at once when external sources
     * are allowed (see [cached]).
     */
    fun offer(videoId: String, stream: com.opentune.data.addons.AddonStream): Rendition {
        val rendition = Rendition(java.util.UUID.randomUUID().toString(), stream.url,
            "${stream.provider} · ${if (stream.lossless) "lossless requested" else stream.codec}", stream.lossless, now(),
            provider = stream.provider, bits = stream.bits, sampleRate = stream.sampleRate, kbps = stream.kbps,
            maximumSearch = true, sessionRevision = com.opentune.data.addons.AddonStore.revision.value,
            addonId = stream.addonId, mimeType = stream.mimeType, picked = true,
            trackId = stream.trackId.ifEmpty { null }, tier = stream.tier.ifEmpty { null })
        losslessMissUntil.remove(videoId)
        saavnMissUntil.remove(videoId)
        saavnReports.remove(videoId)
        remember(videoId, rendition)
        report(videoId, "${stream.provider}: picked in Search")
        return rendition
    }

    internal fun remember(videoId: String, rendition: Rendition, preserveLossless: Boolean = false) {
        if (renditions.size > 256) renditions.clear()
        pinned.entries.removeIf { now() - it.value.second.at >= 2 * 60 * 60_000L }
        pinned[rendition.key] = videoId to rendition
        renditions.compute(videoId) { _, current ->
            if (preserveLossless && current?.lossless == true && cached(videoId) == current) current else rendition
        }
    }

    fun get(videoId: String, key: String): Rendition? = pinned[key]?.takeIf { it.first == videoId }?.second

    fun resolve(videoId: String, key: String): String {
        val rendition = get(videoId, key)
        if (rendition == null || !addonAllowed(rendition) || !(rendition.picked || allowed(rendition.lossless))) {
            throw IOException("External rendition expired; resume the original stream")
        }
        return links[key]?.current ?: rendition.url
    }

    /** The link a rendition plays from now: a newer one than it was found with, once [refreshLink] got one. */
    fun urlOf(rendition: Rendition): String = links[rendition.key]?.current ?: rendition.url

    /** The add-on rendition [url] is a link of, as (video id, key). */
    private fun ownerOf(url: String): Pair<String, Rendition>? =
        pinned.values.firstOrNull { (_, r) -> r.addonId != null && (r.url == url || links[r.key]?.all?.contains(url) == true) }

    /** Whether [url] is a link to an add-on's file, rather than YouTube's or JioSaavn's. */
    fun isAddonLink(url: String): Boolean = ownerOf(url) != null

    /** [refreshLink] for whichever add-on file [url] is a link of; null for anything else. */
    fun refreshLinkFor(url: String): String? = ownerOf(url)?.let { (videoId, r) -> refreshLink(videoId, r.key, url) }

    /** Each rendition's links since it was found, newest last, and when it last asked for one. */
    private class Links(val all: List<String>, val askedAt: Long, val asks: Int) { val current get() = all.last() }
    private val links = ConcurrentHashMap<String, Links>()

    /**
     * After [failedUrl] wouldn't open (expired, refused): a new link to the same file,
     * asked of the add-on that verified it, with no new search, so the song carries on
     * in lossless instead of falling back. The newest link when another load already got
     * one; null when there's none to be had. Blocks; called from the player's loader thread.
     */
    fun refreshLink(videoId: String, key: String, failedUrl: String): String? {
        val rendition = get(videoId, key) ?: return null
        val addon = rendition.addonId ?: return null
        val track = rendition.trackId ?: return null
        synchronized(rendition) {
            val held = links[key] ?: Links(listOf(rendition.url), 0, 0)
            // A segment of a manifest, or something else this rendition never handed out.
            if (failedUrl !in held.all) return null
            if (failedUrl != held.current) return held.current
            val now = now()
            if (held.asks >= LINK_ASKS || now - held.askedAt < LINK_ASK_GAP_MS) return null
            val fresh = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(LINK_ASK_LIMIT_MS) {
                    com.opentune.data.addons.AddonResolver.freshLink(addon, track, rendition.tier.orEmpty(), videoId)
                }
            }
            if (links.size > 256) links.clear()
            links[key] = Links(if (fresh != null && fresh != held.current) held.all + fresh else held.all, now, held.asks + 1)
            if (fresh != null && fresh != held.current) com.opentune.data.DebugLog.d("ExternalStreams", "$videoId: ${rendition.provider} gave a new link after the last one failed")
            return fresh?.takeIf { it != failedUrl }
        }
    }

    /** Verification/renewal should take effect immediately, including after a cached miss. */
    fun retryLookups() {
        losslessMissUntil.clear()
        saavnMissUntil.clear()
        transientMiss.clear()
        losslessSearched.clear()
        heard.clear()
        com.opentune.data.addons.AddonResolver.retry()
        reports.clear()
        // Keep currently playing rendition keys valid until the player swaps sources.
    }

    fun failed(videoId: String, key: String? = null) {
        // A lookup may have finished while an older rendition was playing.
        // Invalidate the stream that actually failed, not the new candidate.
        val failed = if (key == null) renditions.remove(videoId) else get(videoId, key)
        if (key != null && renditions[videoId]?.key == key) renditions.remove(videoId)
        val addon = failed?.addonId
        if (addon != null) com.opentune.data.addons.AddonResolver.failed(addon, videoId)
        // Its file broke, so its answer no longer stands: asked again, for a new link, once it may be.
        if (addon != null) heard.computeIfPresent(videoId) { _, h -> Heard(h.revision, h.at, h.settled - addon, h.erred + addon, h.lines) }
        pinned.entries.removeIf { it.value.first == videoId && (key == null || it.key == key) }
        // Not offered again as a fallback: the next try went straight back to the file that had just failed.
        if (failed != null) alternatives.computeIfPresent(videoId) { _, list -> list.filterNot { it.key == failed.key || it.url == failed.url }.ifEmpty { null } }
        // A JioSaavn stream that broke while playing isn't offered again for a while.
        if (addon == null && renditions[videoId] == null) saavnMissUntil[videoId] = now() + TTL
        report(videoId, when {
            addon != null && saavnFor(videoId) != null -> "The selected add-on failed during playback; using JioSaavn meanwhile and trying the next add-on."
            addon != null -> "The selected add-on failed during playback; using fallback audio and trying the next add-on."
            else -> "The selected external stream failed during playback; using YouTube temporarily."
        })
    }

    /** A JioSaavn stream still held for [videoId]: what a failed add-on stream falls back to before YouTube. */
    fun saavnFor(videoId: String): Rendition? = if (!allowed(false)) null else
        pinned.values.firstOrNull { (id, r) -> id == videoId && r.addonId == null && !r.lossless && r.provider == "JioSaavn" && now() - r.at < TTL }?.second

    private fun addonAllowed(rendition: Rendition): Boolean = rendition.addonId?.let { key ->
        com.opentune.data.addons.AddonStore.addons.value.firstOrNull { it.key == key }?.let(com.opentune.data.addons.AddonStore::usable) == true
    } ?: true

    private fun now(): Long = System.nanoTime() / 1_000_000

    /** A definite miss for [videoId] in [misses], for a minute. */
    private fun remember(misses: ConcurrentHashMap<String, Long>, videoId: String) {
        if (misses.size > 256) misses.clear()
        misses[videoId] = now() + MISS_MS
    }

    /** Whether [videoId]'s last FLAC search came back empty only because servers erred: worth asking again soon. */
    fun missWasTransient(videoId: String): Boolean = videoId in transientMiss

    /** Whether a FLAC search for [videoId] has run to the end. */
    fun losslessSearched(videoId: String): Boolean = videoId in losslessSearched

    private const val MISS_MS = 60_000L
    private const val VERIFIED_FRESH_MS = 120_000L
    /** New links asked for one file at most this many times, this far apart, each waited on this long. */
    private const val LINK_ASKS = 4
    private const val LINK_ASK_GAP_MS = 3_000L
    private const val LINK_ASK_LIMIT_MS = 8_000L
    /** What the add-ons report when they erred rather than answered. */
    private val TRANSIENT = listOf("HTTP ", "unavailable", "no answer", "rate limited", "temporarily")
}


