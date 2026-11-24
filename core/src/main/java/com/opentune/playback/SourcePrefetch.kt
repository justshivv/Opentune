package com.opentune.playback

import com.opentune.data.lossless.SourceTier
import com.opentune.data.model.Song
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Waits up to [timeoutMs] for a song's startup source to be chosen, [choosing] being true until it is,
 * including before the choice has begun. False when it never came.
 */
internal suspend fun awaitStartupChoice(choosing: () -> Boolean, timeoutMs: Long = 30_000, pollMs: Long = 100): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (choosing()) delay(pollMs)
        true
    } == true

/** Upcoming songs yield catalogue capacity until the playing song has its first answer. */
internal suspend fun awaitForegroundLookup(target: String, current: () -> String?, pending: (String) -> Boolean) {
    while (true) {
        val playing = current() ?: return
        if (playing == target || !pending(playing)) return
        delay(100)
    }
}

/** How a song ahead was opened: not yet, on YouTube, or already on an external stream. */
internal enum class AheadOpened { NOT_YET, YOUTUBE, EXTERNAL }

/**
 * The positions among [ahead] (the next songs in play order: id and how each was opened) to point
 * at a FLAC just found for [id]. Only songs already opened on YouTube: one not opened yet picks the
 * FLAC by itself when it opens, and one on an external stream has its source.
 */
internal fun aheadToStartOnFlac(id: String, ahead: List<Pair<String, AheadOpened>>): List<Int> =
    ahead.withIndex().filter { (_, song) -> song.first == id && song.second == AheadOpened.YOUTUBE }.map { it.index }

/**
 * Whether a song just opened on YouTube is searched for its FLAC again: not the song playing (its
 * upgrade does that), only one of the songs readied ahead in lossless ([ahead]: one of the next
 * two, with Preload upcoming songs on), only with lossless allowed, once per opening, and not
 * while a search is running.
 */
internal fun lookAgainForFlac(playing: Boolean, ahead: Boolean, losslessAllowed: Boolean, firstForThisOpening: Boolean, searching: Boolean): Boolean =
    !playing && ahead && losslessAllowed && firstForThisOpening && !searching

/** No source's latency or failure delays another's preparation. */
internal suspend fun prefetchExternalSources(blocks: List<suspend () -> Unit>) = supervisorScope {
    suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    for (block in blocks) launch { attempt(block) }
    Unit
}

/**
 * One song to ready ahead of time: its id, the song, the source tiers that want it, and
 * whether its FLAC is searched for too (the lossless tier wants it).
 */
internal data class AheadWant(
    val id: String,
    val song: Song,
    val lossless: Boolean,
    val tiers: Set<SourceTier> = if (lossless) setOf(SourceTier.LOSSLESS, SourceTier.HIGH_BITRATE) else setOf(SourceTier.HIGH_BITRATE),
)

/**
 * The songs to ready ahead of time, from [upcoming] (the songs after the playing one, in play
 * order): none with Preload upcoming songs off ([preload]); otherwise, of the next [saavnAhead],
 * those [eligible] (not a file, a podcast or a server's song), for JioSaavn when [saavn] is
 * allowed, and of the next [losslessAhead] with their FLAC searched for too when [lossless] is.
 * Places are counted before any are left out, so no FLAC is looked for past [losslessAhead]. A
 * song there twice is readied once.
 */
internal fun aheadWanted(
    upcoming: List<Song>,
    preload: Boolean,
    saavn: Boolean,
    lossless: Boolean,
    saavnAhead: Int,
    losslessAhead: Int,
    eligible: (Song) -> Boolean = { true },
): List<AheadWant> {
    if (!preload || (!saavn && !lossless)) return emptyList()
    val near = if (lossless) upcoming.take(losslessAhead).mapTo(HashSet()) { it.videoId } else emptySet()
    val reach = if (saavn) maxOf(saavnAhead, losslessAhead) else losslessAhead
    return upcoming.take(reach).filter(eligible).distinctBy { it.videoId }.map { AheadWant(it.videoId, it, it.videoId in near) }
}

/**
 * As [aheadWanted], with the places chosen by a [QueuePlan] rather than counted: of [upcoming],
 * the places in [at] (each source tier's places) are readied, for the tiers in [allowed]. A song
 * there twice is readied once, with every tier that wants it.
 */
internal fun aheadPlanned(
    upcoming: List<Song>,
    preload: Boolean,
    allowed: Set<SourceTier>,
    at: Map<SourceTier, Set<Int>>,
    eligible: (Song) -> Boolean = { true },
): List<AheadWant> {
    if (!preload || allowed.isEmpty()) return emptyList()
    val tiersById = HashMap<String, Set<SourceTier>>()
    upcoming.forEachIndexed { i, song ->
        val tiers = allowed.filterTo(HashSet()) { tier -> i in at[tier].orEmpty() }
        if (tiers.isNotEmpty()) tiersById.merge(song.videoId, tiers) { a, b -> a + b }
    }
    return upcoming.filter { it.videoId in tiersById }.filter(eligible).distinctBy { it.videoId }.map { song ->
        val tiers = tiersById[song.videoId].orEmpty()
        AheadWant(song.videoId, song, SourceTier.LOSSLESS in tiers, tiers)
    }
}

/**
 * The lookups readying songs ahead, by video id, and the rules they keep:
 *  - each waits [settleMs] first, so a song skipped past within a moment is never looked up;
 *  - at most [atOnce] run at a time;
 *  - a song still ahead keeps the lookup it has (each skip used to cancel them all and ask again,
 *    so skipping a few in a row readied none, and the add-ons, asked the same over and over, began
 *    answering with errors), and the song just started keeps its own too, as its upgrade joins it;
 *  - a song already readied isn't looked up again while it stays ahead: each search reads the
 *    start of every file it verifies, and asking again on every queue change had the file servers
 *    answering HTTP 429.
 * [lookUp] does the work for a song, for the source tiers that want it.
 */
internal class AheadLookups(
    private val scope: CoroutineScope,
    private val settleMs: Long,
    atOnce: Int,
    private val context: CoroutineContext = EmptyCoroutineContext,
    private val lookUp: suspend (song: Song, tiers: Set<SourceTier>) -> Unit,
) {
    private class Lookup(val job: Job, val tiers: Set<SourceTier>)

    private val slots = Semaphore(atOnce)
    private val running = ConcurrentHashMap<String, Lookup>()
    /** Songs readied while they've been ahead: the tiers they were readied for. */
    private val readied = ConcurrentHashMap<String, Set<SourceTier>>()

    /** Whether a lookup for [id] is under way. */
    fun looking(id: String): Boolean = running[id]?.job?.isActive == true

    /** Readies [wanted] and lets go of the lookups for songs no longer ahead, but [keep]'s (the song playing). */
    fun update(wanted: List<AheadWant>, keep: String?) {
        val ids = wanted.mapTo(HashSet()) { it.id }
        running.keys.filter { it !in ids && it != keep }.forEach { running.remove(it)?.job?.cancel() }
        readied.keys.retainAll { it in ids || it == keep }
        for (want in wanted) {
            val now = running[want.id]
            if (now != null && now.job.isActive && now.tiers.containsAll(want.tiers)) continue
            val done = readied[want.id]
            if (done != null && done.containsAll(want.tiers)) continue
            // A song a further tier wants while its lookup ran: asked again with every tier.
            now?.job?.cancel()
            start(want.song, want.tiers, settleMs)
        }
    }

    /** [song] looked up for its FLAC again, at once: opened on YouTube, with the FLAC found for it gone. */
    fun again(song: Song) {
        if (looking(song.videoId)) return
        start(song, setOf(SourceTier.LOSSLESS, SourceTier.HIGH_BITRATE), settle = 0L)
    }

    /** Every lookup called off: Preload upcoming songs turned off. */
    fun cancelAll() {
        running.values.forEach { it.job.cancel() }
        running.clear()
        readied.clear()
    }

    private fun start(song: Song, tiers: Set<SourceTier>, settle: Long) {
        val id = song.videoId
        lateinit var lookup: Lookup
        val job = scope.launch(context, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (settle > 0) delay(settle)
            slots.withPermit {
                try { lookUp(song, tiers) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            readied[id] = (readied[id] ?: emptySet()) + tiers
            running.remove(id, lookup)
        }
        lookup = Lookup(job, tiers)
        running[id] = lookup
        job.start()
    }
}
