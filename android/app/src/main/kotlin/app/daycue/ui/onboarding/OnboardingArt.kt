package app.daycue.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion

/**
 * The onboarding composition (VISUAL 9): a context plane, then the cue disc, then the rule, built up as the owner
 * moves through the steps. [stage] 1 = plane, 2 = plane + disc, 3 = plane + disc + rule. Decorative.
 * Under reduced motion each stage appears at once.
 */
@Composable
fun OnboardingComposition(stage: Int, modifier: Modifier = Modifier) {
    val colors = DayCueTheme.colors
    val reduce = LocalReduceMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val plane = remember { Animatable(0f) }
    val disc = remember { Animatable(0f) }
    val rule = remember { Animatable(0f) }
    LaunchedEffect(stage, reduce) {
        suspend fun to(a: Animatable<Float, *>, target: Float) = if (reduce) a.snapTo(target) else a.animateTo(target, tween(300))
        to(plane, if (stage >= 1) 1f else 0f)
        to(disc, if (stage >= 2) 1f else 0f)
        to(rule, if (stage >= 3) 1f else 0f)
    }
    val fontScale = LocalDensity.current.fontScale
    val height: Dp = if (fontScale >= 1.5f) 96.dp else 200.dp
    Canvas(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        val left = if (rtl) 0.30f * w else 0.08f * w
        val rect = Rect(left, 0.14f * h, left + 0.62f * w, 0.14f * h + 0.78f * h)
        val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(6 * dp))) }
        val edge = if (colors.isDark) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.30f) else colors.ink.copy(alpha = 0.08f)
        rotate(-3f, rect.center) {
            val a = plane.value
            translate(dp, 1.5f * dp) { drawPath(path, edge.copy(alpha = edge.alpha * a)) }
            drawPath(path, colors.planeHome.copy(alpha = a))
        }
        val d = 0.30f * h
        val r = d / 2f
        val startX = if (rtl) 0.04f * w + r else w - 0.04f * w - r
        val center = Offset(startX, 0.22f * h + (1f - disc.value) * 12 * dp)
        if (disc.value > 0f) {
            val dp2 = Path().apply { addOval(Rect(center, r * (0.6f + 0.4f * disc.value))) }
            drawPath(dp2, colors.hydration.shape.copy(alpha = disc.value))
        }
        if (rule.value > 0f) {
            val y = 0.96f * h
            val len = w * rule.value
            val x0 = if (rtl) w - len else 0f
            drawRect(colors.ink.copy(alpha = 0.2f), topLeft = Offset(x0, y), size = Size(len, 1.5f * dp))
        }
    }
}

/** The app's own small mark: a plane with a disc. Used for permission blocks (no cue mark for system things). */
@Composable
fun AppMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val colors = DayCueTheme.colors
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val plane = Path().apply { addRoundRect(RoundRect(Rect(3f * s, 6f * s, 17f * s, 20f * s), CornerRadius(2.5f * s))) }
        drawPath(plane, colors.planeHome)
        drawCircle(colors.ink, 5f * s, Offset(16.5f * s, 8.5f * s))
    }
}

