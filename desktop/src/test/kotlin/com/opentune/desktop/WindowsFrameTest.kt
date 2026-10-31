package com.opentune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.sun.jna.Pointer
import java.awt.GraphicsEnvironment
import java.awt.Robot
import javax.swing.SwingUtilities
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The title bar OpenTune draws on Windows, on a real window: it keeps Windows' resize edges, gives
 * the app all of the window, still draws, and maximises onto the screen's work area. Runs only on
 * Windows with a display (CI's Windows machine); skipped everywhere else.
 */
class WindowsFrameTest {
    @Test fun ownFrame() {
        assumeTrue(WindowsFrame.isWindows && !GraphicsEnvironment.isHeadless())
        assertTrue("JNA and the setting should allow the frame", WindowsFrame.drawn)
        val u = WindowsFrame.api!!.user
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.isUndecorated = true
            window.setContent { Box(Modifier.fillMaxSize().background(Color(FILL))) }
            window.setBounds(120, 120, 900, 600)
            window.isVisible = true
        }
        Thread.sleep(1_500)
        val handle = window.windowHandle
        var installed = false
        SwingUtilities.invokeAndWait { installed = WindowsFrame.install(handle) }
        assertTrue("frame installed", installed)
        Thread.sleep(1_000)
        val h = Pointer(handle)
        try {
            val style = u.GetWindowLongPtrW(h, WindowsFrame.GWL_STYLE)
            assertTrue("resizable frame style", style and WindowsFrame.WS_THICKFRAME != 0L)
            assertTrue("caption style, for Snap and the maximise animation", style and WindowsFrame.WS_CAPTION == WindowsFrame.WS_CAPTION)

            val outer = IntArray(4).also { u.GetWindowRect(h, it) }
            val inner = IntArray(4).also { u.GetClientRect(h, it) }
            println("window ${outer.toList()}, client ${inner.toList()}")
            assertEquals("the app has the whole width", outer[2] - outer[0], inner[2])
            assertEquals("the app has the whole height, no caption", outer[3] - outer[1], inner[3])

            val midX = (outer[0] + outer[2]) / 2
            val midY = (outer[1] + outer[3]) / 2
            fun hit(x: Int, y: Int) = u.SendMessageW(h, WindowsFrame.WM_NCHITTEST, 0, (x.toLong() and 0xFFFF) or ((y.toLong() and 0xFFFF) shl 16))
            assertEquals("left edge", WindowsFrame.HTLEFT, hit(outer[0] + 1, midY))
            assertEquals("right edge", WindowsFrame.HTRIGHT, hit(outer[2] - 2, midY))
            assertEquals("top edge", WindowsFrame.HTTOP, hit(midX, outer[1] + 1))
            assertEquals("bottom edge", WindowsFrame.HTBOTTOM, hit(midX, outer[3] - 2))
            assertEquals("corner", WindowsFrame.HTBOTTOMRIGHT, hit(outer[2] - 2, outer[3] - 2))
            assertEquals("middle is the app's", WindowsFrame.HTCLIENT, hit(midX, midY))

            val robot = Robot()
            val middle = robot.getPixelColor(midX, midY)
            println("middle pixel $middle")
            assertTrue("the app still draws: $middle", near(middle.rgb, FILL.toInt()))

            assertTrue(WindowsFrame.toggleMaximize())
            Thread.sleep(1_500)
            assertTrue("maximised", u.IsZoomed(h))
            val maxed = IntArray(4).also { u.GetClientRect(h, it) }
            val screen = window.graphicsConfiguration.bounds
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(window.graphicsConfiguration)
            println("maximised client ${maxed.toList()}, screen $screen, taskbar $insets")
            // The work area's corner is the app's, not a frame or something behind it.
            val corner = robot.getPixelColor(screen.x + insets.left + 3, screen.y + insets.top + 3)
            println("work area corner pixel $corner")
            assertTrue("maximised onto the work area: $corner", near(corner.rgb, FILL.toInt()))
            assertTrue(WindowsFrame.toggleMaximize())
            Thread.sleep(1_500)
            assertFalse("restored", u.IsZoomed(h))

            // Where Windows has no materials (before Windows 11 22H2) this stays off, quietly.
            WindowsFrame.applyMaterial(dark = true)
            println("material: ${WindowsFrame.activeMaterial.value} (Windows 11: ${WindowsFrame.isWindows11})")
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    private fun near(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).all { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= 12 }

    private companion object {
        const val FILL = 0xFF2050A0
    }
}
