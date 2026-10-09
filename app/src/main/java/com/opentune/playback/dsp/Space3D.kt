package com.opentune.playback.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D sound for headphones: the music travels all the way round the listener,
 * in front, overhead, behind and underneath, rather than only from ear to ear
 * as 8D audio does.
 *
 * The path is a circle around the head whose tilt swings slowly up and down,
 * so over a few turns it passes every side. Where the sound is comes from the
 * cues the ears use:
 *
 * - left and right: the near ear hears it louder and up to 0.65 ms sooner,
 *   and the far ear duller, as the head is in the way;
 * - up and down: the notch the outer ear cuts in the treble moves up as the
 *   sound rises, and the top end brightens above and darkens below;
 * - behind: a little duller again;
 * - a room: a few faint early echoes, so the sound sits around the head
 *   rather than inside it.
 *
 * It isn't Dolby Atmos, which needs tracks mixed for it and Dolby's decoder;
 * it moves an ordinary stereo song around you.
 */
internal class Space3D(private val sampleRate: Int) {
    private var turn = 0.0
    private var clock = 0.0
    private val ear = Array(2) { FloatArray(EAR_SLOTS) }
    private var earAt = 0
    private val shadow = FloatArray(2)
    private val behind = FloatArray(2)
    private val pinna = Array(2) { Swept() }
    private val air = Array(2) { Swept() }
    private val room = FloatArray(ROOM_SLOTS)
    private var roomAt = 0
    private var sinceUpdate = UPDATE_EVERY
    private val taps = ROOM_TAPS_MS.map { (it * sampleRate / 1000f).toInt().coerceIn(1, ROOM_SLOTS - 1) }

    /** Where the sound is now, for tests and pictures: x right, y front, z up, on a unit sphere. */
    var x = 0f; private set
    var y = 1f; private set
    var z = 0f; private set

    /** Moves [frame] (stereo, in place) one sample along a turn of [periodSeconds]. */
    fun process(frame: FloatArray, periodSeconds: Float) {
        turn += 2 * PI / (periodSeconds * sampleRate)
        if (turn > 2 * PI) turn -= 2 * PI
        clock += 1.0 / sampleRate
        // The circle tilts about the left-right axis and back, once every few
        // turns: the front of the circle rises overhead while the back dips
        // below, then the other way.
        val tilt = TILT_MAX * sin(2 * PI * clock / (periodSeconds * TILT_TURNS))
        val ahead = cos(turn)
        x = sin(turn).toFloat()
        y = (ahead * cos(tilt)).toFloat()
        z = (ahead * sin(tilt)).toFloat()

        if (++sinceUpdate >= UPDATE_EVERY) {
            sinceUpdate = 0
            // The outer ear's notch: about 6.5 kHz level with the ears, higher above, lower below.
            val notch = 6_500.0 + 2_500.0 * z
            val brighten = 4.0 * z
            for (c in 0..1) {
                pinna[c].peaking(sampleRate.toDouble(), notch, 2.5, -7.0)
                air[c].highShelf(sampleRate.toDouble(), 7_500.0, brighten)
            }
        }

        // Mostly one voice going round, with a little of the stereo kept for width.
        val mono = (frame[0] + frame[1]) * 0.5f
        val l = mono * 0.85f + frame[0] * 0.15f
        val r = mono * 0.85f + frame[1] * 0.15f

        // Far ear later.
        ear[0][earAt] = l
        ear[1][earAt] = r
        val lag = (abs(x) * 0.00065f * sampleRate).toInt().coerceIn(0, EAR_SLOTS - 1)
        val lagged = (earAt - lag + EAR_SLOTS) % EAR_SLOTS
        var outL = if (x > 0) ear[0][lagged] else l
        var outR = if (x < 0) ear[1][lagged] else r
        earAt = (earAt + 1) % EAR_SLOTS

        // Near ear louder (equal power), the far ear in the head's shadow.
        val angle = (x + 1f) * (PI.toFloat() / 4f)
        outL *= cos(angle) * 1.25f
        outR *= sin(angle) * 1.25f
        val shade = abs(x) * 0.7f
        if (x > 0) {
            shadow[0] += (1f - shade) * (outL - shadow[0]); outL = shadow[0]; shadow[1] = outR
        } else {
            shadow[1] += (1f - shade) * (outR - shadow[1]); outR = shadow[1]; shadow[0] = outL
        }

        // Behind: duller, the further back the more.
        val dull = (-y).coerceAtLeast(0f) * 0.5f
        behind[0] += 0.3f * (outL - behind[0])
        behind[1] += 0.3f * (outR - behind[1])
        outL = outL * (1f - dull) + behind[0] * dull
        outR = outR * (1f - dull) + behind[1] * dull

        // Height.
        outL = air[0].process(pinna[0].process(outL.toDouble())).toFloat()
        outR = air[1].process(pinna[1].process(outR.toDouble())).toFloat()

        // The room: early echoes, alternating ears, quieter as they come later.
        room[roomAt] = mono
        var echoL = 0f
        var echoR = 0f
        taps.forEachIndexed { i, d ->
            val v = room[(roomAt - d + ROOM_SLOTS) % ROOM_SLOTS] * ROOM_GAINS[i]
            if (i % 2 == 0) echoL += v else echoR += v
        }
        roomAt = (roomAt + 1) % ROOM_SLOTS

        frame[0] = outL + echoL
        frame[1] = outR + echoR
    }

    fun reset() {
        ear.forEach { it.fill(0f) }
        room.fill(0f)
        shadow.fill(0f)
        behind.fill(0f)
        pinna.forEach { it.reset() }
        air.forEach { it.reset() }
    }

    /** A biquad whose coefficients move with the sound; the cookbook formulas, as [Biquad] uses. */
    private class Swept {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() { z1 = 0.0; z2 = 0.0 }

        fun peaking(fs: Double, f: Double, q: Double, gainDb: Double) {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs
            val alpha = sin(w) / (2 * q)
            val cw = cos(w)
            val a0 = 1 + alpha / a
            set((1 + alpha * a) / a0, -2 * cw / a0, (1 - alpha * a) / a0, -2 * cw / a0, (1 - alpha / a) / a0)
        }

        fun highShelf(fs: Double, f: Double, gainDb: Double) {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs
            val cw = cos(w)
            val alpha = sin(w) / (2 * 0.70710678)
            val sa = 2 * sqrt(a) * alpha
            val a0 = (a + 1) - (a - 1) * cw + sa
            set(
                a * ((a + 1) + (a - 1) * cw + sa) / a0,
                -2 * a * ((a - 1) + (a + 1) * cw) / a0,
                a * ((a + 1) + (a - 1) * cw - sa) / a0,
                2 * ((a - 1) - (a + 1) * cw) / a0,
                ((a + 1) - (a - 1) * cw - sa) / a0,
            )
        }

        private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
            b0 = nb0; b1 = nb1; b2 = nb2; a1 = na1; a2 = na2
        }
    }

    private companion object {
        const val EAR_SLOTS = 64
        const val ROOM_SLOTS = 8192
        /** Filters follow the sound this often, in samples. */
        const val UPDATE_EVERY = 32
        /** How far the circle tips up and down, and over how many turns it swings once. */
        val TILT_MAX = 75.0 * PI / 180
        const val TILT_TURNS = 3.0
        val ROOM_TAPS_MS = listOf(7.3f, 11.1f, 17.9f, 23.3f, 31.7f, 37.1f)
        val ROOM_GAINS = floatArrayOf(0.13f, 0.12f, 0.09f, 0.08f, 0.05f, 0.045f)
    }
}
