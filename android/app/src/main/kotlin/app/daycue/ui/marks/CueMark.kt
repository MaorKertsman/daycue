package app.daycue.ui.marks

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daycue.ui.marks.Grain.drawGrain
import app.daycue.ui.theme.DayCueColors
import app.daycue.ui.theme.DayCueTheme
import androidx.compose.foundation.layout.size

internal enum class Role {
    /** Cue-hue fill; gets the state edge. */
    Shape,
    /** Dark accent fill. */
    Ink,
    /** Always-drawn 1.5 ink outline (unselected posture modes). */
    Outline,
    /** Fill that is already edged by [Edge] (hydration level). */
    LevelShape,
    /** Optional 1.5 ink edge, hidden for the scheduled, paused and unknown states. */
    Edge,
}

/**
 * [edgeWhenLarge]: the current posture mode gets a 1.5 ink edge on marks of 48dp and up. [blendPaper]: share of
 * paper mixed into the fill as a SOLID colour (the receding routine disc), never alpha, so it stays lighter than
 * the lead disc in dark too.
 */
internal class Part(
    val path: Path,
    val role: Role,
    val alpha: Float = 1f,
    val edgeWhenLarge: Boolean = false,
    val blendPaper: Float = 0f,
)

private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
private fun slab(l: Float, t: Float, r: Float, b: Float, rad: Float) =
    Path().apply { addRoundRect(RoundRect(l, t, r, b, rad, rad)) }

private fun arch(): Path = Path().apply {
    moveTo(7f, 21f)
    lineTo(7f, 8f)
    arcTo(Rect(7f, 3f, 17f, 13f), 180f, 180f, false)
    lineTo(17f, 21f)
    close()
}

private fun Path.rotated(deg: Float, px: Float, py: Float): Path {
    val m = Matrix()
    m.translate(px, py)
    m.rotateZ(deg)
    m.translate(-px, -py)
    return Path().also { it.addPath(this); it.transform(m) }
}

/** Cue mark geometry on the 24-unit grid, exactly per VISUAL.md 4.2 / assets/cue-marks.svg. */
internal fun markParts(cue: CueType, hydrationLevel: Float?, posture: PostureMode?): List<Part> = when (cue) {
    CueType.Sunscreen -> listOf(
        Part(circle(13f, 10f, 6.5f), Role.Shape),
        Part(slab(3f, 15f, 21f, 19f, 2f), Role.Ink),
    )
    CueType.Hydration, CueType.Bottle -> {
        val vessel = arch()
        val level = hydrationLevel?.coerceIn(0f, 1f)
        val fill = if (level == null || level >= 1f) vessel else {
            val top = 21f - level * 18f
            Path.combine(PathOperation.Intersect, vessel, Path().apply { addRect(Rect(7f, top, 17f, 21f)) })
        }
        buildList {
            add(Part(fill, Role.LevelShape))
            add(Part(vessel, Role.Edge))
            if (cue == CueType.Bottle) add(Part(slab(9f, 1f, 15f, 3f, 0.75f), Role.Ink))
        }
    }
    CueType.Posture -> {
        val sit = slab(2.75f, 14.5f, 8.25f, 20f, 1f)
        val stand = slab(10f, 8f, 14f, 20f, 1f)
        val walk = circle(19f, 17f, 2.75f)
        fun p(mode: PostureMode?, path: Path) =
            if (posture == null) Part(path, Role.Shape)
            else if (posture == mode) Part(path, Role.Shape, edgeWhenLarge = true)
            else Part(path, Role.Outline)
        listOf(p(PostureMode.Sit, sit), p(PostureMode.Stand, stand), p(PostureMode.Walk, walk))
    }
    CueType.Medication -> {
        val left = Path().apply {
            moveTo(12f, 8f); lineTo(7f, 8f)
            arcTo(Rect(3f, 8f, 11f, 16f), 270f, -180f, false)
            lineTo(12f, 16f); close()
        }
        val right = Path().apply {
            moveTo(12f, 8f); lineTo(17f, 8f)
            arcTo(Rect(13f, 8f, 21f, 16f), 270f, 180f, false)
            lineTo(12f, 16f); close()
        }
        listOf(Part(left.rotated(-35f, 12f, 12f), Role.Shape), Part(right.rotated(-35f, 12f, 12f), Role.Ink))
    }
    CueType.Calendar -> listOf(
        Part(slab(4f, 4f, 20f, 20f, 3f), Role.Shape),
        Part(slab(12.5f, 12.5f, 17.5f, 17.5f, 1f), Role.Ink),
    )
    CueType.Routine -> listOf(
        Part(circle(7f, 12f, 5f), Role.Shape),
        Part(circle(14.5f, 12f, 3.5f), Role.Shape, blendPaper = 0.3f),
        Part(circle(20f, 12f, 2.25f), Role.Ink),
    )
    CueType.Alarm -> {
        val half = Path().apply {
            moveTo(4f, 19f)
            arcTo(Rect(4f, 11f, 20f, 27f), 180f, 180f, false)
            close()
        }
        listOf(
            Part(half, Role.Shape, 0.85f),
            Part(slab(2f, 19f, 22f, 21f, 1f), Role.Ink),
            Part(circle(12f, 6f, 1.75f), Role.Ink),
        )
    }
}

/**
 * A cue mark. Decorative: put the meaning in text next to it (VISUAL.md do #2). Marks of 48dp and up
 * get the paper edge and grain; smaller marks stay flat.
 *
 * @param state state treatment or null for the plain mark
 * @param hydrationLevel 0..1 fill level for [CueType.Hydration] / [CueType.Bottle], null = full
 * @param postureMode current mode for [CueType.Posture], null = generic icon with all three filled
 * @param seedScale scale of the due "seed" dot, so callers can play the one-time due animation
 */
@Composable
fun CueMark(
    cue: CueType,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    state: CueState? = null,
    hydrationLevel: Float? = null,
    postureMode: PostureMode? = null,
    seedScale: Float = 1f,
) {
    val colors = DayCueTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val parts = remember(cue, hydrationLevel, postureMode) { markParts(cue, hydrationLevel, postureMode) }
    // Order-encoding marks mirror in RTL; all other marks keep their geometry (VISUAL.md 4.2).
    val mirrorBody = rtl && (cue == CueType.Routine || cue == CueType.Posture)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            drawMark(cue, parts, state, colors, mirrorBody, rtl, large = size >= 48.dp, unit = s, seedScale)
        }
    }
}

private fun DrawScope.drawMark(
    cue: CueType,
    parts: List<Part>,
    state: CueState?,
    colors: DayCueColors,
    mirrorBody: Boolean,
    rtl: Boolean,
    large: Boolean,
    unit: Float,
    seedScale: Float,
) {
    val tone = when (cue) {
        CueType.Sunscreen -> colors.sunscreen
        CueType.Hydration, CueType.Bottle -> colors.hydration
        CueType.Posture -> colors.posture
        CueType.Medication -> colors.medication
        CueType.Calendar -> colors.calendar
        CueType.Routine -> colors.routine
        CueType.Alarm -> colors.alarm
    }
    val stateTone = state?.let { colors.tone(it) }
    val fill = if (state == CueState.Snoozed || state == CueState.Paused) stateTone!!.shape else tone.shape
    val ink = if (state == CueState.Paused) stateTone!!.ink else tone.ink
    val unknown = state == CueState.Unknown
    val stroke = Stroke(width = 1.5f)
    val edgeColor = if (unknown) colors.unknown.ink else ink

    fun edged() = state == CueState.Due || state == CueState.Snoozed

    withTransform({ if (mirrorBody) scale(-1f, 1f, Offset(12f, 0f)) }) {
        if (large && !unknown) {
            // Paper edge (an unknown shape is only hatch lines on transparent: no body, so no shadow): offset copy in physical top-left light direction (never mirrored).
            val dx = (if (mirrorBody) -1f else 1f) * 1.dp.toPx() / unit
            val dy = 1.5.dp.toPx() / unit
            val shadow = if (colors.isDark) Color.Black.copy(alpha = 0.30f) else colors.ink.copy(alpha = 0.08f)
            translate(dx, dy) {
                parts.forEach { if (it.role == Role.Shape || it.role == Role.Ink || it.role == Role.LevelShape) drawPath(it.path, shadow) }
            }
        }
        parts.forEach { part ->
            when (part.role) {
                Role.Shape, Role.LevelShape -> {
                    if (unknown) {
                        clipPath(part.path) { drawHatch(colors.unknown.shape) }
                        drawPath(part.path, colors.unknown.ink, style = stroke)
                    } else {
                        drawPath(part.path, if (part.blendPaper > 0f) lerp(fill, colors.paper, part.blendPaper) else fill, alpha = part.alpha)
                        if (part.role == Role.Shape && (edged() || (part.edgeWhenLarge && large))) drawPath(part.path, tone.ink, style = stroke)
                    }
                    if (large && !unknown) drawGrain(part.path, colors.isDark)
                }
                Role.Ink -> {
                    if (unknown) drawPath(part.path, colors.unknown.ink, style = Stroke(width = 1.2f))
                    else drawPath(part.path, ink, alpha = part.alpha)
                    if (large && !unknown) drawGrain(part.path, colors.isDark)
                }
                // Inactive posture outlines recede in dark (60% alpha), so they never outshine the active fill.
                Role.Outline -> drawPath(part.path, edgeColor, alpha = if (colors.isDark && !unknown) 0.6f else 1f, style = stroke)
                Role.Edge -> {
                    val show = when (state) {
                        null, CueState.Due, CueState.Snoozed, CueState.Error -> true
                        else -> false
                    }
                    if (show) drawPath(part.path, ink, style = stroke)
                }
            }
        }
    }

    // State overlays use the logical end corner: they mirror with the layout direction.
    withTransform({ if (rtl) scale(-1f, 1f, Offset(12f, 0f)) }) {
        when (state) {
            CueState.Due -> {
                scale(seedScale, seedScale, Offset(20.5f, 3.5f)) {
                    drawCircle(colors.ink, radius = 2.5f, center = Offset(20.5f, 3.5f))
                }
            }
            CueState.Paused -> drawRoundRect(
                color = ink,
                topLeft = Offset(5f, 15f),
                size = Size(14f, 2f),
                cornerRadius = CornerRadius(1f, 1f),
            )
            CueState.Error -> rotate(45f, Offset(20f, 3f)) {
                drawRoundRect(
                    color = colors.error.ink,
                    topLeft = Offset(17.5f, 0.5f),
                    size = Size(5f, 5f),
                    cornerRadius = CornerRadius(0.75f, 0.75f),
                )
            }
            else -> Unit
        }
    }
}

/** Unknown hatch: 45 degree lines, 1 unit wide, 3 unit pitch on marks; shapes of 48dp and up pass `pitch = 6`. */
internal fun DrawScope.drawHatch(color: Color, extent: Float = 24f, pitch: Float = 3f) {
    rotate(45f, Offset(extent / 2, extent / 2)) {
        var x = -extent
        while (x < extent * 2) {
            drawLine(color, Offset(x, -extent), Offset(x, extent * 2), strokeWidth = 1f)
            x += pitch
        }
    }
}
