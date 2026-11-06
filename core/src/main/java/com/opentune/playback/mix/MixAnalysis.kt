package com.opentune.playback.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * What a transition needs to know about two songs, worked out from their
 * sound: where the outgoing one really ends (not where its file does, which
 * is often seconds of silence later), whether it fades out, where the
 * incoming one really starts, and the beat of each, so a blend can start on
 * a beat and last whole bars.
 *
 * Everything here works on [Features], a loudness and an onset value for each
 * ~10 ms of sound, so it runs on plain arrays and is tested without a decoder.
 */

/** A stretch of a song as one loudness and one onset strength per hop. */
class Features(
    /** Song time of the first hop. */
    val startMs: Long,
    val hopMs: Double,
    /** Mean power of each hop, in dB below full scale. */
    val powerDb: FloatArray,
    /** How sharply the sound rose at each hop: drum hits, plucked notes. */
    val onset: FloatArray,
    /** How sharply the bass rose at each hop (kick drums), for where bars start; the onset when not measured. */
    val bass: FloatArray = onset,
) {
    val size: Int get() = powerDb.size
    val endMs: Long get() = timeOf(size.toDouble())

    fun timeOf(index: Double): Long = startMs + (index * hopMs).roundToLong()
    fun indexOf(ms: Long): Int = ((ms - startMs) / hopMs).roundToInt().coerceIn(0, max(0, size - 1))

    companion object {
        /**
         * From the mean square of each hop, over the whole band and below about
         * 150 Hz. The onset curve is the rise in each, log-compressed so a quiet
         * hit counts as well as a loud one, with the bass rise (kick drums)
         * weighted up and the local average taken off.
         */
        fun of(startMs: Long, hopMs: Double, energy: FloatArray, low: FloatArray): Features {
            val n = energy.size
            val powerDb = FloatArray(n) { db(energy[it]) }
            val raw = FloatArray(n)
            val bass = FloatArray(n)
            for (i in 1 until n) {
                val dl = compress(low[i]) - compress(low[i - 1])
                val df = compress(energy[i]) - compress(energy[i - 1])
                bass[i] = max(0f, dl)
                raw[i] = max(0f, dl) * 1.5f + max(0f, df)
            }
            val w = max(1, (ONSET_MEAN_MS / hopMs).roundToInt())
            val onset = FloatArray(n)
            // Centred moving mean.
            val prefix = DoubleArray(n + 1)
            for (i in 0 until n) prefix[i + 1] = prefix[i] + raw[i]
            for (i in 0 until n) {
                val a = max(0, i - w)
                val b = min(n, i + w + 1)
                val mean = (prefix[b] - prefix[a]) / (b - a)
                onset[i] = max(0.0, raw[i] - mean).toFloat()
            }
            return Features(startMs, hopMs, powerDb, onset, bass)
        }

        private fun compress(e: Float): Float = ln(1f + 1000f * e)
        private const val ONSET_MEAN_MS = 250.0
    }
}

internal fun db(power: Float): Float = if (power <= 1e-12f) -120f else 10f * log10(power)

/** Power averaged over [windowMs] around each hop, in dB: loudness as heard, not hit by hit. */
internal fun smoothedDb(f: Features, windowMs: Double): FloatArray {
    val n = f.size
    val w = max(0, (windowMs / f.hopMs / 2).roundToInt())
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + 10.0.pow(f.powerDb[i] / 10.0)
    return FloatArray(n) { i ->
        val a = max(0, i - w)
        val b = min(n, i + w + 1)
        db(((prefix[b] - prefix[a]) / (b - a)).toFloat())
    }
}

internal fun percentile(values: FloatArray, from: Int, to: Int, q: Float): Float {
    if (to <= from) return -120f
    val s = values.copyOfRange(from, to).sorted()
    return s[((s.size - 1) * q).roundToInt().coerceIn(0, s.size - 1)]
}

/**
 * A steady beat: one every [periodMs], one of them at [anchorMs]. When the beats
 * clearly come in groups (bars of four, or at least the kick-snare pairs of two),
 * [downbeatMs] is a beat that starts a group and [groupBeats] the beats in one.
 */
data class Beat(
    val periodMs: Double,
    val anchorMs: Double,
    val confidence: Float,
    val downbeatMs: Double? = null,
    val groupBeats: Int = 1,
) {
    val bpm: Double get() = 60_000.0 / periodMs
    val sure: Boolean get() = confidence >= SURE

    /** The start of the group of [beats] nearest to [ms]; just the nearest beat when the groups aren't known. */
    fun groupNearest(ms: Long, beats: Int = groupBeats): Long {
        val d = downbeatMs ?: return nearest(ms)
        val g = periodMs * beats
        return (d + Math.round((ms - d) / g) * g).roundToLong()
    }

    /** The first start of a group of [beats] at or after [ms]; the first beat when the groups aren't known. */
    fun groupAtOrAfter(ms: Long, beats: Int = groupBeats): Long {
        val d = downbeatMs ?: return atOrAfter(ms)
        val g = periodMs * beats
        return (d + kotlin.math.ceil((ms - d) / g - 1e-6) * g).roundToLong()
    }

    /** The beat nearest to [ms]. */
    fun nearest(ms: Long): Long = (anchorMs + Math.round((ms - anchorMs) / periodMs) * periodMs).roundToLong()

    /** The first beat at or after [ms]. */
    fun atOrAfter(ms: Long): Long = (anchorMs + kotlin.math.ceil((ms - anchorMs) / periodMs - 1e-6) * periodMs).roundToLong()

    companion object {
        /** Beats this clear stand well out of the music between them. */
        const val SURE = 1.8f
    }
}

/**
 * The beat in [f] between [fromMs] and [toMs], or null when nothing in it
 * repeats steadily enough to call a beat (speech, ambient pads, rubato).
 *
 * The period is the autocorrelation peak of the onset curve between 70 and
 * 180 BPM, leaning towards 120 so a song isn't heard at half or double its
 * speed, then refined with a comb over the whole stretch: the period and
 * phase whose ticks land on the strongest onsets. The confidence is how much
 * stronger the onsets on the ticks are than the onsets overall.
 */
fun beatGrid(f: Features, fromMs: Long = f.startMs, toMs: Long = f.endMs): Beat? {
    val a = f.indexOf(fromMs)
    val b = min(f.size, f.indexOf(toMs) + 1)
    val n = b - a
    val o = f.onset
    val minLag = (60_000.0 / MAX_BPM / f.hopMs).toInt()
    val maxLag = (60_000.0 / MIN_BPM / f.hopMs).toInt() + 1
    if (n < maxLag * 6) return null
    var mean = 0.0
    for (i in a until b) mean += o[i]
    mean /= n
    if (mean <= 1e-6) return null

    // 1. Autocorrelation, weighted towards a moderate tempo.
    var bestLag = -1
    var bestScore = 0.0
    val r = DoubleArray(maxLag + 2)
    for (lag in minLag..maxLag + 1) {
        var s = 0.0
        for (i in a until b - lag) s += o[i] * o[i + lag]
        r[lag] = s / (n - lag)
    }
    for (lag in minLag..maxLag) {
        val bpm = 60_000.0 / (lag * f.hopMs)
        val lean = exp(-0.5 * (log2(bpm / LEAN_BPM) / LEAN_OCTAVES).pow(2))
        val score = r[lag] * lean
        if (score > bestScore) { bestScore = score; bestLag = lag }
    }
    if (bestLag < 0) return null
    // Between hops, by the parabola through the peak and its neighbours.
    val y0 = r[bestLag - 1]; val y1 = r[bestLag]; val y2 = r[bestLag + 1]
    val curve = y0 - 2 * y1 + y2
    val lag = bestLag + if (curve < 0) (0.5 * (y0 - y2) / curve).coerceIn(-0.5, 0.5) else 0.0

    // 2. Comb: period within 2 % of that, and the phase, by onsets landing on the ticks.
    var best = Triple(lag, 0.0, -1.0)
    var step = -20
    while (step <= 20) {
        val period = lag * (1 + step / 1000.0)
        var phase = 0.0
        while (phase < period) {
            var sum = 0.0
            var count = 0
            var t = a + phase
            while (t < b) {
                val i = t.roundToInt()
                // A tick may fall a hop either side of where the hit was measured.
                var m = o[i.coerceIn(a, b - 1)]
                if (i - 1 >= a) m = max(m, o[i - 1])
                if (i + 1 < b) m = max(m, o[i + 1])
                sum += m
                count++
                t += period
            }
            val score = sum / max(1, count)
            if (score > best.third) best = Triple(period, phase, score)
            phase += 1.0
        }
        step += 1
    }
    val (period, phase, score) = best
    // The ticks are checked a hop either side, so compare with the onsets taken the same way.
    var spread = 0.0
    for (i in a until b) {
        var m = o[i]
        if (i - 1 >= a) m = max(m, o[i - 1])
        if (i + 1 < b) m = max(m, o[i + 1])
        spread += m
    }
    spread /= n
    val confidence = (score / max(1e-9, spread)).toFloat()
    return Beat(period * f.hopMs, f.startMs + (a + phase) * f.hopMs, confidence)
}

/**
 * [beat] with where its groups start, found between [fromMs] and [toMs]: the
 * kick lands hardest on the one of each bar, so of each four beats the one
 * whose bass hits are clearly strongest starts the bar. Where beats one and
 * three hit alike (kick on both, snare between), the beats still pair up, and
 * pairs are kept in line instead. Unchanged when no beat stands out.
 */
fun withDownbeat(f: Features, beat: Beat, fromMs: Long, toMs: Long): Beat {
    val sums = DoubleArray(4)
    val counts = IntArray(4)
    val from = max(fromMs, f.startMs)
    var t = beat.anchorMs + kotlin.math.ceil((from - beat.anchorMs) / beat.periodMs - 1e-6) * beat.periodMs
    val first = t
    var k = 0
    val to = min(toMs, f.endMs)
    while (t < to) {
        val i = f.indexOf(t.roundToLong())
        var m = 0f
        for (j in i - 2..i + 2) if (j in 0 until f.size) m = max(m, f.bass[j] + 0.5f * f.onset[j])
        sums[k % 4] += m.toDouble()
        counts[k % 4]++
        k++
        t += beat.periodMs
    }
    if (counts.any { it < MIN_BARS }) return beat
    val bar = DoubleArray(4) { sums[it] / counts[it] }
    val order = (0..3).sortedByDescending { bar[it] }
    if (bar[order[0]] >= bar[order[1]] * DOWNBEAT_SURE) {
        return beat.copy(downbeatMs = first + order[0] * beat.periodMs, groupBeats = 4)
    }
    val pair = doubleArrayOf((sums[0] + sums[2]) / (counts[0] + counts[2]), (sums[1] + sums[3]) / (counts[1] + counts[3]))
    val strong = if (pair[0] >= pair[1]) 0 else 1
    if (pair[strong] >= pair[1 - strong] * DOWNBEAT_SURE) {
        return beat.copy(downbeatMs = first + strong * beat.periodMs, groupBeats = 2)
    }
    return beat
}

/** How loud the body of [f] is between hops [from] and [to]: the level it holds most of the time, in dB. */
internal fun bodyLevel(f: Features, from: Int = 0, to: Int = f.size): Float = percentile(smoothedDb(f, 400.0), from, to, 0.8f)

/** How a song ends: where its sound stops, where its outro starts, and its beat there. */
data class Ending(
    /** Where the music stops; anything after is silence or near it. */
    val endMs: Long,
    /** Where the song starts to fall away from its body (a fade-out or quiet coda); equal to [endMs] for a hard stop. */
    val outroMs: Long,
    val beat: Beat?,
    /** The stretch read, as a loudness picture for showing the mix. */
    val wave: Wave? = null,
    val key: Key? = null,
    /** How loud the song is before its outro, in dB. */
    val levelDb: Float? = null,
) {
    val outroLengthMs: Long get() = endMs - outroMs
}

/** A song's loudness over a stretch of it, [values] 0..1 evenly from [startMs] to [endMs], for drawing. */
class Wave(val startMs: Long, val endMs: Long, val values: FloatArray) {
    companion object {
        /** [f]'s loudness at [points] places: the loudest moment in each slice, on a 60 dB scale. */
        fun of(f: Features, points: Int = 240): Wave {
            val out = FloatArray(points)
            val per = f.size.toDouble() / points
            for (i in 0 until points) {
                val a = (i * per).toInt()
                val b = ((i + 1) * per).toInt().coerceAtMost(f.size).coerceAtLeast(a + 1)
                var top = -120f
                for (k in a until b) top = max(top, f.powerDb[k])
                out[i] = ((top + 60f) / 60f).coerceIn(0f, 1f)
            }
            return Wave(f.startMs, f.endMs, out)
        }
    }
}

/** How a song starts: where its sound begins, whether it eases in, and its beat. */
data class Opening(
    /** Where the music starts; anything before is silence. */
    val startMs: Long,
    /** The first seconds are clearly quieter than the song: an intro to blend over. */
    val quietIntro: Boolean,
    val beat: Beat?,
    val wave: Wave? = null,
    val key: Key? = null,
    /** How loud the song's body is, in dB. */
    val levelDb: Float? = null,
    /** Where a quiet intro has built up to the song's body: the moment its full sound arrives. */
    val bodyAtMs: Long? = null,
)

/** [f] covers the last stretch of a song [durationMs] long. */
fun analyzeEnding(f: Features, durationMs: Long, chroma: FloatArray? = null): Ending? {
    if (f.size < 100) return null
    val s = smoothedDb(f, 400.0)
    // The song's body: loud hops in the part before the end.
    val body = percentile(s, 0, max(1, (f.size * 0.6).toInt()), 0.8f)
    if (body < -60f) return null
    val floor = max(body - SILENCE_BELOW_DB, -65f)
    var last = f.size - 1
    while (last > 0 && s[last] < floor) last--
    if (last <= 0) return null
    val end = min(durationMs, f.timeOf(last + 1.0) + END_MARGIN_MS)
    var k = last
    while (k > 0 && s[k] < body - OUTRO_BELOW_DB) k--
    val outro = min(end, f.timeOf(k.toDouble()))
    val from = max(f.startMs, outro - BEAT_SPAN_MS)
    val to = outro.coerceAtLeast(f.startMs + BEAT_SPAN_MS / 2)
    val beat = beatGrid(f, from, to)?.let { withDownbeat(f, it, from, to) }
    return Ending(end, outro, beat, Wave.of(f), estimateKey(chroma), bodyLevel(f, 0, max(1, (f.size * 0.6).toInt())))
}

/** [f] covers the first stretch of a song. */
fun analyzeOpening(f: Features, chroma: FloatArray? = null): Opening? {
    if (f.size < 100) return null
    val s = smoothedDb(f, 200.0)
    val body = percentile(s, 0, f.size, 0.8f)
    if (body < -60f) return null
    val floor = max(body - SILENCE_BELOW_DB, -65f)
    var first = 0
    while (first < f.size && f.powerDb[first] < floor) first++
    if (first >= f.size) return null
    val start = max(0L, f.timeOf(first.toDouble()) - START_MARGIN_MS)
    val intro = smoothedDb(f, 1000.0)
    val a = f.indexOf(start)
    val b = min(f.size, f.indexOf(start + INTRO_SPAN_MS) + 1)
    var power = 0.0
    for (i in a until b) power += 10.0.pow(intro[i] / 10.0)
    val introDb = db((power / max(1, b - a)).toFloat())
    val beat = beatGrid(f, start, start + BEAT_SPAN_MS)?.let { withDownbeat(f, it, start, start + BEAT_SPAN_MS) }
    val quiet = introDb <= body - QUIET_INTRO_DB
    // Where the intro has built up to within a few dB of the body.
    val bodyAt = if (!quiet) null else {
        var i = a
        while (i < f.size && intro[i] < body - BODY_REACHED_DB) i++
        if (i < f.size) f.timeOf(i.toDouble()) else null
    }
    return Opening(start, quiet, beat, Wave.of(f), estimateKey(chroma), bodyLevel(f), bodyAt)
}

/**
 * When and how one song hands over to the next.
 *
 * At [mixAtMs] in the outgoing song the incoming one starts from [inFromMs];
 * the two then blend for [blendMs], and the outgoing song stops by [outEndMs].
 * With a [beatMs], the incoming song is kept in step: its beats at
 * [inBeatMs] + k·beat fall on the outgoing song's at [outBeatMs] + k·beat.
 */
data class MixPlan(
    val mixAtMs: Long,
    val outEndMs: Long,
    val inFromMs: Long,
    val blendMs: Long,
    val beatMs: Double? = null,
    val outBeatMs: Long = mixAtMs,
    val inBeatMs: Long = inFromMs,
    /** Trade the bass over: the outgoing song's falls away as the incoming song's comes in. */
    val bassSwap: Boolean = false,
    /** The incoming song's own beat, when it differs from [beatMs] (the outgoing one's). */
    val inBeatPeriodMs: Double? = beatMs,
    /**
     * The speed the outgoing song is eased to as the blend begins, so its tempo meets
     * the incoming one's (pitch kept); 1 for no change.
     */
    val outSpeed: Float = 1f,
    /**
     * How many beats are kept in line: 4 puts the incoming song's bars on the outgoing
     * one's, 2 its kick-snare pairs, 1 just its beats.
     */
    val alignBeats: Int = 1,
    /** The outgoing song fades out on its own; the blend leaves the fade to it until late. */
    val outFades: Boolean = false,
    /** The incoming song eases in on its own (a quiet intro); the blend brings it up sooner. */
    val inEases: Boolean = false,
    /** Where through the blend the bass changes hands (a bar line near the middle), and over how much of it. */
    val swapAt: Float = 0.5f,
    val swapWidth: Float = 0.25f,
    /** How much louder the incoming song is than the outgoing one, in dB, for levelling the two through the blend. */
    val levelDb: Float = 0f,
)

/** A plain crossfade of [fadeMs], without the silence at either end. */
fun crossfadePlan(durationMs: Long, fadeMs: Long, ending: Ending?, opening: Opening?): MixPlan {
    val end = ending?.endMs?.coerceAtMost(durationMs) ?: durationMs
    val blend = min(fadeMs, end / 3)
    return MixPlan(mixAtMs = end - blend, outEndMs = end, inFromMs = opening?.startMs ?: 0L, blendMs = blend)
}

/**
 * Automix: a blend shaped by the two songs.
 *
 * A song that fades out is blended across its fade; one that stops dead gets
 * a short blend just before the stop, longer when the next song eases in.
 * When the outgoing beat is clear the blend starts on a beat and lasts whole
 * bars, and when both songs have a clear beat at nearly the same tempo the
 * incoming song is kept in step with the outgoing one. The bass is traded
 * over rather than doubled.
 */
fun automixPlan(durationMs: Long, ending: Ending?, opening: Opening?): MixPlan {
    val end = ending?.endMs?.coerceAtMost(durationMs) ?: durationMs
    val outro = ending?.outroLengthMs ?: 0L
    var blend = when {
        ending == null -> DEFAULT_AUTOMIX_MS
        outro >= FADE_OUT_MS -> outro.coerceIn(MIN_AUTOMIX_MS, MAX_AUTOMIX_MS)
        opening?.quietIntro == true -> EASED_AUTOMIX_MS
        else -> HARD_STOP_AUTOMIX_MS
    }
    // Intro over outro: the next song's quiet intro runs under the end of this one, so its full
    // sound arrives as this one finishes. Longer over a fade-out than over a song that stops dead.
    val introLength = opening?.bodyAtMs?.let { it - opening.startMs }
    if (ending != null && introLength != null && introLength >= MIN_AUTOMIX_MS / 2) {
        blend = introLength.coerceAtMost(if (outro >= FADE_OUT_MS) MAX_AUTOMIX_MS + 4_000 else INTRO_OVER_STOP_MS)
    }
    val outBeat = ending?.beat?.takeIf { it.sure }
    val inBeat = opening?.beat?.takeIf { it.sure }
    var mixAt = end - blend
    var beatMs: Double? = null
    if (outBeat != null) {
        // Whole bars of four: one, two, four or eight, whichever is nearest.
        val bar = outBeat.periodMs * 4
        val bars = listOf(1, 2, 4, 8).minBy { abs(ln(it * bar / blend)) }
        blend = (bars * bar).roundToLong().coerceIn(MIN_AUTOMIX_MS / 2, MAX_AUTOMIX_MS + 2_000)
        // On the one of a bar where the bars are known, so the blend runs bar line to bar line.
        mixAt = outBeat.groupNearest(end - blend)
        if (mixAt > end - MIN_BLEND_MS) mixAt -= (outBeat.periodMs * outBeat.groupBeats).roundToLong()
        blend = min(blend, end - mixAt)
        if (inBeat != null && abs(inBeat.periodMs / outBeat.periodMs - 1) <= TEMPO_MATCH) beatMs = outBeat.periodMs
    }
    // Two keys that clash can't overlap long without sounding wrong: a short blend.
    val clash = ending?.key != null && opening?.key != null && ending.key.distance(opening.key) > 1 &&
        minOf(ending.key.confidence, opening.key.confidence) > KEY_SURE
    if (clash && outro < FADE_OUT_MS) {
        val bar = outBeat?.periodMs?.times(4)
        blend = minOf(blend, bar?.let { (it * 2).roundToLong() } ?: CLASH_BLEND_MS)
        mixAt = (outBeat?.groupNearest(end - blend) ?: (end - blend)).coerceAtMost(end - MIN_BLEND_MS)
    }
    mixAt = mixAt.coerceIn(end / 2, end - MIN_BLEND_MS)
    blend = (end - mixAt).coerceAtMost(blend).coerceAtLeast(MIN_BLEND_MS)
    // Both songs' bars known: bar on bar (or pair on pair), the incoming song cued to the one of its first bar.
    val align = if (beatMs != null && outBeat?.downbeatMs != null && inBeat?.downbeatMs != null &&
        mixAt == outBeat.groupNearest(mixAt)) min(outBeat.groupBeats, inBeat.groupBeats) else 1
    val start = opening?.startMs ?: 0L
    val inFrom = if (align > 1) inBeat!!.groupAtOrAfter(start, align) else start
    // Tempos close but not the same: the outgoing song is eased to the incoming one's tempo.
    val speed = if (beatMs != null && inBeat != null) (outBeat!!.periodMs / inBeat.periodMs).toFloat() else 1f
    // The bass changes hands on the bar line nearest the middle, over about two beats.
    val beatLength = outBeat?.periodMs
    val swapAt = beatLength?.let { b ->
        val bar = b * 4
        ((Math.round(blend / 2.0 / bar) * bar) / blend).toFloat().coerceIn(0.3f, 0.7f)
    } ?: 0.5f
    val swapWidth = beatLength?.let { (it * 2 / blend).toFloat().coerceIn(0.08f, 0.3f) } ?: 0.25f
    val level = if (ending?.levelDb != null && opening?.levelDb != null) (opening.levelDb - ending.levelDb).coerceIn(-MAX_LEVEL_DB, MAX_LEVEL_DB) else 0f
    return MixPlan(
        mixAtMs = mixAt,
        outEndMs = end,
        inFromMs = inFrom,
        blendMs = blend,
        beatMs = beatMs,
        outBeatMs = mixAt,
        inBeatMs = if (align > 1) inFrom else inBeat?.atOrAfter(inFrom) ?: inFrom,
        bassSwap = true,
        inBeatPeriodMs = if (beatMs != null) inBeat?.periodMs else null,
        outSpeed = if (abs(speed - 1f) < 0.004f) 1f else speed,
        alignBeats = align,
        outFades = outro >= FADE_OUT_MS,
        inEases = opening?.quietIntro == true,
        swapAt = swapAt,
        swapWidth = swapWidth,
        levelDb = level,
    )
}

/**
 * How far to move the incoming song to put it in step with the outgoing one,
 * in ms between −beat/2 and +beat/2 (a bar, or a pair of beats, when the plan
 * keeps those in line). Zero for a plan without a beat.
 */
fun stepCorrection(plan: MixPlan, outPositionMs: Long, inPositionMs: Long): Long {
    val group = plan.alignBeats.coerceAtLeast(1)
    val outBeat = (plan.beatMs ?: return 0L) * group
    val inBeat = (plan.inBeatPeriodMs ?: plan.beatMs) * group
    // Where each song is within its own beat (or bar), 0..1; the incoming one is moved to match.
    val outPhase = ((outPositionMs - plan.outBeatMs) / outBeat).let { it - kotlin.math.floor(it) }
    val inPhase = ((inPositionMs - plan.inBeatMs) / inBeat).let { it - kotlin.math.floor(it) }
    var e = inPhase - outPhase
    if (e > 0.5) e -= 1.0
    if (e < -0.5) e += 1.0
    return (-e * inBeat).roundToLong()
}

/**
 * Gains for the outgoing and incoming songs at [p] of the way through a blend:
 * equal power, so the level holds. A song that [outFades] on its own is left at
 * full level for the first third and faded over the rest, so its fade isn't
 * doubled and it doesn't drop away before the next song is there. One that
 * [inEases] in (a quiet intro) is at its own level by 60 % of the way.
 */
fun blendGains(p: Float, outFades: Boolean = false, inEases: Boolean = false): Pair<Float, Float> {
    val q = p.coerceIn(0f, 1f)
    val qo = if (outFades) smoothstep(0.35f, 1f, q) else q
    // A quiet intro is soft already: it comes up to its own level over the first part of the blend.
    val qi = if (inEases) smoothstep(0f, 0.6f, q) else q
    return kotlin.math.cos(qo * Math.PI / 2).toFloat() to kotlin.math.sin(qi * Math.PI / 2).toFloat()
}

/**
 * The bass cut on each song at [p] through a bass swap: the outgoing song's bass
 * goes as the incoming song's comes in, both over the same short stretch around
 * [swapAt], so the two basslines are never heard together and never both gone.
 */
fun bassCuts(p: Float, swapAt: Float = 0.5f, width: Float = 0.25f): Pair<Float, Float> {
    val w = smoothstep(swapAt - width / 2, swapAt + width / 2, p)
    return BASS_CUT_HZ * w to BASS_CUT_HZ * (1f - w)
}

/**
 * Gain trims that level two songs [levelDb] apart through a blend, as factors for
 * the outgoing and incoming songs at [p]. Only ever down: a louder incoming song
 * comes in at the outgoing one's level and rises to its own by the end; a louder
 * outgoing one is brought down to the incoming one's as it goes.
 */
fun levelTrims(levelDb: Float, p: Float): Pair<Float, Float> {
    val q = p.coerceIn(0f, 1f)
    fun gain(db: Float) = 10.0.pow(db / 20.0).toFloat()
    return if (levelDb > 0f) 1f to gain(-levelDb * (1f - q)) else gain(levelDb * q) to 1f
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Root mean square of [x] around zero; for tests. */
internal fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { s, v -> s + v * v } / max(1, x.size)).toFloat()

private const val MIN_BPM = 70.0
private const val MAX_BPM = 180.0
private const val LEAN_BPM = 120.0
private const val LEAN_OCTAVES = 1.0
private const val SILENCE_BELOW_DB = 32f
private const val OUTRO_BELOW_DB = 6f
private const val QUIET_INTRO_DB = 7f
private const val END_MARGIN_MS = 150L
private const val START_MARGIN_MS = 30L
private const val INTRO_SPAN_MS = 5_000L
private const val BEAT_SPAN_MS = 20_000L
/** An outro at least this long is a fade-out to blend across. */
private const val FADE_OUT_MS = 2_500L
private const val MIN_AUTOMIX_MS = 4_000L
private const val MAX_AUTOMIX_MS = 12_000L
private const val DEFAULT_AUTOMIX_MS = 6_000L
private const val EASED_AUTOMIX_MS = 6_000L
private const val HARD_STOP_AUTOMIX_MS = 3_500L
private const val MIN_BLEND_MS = 1_500L
/** Tempos within this of each other are close enough to keep in step without stretching. */
private const val TEMPO_MATCH = 0.08
private const val KEY_SURE = 0.02f
private const val CLASH_BLEND_MS = 2_500L
private const val BASS_CUT_HZ = 200f
/** The one of a bar must hit this much harder than any other beat of it to be called. */
private const val DOWNBEAT_SURE = 1.15
/** Bars needed to tell where they start. */
private const val MIN_BARS = 3
/** Songs further apart than this are levelled only this far. */
private const val MAX_LEVEL_DB = 9f
/** An intro has reached the song's body when it's within this of its level. */
private const val BODY_REACHED_DB = 3f
/** The longest intro laid over a song that stops dead. */
private const val INTRO_OVER_STOP_MS = 8_000L


// ---- Key -------------------------------------------------------------------------

/** A musical key: [tonic] 0 (C) to 11 (B), major or minor, and how clearly the music says it. */
data class Key(val tonic: Int, val minor: Boolean, val confidence: Float) {
    /** The Camelot wheel's name for it, as DJs mark keys: "8B" is C major, "8A" A minor. */
    val camelot: String get() = "${camelotNumber}${if (minor) "A" else "B"}"
    val camelotNumber: Int get() {
        val major = if (minor) (tonic + 3) % 12 else tonic
        return ((major * 7) % 12 + 7) % 12 + 1
    }
    val name: String get() = NOTES[tonic] + if (minor) " minor" else " major"

    /**
     * Steps between two keys round the Camelot wheel: 0 the same key or its relative,
     * 1 a neighbour; those blend without clashing. More is a clash.
     */
    fun distance(other: Key): Int {
        val d = kotlin.math.abs(camelotNumber - other.camelotNumber).let { minOf(it, 12 - it) }
        return if (minor == other.minor) d else if (d == 0) 0 else d + 1
    }

    companion object {
        private val NOTES = listOf("C", "D\u266d", "D", "E\u266d", "E", "F", "F\u266f", "G", "A\u266d", "A", "B\u266d", "B")
    }
}

// Krumhansl and Kessler's key profiles: how much each step of the scale is heard in music in that key.
private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

/** The key whose profile best matches [chroma] (12 pitch-class weights, C first); null when it's unclear. */
fun estimateKey(chroma: FloatArray?): Key? {
    if (chroma == null || chroma.size != 12 || chroma.all { it <= 0f }) return null
    var best = -2.0; var second = -2.0; var bestTonic = 0; var bestMinor = false
    for (minor in listOf(false, true)) {
        val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
        for (tonic in 0 until 12) {
            val r = correlation(DoubleArray(12) { chroma[(it + tonic) % 12].toDouble() }, profile)
            if (r > best) { second = best; best = r; bestTonic = tonic; bestMinor = minor } else if (r > second) second = r
        }
    }
    if (best < 0.5) return null
    return Key(bestTonic, bestMinor, (best - second).toFloat())
}

private fun correlation(a: DoubleArray, b: DoubleArray): Double {
    val ma = a.average(); val mb = b.average()
    var num = 0.0; var da = 0.0; var db = 0.0
    for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; num += x * y; da += x * x; db += y * y }
    return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
}

/**
 * How much of each of the 12 pitch classes [samples] (mono, at [rate] Hz) holds:
 * short-time spectra (4096-point FFT, half overlapping), each bin from about 55 Hz
 * to 1.8 kHz given to the note it's nearest, its weight compressed so loud
 * moments don't swamp the rest. C first.
 */
fun chroma(samples: FloatArray, count: Int, rate: Int): FloatArray? {
    val n = 4096
    if (count < n * 4 || rate <= 0) return null
    val out = DoubleArray(12)
    val window = DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2 * PI * it / (n - 1)) }
    val re = DoubleArray(n); val im = DoubleArray(n)
    // The note each useful bin belongs to, worked out once.
    val lo = (55.0 * n / rate).toInt().coerceAtLeast(1)
    val hi = (1_800.0 * n / rate).toInt().coerceAtMost(n / 2 - 1)
    val noteOf = IntArray(hi + 1) { k ->
        if (k < lo) -1 else {
            val f = k.toDouble() * rate / n
            val midi = 69 + 12 * log2(f / 440.0)
            ((Math.round(midi).toInt() % 12) + 12) % 12
        }
    }
    var start = 0
    while (start + n <= count) {
        for (i in 0 until n) { re[i] = samples[start + i] * window[i]; im[i] = 0.0 }
        fft(re, im)
        for (k in lo..hi) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            out[noteOf[k]] += sqrt(mag)
        }
        start += n / 2
    }
    val top = out.maxOrNull() ?: return null
    if (top <= 0) return null
    return FloatArray(12) { (out[it] / top).toFloat() }
}

/** In-place radix-2 FFT; [re].size a power of two. */
private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
    }
    var len = 2
    while (len <= n) {
        val ang = -2 * PI / len
        val wr = kotlin.math.cos(ang); val wi = kotlin.math.sin(ang)
        var i = 0
        while (i < n) {
            var cr = 1.0; var ci = 0.0
            for (k in 0 until len / 2) {
                val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                re[i + k] += ar; im[i + k] += ai
                val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
            }
            i += len
        }
        len = len shl 1
    }
}
