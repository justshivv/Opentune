package com.opentune.playback

import kotlin.math.PI
import kotlin.math.sin

/**
 * The fade on pause, play and a skip by hand, as plain state, so it's tested without a player:
 * where the sound is between silent and full, which way it's going, and what the player has to
 * do when it gets there. [TransportFades] drives it against the real player.
 *
 * The sound's place is one number, 0 silent to 1 full, heard as [level] on an equal-power
 * curve; it moves at one full fade per [lengthMs], so a change of mind halfway (play pressed
 * while a pause fades) turns back from where it is, over what's left. A pause fades the
 * whole way down, then pauses; play starts silent and rises. A skip fades down quickly
 * ([skipOutMs]), makes the change, then rises again once the new song is heard.
 */
class TransportFade {
    enum class Phase {
        /** Nothing fading: the sound is the player's own. */
        IDLE,
        /** Going down to a pause, which is already shown. */
        PAUSING,
        /** Going down to a skip, made at the bottom. */
        SKIPPING,
        /** Coming up, after play or a skip. */
        RISING,
    }

    /** What to do to the player now, in this order: make the waiting skip, pause, play. */
    data class Step(val change: Boolean = false, val pause: Boolean = false, val play: Boolean = false) {
        companion object {
            val NONE = Step()
        }
    }

    var phase = Phase.IDLE
        private set

    /** The sound's place, 0 silent to 1 full. */
    private var x = 1f
    /** How long a full fade would take on the move under way. */
    private var spanMs = 1L
    /** For a skip, how long the rise after the change takes. */
    private var riseMs = 1L

    /** The player's volume now: [x] on an equal-power curve, so the fade sounds even. */
    val level: Float get() = sin(x * PI / 2).toFloat()

    /** What the session shows for play/pause: paused from the moment a pause is pressed. */
    fun shownPlayWhenReady(actual: Boolean): Boolean = if (phase == Phase.PAUSING) false else actual

    /**
     * Pause pressed. [playing] is whether the player is meant to play now; [canFade] whether a
     * fade may be made at all (on, nothing else holding the volume).
     */
    fun pause(lengthMs: Long, canFade: Boolean, playing: Boolean): Step = when {
        // A second press mid-fade means now.
        phase == Phase.PAUSING -> finish(Step(pause = true))
        // Pressed while a skip fades: the skip is made and the new song waits, paused.
        phase == Phase.SKIPPING -> finish(Step(change = true, pause = true))
        !playing || !canFade -> finish(Step(pause = true))
        else -> {
            go(Phase.PAUSING, lengthMs)
            Step.NONE
        }
    }

    /** Play pressed. */
    fun play(lengthMs: Long, canFade: Boolean, playing: Boolean): Step = when {
        // A change of mind while a pause fades: back up from where it is.
        phase == Phase.PAUSING -> {
            go(Phase.RISING, lengthMs)
            Step.NONE
        }
        // Already on its way up, or to a skip that rises after.
        phase == Phase.RISING || phase == Phase.SKIPPING -> Step(play = !playing)
        playing || !canFade -> finish(Step(play = true))
        else -> {
            x = 0f
            go(Phase.RISING, lengthMs)
            Step(play = true)
        }
    }

    /**
     * A skip pressed, or a song picked from the queue. [sounding] is whether the playing song is
     * being heard; a skip from silence (paused, still loading) is made at once.
     */
    fun skip(lengthMs: Long, canFade: Boolean, sounding: Boolean): Step = when {
        // Pressed while a pause fades: paused now, on the new song.
        phase == Phase.PAUSING -> finish(Step(change = true, pause = true))
        // Pressed again while the last one fades down: both are made now, and the new song rises.
        phase == Phase.SKIPPING -> {
            x = 0f
            riseMs = lengthMs.coerceAtLeast(1L)
            go(Phase.RISING, riseMs)
            Step(change = true)
        }
        // Still rising into a song not heard yet: the change is made now, and it rises from where it is.
        phase == Phase.RISING && canFade && !sounding -> Step(change = true)
        !canFade || !sounding -> finish(Step(change = true))
        else -> {
            riseMs = lengthMs.coerceAtLeast(1L)
            go(Phase.SKIPPING, skipOutMs(lengthMs))
            Step.NONE
        }
    }

    /**
     * [elapsedMs] went by. A fade down runs on the clock (a stall mustn't hold a pause up);
     * a rise only while [sounding], so a song still loading doesn't come in at full.
     */
    fun tick(elapsedMs: Long, sounding: Boolean): Step {
        val step = elapsedMs.coerceAtLeast(0L).toFloat() / spanMs
        return when (phase) {
            Phase.IDLE -> Step.NONE
            Phase.PAUSING -> {
                x = (x - step).coerceAtLeast(0f)
                if (x <= 0f) finish(Step(pause = true)) else Step.NONE
            }
            Phase.SKIPPING -> {
                x = (x - step).coerceAtLeast(0f)
                if (x <= 0f) {
                    go(Phase.RISING, riseMs)
                    Step(change = true)
                } else Step.NONE
            }
            Phase.RISING -> {
                if (sounding) x = (x + step).coerceAtMost(1f)
                if (x >= 1f) finish(Step.NONE) else Step.NONE
            }
        }
    }

    /**
     * Something else moved the player mid-fade (the system paused it for a call or for
     * headphones coming out, the sleep timer stopped it, a seek, a new queue). The fade lets
     * go at full volume; a pause or skip that was on its way is still made.
     */
    fun interrupt(): Step = when (phase) {
        Phase.PAUSING -> finish(Step(pause = true))
        Phase.SKIPPING -> finish(Step(change = true))
        else -> finish(Step.NONE)
    }

    /** Lets go without touching anything: something else (a blend) takes the volume over. */
    fun release() {
        phase = Phase.IDLE
        x = 1f
    }

    private fun go(to: Phase, ms: Long) {
        phase = to
        spanMs = ms.coerceAtLeast(1L)
    }

    private fun finish(step: Step): Step {
        phase = Phase.IDLE
        x = 1f
        return step
    }

    companion object {
        /** A skip shouldn't keep the old song long: it goes down in half the length, a second at most. */
        fun skipOutMs(lengthMs: Long): Long = (lengthMs / 2).coerceIn(150L, 1_000L)
    }
}
