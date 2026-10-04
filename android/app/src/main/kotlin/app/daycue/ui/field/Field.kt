package app.daycue.ui.field

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.Grain.drawGrain
import app.daycue.ui.marks.PostureMode
import app.daycue.ui.marks.drawHatch
import app.daycue.ui.marks.tone
import app.daycue.ui.theme.DayCueColors
import app.daycue.ui.theme.DayCueMotion
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the app thinks the user is. [Unknown] is drawn as the hatch and never as a confident place. */
enum class FieldContext { Home, Work, Outdoors, Transit, Unknown }

/** What is running inside the context plane. [fill] is elapsed / duration, 0..1, filled bottom-up. */
@Immutable
sealed interface FieldActive {
    data object None : FieldActive
    data class Posture(val mode: PostureMode, val fill: Float, val next: PostureMode? = null) : FieldActive
    data class Routine(val fill: Float) : FieldActive
}

/** Field height by font scale (VISUAL.md section 5): strip at >= 1.6, 120dp at >= 1.3, else clamp(0.28 * screen). */
@Composable
fun fieldHeightDp(): Float {
    val fontScale = LocalDensity.current.fontScale
    val screenH = LocalConfiguration.current.screenHeightDp
    return when {
        fontScale >= 1.6f -> 56f
        fontScale >= 1.3f -> 120f
        else -> (0.28f * screenH).coerceIn(168f, 240f)
    }
}

/**
 * The Field: time without charts. Text-free composition edge to edge on paper.
 *
 * - Time until the next cue: the disc approaches the plane, p = 1 - clamp(remaining / horizon).
 * - Progress of what runs: fill level inside the active primitive, with a 1.5dp ink edge.
 * - Reduced motion (in-app setting, animator scale 0, battery saver) removes the ambient loop, springs and
 *   slides; the disc and fill jump and the fill is quantized to 5%. Ambient motion stops after 60s regardless.
 *
 * @param remainingMinutes minutes until the next cue; ignored when [overdue]
 * @param horizonMinutes min(120, the cue's interval)
 * @param description the single TalkBack node, e.g. "At home. Standing, 12 minutes left. Next: hydration in 40 minutes."
 */
@Composable
fun Field(
    context: FieldContext,
    active: FieldActive,
    nextCue: CueType,
    remainingMinutes: Int,
    horizonMinutes: Int,
    description: String,
    modifier: Modifier = Modifier,
    overdue: Boolean = false,
    ambient: Boolean = true,
) {
    val colors = DayCueTheme.colors
    val reduce = LocalReduceMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val heightDp = fieldHeightDp()

    val targetP = if (overdue) 1f else 1f - (remainingMinutes.toFloat() / horizonMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val due = targetP >= 1f
    val p = remember { Animatable(if (reduce) targetP else 0f) }
    val seed = remember { Animatable(if (reduce && due) 1f else 0f) }
    val entered = remember { booleanArrayOf(false) }
    LaunchedEffect(targetP, reduce) {
        when {
            reduce -> p.snapTo(targetP)
            due -> p.animateTo(targetP, DayCueMotion.settle(false))
            else -> p.animateTo(targetP, DayCueMotion.progress(false, if (entered[0]) DayCueMotion.ProgressMs else DayCueMotion.EntryMs))
        }
        entered[0] = true
    }
    LaunchedEffect(due, reduce) {
        when {
            !due -> seed.snapTo(0f)
            reduce -> seed.snapTo(1f)
            else -> seed.animateTo(1f, DayCueMotion.settle(false))
        }
    }

    val targetFill = when (active) {
        is FieldActive.Posture -> active.fill
        is FieldActive.Routine -> active.fill
        FieldActive.None -> 0f
    }.coerceIn(0f, 1f).let { if (reduce) (it * 20f).roundToInt() / 20f else it }
    val fill = remember { Animatable(targetFill) }
    LaunchedEffect(targetFill, reduce) {
        if (reduce) fill.snapTo(targetFill) else fill.animateTo(targetFill, DayCueMotion.progress(false))
    }

    // Ambient loop: sine, 28s period, auto-stops after 60s so the screen comes to rest.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ambient, reduce) {
        if (!ambient || reduce) {
            phase.floatValue = 0f
            return@LaunchedEffect
        }
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it } - start
            if (t > DayCueMotion.AmbientStopAfterMs * 1_000_000L) break
            phase.floatValue = sin(2.0 * PI * t / (DayCueMotion.AmbientPeriodMs * 1_000_000.0)).toFloat()
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        withTransform({ if (rtl) scale(-1f, 1f, Offset(size.width / 2f, 0f)) }) {
            drawField(
                colors = colors,
                context = context,
                active = active,
                nextCue = nextCue,
                p = p.value,
                seedScale = seed.value,
                fill = fill.value,
                ambientPhase = phase.floatValue,
                rtl = rtl,
                strip = heightDp < 60f,
            )
        }
    }
}

private fun DrawScope.drawField(
    colors: DayCueColors,
    context: FieldContext,
    active: FieldActive,
    nextCue: CueType,
    p: Float,
    seedScale: Float,
    fill: Float,
    ambientPhase: Float,
    rtl: Boolean,
    strip: Boolean,
) {
    val w = size.width
    val h = size.height
    val dp = 1.dp.toPx()
    // Paper-edge offset is physical (light from the top-left), so undo the RTL canvas flip.
    val edgeDx = (if (rtl) -1f else 1f) * dp
    val edgeDy = 1.5f * dp
    val edgeColor = if (colors.isDark) Color.Black.copy(alpha = 0.30f) else colors.ink.copy(alpha = 0.08f)

    // 1. Context plane
    var plane = if (strip) Rect(20 * dp, 0.1f * h, 20 * dp + 0.5f * w, 0.9f * h)
    else Rect(0.08f * w, 0.14f * h, 0.08f * w + 0.62f * w, 0.14f * h + 0.78f * h)
    if (context == FieldContext.Unknown) {
        val c = plane.center
        plane = Rect(c.x - plane.width * 0.425f, c.y - plane.height * 0.425f, c.x + plane.width * 0.425f, c.y + plane.height * 0.425f)
    }
    val rot = -3f + 0.4f * ambientPhase
    val radius = 6 * dp
    val planePath = Path().apply { addRoundRect(RoundRect(plane, CornerRadius(radius))) }
    val planeColor = when (context) {
        FieldContext.Home -> colors.planeHome
        FieldContext.Work -> colors.planeWork
        FieldContext.Outdoors -> colors.planeOutdoors
        FieldContext.Transit -> colors.planeTransit
        FieldContext.Unknown -> Color.Transparent
    }
    val largePlane = minOf(plane.width, plane.height) >= 48 * dp

    rotate(rot, plane.center) {
        if (largePlane && context != FieldContext.Unknown) translate(edgeDx, edgeDy) { drawPath(planePath, edgeColor) }
        if (context == FieldContext.Unknown) {
            clipPath(planePath) {
                // 6dp pitch on a big plane (3dp reads as woven fabric). The canvas is mirrored in RTL; the hatch is not.
                withTransform({
                    if (rtl) scale(-1f, 1f, plane.center)
                    scale(dp, dp, Offset.Zero)
                }) { drawHatch(colors.unknown.shape, extent = maxOf(w, h) / dp, pitch = if (largePlane) 6f else 3f) }
            }
            drawPath(planePath, colors.unknown.ink, style = Stroke(1.5f * dp))
        } else {
            drawPath(planePath, planeColor)
            drawGrain(planePath, colors.isDark)
        }

        // 2b. Collapsed strip keeps a 24dp active mark at the plane's start, so it never reads as a skeleton.
        if (strip) {
            val e = 24 * dp
            val left = plane.left + 8 * dp
            val bottom = plane.center.y + e / 2f
            when (active) {
                FieldActive.None -> Unit
                is FieldActive.Posture -> {
                    val box = primitiveBounds(active.mode, left, bottom, e)
                    drawFillPrimitive(primitivePath(active.mode, box, dp), box, fill, colors.posture.shape, colors.posture.ink, dp)
                }
                is FieldActive.Routine -> {
                    val box = Rect(left, bottom - e, left + e, bottom)
                    drawFillPrimitive(Path().apply { addOval(box) }, box, fill, colors.routine.shape, colors.routine.ink, dp)
                }
            }
        }

        // 2. Active element, inside the plane
        if (!strip) {
            val elemH = 0.36f * h
            val left = plane.left + 0.06f * w
            val bottom = plane.bottom - 0.08f * h
            when (active) {
                FieldActive.None -> Unit
                is FieldActive.Posture -> {
                    val tone = colors.posture
                    val box = primitiveBounds(active.mode, left, bottom, elemH)
                    drawFillPrimitive(primitivePath(active.mode, box, dp), box, fill, tone.shape, tone.ink, dp)
                    active.next?.let { next ->
                        // Next mode: outline at 0.6x, just outside the plane's end edge (VISUAL section 5).
                        val nextH = elemH * 0.6f
                        val nb = primitiveBounds(next, plane.right + 0.05f * w, bottom, nextH)
                        drawPath(primitivePath(next, nb, dp), tone.ink, style = Stroke(1.5f * dp))
                    }
                }
                is FieldActive.Routine -> {
                    val box = Rect(left, bottom - elemH, left + elemH, bottom)
                    drawFillPrimitive(Path().apply { addOval(box) }, box, fill, colors.routine.shape, colors.routine.ink, dp)
                }
            }
        }
    }

    // 3. Next-cue disc
    val d = if (strip) 0.7f * h else 0.30f * h
    val r = d / 2f
    val start = if (strip) Offset(w - 20 * dp - r, h / 2f) else Offset(w - 0.04f * w - r, 0.22f * h)
    val arrival = Offset(plane.right + 0.1f * d, plane.top + 0.45f * plane.height)
    val float = if (strip) 0f else 1.5f * dp * sin(ambientPhase * 1.3f)
    val center = Offset(start.x + (arrival.x - start.x) * p, start.y + (arrival.y - start.y) * p + float)
    val discPath = Path().apply { addOval(Rect(center, r)) }
    val tone = colors.tone(nextCue)
    if (d >= 48 * dp) translate(edgeDx, edgeDy) { drawPath(discPath, edgeColor) }
    drawPath(discPath, tone.shape)
    drawGrain(discPath, colors.isDark)
    // Due: the disc overlaps the plane and gets the cue-ink edge (and the seed below).
    if (p >= 0.999f) drawPath(discPath, tone.ink, style = Stroke(1.5f * dp))
    if (seedScale > 0f) {
        val seedCenter = Offset(center.x + 0.7f * r, center.y - 0.7f * r)
        drawCircle(colors.ink, radius = 0.1f * d * seedScale, center = seedCenter)
    }

    // 4. Now line
    if (!strip) {
        drawRect(colors.ink.copy(alpha = 0.2f), topLeft = Offset(0f, 0.96f * h), size = Size(w, 1.5f * dp))
    }
}

private fun primitiveBounds(mode: PostureMode, left: Float, bottom: Float, elemH: Float): Rect = when (mode) {
    PostureMode.Stand -> Rect(left, bottom - elemH, left + elemH / 3f, bottom)
    PostureMode.Sit -> Rect(left, bottom - elemH * 0.46f, left + elemH * 0.46f, bottom)
    PostureMode.Walk -> Rect(left, bottom - elemH * 0.46f, left + elemH * 0.46f, bottom)
}

private fun primitivePath(mode: PostureMode, box: Rect, dp: Float): Path = Path().apply {
    when (mode) {
        PostureMode.Walk -> addOval(box)
        else -> {
            val rad = 0.08f * minOf(box.width, box.height)
            addRoundRect(RoundRect(box, CornerRadius(maxOf(rad, 1.5f * dp))))
        }
    }
}

/** Bottom-up fill with a 1.5dp ink edge; the unfilled part is transparent so the plane tone shows through (not a gauge). */
private fun DrawScope.drawFillPrimitive(
    path: Path,
    box: Rect,
    fill: Float,
    shape: Color,
    edge: Color,
    dp: Float,
) {
    clipPath(path) {
        drawRect(shape, topLeft = Offset(box.left, box.bottom - fill * box.height), size = Size(box.width, fill * box.height))
    }
    drawPath(path, edge, style = Stroke(1.5f * dp))
}
