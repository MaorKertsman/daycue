package app.daycue.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import app.daycue.R
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.PostureMode
import app.daycue.ui.marks.stateInk
import app.daycue.ui.marks.tone
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

/** Words for dose / command states. Always text, never a chip (UX section 6, VISUAL.md "Status text"). */
enum class StatusKind(@StringRes val wordRes: Int) {
    Upcoming(R.string.status_upcoming),
    Due(R.string.state_due),
    Taken(R.string.status_taken),
    Skipped(R.string.status_skipped),
    NotConfirmed(R.string.status_not_confirmed),
    Waiting(R.string.status_waiting),
    Applied(R.string.status_applied),
    Rejected(R.string.status_rejected),
    Expired(R.string.status_expired),
}

enum class ReadinessStatus(@StringRes val wordRes: Int) {
    Ready(R.string.readiness_ready),
    Limited(R.string.readiness_limited),
    Off(R.string.readiness_off),
    NotNeeded(R.string.readiness_not_needed),
    Checking(R.string.readiness_checking),
}

/**
 * List row: no card. Min height 64dp (56dp for one line), leading 40dp slot holding a 28dp mark, primary
 * `titleSmall`, secondary `bodySmall`. Pressed = `sunk` fill. 1dp `outline` divider inset from the text start.
 * Rows grow with font scale; text wraps to 3 lines before ellipsizing.
 */
@Composable
fun DayCueRow(
    primary: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    secondaryColor: Color = DayCueTheme.colors.ink2,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role = Role.Button,
    divider: Boolean = true,
    enabled: Boolean = true,
    semanticsExtra: SemanticsPropertyReceiver.() -> Unit = {},
) {
    val c = DayCueTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val minHeight = if (secondary == null && extra == null) DayCueSpacing.rowMinOneLine else DayCueSpacing.rowMin
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(if (pressed && onClick != null) c.sunk else Color.Transparent)
                .then(
                    if (onClick != null) Modifier.dayCueClickable(interaction, onClick, role, enabled, onClickLabel)
                    else Modifier.semantics(mergeDescendants = true) {},
                )
                .semantics {
                    if (!enabled) disabled()
                    semanticsExtra()
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(DayCueSpacing.markSlot), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(DayCueSpacing.inRow))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    primary,
                    style = DayCueTheme.type.titleSmall,
                    color = if (enabled) c.ink else c.ink2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary != null) {
                    Text(
                        secondary,
                        style = DayCueTheme.type.bodySmall,
                        color = secondaryColor,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                extra?.invoke(this)
            }
            if (trailing != null) {
                Spacer(Modifier.width(DayCueSpacing.inRow))
                trailing()
            }
        }
        if (divider) {
            Box(
                Modifier
                    .padding(start = if (leading != null) DayCueSpacing.markSlot + DayCueSpacing.inRow else 0.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.outline),
            )
        }
    }
}

/** Label + current value + chevron, opens a sheet. Changed values are marked in words. */
@Composable
fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    changed: Boolean = false,
    disabledReason: String? = null,
    leading: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
) {
    val c = DayCueTheme.colors
    val enabled = disabledReason == null
    DayCueRow(
        primary = label,
        secondary = if (enabled) value else "$value · $disabledReason",
        modifier = modifier,
        leading = leading,
        enabled = enabled,
        onClick = if (enabled) onClick else null,
        divider = divider,
        extra = if (changed) {
            { Text(stringResource(R.string.setting_changed), style = DayCueTheme.type.labelSmall, color = c.ink) }
        } else null,
        trailing = { GlyphIcon(Glyph.Chevron, if (enabled) c.ink2 else c.outlineStrong) },
    )
}

/** Row with a switch. The whole row toggles and TalkBack announces the label, not "Switch". */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    lockedReason: String? = null,
) {
    // A locked switch (for example the last enabled mode) is drawn in its real state at 38% alpha and announced
    // as "on, can't turn off: <reason>".
    val lockedState = lockedReason?.let { stringResource(R.string.switch_locked, it) }
    DayCueRow(
        primary = label,
        secondary = secondary,
        modifier = modifier,
        leading = leading,
        enabled = enabled,
        divider = divider,
        role = Role.Switch,
        onClick = { onCheckedChange(!checked) },
        semanticsExtra = {
            toggleableState = androidx.compose.ui.state.ToggleableState(checked)
            if (lockedState != null) stateDescription = lockedState
        },
        trailing = { DayCueSwitch(checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * `label` in `ink2` preceded by a slab: 12dp wide, scaled with the font (max 20dp), centred on the FIRST line
 * so a wrapped header does not float it and at 2.0 it never reads as a hyphen. 32dp above, 8dp below.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, topPadding: Dp = DayCueSpacing.section) {
    val c = DayCueTheme.colors
    val density = LocalDensity.current
    val style = DayCueTheme.type.label
    val firstLineCenter = with(density) { style.lineHeight.toDp() / 2 }
    val slabWidth = (12f * density.fontScale).coerceAtMost(20f).dp
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = DayCueSpacing.x2)
            .semantics { heading() },
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = firstLineCenter - 1.5.dp)
                .size(width = slabWidth, height = 3.dp)
                .background(c.ink2, androidx.compose.foundation.shape.RoundedCornerShape(1.5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = style, color = c.ink2)
    }
}

/** State as plain text in the state's `ink` color. Never a pill. */
@Composable
fun StatusText(
    kind: StatusKind,
    modifier: Modifier = Modifier,
    detail: String? = null,
    cue: CueType = CueType.Hydration,
) {
    val c = DayCueTheme.colors
    val color = when (kind) {
        StatusKind.Due -> c.tone(cue).ink
        StatusKind.Waiting -> c.snoozed.ink
        StatusKind.Rejected -> c.error.ink
        StatusKind.Applied -> c.ink
        else -> c.ink2
    }
    val word = stringResource(kind.wordRes)
    Text(
        if (detail != null) "$word · $detail" else word,
        modifier = modifier,
        style = DayCueTheme.type.bodySmall,
        color = color,
    )
}

/** Error notch alone (a 45 degree diamond), the leading mark of readiness and integration problems. */
@Composable
fun StatusNotch(modifier: Modifier = Modifier, markSize: androidx.compose.ui.unit.Dp = DayCueSpacing.markSize, error: Boolean = true) {
    val c = DayCueTheme.colors
    val color = if (error) c.error.ink else c.ink2
    Canvas(modifier.size(markSize)) {
        val s = this.size.minDimension / 24f
        rotate(45f, Offset(this.size.width / 2, this.size.height / 2)) {
            drawRoundRect(
                color,
                topLeft = Offset(5f * s, 5f * s),
                size = Size(14f * s, 14f * s),
                cornerRadius = CornerRadius(1f * s),
            )
        }
    }
}

/** Upcoming item: time or waiting reason. */
@Composable
fun NextRow(
    cue: CueType,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    state: CueState = CueState.Scheduled,
    onClick: () -> Unit = {},
    divider: Boolean = true,
) {
    val c = DayCueTheme.colors
    DayCueRow(
        primary = title,
        secondary = detail,
        secondaryColor = c.stateInk(cue, state),
        modifier = modifier,
        leading = { CueMark(cue, state = state) },
        onClick = onClick,
        divider = divider,
    )
}

/** Ongoing item (posture, routine, session, override, paused). No progress bar on Today: state is text. */
@Composable
fun RunningRow(
    cue: CueType,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    postureMode: PostureMode? = null,
    paused: Boolean = false,
    onClick: () -> Unit = {},
    divider: Boolean = true,
) {
    val c = DayCueTheme.colors
    DayCueRow(
        primary = title,
        secondary = detail,
        secondaryColor = if (paused) c.paused.ink else c.ink2,
        modifier = modifier,
        leading = { CueMark(cue, postureMode = postureMode, state = if (paused) CueState.Paused else null) },
        trailing = { GlyphIcon(Glyph.Chevron, c.ink2) },
        onClick = onClick,
        divider = divider,
    )
}

/** Status word + consequence + Fix. Optional integrations never count as problems. */
@Composable
fun ReadinessRow(
    name: String,
    status: ReadinessStatus,
    modifier: Modifier = Modifier,
    consequence: String? = null,
    onFix: (() -> Unit)? = null,
    fixLabel: String = stringResource(R.string.action_fix),
    divider: Boolean = true,
) {
    val c = DayCueTheme.colors
    val problem = status == ReadinessStatus.Limited || status == ReadinessStatus.Off
    val word = stringResource(status.wordRes)
    DayCueRow(
        primary = name,
        modifier = modifier,
        divider = divider,
        // The 40dp leading slot is always there (empty when ready) so text start and divider inset never change.
        leading = { if (problem) StatusNotch() },
        extra = {
            Text(word, style = DayCueTheme.type.bodySmall, color = if (problem) c.error.ink else c.ink2)
            if (consequence != null && status != ReadinessStatus.Ready) {
                Text(consequence, style = DayCueTheme.type.bodySmall, color = c.ink2)
            }
        },
        trailing = if (problem && onFix != null) ({ DayCueTextButton(fixLabel, onFix, Modifier.offset(x = 12.dp)) }) else null,
    )
}

/**
 * Routine step row (UX 3.7, VISUAL 9): six-dot `ink2` 48dp [handle] at the start, the step number beside a
 * receding routine disc (16/13/11dp, then 11dp), name and "5 min · timed" secondary, `⋮` at the end.
 */
@Composable
fun RoutineStepRow(
    index: Int,
    name: String,
    secondary: String,
    handle: @Composable () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    val c = DayCueTheme.colors
    val moreDescription = stringResource(R.string.cd_more_actions)
    // The step number sits INSIDE the routine disc; the discs recede (28/26/24dp) and lighten after the first step.
    val disc = when (index) { 0 -> 28.dp; 1 -> 26.dp; else -> 24.dp }
    val shapeColor = androidx.compose.ui.graphics.lerp(c.routine.shape, c.paper, if (index == 0) 0.45f else 0.65f)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = DayCueSpacing.rowMin), verticalAlignment = Alignment.CenterVertically) {
            handle()
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier.defaultMinSize(minWidth = disc, minHeight = disc).background(shapeColor, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = DayCueTheme.type.labelSmall, color = c.ink)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(name, style = DayCueTheme.type.titleSmall, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(secondary, style = DayCueTheme.type.bodySmall, color = c.ink2)
            }
            GlyphButton(Glyph.MoreVertical, moreDescription, onMore, tint = c.ink2)
        }
        if (divider) {
            Box(Modifier.padding(start = DayCueSpacing.minTouch + 48.dp).fillMaxWidth().height(1.dp).background(c.outline))
        }
    }
}
