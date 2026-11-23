package com.opentune.playback

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

/** Neither provider's latency or failure delays the other's preparation. */
internal suspend fun prefetchExternalSources(lossless: suspend () -> Unit, lossy: suspend () -> Unit) = supervisorScope {
    suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    launch { attempt(lossless) }
    launch { attempt(lossy) }
    Unit
}

/** One song to ready ahead of time: its id, the song, and whether its FLAC is searched for too. */
internal data class AheadWant(val id: String, val song: Song, val lossless: Boolean)

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
 * As [aheadWanted], with the places chosen by a [QueuePlan] rather than counted: the songs at
 * [saavnAt] of [upcoming] are looked up on JioSaavn, and those at [losslessAt] for their FLAC too.
 * A song there twice is readied once, with its FLAC if either place wants it.
 */
internal fun aheadPlanned(
    upcoming: List<Song>,
    preload: Boolean,
    saavn: Boolean,
    lossless: Boolean,
    saavnAt: Set<Int>,
    losslessAt: Set<Int>,
    eligible: (Song) -> Boolean = { true },
): List<AheadWant> {
    if (!preload || (!saavn && !lossless)) return emptyList()
    val near = if (lossless) upcoming.filterIndexed { i, _ -> i in losslessAt }.mapTo(HashSet()) { it.videoId } else emptySet()
    val wanted = upcoming.filterIndexed { i, _ -> (saavn && i in saavnAt) || i in losslessAt && lossless }
    return wanted.filter(eligible).distinctBy { it.videoId }.map { AheadWant(it.videoId, it, it.videoId in near) }
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
 * [lookUp] does the work for a song; [lossless] says whether its FLAC is searched for too.
 */
internal class AheadLookups(
    private val scope: CoroutineScope,
    private val settleMs: Long,
    atOnce: Int,
    private val context: CoroutineContext = EmptyCoroutineContext,
    private val lookUp: suspend (song: Song, lossless: Boolean) -> Unit,
) {
    private class Lookup(val job: Job, val lossless: Boolean)

    private val slots = Semaphore(atOnce)
    private val running = ConcurrentHashMap<String, Lookup>()
    /** Songs readied while they've been ahead: whether their FLAC was searched for too. */
    private val readied = ConcurrentHashMap<String, Boolean>()

    /** Whether a lookup for [id] is under way. */
    fun looking(id: String): Boolean = running[id]?.job?.isActive == true

    /** Readies [wanted] and lets go of the lookups for songs no longer ahead, but [keep]'s (the song playing). */
    fun update(wanted: List<AheadWant>, keep: String?) {
        val ids = wanted.mapTo(HashSet()) { it.id }
        running.keys.filter { it !in ids && it != keep }.forEach { running.remove(it)?.job?.cancel() }
        readied.keys.retainAll { it in ids || it == keep }
        for (want in wanted) {
            val now = running[want.id]
            if (now != null && now.job.isActive && (now.lossless || !want.lossless)) continue
            val done = readied[want.id]
            if (done != null && (done || !want.lossless)) continue
            // A song that came near enough for its FLAC while its JioSaavn lookup ran: asked again with it.
            now?.job?.cancel()
            start(want.song, want.lossless, settleMs)
        }
    }

    /** [song] looked up for its FLAC again, at once: opened on YouTube, with the FLAC found for it gone. */
    fun again(song: Song) {
        if (looking(song.videoId)) return
        start(song, lossless = true, settle = 0L)
    }

    /** Every lookup called off: Preload upcoming songs turned off. */
    fun cancelAll() {
        running.values.forEach { it.job.cancel() }
        running.clear()
        readied.clear()
    }

    private fun start(song: Song, lossless: Boolean, settle: Long) {
        val id = song.videoId
        lateinit var lookup: Lookup
        val job = scope.launch(context, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (settle > 0) delay(settle)
            slots.withPermit {
                try { lookUp(song, lossless) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            readied[id] = lossless || readied[id] == true
            running.remove(id, lookup)
        }
        lookup = Lookup(job, lossless)
        running[id] = lookup
        job.start()
    }
}
