package com.opentune.playback.mix

/** Buffering or a user pause must not consume the audible blend's duration. */
internal fun blendCanAdvance(outPlaying: Boolean, inPlaying: Boolean, outgoingEnded: Boolean): Boolean =
    inPlaying && (outPlaying || outgoingEnded)

/** Keep whichever stream is still audible while the other buffers; leave pauses alone. */
internal fun bufferingGains(outPlaying: Boolean, inPlaying: Boolean): Pair<Float, Float>? = when {
    outPlaying && !inPlaying -> 1f to 0f
    inPlaying && !outPlaying -> 0f to 1f
    else -> null
}

/** Limits gain changes during recovery from buffering, without changing the planned curve. */
internal class BlendLevels(outgoing: Float = 1f, incoming: Float = 0f) {
    var outgoing = outgoing
        private set
    var incoming = incoming
        private set

    fun towards(out: Float, into: Float, elapsedMs: Long): Pair<Float, Float> {
        val step = elapsedMs.coerceIn(0, 100).toFloat() / 180f
        outgoing += (out.coerceIn(0f, 1f) - outgoing).coerceIn(-step, step)
        incoming += (into.coerceIn(0f, 1f) - incoming).coerceIn(-step, step)
        return outgoing to incoming
    }
}

/**
 * The transition view's clock through a blend, on the playing song's time line. It starts
 * where the playing song really was when the next song started sounding ([outMs]), and
 * moves on as the next song plays from where it really was then ([inMs]), so a blend that
 * started late doesn't send it back to the planned mix point. It never goes back: a seek
 * that puts the next song on the beat moves it on from where it was, never back.
 */
internal class BlendClock(private val outMs: Long, private val inMs: Long, private val outSpeed: Float) {
    private var shown = outMs
    private var lift = 0L

    /** The time to show while the next song is at [inNowMs]. */
    fun at(inNowMs: Long): Long {
        val t = outMs + ((inNowMs - inMs) * outSpeed).toLong() + lift
        if (t < shown) {
            lift += shown - t
            return shown
        }
        shown = t
        return t
    }
}
