package com.opentune.playback

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.data.DebugLog as Log
import com.opentune.data.settings.AppSettings
import com.opentune.playback.mix.MixAnalyzer
import com.opentune.playback.mix.MixPlan
import com.opentune.playback.mix.automixPlan
import com.opentune.playback.mix.bassCuts
import com.opentune.playback.mix.blendGains
import com.opentune.playback.mix.crossfadePlan
import com.opentune.playback.mix.levelTrims
import com.opentune.playback.mix.stepCorrection
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Blends the end of each song into the start of the next: a crossfade of a
 * set length, or Automix, which reads both songs and shapes the blend to them.
 *
 * The main player stays the one the media session, the notification and the
 * history see. Shortly before the blend, a second player (the "tail") starts
 * the same stream of the playing song, silently and through the same effects,
 * and is lined up with the main player to within a few milliseconds. At the
 * mix point the sound moves onto the tail in a 50 ms crossover of two copies
 * of the same moment, which can't be heard, and the main player, now silent,
 * moves on to the next song. Only once that song is really sounding does the
 * blend begin; until then the tail carries on at full level, so there's never
 * a gap. The tail's own loudness gain stays the outgoing song's.
 *
 * The earlier version started the tail and muted the main player the moment
 * the tail said it was playing, before its sound had reached the speaker
 * (a short gap), and from wherever it had got to while loading (a bit of the
 * song heard twice). It also started fading before the next song had begun.
 *
 * A skip, seek or pause from the listener during a blend is respected: the
 * old song's tail fades out in [BAIL_MS] and the main player is left at full
 * volume.
 */
@OptIn(UnstableApi::class)
internal class Crossfade(
    private val scope: CoroutineScope,
    private val main: ExoPlayer,
    /** A second player built like the main one, with its own effects and the playing song's loudness. */
    private val newTail: () -> Tail,
    /** The exact stream the main player is playing for [item], so the tail plays the same file. */
    private val sameStream: (MediaItem) -> MediaItem?,
    /** The stream address to analyze for [item], or null to skip analysis. */
    private val analysisUri: (MediaItem) -> Uri?,
    private val analyzer: MixAnalyzer,
    /** The main player's bass cut, for the bass swap. */
    private val mainLowCut: (Float) -> Unit,
    /** True while something else (a quality swap) is moving the main player. */
    private val busy: () -> Boolean,
    /** How long the main player's sound has been silent, as it's decoded (see DspAudioProcessor.meter). */
    private val silentMs: () -> Long = { 0L },
    /** True when loudness normalization already brings both songs to one level, so a mix needn't. */
    private val levelled: (MediaItem, MediaItem) -> Boolean = { _, _ -> false },
    /** A song's length from its details, for a stream that doesn't say how long it is. */
    private val durationOf: (MediaItem) -> Long? = { null },
    /** Called as a mix starts: a search for a better stream for the ending song is called off, as it can't help now. */
    private val beforeMix: () -> Unit = {},
) {
    /**
     * A second player and what to do when it's finished with. [level] gives it the
     * loudness gain of the song it has loaded, the same the main player will give
     * that song, so nothing jumps when the main player takes it over.
     */
    class Tail(val player: ExoPlayer, val lowCut: (Float) -> Unit, val level: () -> Unit = {}, val release: () -> Unit)

    /** Where a transition is: the next song loading, the two blending, or the main player taking it over. */
    private enum class Phase { IDLE, LOADING, BLENDING, HANDOVER }

    /** The second player, carrying the next song in from its first moment until the main player takes it. */
    private var tail: Tail? = null
    private var mix: Job? = null
    private var lastMixed: Pair<Int, String>? = null
    /** Tries at a transition for the song in [triesFor]: a next song that didn't load gets another go. */
    private var tries = 0
    private var triesFor: Pair<Int, String>? = null
    /** True while this class moves the main player itself, so its own seeks aren't read as the listener's. */
    private var handingOver = false
    @Volatile private var phase = Phase.IDLE
    /** The playing song went silent while the next one loaded: start now rather than at the mix point. */
    @Volatile private var urgent = false
    /** The plan of the blend under way, for the transition view's clock. */
    @Volatile private var blendPlan: MixPlan? = null

    // The plan for the playing song, worked out once it's near its end.
    private var plan: MixPlan? = null
    /** Where the playing song's sound stops, as read from it; null when it couldn't be read. */
    @Volatile private var soundEndsMs: Long? = null
    /** For a song that couldn't be read: whether the rest of it, from where it went quiet, is silent. */
    private var restSilent: Boolean? = null
    private var restCheck: Job? = null
    private var restCheckFor: Pair<Int, String>? = null
    private var restCheckFrom = 0L
    private var planFor: Triple<Int, String, String>? = null
    private var planning: Job? = null

    /** True while two songs are blending, when nothing else should move the main player's volume. */
    val blending: Boolean get() = tail != null

    private val settings get() = AppSettings.playback.value
    private val enabled get() = settings.automix || settings.crossfadeSeconds > 0

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (handingOver || tail == null) return
            // The old song ran to its end under the blend: the main player is on the next one now,
            // which the second player is already playing; it stays silent until the hand-over.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && phase == Phase.BLENDING) {
                main.volume = 0f
                return
            }
            bail()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !handingOver && tail != null) bail()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val t = tail?.player ?: return
            // Loading, the next song waits paused at its cue point whatever the player does.
            if (phase == Phase.LOADING) return
            t.playWhenReady = playWhenReady
        }
    }

    fun start() {
        main.addListener(listener)
        // The playing song's time for the transition view; through a blend, the song coming in
        // carries it on, as it's what's heard.
        MixState.clock = {
            val bp = blendPlan
            val t = tail?.player
            if (bp != null && t != null && phase != Phase.LOADING) bp.mixAtMs + ((t.currentPosition - bp.inFromMs) * bp.outSpeed).toLong()
            else main.currentPosition
        }
        scope.launch {
            while (isActive) {
                val wait = runCatching { check() }.onFailure { Log.w(TAG, "Crossfade check failed", it) }.getOrDefault(POLL_MS)
                delay(wait)
            }
        }
    }

    fun release() {
        main.removeListener(listener)
        MixState.clock = null
        mix?.cancel()
        planning?.cancel()
        releaseTail()
        mainLowCut(0f)
        main.volume = 1f
    }

    /** Looks at where the song is and starts what's due; returns how long to wait before looking again. */
    private fun check(): Long {
        if (tail != null) {
            // The next song is loading and the playing one's sound has ended for good: go now.
            if (phase == Phase.LOADING && silentMs() >= SILENCE_SKIP_MS) {
                val item = main.currentMediaItem
                val duration = main.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item?.let(durationOf)
                if (item != null && duration != null && endedForGood(item, main.currentPosition, duration)) urgent = true
            }
            return SOON_MS
        }
        if (!enabled || BitPerfectUsb.active) return POLL_MS
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return POLL_MS
        val item = main.currentMediaItem ?: return POLL_MS
        // Some streams (a FLAC sent as it's read) don't say how long they are; the song's details do.
        val duration = main.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: durationOf(item) ?: return POLL_MS
        if (duration < MIN_SONG_MS) return POLL_MS
        val key = main.currentMediaItemIndex to item.mediaId
        if (key == lastMixed) return POLL_MS
        val position = main.currentPosition
        val left = duration - position
        if (left > analyzeAhead()) return POLL_MS

        val next = main.getMediaItemAt(main.nextMediaItemIndex)
        // Planned again if the next song changes (the queue was edited).
        val planKey = Triple(key.first, key.second, next.mediaId)
        if (planFor != planKey) {
            planFor = planKey
            plan = null
            soundEndsMs = null
            planning?.cancel()
            // The pair shows in the transition view at once, its waves filled in once read.
            MixState.picture(MixState.Picture(
                MixState.Side.of(item, duration, null, null), MixState.Side.of(next, null, null, null),
                quickPlan(duration), settings.automix, reading = true,
            ))
            planning = scope.launch { plan = makePlan(item, next, duration) }
        }
        // The song has gone silent near its end (a long fade to nothing, silence before the file
        // stops): the next song is loaded and started straight away. Seen as the sound is decoded,
        // so it works whether or not the song could be read ahead.
        val silent = left in MIN_LEFT_MS..SILENCE_WINDOW_MS && position > duration / 2 && silentMs() >= SILENCE_SKIP_MS &&
            endedForGood(item, position, duration)
        // Still reading the songs: plan without them if time is short.
        val p = plan ?: if (silent || left <= quickPlanLeft()) quickPlan(duration).also {
            plan = it
            planning?.cancel()
            MixState.upcoming.value?.let { pic -> MixState.picture(pic.copy(plan = it, reading = false)) }
        } else return SOON_MS
        if (!silent && position < p.mixAtMs - PREP_MS) return ((p.mixAtMs - PREP_MS - position) / 2).coerceIn(SOON_MS, POLL_MS)
        if (busy()) return SOON_MS
        if (triesFor != key) { triesFor = key; tries = 0 }
        tries++
        lastMixed = key
        beforeMix()
        begin(p, now = silent)
        return POLL_MS
    }

    private fun quickPlanLeft(): Long = PREP_MS + (if (settings.automix) MAX_QUICK_MS else settings.crossfadeSeconds * 1000L) + 1_000

    private fun quickPlan(duration: Long): MixPlan =
        if (settings.automix) automixPlan(duration, null, null) else crossfadePlan(duration, settings.crossfadeSeconds * 1000L, null, null)

    /**
     * Whether the quiet the playing song has gone into is its end, not a pause in it. Read
     * from the song: past where its sound stops. Not read: the rest of the file is checked
     * to be silent first (in the background; false until it's known), and failing that the
     * quiet has to last [UNSURE_SILENCE_MS]. So a quiet moment before a last chord never
     * starts the next song early.
     */
    private fun endedForGood(item: MediaItem, position: Long, duration: Long): Boolean {
        soundEndsMs?.let { return position >= it - END_SLACK_MS }
        val key = main.currentMediaItemIndex to item.mediaId
        // Sound was still to come after the last quiet spell; a later one is checked again.
        if (restCheckFor == key && restSilent == false && position - restCheckFrom > RECHECK_MS) restCheckFor = null
        if (restCheckFor != key) {
            restCheckFor = key
            restCheckFrom = position
            restSilent = null
            restCheck?.cancel()
            val uri = analysisUri(item)
            restCheck = if (uri == null) null else scope.launch {
                restSilent = runCatching { analyzer.restIsSilent(uri, position, duration) }
                    .onFailure { if (it is CancellationException) throw it }.getOrNull()
                Log.d(TAG, "Rest of ${item.mediaId} from ${position}ms silent: $restSilent")
            }
        }
        return when (restSilent) {
            true -> true
            false -> false
            null -> restCheck?.isActive != true && silentMs() >= UNSURE_SILENCE_MS
        }
    }

    /** How long before a song's end the two songs are read: sooner the harder Automix is set to work. */
    private fun analyzeAhead(): Long = when (settings.automixPerformance) {
        com.opentune.data.settings.AutomixPerformance.EFFICIENT -> ANALYZE_AHEAD_MS
        com.opentune.data.settings.AutomixPerformance.BALANCED -> ANALYZE_AHEAD_MS + 15_000
        com.opentune.data.settings.AutomixPerformance.PERFORMANCE -> ANALYZE_AHEAD_MS + 45_000
    }

    private suspend fun makePlan(item: MediaItem, next: MediaItem, duration: Long): MixPlan = kotlinx.coroutines.coroutineScope {
        val outUri = analysisUri(item)
        val inUri = analysisUri(next)
        suspend fun <T> read(block: suspend () -> T): T? = runCatching { block() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        // Both songs at once, unless Automix is set to work one thread at a time.
        val together = settings.automixPerformance.threads > 1
        val endingRead = async(start = if (together) kotlinx.coroutines.CoroutineStart.DEFAULT else kotlinx.coroutines.CoroutineStart.LAZY) {
            outUri?.let { read { analyzer.ending(it, duration) } }
        }
        val openingRead = async(start = if (together) kotlinx.coroutines.CoroutineStart.DEFAULT else kotlinx.coroutines.CoroutineStart.LAZY) {
            inUri?.let { read { analyzer.opening(it) } }
        }
        val ending = endingRead.await()
        val opening = openingRead.await()
        soundEndsMs = ending?.endMs
        val p = if (settings.automix) automixPlan(duration, ending, opening)
            else crossfadePlan(duration, settings.crossfadeSeconds * 1000L, ending, opening)
        Log.d(TAG, "Plan for ${item.mediaId} → ${next.mediaId}: $p")
        MixState.picture(MixState.Picture(
            MixState.Side.of(item, duration, ending?.beat, ending?.wave, ending?.key),
            MixState.Side.of(next, null, opening?.beat, opening?.wave, opening?.key), p, settings.automix,
        ))
        p
    }

    /** What the player says while two songs blend, in place of the quality badge. */
    private fun label() = "Mixing"

    /**
     * One transition, start to finish:
     *
     * 1. The next song loads on the second player, paused at its cue point, while the
     *    playing song carries on: by the mix point it's buffered and starts at once.
     * 2. At the mix point (or straight away, when the playing song has gone silent) it
     *    starts, silent, at the playing song's tempo, and is put on its beat.
     * 3. The two blend, the bass traded over, until the playing song's end.
     * 4. The next song eases back to its own tempo.
     * 5. The main player moves to the same stream of the next song, silently, lines up
     *    with the second player, and takes the sound over in a crossover of two copies of
     *    the same moment, which can't be heard.
     *
     * If the next song can't load before the playing one ends, nothing is blended and the
     * songs play back to back as without a crossfade.
     */
    private fun begin(p: MixPlan, now: Boolean) {
        val nextIndex = main.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = main.getMediaItemAt(nextIndex)
        val t = runCatching { newTail() }.onFailure { Log.w(TAG, "Couldn't make the second player", it) }.getOrNull() ?: return
        tail = t
        urgent = now
        phase = Phase.LOADING
        val tp = t.player
        tp.volume = 0f
        tp.playWhenReady = false
        // While they blend, the next song runs at the playing one's tempo; it eases back after.
        val inSpeed = if (p.outSpeed != 1f) 1f / p.outSpeed else 1f
        tp.playbackParameters = androidx.media3.common.PlaybackParameters(inSpeed, 1f)
        tp.setMediaItem(next, p.inFromMs)
        tp.prepare()
        val playing = main.currentMediaItemIndex
        // Levelled here only when loudness normalization isn't doing it already.
        val matchLevels = p.levelDb != 0f && main.currentMediaItem?.let { !levelled(it, next) } == true
        val playingKey = main.currentMediaItemIndex to (main.currentMediaItem?.mediaId ?: "")
        // Set once the next song is heard; a transition that ends before then may be tried again.
        var started = false
        mix = scope.launch {
            try {
                // 1. Loading, until it's due.
                while (true) {
                    if (main.currentMediaItemIndex != playing) return@launch
                    val ready = tp.playbackState == Player.STATE_READY && (tp.bufferedPosition - tp.currentPosition >= READY_AHEAD_MS || tp.isLoading.not())
                    val due = urgent || main.currentPosition >= p.mixAtMs
                    if (ready && due) break
                    tp.playerError?.let { Log.w(TAG, "The next song didn't load (try $tries)", it); return@launch }
                    delay(FRAME_MS * 2)
                }
                // 2. In, silent, on the beat, at its own loudness gain.
                runCatching { t.level() }.onFailure { Log.w(TAG, "Couldn't set the next song's level", it) }
                phase = Phase.BLENDING
                blendPlan = p
                MixState.start(label())
                // From here on the player shows the song coming in, with its own time.
                MixState.show(MixState.Showing(next, nextIndex,
                    durationMs = { tp.duration.takeIf { it > 0 } ?: durationOf(next) ?: 0L },
                    positionMs = { tp.currentPosition }))
                tp.playWhenReady = main.playWhenReady
                if (!sounding(tp, START_TIMEOUT_MS)) {
                    Log.w(TAG, "The next song didn't start sounding (try $tries)")
                    return@launch
                }
                started = true
                if (p.beatMs != null && !urgent) putOnBeat(tp, p)
                // 3. The blend, over what's left of the playing song.
                val left = (p.outEndMs - main.currentPosition).coerceAtLeast(MIN_LEFT_MS)
                val length = if (urgent) minOf(p.blendMs, URGENT_BLEND_MS) else minOf(p.blendMs, left)
                ramp(length, stop = { main.currentMediaItemIndex != playing }) { q ->
                    MixState.progress(q * 0.92f)
                    val (out, inc) = blendGains(q, p.outFades && !urgent, p.inEases && !urgent)
                    val (outTrim, inTrim) = if (matchLevels) levelTrims(p.levelDb, q) else 1f to 1f
                    main.volume = out * outTrim
                    tp.volume = inc * inTrim
                    if (p.bassSwap) {
                        val (outCut, inCut) = bassCuts(q, p.swapAt, p.swapWidth)
                        mainLowCut(outCut)
                        t.lowCut(inCut)
                    }
                }
                main.volume = 0f
                tp.volume = 1f
                mainLowCut(0f)
                t.lowCut(0f)
                // 4. Back to its own tempo.
                if (inSpeed != 1f) easeSpeed(tp, 1f)
                // 5. The main player takes the next song over.
                phase = Phase.HANDOVER
                handOver(tp, next, nextIndex)
                MixState.progress(1f)
            } finally {
                MixState.end()
                // That transition is over: the view waits for the next one to be planned.
                if (started) MixState.picture(null)
                blendPlan = null
                phase = Phase.IDLE
                urgent = false
                // However it ended, the main player is left at full level. A bail has taken the second player already.
                if (tail === t) {
                    mainLowCut(0f)
                    main.volume = 1f
                    releaseTail()
                    // The next song never came in, and the playing one still has time: another go.
                    val stillOn = main.currentMediaItemIndex to (main.currentMediaItem?.mediaId ?: "")
                    if (!started && tries < MAX_TRIES && stillOn == playingKey) lastMixed = null
                }
            }
        }
    }

    /**
     * The main player, silent, onto the next song at the second player's place, on the very
     * stream the second player chose (so the two are the same recording), lined up with it,
     * then a crossover in a breath.
     */
    private suspend fun handOver(tp: ExoPlayer, next: MediaItem, nextIndex: Int) {
        val chosen = tp.currentMediaItem?.let(sameStream)
        handingOver = true
        try {
            val index = if (main.currentMediaItemIndex == nextIndex || main.currentMediaItem?.mediaId == next.mediaId) main.currentMediaItemIndex else nextIndex
            if (chosen != null && index < main.mediaItemCount && main.getMediaItemAt(index).mediaId == chosen.mediaId) {
                main.replaceMediaItem(index, chosen.buildUpon().setMediaMetadata(main.getMediaItemAt(index).mediaMetadata).build())
            }
            main.seekTo(index, tp.currentPosition + TAIL_LEAD_MS)
        } finally {
            handingOver = false
        }
        lineUp(follower = main, leader = tp)
        val d = drift(follower = main, leader = tp)
        // Two copies of one moment, crossed; a wider cross if they couldn't be brought closer.
        ramp(if (abs(d) <= MAX_DRIFT_MS) CROSSOVER_MS else WIDE_CROSSOVER_MS) { q ->
            tp.volume = 1f - q
            main.volume = q
        }
    }

    /** Eases [tp]'s speed to [speed] in small steps, so a tempo change is heard as a drift, not a jump. */
    private suspend fun easeSpeed(tp: ExoPlayer, speed: Float) {
        val from = tp.playbackParameters.speed
        for (i in 1..TEMPO_STEPS) {
            tp.playbackParameters = androidx.media3.common.PlaybackParameters(from + (speed - from) * i / TEMPO_STEPS, 1f)
            delay(TEMPO_STEP_MS)
        }
    }

    /** Moves the next song (still all but silent) so its beats fall on the playing song's. */
    private suspend fun putOnBeat(tp: ExoPlayer, p: MixPlan) {
        // A seek restarts the sound a little after the moment asked for; learned from each try.
        var lead = SEEK_LEAD_MS
        delay(SETTLE_MS)
        repeat(STEP_TRIES) {
            val fix = stepCorrection(p, main.currentPosition, tp.currentPosition)
            if (abs(fix) <= IN_STEP_MS) return
            tp.seekTo((tp.currentPosition + fix + lead).coerceAtLeast(0L))
            if (!sounding(tp, START_TIMEOUT_MS)) return
            delay(SETTLE_MS)
            lead += stepCorrection(p, main.currentPosition, tp.currentPosition).coerceIn(-200L, 200L)
        }
    }

    /**
     * Brings [follower] to [leader]'s place in the same song, to within [ALIGNED_MS].
     * Each position counts from when its sound is heard, so once both have been playing a
     * moment they can be compared directly; a seek puts the follower right, and its
     * start-up delay is learned from each try.
     */
    private suspend fun lineUp(follower: ExoPlayer, leader: ExoPlayer): Boolean {
        var lead = TAIL_LEAD_MS
        repeat(ALIGN_TRIES) {
            if (!sounding(follower, START_TIMEOUT_MS * 2)) return false
            delay(SETTLE_MS)
            val d = drift(follower, leader)
            if (abs(d) <= ALIGNED_MS) return true
            lead = (lead - d).coerceIn(0L, 1_500L)
            handingOver = true
            try { follower.seekTo(leader.currentPosition + lead) } finally { handingOver = false }
        }
        if (!sounding(follower, START_TIMEOUT_MS)) return false
        delay(SETTLE_MS)
        return abs(drift(follower, leader)) <= ALIGNED_MS
    }

    /** How far [follower] is ahead of [leader], the middle of a few readings so one odd reading doesn't count. */
    private suspend fun drift(follower: ExoPlayer, leader: ExoPlayer): Long {
        val readings = LongArray(DRIFT_READINGS)
        for (i in readings.indices) {
            readings[i] = follower.currentPosition - leader.currentPosition
            if (i < readings.lastIndex) delay(DRIFT_GAP_MS)
        }
        readings.sort()
        return readings[readings.size / 2]
    }

    /** Waits until [p] is playing and its position is moving, i.e. its sound is being heard. */
    private suspend fun sounding(p: ExoPlayer, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs.coerceAtLeast(FRAME_MS)) {
        while (p.playbackState != Player.STATE_READY || !p.isPlaying) delay(FRAME_MS)
        val from = p.currentPosition
        while (p.currentPosition <= from) delay(FRAME_MS)
        true
    } == true

    /** Runs [step] from 0 to 1 over [ms] of playing time; paused time doesn't count, and [stop] skips to the end. */
    private suspend fun ramp(ms: Long, stop: () -> Boolean = { false }, step: (Float) -> Unit) {
        var done = 0L
        var last = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (main.isPlaying || tail?.player?.isPlaying == true) done += now - last
            last = now
            val q = if (stop()) 1f else (done.toFloat() / ms).coerceIn(0f, 1f)
            step(q)
            if (q >= 1f) break
            delay(FRAME_MS)
        }
    }

    /** The listener moved on mid-transition: the second player's song fades out quickly. */
    private fun bail() {
        mix?.cancel()
        val t = tail ?: return
        // The tail is this fade's now; the cancelled mix leaves it alone.
        tail = null
        // The next song hadn't come in yet: this song may blend again (a seek back).
        if (t.player.volume == 0f) lastMixed = null
        phase = Phase.IDLE
        urgent = false
        main.volume = 1f
        mainLowCut(0f)
        mix = scope.launch {
            val v = t.player.volume
            val from = SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    val q = ((SystemClock.elapsedRealtime() - from).toFloat() / BAIL_MS).coerceIn(0f, 1f)
                    t.player.volume = v * (1f - q)
                    if (q >= 1f) break
                    delay(FRAME_MS)
                }
            } finally {
                close(t)
            }
        }
    }

    private fun releaseTail() {
        val t = tail ?: return
        tail = null
        close(t)
    }

    private fun close(t: Tail) {
        runCatching { t.player.stop() }
        runCatching { t.release() }
    }

    private companion object {
        const val TAG = "Crossfade"
        const val POLL_MS = 500L
        const val SOON_MS = 50L
        const val FRAME_MS = 10L
        const val MIN_SONG_MS = 30_000L
        /** How long before the end the two songs are read. */
        const val ANALYZE_AHEAD_MS = 45_000L
        /** The longest blend a plan made without reading the songs uses. */
        const val MAX_QUICK_MS = 6_000L
        /** The tail starts this long before the mix point, to line up in time. */
        const val PREP_MS = 15_000L
        /** Silence this long near a song's end, as it's decoded, and the next song starts. */
        const val SILENCE_SKIP_MS = 1_500L
        /** Silence this long counts as the end when the rest of the song couldn't be checked. */
        const val UNSURE_SILENCE_MS = 4_000L
        /** A reading of where the sound stops is trusted to within this. */
        const val END_SLACK_MS = 250L
        const val RECHECK_MS = 3_000L
        /** Only this near the end does silence count as the song's ending. */
        const val SILENCE_WINDOW_MS = 40_000L
        /** A silent ending's blend: the next song comes in quickly, over nothing. */
        const val URGENT_BLEND_MS = 1_200L
        /** Buffered this far ahead, the next song can start without stopping to load. */
        const val READY_AHEAD_MS = 3_000L
        const val WIDE_CROSSOVER_MS = 300L
        const val DRIFT_READINGS = 5
        const val DRIFT_GAP_MS = 25L
        const val MIN_LEFT_MS = 400L
        /** The tail is started this far ahead to cover its start-up; corrected after. */
        const val TAIL_LEAD_MS = 150L
        const val START_TIMEOUT_MS = 1_500L
        const val SETTLE_MS = 250L
        const val ALIGN_TRIES = 5
        const val ALIGNED_MS = 20L
        const val MAX_DRIFT_MS = 35L
        const val CROSSOVER_MS = 80L
        const val STEP_TRIES = 2
        /** The next song eases back to its own tempo over four seconds, too slowly to hear as a change. */
        const val TEMPO_STEPS = 40
        const val TEMPO_STEP_MS = 100L
        const val SEEK_LEAD_MS = 80L
        const val IN_STEP_MS = 15L
        const val BAIL_MS = 150L
        /** A transition whose next song won't load is tried this many times in all. */
        const val MAX_TRIES = 2
    }
}

/**
 * Whether two songs are blending now, and how far through, for the player to
 * show "Mixing" where the quality badge sits while it happens.
 */
object MixState {
    data class Mix(val label: String, val progress: Float)

    /** One song of a transition as the transition view shows it. */
    data class Side(
        val title: String, val artist: String, val artworkUrl: String?, val durationMs: Long?,
        val bpm: Double?, val wave: com.opentune.playback.mix.Wave?,
        /** The song's key on the Camelot wheel ("10A"), when it's clear. */
        val key: String? = null,
    ) {
        companion object {
            fun of(item: MediaItem, durationMs: Long?, beat: com.opentune.playback.mix.Beat?, wave: com.opentune.playback.mix.Wave?, key: com.opentune.playback.mix.Key? = null): Side {
                val m = item.mediaMetadata
                return Side(
                    m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(),
                    m.extras?.getString(EXTRA_THUMB) ?: m.artworkUri?.toString(),
                    durationMs ?: item.mediaMetadata.durationMs,
                    beat?.takeIf { it.sure }?.bpm, wave, key?.camelot,
                )
            }
        }
    }

    /** The next transition, as planned: the two songs, where and how they meet; [reading] while they're still being read. */
    data class Picture(val out: Side, val into: Side, val plan: MixPlan, val automix: Boolean, val reading: Boolean = false)

    /**
     * Where the playing song is, on its own clock, for the transition view's playhead; carried
     * on through the blend by the song coming in. Read on the main thread only.
     */
    @Volatile var clock: (() -> Long)? = null
        internal set

    private val _picture = kotlinx.coroutines.flow.MutableStateFlow<Picture?>(null)
    val upcoming: kotlinx.coroutines.flow.StateFlow<Picture?> = _picture

    fun picture(p: Picture?) { _picture.value = p }

    private val _now = kotlinx.coroutines.flow.MutableStateFlow<Mix?>(null)
    val now: kotlinx.coroutines.flow.StateFlow<Mix?> = _now

    internal fun start(label: String) { _now.value = Mix(label, 0f) }
    internal fun progress(p: Float) { _now.value?.let { _now.value = it.copy(progress = p.coerceIn(0f, 1f)) } }
    internal fun end() { _now.value = null; _showing.value = null }

    /**
     * The song a mix is bringing in, for the player to show from the moment the mix starts
     * rather than once the main player takes it over: which queue entry it is, and its
     * time, read from the second player. Only ever read on the main thread.
     */
    class Showing(val item: MediaItem, val index: Int, val durationMs: () -> Long, val positionMs: () -> Long)

    private val _showing = kotlinx.coroutines.flow.MutableStateFlow<Showing?>(null)
    val showing: kotlinx.coroutines.flow.StateFlow<Showing?> = _showing

    internal fun show(s: Showing?) { _showing.value = s }
}
