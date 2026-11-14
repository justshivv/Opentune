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
