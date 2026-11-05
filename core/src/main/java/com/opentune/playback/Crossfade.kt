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
import com.opentune.playback.mix.stepCorrection
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) {
    /** A second player and what to do when it's finished with. */
    class Tail(val player: ExoPlayer, val lowCut: (Float) -> Unit, val release: () -> Unit)

    private var tail: Tail? = null
    private var mix: Job? = null
    private var lastMixed: Pair<Int, String>? = null
    /** True while this class moves the main player itself, so its own seeks aren't read as the listener's. */
    private var handingOver = false

    // The plan for the playing song, worked out once it's near its end.
    private var plan: MixPlan? = null
    private var planFor: Triple<Int, String, String>? = null
    private var planning: Job? = null

    /** True while two songs are blending, when nothing else should move the main player's volume. */
    val blending: Boolean get() = tail != null

    private val settings get() = AppSettings.playback.value
    private val enabled get() = settings.automix || settings.crossfadeSeconds > 0

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!handingOver && tail != null) bail()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !handingOver && tail != null) bail()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val t = tail?.player ?: return
            if (!main.playWhenReady) t.pause() else t.play()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val t = tail?.player ?: return
            if (playWhenReady) t.play() else t.pause()
        }
    }

    fun start() {
        main.addListener(listener)
        scope.launch {
            while (isActive) {
                val wait = runCatching { check() }.onFailure { Log.w(TAG, "Crossfade check failed", it) }.getOrDefault(POLL_MS)
                delay(wait)
            }
        }
    }

    fun release() {
        main.removeListener(listener)
        mix?.cancel()
        planning?.cancel()
        releaseTail()
        mainLowCut(0f)
        main.volume = 1f
    }

    /** Looks at where the song is and starts what's due; returns how long to wait before looking again. */
    private fun check(): Long {
        if (!enabled || tail != null || BitPerfectUsb.active) return POLL_MS
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return POLL_MS
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < MIN_SONG_MS) return POLL_MS
        val item = main.currentMediaItem ?: return POLL_MS
        val key = main.currentMediaItemIndex to item.mediaId
        if (key == lastMixed) return POLL_MS
        val position = main.currentPosition
        val left = duration - position
        if (left > ANALYZE_AHEAD_MS) return POLL_MS
        // The song has gone silent near its end (a long fade to nothing, seconds of silence
        // before the file stops, a gap before a hidden track): the next song starts now.
        // Seen as the sound is decoded, so it works whether or not the song could be read ahead.
        if (left in MIN_LEFT_MS..SILENCE_WINDOW_MS && position > duration / 2 && silentMs() >= SILENCE_SKIP_MS && !busy()) {
            lastMixed = key
            skipSilence(plan?.inFromMs ?: 0L)
            return POLL_MS
        }

        val next = main.getMediaItemAt(main.nextMediaItemIndex)
        // Planned again if the next song changes (the queue was edited).
        val planKey = Triple(key.first, key.second, next.mediaId)
        if (planFor != planKey) {
            planFor = planKey
            plan = null
            planning?.cancel()
            planning = scope.launch { plan = makePlan(item, next, duration) }
        }
        // Still reading the songs: plan without them if time is short.
        val p = plan ?: if (left <= quickPlanLeft()) quickPlan(duration).also { plan = it; planning?.cancel() } else return SOON_MS
        if (position > p.mixAtMs - MIN_PREP_MS) {
            // Too late to line the tail up (a seek near the end): back to back this time.
            lastMixed = key
            return POLL_MS
        }
        if (position < p.mixAtMs - PREP_MS) return ((p.mixAtMs - PREP_MS - position) / 2).coerceIn(SOON_MS, POLL_MS)
        if (busy()) return SOON_MS
        lastMixed = key
        begin(item, p)
        return POLL_MS
    }

    private fun quickPlanLeft(): Long = PREP_MS + (if (settings.automix) MAX_QUICK_MS else settings.crossfadeSeconds * 1000L) + 1_000

    private fun quickPlan(duration: Long): MixPlan =
        if (settings.automix) automixPlan(duration, null, null) else crossfadePlan(duration, settings.crossfadeSeconds * 1000L, null, null)

    private suspend fun makePlan(item: MediaItem, next: MediaItem, duration: Long): MixPlan {
        val outUri = analysisUri(item)
        val inUri = analysisUri(next)
        val ending = outUri?.let { runCatching { analyzer.ending(it, duration) }.onFailure { if (it is CancellationException) throw it }.getOrNull() }
        val opening = inUri?.let { runCatching { analyzer.opening(it) }.onFailure { if (it is CancellationException) throw it }.getOrNull() }
        val p = if (settings.automix) automixPlan(duration, ending, opening)
            else crossfadePlan(duration, settings.crossfadeSeconds * 1000L, ending, opening)
        Log.d(TAG, "Plan for ${item.mediaId} → ${next.mediaId}: $p")
        MixState.picture(MixState.Picture(MixState.Side.of(item, duration, ending?.beat, ending?.wave), MixState.Side.of(next, null, opening?.beat, opening?.wave), p, settings.automix))
        return p
    }

    /** Past a silent ending into the next song, which rises in so it doesn't start with a click. */
    private fun skipSilence(inFromMs: Long) {
        Log.d(TAG, "Silent ending: on to the next song")
        mix = scope.launch {
            try {
                MixState.start(label())
                main.volume = 0f
                handingOver = true
                try { main.seekTo(main.nextMediaItemIndex, inFromMs) } finally { handingOver = false }
                sounding(main, START_TIMEOUT_MS * 2)
                ramp(SILENCE_RISE_MS) { q -> main.volume = q; MixState.progress(q) }
            } finally {
                main.volume = 1f
                MixState.end()
            }
        }
    }

    /**
     * The tail couldn't be lined up with the song: no blend of two copies, but no hard cut
     * either. The song fades out, the next one starts and fades in.
     */
    private suspend fun fadeThrough(p: MixPlan) {
        try {
            val out = (p.outEndMs - main.currentPosition).coerceIn(MIN_LEFT_MS, FADE_THROUGH_MS)
            ramp(out) { q -> main.volume = 1f - q; MixState.progress(q * 0.5f) }
            handingOver = true
            try { main.seekTo(main.nextMediaItemIndex, p.inFromMs) } finally { handingOver = false }
            sounding(main, START_TIMEOUT_MS * 2)
            ramp(FADE_THROUGH_MS) { q -> main.volume = q; MixState.progress(0.5f + q * 0.5f) }
        } finally {
            // However it ends (a skip mid-fade), the music is left at full level.
            main.volume = 1f
        }
    }

    private fun label() = if (settings.automix) "Automix" else "Crossfade"

    private fun begin(item: MediaItem, p: MixPlan) {
        val stream = sameStream(item) ?: return
        val t = runCatching { newTail() }.onFailure { Log.w(TAG, "Couldn't make the tail player", it) }.getOrNull() ?: return
        tail = t
        val tp = t.player
        tp.volume = 0f
        tp.playbackParameters = main.playbackParameters
        tp.setMediaItem(stream, main.currentPosition + TAIL_LEAD_MS)
        tp.prepare()
        tp.playWhenReady = main.playWhenReady
        val index = main.currentMediaItemIndex
        mix = scope.launch {
            try {
                // 1. The tail plays, silent, lined up with the song as it is heard now.
                val lined = lineUp(tp)
                // 2. Up to the mix point.
                while (main.currentPosition < p.mixAtMs - CROSSOVER_MS) {
                    if (main.currentMediaItemIndex != index) return@launch
                    delay(FRAME_MS)
                }
                val drift = if (lined) drift(tp) else Long.MAX_VALUE
                if (abs(drift) > MAX_DRIFT_MS) {
                    Log.w(TAG, "Tail ${if (lined) "drifted $drift ms" else "didn't line up"} for ${item.mediaId}; fading through instead")
                    releaseTail()
                    MixState.start(label())
                    fadeThrough(p)
                    return@launch
                }
                MixState.start(label())
                // 3. The sound moves onto the tail: two copies of the same moment, crossed in a breath.
                ramp(CROSSOVER_MS) { q ->
                    main.volume = 1f - q
                    tp.volume = q
                }
                // 4. The main player, silent, moves on to the next song.
                handingOver = true
                try {
                    main.seekTo(main.nextMediaItemIndex, p.inFromMs)
                } finally {
                    handingOver = false
                }
                // 5. Wait until the next song is really sounding; the tail plays on at full level meanwhile.
                val outLeft = { (p.outEndMs - tp.currentPosition).coerceAtLeast(0L) }
                if (!sounding(main, outLeft())) {
                    Log.w(TAG, "Next song didn't start before the old one ran out")
                    main.volume = 1f
                    return@launch
                }
                // 6. In step with the old song's beat, when the plan has one.
                if (p.beatMs != null) keepInStep(tp, p, outLeft)
                // 7. The blend, over what's left of the old song, at most the planned length.
                val length = minOf(p.blendMs, outLeft()).coerceAtLeast(MIN_LEFT_MS)
                ramp(length, stop = { tp.playbackState == Player.STATE_ENDED }) { q ->
                    MixState.progress(q)
                    val (out, inc) = blendGains(q)
                    tp.volume = out
                    main.volume = inc
                    if (p.bassSwap) {
                        val (outCut, inCut) = bassCuts(q)
                        t.lowCut(outCut)
                        mainLowCut(inCut)
                    }
                }
            } finally {
                MixState.end()
                // However it ended, the main player is left at full level. A bail has taken the tail already.
                if (tail === t) {
                    mainLowCut(0f)
                    main.volume = 1f
                    releaseTail()
                }
            }
        }
    }

    /**
     * Brings the tail to the main player's position, to within [ALIGNED_MS].
     * The tail's position counts from when its sound is heard, as the main
     * player's does, so once it's been playing a moment the two can be
     * compared directly; a seek puts it right, and its start-up delay is
     * learned from the first try.
     */
    private suspend fun lineUp(tp: ExoPlayer): Boolean {
        var lead = TAIL_LEAD_MS
        repeat(ALIGN_TRIES) {
            if (!sounding(tp, START_TIMEOUT_MS)) return false
            // Positions settle once each output has reported a timestamp or two.
            delay(SETTLE_MS)
            val drift = drift(tp)
            if (abs(drift) <= ALIGNED_MS) return true
            lead = (lead - drift).coerceIn(0L, 1_500L)
            tp.seekTo(main.currentPosition + lead)
        }
        if (!sounding(tp, START_TIMEOUT_MS)) return false
        delay(SETTLE_MS)
        return abs(drift(tp)) <= ALIGNED_MS
    }

    /** How far the tail is ahead of the song, the middle of a few readings so one odd reading doesn't count. */
    private suspend fun drift(tp: ExoPlayer): Long {
        val readings = LongArray(DRIFT_READINGS)
        for (i in readings.indices) {
            readings[i] = tp.currentPosition - main.currentPosition
            if (i < readings.lastIndex) delay(DRIFT_GAP_MS)
        }
        readings.sort()
        return readings[readings.size / 2]
    }

    /** Moves the incoming song (still silent) so its beats fall on the outgoing song's. */
    private suspend fun keepInStep(tp: ExoPlayer, p: MixPlan, outLeft: () -> Long) {
        // A seek restarts the sound a little after the moment asked for, so the
        // song comes back that much late; learned from each try.
        var lead = SEEK_LEAD_MS
        delay(SETTLE_MS)
        repeat(STEP_TRIES) {
            if (outLeft() < MIN_STEP_LEFT_MS) return
            val fix = stepCorrection(p, tp.currentPosition, main.currentPosition)
            if (abs(fix) <= IN_STEP_MS) return
            handingOver = true
            try {
                main.seekTo((main.currentPosition + fix + lead).coerceAtLeast(0L))
            } finally {
                handingOver = false
            }
            if (!sounding(main, outLeft())) return
            delay(SETTLE_MS)
            lead += stepCorrection(p, tp.currentPosition, main.currentPosition).coerceIn(-200L, 200L)
        }
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
            if (main.isPlaying) done += now - last
            last = now
            val q = if (stop()) 1f else (done.toFloat() / ms).coerceIn(0f, 1f)
            step(q)
            if (q >= 1f) break
            delay(FRAME_MS)
        }
    }

    /** The listener moved on mid-blend: drop the old song's tail quickly. */
    private fun bail() {
        mix?.cancel()
        val t = tail ?: return
        // The tail is this fade's now; the cancelled mix leaves it alone.
        tail = null
        // Still silent: the blend hadn't started, so this song may blend again (a seek back).
        if (t.player.volume == 0f) lastMixed = null
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
        const val PREP_MS = 6_000L
        /** Less than this before the mix point and there isn't time to line up. */
        const val MIN_PREP_MS = 2_000L
        /** Silence this long near a song's end, as it's decoded, and the next song starts. */
        const val SILENCE_SKIP_MS = 1_500L
        /** Only this near the end does silence count as the song's ending. */
        const val SILENCE_WINDOW_MS = 40_000L
        const val SILENCE_RISE_MS = 600L
        const val FADE_THROUGH_MS = 1_800L
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
        const val SEEK_LEAD_MS = 80L
        const val IN_STEP_MS = 15L
        /** Not worth stepping the incoming song when the old one is this close to ending. */
        const val MIN_STEP_LEFT_MS = 2_500L
        const val BAIL_MS = 150L
    }
}

/**
 * Whether two songs are blending now, and how far through, for the player to
 * show "Automix" where the quality badge sits while it happens.
 */
object MixState {
    data class Mix(val label: String, val progress: Float)

    /** One song of a transition as the transition view shows it. */
    data class Side(
        val title: String, val artist: String, val artworkUrl: String?, val durationMs: Long?,
        val bpm: Double?, val wave: com.opentune.playback.mix.Wave?,
    ) {
        companion object {
            fun of(item: MediaItem, durationMs: Long?, beat: com.opentune.playback.mix.Beat?, wave: com.opentune.playback.mix.Wave?): Side {
                val m = item.mediaMetadata
                return Side(
                    m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(),
                    m.extras?.getString(EXTRA_THUMB) ?: m.artworkUri?.toString(),
                    durationMs ?: item.mediaMetadata.durationMs,
                    beat?.takeIf { it.sure }?.bpm, wave,
                )
            }
        }
    }

    /** The next transition, as planned: the two songs, where and how they meet. */
    data class Picture(val out: Side, val into: Side, val plan: MixPlan, val automix: Boolean)

    private val _picture = kotlinx.coroutines.flow.MutableStateFlow<Picture?>(null)
    val upcoming: kotlinx.coroutines.flow.StateFlow<Picture?> = _picture

    fun picture(p: Picture?) { _picture.value = p }

    private val _now = kotlinx.coroutines.flow.MutableStateFlow<Mix?>(null)
    val now: kotlinx.coroutines.flow.StateFlow<Mix?> = _now

    internal fun start(label: String) { _now.value = Mix(label, 0f) }
    internal fun progress(p: Float) { _now.value?.let { _now.value = it.copy(progress = p.coerceIn(0f, 1f)) } }
    internal fun end() { _now.value = null }
}
