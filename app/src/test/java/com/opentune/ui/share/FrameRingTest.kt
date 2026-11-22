package com.opentune.ui.share

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The video's frames, drawn side by side, come out whole and in order, and never stall. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FrameRingTest {
    @Test fun framesComeOutInOrderWhileDrawnSideBySide() = runBlocking {
        for (workers in listOf(1, 2, 3, 4)) {
            val busy = AtomicInteger()
            val most = AtomicInteger()
            val seen = mutableListOf<Int>()
            withTimeout(30_000) {
                FrameRing.run(240, workers, 4, 4,
                    draw = { _, n, b ->
                        most.accumulateAndGet(busy.incrementAndGet(), ::maxOf)
                        // Some frames take much longer than others, as a line change does.
                        Thread.sleep(if (n % 7 == 0) 6 else 1)
                        b.eraseColor(0xFF000000.toInt() or n)
                        busy.decrementAndGet()
                    },
                ) { n, b ->
                    // The frame handed over is the one asked for, not one drawn over it since.
                    assertEquals(n, b.getPixel(0, 0) and 0xFFFFFF)
                    seen += n
                    if (n % 11 == 0) Thread.sleep(5)
                }
            }
            assertEquals((0 until 240).toList(), seen)
            if (workers > 1) assertTrue("$workers workers never drew at once", most.get() > 1)
        }
    }
}
