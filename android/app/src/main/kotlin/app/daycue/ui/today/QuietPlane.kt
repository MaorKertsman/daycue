package app.daycue.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daycue.ui.field.FieldContext
import app.daycue.ui.theme.DayCueTheme

/**
 * The Field's empty composition (VISUAL 9): the context plane alone, no disc, when nothing is timed. It keeps the
 * screen from showing a blank band between the context line and the lists. Decorative; the context line speaks.
 */
@Composable
fun QuietPlane(context: FieldContext, modifier: Modifier = Modifier) {
    val colors = DayCueTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        val left = if (rtl) 0.30f * w else 0.08f * w
        val plane = Rect(left, 0.14f * h, left + 0.62f * w, 0.14f * h + 0.78f * h)
        val path = Path().apply { addRoundRect(RoundRect(plane, CornerRadius(6 * dp))) }
        rotate(-3f, plane.center) {
            if (context == FieldContext.Unknown) {
                clipPath(path) {
                    var x = plane.left - plane.height
                    while (x < plane.right) {
                        drawLine(colors.unknown.shape, Offset(x, plane.bottom), Offset(x + plane.height, plane.top), 1.5f * dp)
                        x += 6 * dp
                    }
                }
                drawPath(path, colors.unknown.ink, style = Stroke(1.5f * dp))
            } else {
                val tone = when (context) {
                    FieldContext.Home -> colors.planeHome
                    FieldContext.Work -> colors.planeWork
                    FieldContext.Outdoors -> colors.planeOutdoors
                    else -> colors.planeTransit
                }
                drawPath(path, tone)
            }
        }
        drawRect(colors.ink.copy(alpha = 0.2f), topLeft = Offset(0f, 0.96f * h), size = androidx.compose.ui.geometry.Size(w, 1.5f * dp))
    }
}
