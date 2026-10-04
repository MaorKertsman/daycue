package app.daycue.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Own icon set, drawn from the shape primitives (disc, slab, arch, rule) so no icon pack license is involved.
 * Tab glyphs have a filled (selected) and outline (unselected) form.
 */
enum class Glyph { Today, Cues, Setup, Chevron, Plus, Minus, Settings, Help, Close, Handle, Check, ChevronDown, More, MoreVertical }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, filled: Boolean = false) {
    // Chevrons point along the reading direction.
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl && glyph == Glyph.Chevron
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({
            scale(s, s, Offset.Zero)
            if (mirror) scale(-1f, 1f, Offset(12f, 0f))
        }) {
            val stroke = Stroke(width = 1.75f, cap = StrokeCap.Round)
            when (glyph) {
                Glyph.Today -> {
                    // Disc over a horizon rule.
                    if (filled) drawCircle(tint, 7.5f, Offset(12f, 10.5f)) else drawCircle(tint, 7.5f, Offset(12f, 10.5f), style = stroke)
                    drawRoundRect(tint, Offset(4f, 19.5f), Size(16f, 1.75f), CornerRadius(0.9f))
                }
                Glyph.Cues -> {
                    val tl = Offset(4f, 4f)
                    if (filled) drawRoundRect(tint, tl, Size(16f, 16f), CornerRadius(3f))
                    else drawRoundRect(tint, tl, Size(16f, 16f), CornerRadius(3f), style = stroke)
                }
                Glyph.Setup -> {
                    val arch = Path().apply {
                        moveTo(5f, 20f); lineTo(5f, 11f)
                        arcTo(Rect(5f, 3f, 19f, 17f), 180f, 180f, false)
                        lineTo(19f, 20f); close()
                    }
                    if (filled) drawPath(arch, tint) else drawPath(arch, tint, style = stroke)
                }
                Glyph.Chevron -> {
                    val path = Path().apply { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
                    drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round))
                }
                Glyph.ChevronDown -> {
                    val path = Path().apply { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }
                    drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round))
                }
                Glyph.Plus -> {
                    drawLine(tint, Offset(5f, 12f), Offset(19f, 12f), 2f, StrokeCap.Round)
                    drawLine(tint, Offset(12f, 5f), Offset(12f, 19f), 2f, StrokeCap.Round)
                }
                Glyph.Minus -> drawLine(tint, Offset(5f, 12f), Offset(19f, 12f), 2f, StrokeCap.Round)
                Glyph.Settings -> {
                    drawCircle(tint, 8f, Offset(12f, 12f), style = stroke)
                    drawCircle(tint, 2.5f, Offset(12f, 12f))
                }
                Glyph.Help -> {
                    drawCircle(tint, 9f, Offset(12f, 12f), style = stroke)
                    val q = Path().apply {
                        moveTo(9.5f, 9.8f)
                        arcTo(Rect(9.5f, 7f, 14.5f, 12f), 180f, 180f, false)
                        cubicTo(14.5f, 13.4f, 12f, 13.2f, 12f, 15f)
                    }
                    drawPath(q, tint, style = Stroke(width = 1.75f, cap = StrokeCap.Round))
                    drawCircle(tint, 1.1f, Offset(12f, 17.4f))
                }
                Glyph.Close -> {
                    drawLine(tint, Offset(6f, 6f), Offset(18f, 18f), 2f, StrokeCap.Round)
                    drawLine(tint, Offset(18f, 6f), Offset(6f, 18f), 2f, StrokeCap.Round)
                }
                Glyph.Handle -> {
                    for (col in listOf(9f, 15f)) for (row in listOf(6f, 12f, 18f)) drawCircle(tint, 1.6f, Offset(col, row))
                }
                Glyph.More -> for (x in listOf(5.5f, 12f, 18.5f)) drawCircle(tint, 1.8f, Offset(x, 12f))
                Glyph.MoreVertical -> for (y in listOf(5.5f, 12f, 18.5f)) drawCircle(tint, 1.8f, Offset(12f, y))
                Glyph.Check -> {
                    val path = Path().apply { moveTo(5f, 12.5f); lineTo(10f, 17f); lineTo(19f, 7f) }
                    drawPath(path, tint, style = Stroke(width = 2.25f, cap = StrokeCap.Round))
                }
            }
        }
    }
}
