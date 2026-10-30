package com.opentune.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The player's own icons where Material has nothing that fits, drawn on its
 * 24-unit grid at the same weight: lyrics as a speech bubble holding a quote,
 * and a pair of earbuds for Bluetooth output.
 */
internal object PlayerIcons {
    /** A rounded speech bubble, outlined, with two quote marks inside. */
    val Lyrics: ImageVector by lazy {
        ImageVector.Builder("Lyrics", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6f, 3.5f)
                horizontalLineTo(18f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
                verticalLineTo(14.5f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
                horizontalLineTo(10.5f)
                lineTo(6.5f, 20.5f)
                verticalLineTo(17f)
                horizontalLineTo(6f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f)
                verticalLineTo(6f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                quoteMark(8.6f, 8.6f)
                quoteMark(12.8f, 8.6f)
            }
        }.build()
    }

    /**
     * A pair of earbuds as BitChord draws them, mirrored about the middle.
     * On the outer side, a round ear tip with a tall oval speaker hole. Behind
     * it, the body: a larger round head showing as a crescent past the tip,
     * kept off it by a thin gap, and a straight stem that flares out to a
     * point under the tip where it meets the head. Traced from BitChord's
     * icon onto the 24-unit grid.
     */
    val Earbuds: ImageVector by lazy {
        ImageVector.Builder("Earbuds", 24.dp, 24.dp, 24f, 24f).apply {
            for (mirror in listOf(false, true)) {
                fun x(v: Float) = if (mirror) 24f - v else v
                val tipX = x(4.84f)
                val tipY = 7.77f
                // The ear tip, its oval hole cut out.
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                    oval(tipX, tipY, TIP_R, TIP_R)
                    oval(x(3.49f), 7.77f, 0.5f, 1.1f)
                }
                // Each part its own path, so where they overlap they add up
                // whichever way the mirroring turned their outlines.
                // The head, its edge drawn round the gap that keeps it off the tip.
                path(fill = SolidColor(Color.Black)) {
                    headAround(x(8.1f), 8.15f, 3.35f, tipX, tipY, TIP_R + 0.4f)
                }
                // The neck: its top edge runs along the gap round the tip, from
                // where the gap meets the head out to a point under the tip,
                // then it curves down into the stem and back up into the head.
                path(fill = SolidColor(Color.Black)) {
                    moveTo(x(5.81f), 10.6f)
                    arcTo(TIP_R + 0.4f, TIP_R + 0.4f, 0f, false, !mirror, x(4.74f), 10.76f)
                    lineTo(x(4.75f), 11.2f)
                    quadTo(x(6.08f), 11.5f, x(6.08f), 12.8f)
                    lineTo(x(9.06f), 12.8f)
                    quadTo(x(9.06f), 11.6f, x(9.9f), 10.6f)
                    lineTo(x(8.0f), 10.0f)
                    close()
                }
                // The stem, square-ish at the foot.
                path(fill = SolidColor(Color.Black)) {
                    val l = minOf(x(6.08f), x(9.06f))
                    val r = maxOf(x(6.08f), x(9.06f))
                    val k = 0.9f
                    moveTo(l, 12.2f)
                    horizontalLineTo(r)
                    verticalLineTo(19.46f - k)
                    quadTo(r, 19.46f, r - k, 19.46f)
                    horizontalLineTo(l + k)
                    quadTo(l, 19.46f, l, 19.46f - k)
                    close()
                }
            }
        }.build()
    }

    private const val TIP_R = 2.59f

    private fun PathBuilder.oval(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx - rx, cy)
        arcToRelative(rx, ry, 0f, true, true, 2 * rx, 0f)
        arcToRelative(rx, ry, 0f, true, true, -2 * rx, 0f)
        close()
    }

    /**
     * A circle of radius [r] about ([cx], [cy]), less where it would come
     * within [keep] of ([ax], [ay]): the long way round its own edge, then
     * back along the inside of the circle it gives way to.
     */
    private fun PathBuilder.headAround(cx: Float, cy: Float, r: Float, ax: Float, ay: Float, keep: Float) {
        val dx = ax - cx
        val dy = ay - cy
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        val a = (r * r - keep * keep + d * d) / (2 * d)
        val h = kotlin.math.sqrt((r * r - a * a).coerceAtLeast(0f))
        val ux = dx / d
        val uy = dy / d
        val px = cx + a * ux
        val py = cy + a * uy
        moveTo(px - h * uy, py + h * ux)
        arcTo(r, r, 0f, true, true, px + h * uy, py - h * ux)
        arcTo(keep, keep, 0f, false, false, px - h * uy, py + h * ux)
        close()
    }

    /**
     * Headphones, drawn plainly: a tall arch with square ends for the band,
     * and two bars either side of it for the sound, the inner ones taller.
     */
    val Headphones: ImageVector by lazy {
        ImageVector.Builder("Headphones", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            ) {
                moveTo(7.4f, 18.6f)
                verticalLineTo(10.8f)
                arcToRelative(4.6f, 4.6f, 0f, false, true, 9.2f, 0f)
                verticalLineTo(18.6f)
            }
            path(fill = SolidColor(Color.Black)) {
                // Inner bars, then the shorter outer ones.
                rect(4.35f, 11.5f, 5.85f, 17.75f)
                rect(18.15f, 11.5f, 19.65f, 17.75f)
                rect(2f, 12.35f, 3.5f, 17.05f)
                rect(20.5f, 12.35f, 22f, 17.05f)
            }
        }.build()
    }

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
        moveTo(l, t)
        horizontalLineTo(r)
        verticalLineTo(b)
        horizontalLineTo(l)
        close()
    }

    /** A filled comma-like quote mark, 2.6 wide, its top left at ([x], [y]). */
    private fun PathBuilder.quoteMark(x: Float, y: Float) {
        moveTo(x, y)
        horizontalLineToRelative(2.6f)
        verticalLineToRelative(2.4f)
        curveToRelative(0f, 1.5f, -0.7f, 2.5f, -2.1f, 2.9f)
        lineToRelative(-0.35f, -0.85f)
        curveToRelative(0.7f, -0.3f, 1.05f, -0.8f, 1.1f, -1.45f)
        horizontalLineTo(x)
        close()
    }

}
