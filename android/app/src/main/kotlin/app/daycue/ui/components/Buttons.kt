package app.daycue.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.daycue.ui.theme.DayCueShapes
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

/** Ripple-free press tint (VISUAL.md motion `quick`): pressed state changes the fill, nothing else. */
internal fun Modifier.dayCueClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    role: Role,
    enabled: Boolean = true,
    onClickLabel: String? = null,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onClick = onClick,
)

@Composable
private fun ButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    container: Color,
    containerPressed: Color,
    content: Color,
    border: Color?,
    compact: Boolean,
    borderWidth: Dp = 1.5.dp,
    checkMark: Boolean = false,
    role: Role = Role.Button,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = if (compact) DayCueSpacing.minTouch else DayCueSpacing.buttonHeight)
            .defaultMinSize(minWidth = DayCueSpacing.minTouch)
            .clip(DayCueShapes.button)
            .background(if (pressed) containerPressed else container)
            .then(if (border != null) Modifier.border(BorderStroke(borderWidth, border), DayCueShapes.button) else Modifier)
            .dayCueClickable(interaction, onClick, role, enabled)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checkMark) {
                GlyphIcon(Glyph.Check, content, size = 16.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = DayCueTheme.type.button, color = content, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** Fill `ink`, text `paper`. One per screen region. Label first, no icon. 52dp (grows with font scale). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DayCueTheme.colors
    ButtonBase(
        text, onClick, modifier, enabled,
        container = if (enabled) c.ink else c.outline,
        containerPressed = c.ink2,
        content = if (enabled) c.paper else c.ink2,
        border = null, compact = false,
    )
}

/**
 * 1.5dp `outlineStrong` border, text `ink`. [compact] gives a 48dp-high button for quick controls.
 * [selected] null = plain button. Non-null = a toggle announced as a switch: when on it gets a 2dp `ink`
 * border, a `sunk` fill and a leading 16dp check (a fill alone is not visible enough, especially in dark).
 * Quick controls do not use it: they hide the control that matches the current state.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    selected: Boolean? = null,
) {
    val c = DayCueTheme.colors
    val on = selected == true
    ButtonBase(
        text, onClick,
        if (selected != null) modifier.semantics { this.selected = on; toggleableState = ToggleableState(on) } else modifier,
        enabled,
        container = if (on) c.sunk else Color.Transparent,
        containerPressed = c.sunk,
        content = if (enabled) c.ink else c.ink2,
        border = if (on) c.ink else if (enabled) c.outlineStrong else c.outline,
        compact = compact,
        borderWidth = if (on) 2.dp else 1.5.dp,
        checkMark = on,
        role = if (selected != null) Role.Switch else Role.Button,
    )
}

/** Secondary style with error `ink` text and border. Never filled red. */
@Composable
fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DayCueTheme.colors
    ButtonBase(
        text, onClick, modifier, enabled,
        container = Color.Transparent,
        containerPressed = c.sunk,
        content = c.error.ink,
        border = c.error.ink,
        compact = false,
    )
}

/**
 * Label with a hand-drawn underline: 1dp, current text color, 3dp below the baseline, label width only. The
 * platform decoration cuts through descenders (g, ק, ן), so it is never used.
 */
@Composable
internal fun UnderlinedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    underline: Boolean = true,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.then(
            if (!underline) Modifier else Modifier.drawBehind {
                val l = layout ?: return@drawBehind
                for (i in 0 until l.lineCount) {
                    val y = l.getLineBaseline(i) + 3.dp.toPx()
                    drawLine(color, Offset(l.getLineLeft(i), y), Offset(l.getLineRight(i), y), strokeWidth = 1.dp.toPx())
                }
            },
        ),
    )
}

/**
 * `ink` text with underline, 48dp touch target. For "Fix", "Change", "Test". Content padding is 0 on the start
 * side and 12dp on the end side, so the label's start edge is exactly the gutter (or the row-text start).
 * Always `ink`: the notch and the status word carry error.
 */
@Composable
fun DayCueTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DayCueTheme.colors.ink,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .defaultMinSize(minWidth = DayCueSpacing.minTouch, minHeight = DayCueSpacing.minTouch)
            .background(if (pressed) DayCueTheme.colors.sunk else Color.Transparent)
            .dayCueClickable(interaction, onClick, Role.Button, enabled)
            .padding(start = 0.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        UnderlinedText(text, DayCueTheme.type.label, color)
    }
}

/** 48dp icon button for the top bar and steppers. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
    tint: Color = DayCueTheme.colors.ink,
) {
    val c = DayCueTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = DayCueShapes.button
    Box(
        modifier
            .size(DayCueSpacing.minTouch)
            .clip(shape)
            .background(if (pressed) c.sunk else Color.Transparent)
            .then(if (outlined) Modifier.border(1.5.dp, if (enabled) c.outlineStrong else c.outline, shape) else Modifier)
            .dayCueClickable(interaction, onClick, Role.Button, enabled)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) tint else c.outlineStrong)
    }
}
