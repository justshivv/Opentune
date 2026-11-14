package com.opentune.playback.mix

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whatever two songs read as (beats or none, bars or none, fades, quiet intros, vocals to
 * any point, keys that clash, very different levels, songs short or long), every Automix
 * plan is one that can be played: the blend fits inside the song, is never shorter than
 * the shortest blend, the next song starts within itself, and the tempo moves only a little.
 */
class AutomixPlanPropertiesTest {
    private fun beat(r: Random): Beat? = if (r.nextInt(4) == 0) null else {
        val period = 60_000.0 / r.nextDouble(70.0, 180.0)
        val groups = listOf(1, 2, 4).random(r)
        Beat(period, r.nextDouble(0.0, period), r.nextDouble(0.5, 4.0).toFloat(),
            downbeatMs = if (groups > 1) r.nextDouble(0.0, period * groups) else null, groupBeats = groups)
    }

    @Test fun everyPlanCanBePlayed() {
        val r = Random(20261106)
        repeat(5_000) { n ->
            val duration = r.nextLong(45_000, 600_000)
            val end = duration - r.nextLong(0, 8_000)
            val outro = end - (if (r.nextBoolean()) r.nextLong(0, 15_000) else 0L)
            val ending = if (r.nextInt(6) == 0) null else Ending(
                endMs = end, outroMs = outro.coerceAtLeast(0), beat = beat(r),
                key = if (r.nextBoolean()) Key(r.nextInt(12), r.nextBoolean(), r.nextFloat()) else null,
                levelDb = if (r.nextBoolean()) r.nextDouble(-40.0, -3.0).toFloat() else null,
                vocalsEndMs = if (r.nextBoolean()) r.nextLong(duration / 2, end + 1) else null,
            )
            val start = r.nextLong(0, 3_000)
            val opening = if (r.nextInt(6) == 0) null else Opening(
                startMs = start, quietIntro = r.nextBoolean(), beat = beat(r),
                key = if (r.nextBoolean()) Key(r.nextInt(12), r.nextBoolean(), r.nextFloat()) else null,
                levelDb = if (r.nextBoolean()) r.nextDouble(-40.0, -3.0).toFloat() else null,
                bodyAtMs = if (r.nextBoolean()) start + r.nextLong(0, 20_000) else null,
            )
            val p = automixPlan(duration, ending, opening)
            val why = "case $n: $p\\n ending=$ending\\n opening=$opening"
            val stop = ending?.endMs?.coerceAtMost(duration) ?: duration
            assertTrue(why, p.outEndMs == stop)
            assertTrue(why, p.mixAtMs >= 0 && p.mixAtMs < stop)
            assertTrue(why, p.blendMs >= 1_500 - 1)
            assertTrue(why, p.mixAtMs + p.blendMs <= stop + 1)
            assertTrue(why, p.inFromMs >= 0)
            assertTrue(why, abs(p.levelDb) <= 9f)
            assertTrue(why, p.alignBeats in setOf(1, 2, 4))
            assertTrue(why, p.outSpeed in 0.9f..1.1f)
            assertTrue(why, p.swapAt in 0f..1f && p.swapWidth in 0f..1f)
            // With the vocals known, the blend doesn't start over them unless there's no room after.
            val vocals = ending?.vocalsEndMs
            if (vocals != null && stop - vocals > 1_500 + 1_500) assertTrue(why, p.mixAtMs >= vocals)
        }
    }
}
