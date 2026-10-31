package com.opentune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.Frame
import java.awt.MouseInfo
import javax.swing.JFrame

/**
 * The window's frame. On a Mac the title bar goes clear and the app runs
 * under it, as Music and other Mac apps do, with the system's own close,
 * minimise and zoom buttons over the sidebar. On Windows the app draws its
 * own (see [WindowsFrame]): the same three coloured buttons at the head of
 * the sidebar. Linux keeps its window manager's title bar.
 */
object DesktopWindow {
    val isMac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

    /** Room above the app's content for the Mac's window buttons. */
    val topInset = if (isMac) 28.dp else 0.dp

    /** Whether the app draws the window's buttons and drag areas itself. */
    val drawsFrame: Boolean get() = WindowsFrame.drawn

    /** The window, and what closing it does; set when it's made. */
    internal var window: JFrame? = null
    internal var close: () -> Unit = {}

    fun style(window: JFrame) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    fun minimize() {
        if (!WindowsFrame.minimize()) window?.extendedState = Frame.ICONIFIED
    }

    fun toggleMaximize() {
        if (WindowsFrame.toggleMaximize()) return
        val w = window ?: return
        w.extendedState = if (w.extendedState and Frame.MAXIMIZED_BOTH != 0) Frame.NORMAL else Frame.MAXIMIZED_BOTH
    }
}

/**
 * Empty title bar: dragging it moves the window, a double click zooms it, as a
 * system title bar does. Put it behind a bar's content, not around it, so the
 * buttons and sliders on the bar take their own presses and only the empty
 * parts reach here. Nothing, where the system draws the title bar.
 */
internal fun Modifier.windowDrag(): Modifier = if (!DesktopWindow.drawsFrame) this else pointerInput(Unit) {
    var lastAt = 0L
    var lastWhere = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown()
        val twice = down.uptimeMillis - lastAt <= viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastWhere).getDistance() <= viewConfiguration.touchSlop
        if (twice) {
            lastAt = 0L
            down.consume()
            DesktopWindow.toggleMaximize()
            return@awaitEachGesture
        }
        lastAt = down.uptimeMillis
        lastWhere = down.position
        // Windows moves it, with Snap and drag-down-to-restore; without the hook, it's moved from here.
        if (WindowsFrame.startDrag()) return@awaitEachGesture
        val window = DesktopWindow.window ?: return@awaitEachGesture
        val from = window.location
        val pointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        drag(down.id) {
            val now = MouseInfo.getPointerInfo()?.location ?: return@drag
            window.setLocation(from.x + now.x - pointer.x, from.y + now.y - pointer.y)
        }
    }
}

/**
 * Close, minimise and zoom: three coloured dots that show what they do under
 * the pointer, as on a Mac and as BitChord's desktop app has them on Windows.
 */
@Composable
internal fun WindowButtons(modifier: Modifier = Modifier) {
    if (!DesktopWindow.drawsFrame) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        CaptionDot("Close", CLOSE, DesktopWindow.close) { ink ->
            val r = GLYPH.toPx()
            line(ink, -r, -r, r, r)
            line(ink, r, -r, -r, r)
        }
        CaptionDot("Minimise", MINIMISE, DesktopWindow::minimize) { ink ->
            val r = GLYPH.toPx()
            line(ink, -r, 0f, r, 0f)
        }
        CaptionDot("Zoom", ZOOM, DesktopWindow::toggleMaximize) { ink ->
            // Two small arrows pointing apart, from opposite corners.
            val r = GLYPH.toPx()
            val t = r * 0.9f
            line(ink, -r, r, -r, r - t); line(ink, -r, r, -r + t, r)
            line(ink, r, -r, r, -r + t); line(ink, r, -r, r - t, -r)
        }
    }
}

@Composable
private fun CaptionDot(label: String, fill: Color, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(CAPTION_TARGET).hoverable(hover)
            // Window furniture: the arrow, not the hand.
            .pointerHoverIcon(PointerIcon.Default)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .drawBehind {
                drawCircle(fill, DOT_RADIUS.toPx())
                if (hovered) glyph(GLYPH_INK)
            },
    )
}

private fun DrawScope.line(ink: Color, x0: Float, y0: Float, x1: Float, y1: Float) =
    drawLine(ink, Offset(center.x + x0, center.y + y0), Offset(center.x + x1, center.y + y1), STROKE.toPx(), StrokeCap.Round)

private val CAPTION_TARGET = 20.dp
private val DOT_RADIUS = 6.dp
private val GLYPH = 2.5.dp
private val STROKE = 1.dp
private val CLOSE = Color(0xFFFF5F57)
private val MINIMISE = Color(0xFFFEBC2E)
private val ZOOM = Color(0xFF28C840)
private val GLYPH_INK = Color(0x99000000)
