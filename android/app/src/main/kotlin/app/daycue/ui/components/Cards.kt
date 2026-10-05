package app.daycue.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import app.daycue.ui.util.NBSP
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.field.FieldContext
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.Grain.drawGrain
import app.daycue.ui.marks.drawHatch
import app.daycue.ui.marks.tone
import app.daycue.ui.theme.DayCueMotion
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

@Immutable
class CueAction(val label: String, val onClick: () -> Unit, val primary: Boolean = false)

/**
 * "Now" item with its actions. One merged TalkBack node ("title, due now, last at 10:40"); the actions are
 * buttons and are also exposed as custom actions. The title is the screen's single serif headline unless
 * [serifTitle] is false. Actions stack vertically at large font scale. Variants: habit, medication
 * (Taken + Snooze only), posture-pending ([actions] = one primary, [textActions] = the quiet ones), merged
 * ([overflowLabel]), test ([testLabel]). [moreActions] adds the 48dp trailing overflow button (Pause, Why now?).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CueCard(
    title: String,
    statusWord: String,
    modifier: Modifier = Modifier,
    cue: CueType = CueType.Hydration,
    state: CueState = CueState.Due,
    statusDetail: String? = null,
    actions: List<CueAction> = emptyList(),
    textActions: List<CueAction> = emptyList(),
    moreActions: List<CueAction> = emptyList(),
    overflowLabel: String? = null,
    onOverflow: () -> Unit = {},
    testLabel: String? = null,
    serifTitle: Boolean = true,
) {
    val c = DayCueTheme.colors
    val statusColor = when (state) {
        CueState.Due -> c.tone(cue).ink
        CueState.Snoozed -> c.snoozed.ink
        CueState.Paused -> c.paused.ink
        CueState.Error -> c.error.ink
        else -> c.ink2
    }
    val spoken = listOfNotNull(title, statusWord, statusDetail).joinToString(", ")
    val stack = LocalDensity.current.fontScale >= 1.3f || actions.size > 2
    val moreDescription = stringResource(R.string.cd_more_actions)
    var menuOpen by remember { mutableStateOf(false) }
    val overflowButton: @Composable () -> Unit = {
        Box {
            GlyphButton(Glyph.More, moreDescription, { menuOpen = true }, tint = c.ink2)
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = c.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                moreActions.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.label, style = DayCueTheme.type.body, color = c.ink) },
                        onClick = { menuOpen = false; a.onClick() },
                    )
                }
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                customActions = (actions + textActions + moreActions).map { a -> CustomAccessibilityAction(a.label) { a.onClick(); true } }
            },
    ) {
        if (testLabel != null) {
            Text(testLabel, style = DayCueTheme.type.labelSmall, color = c.ink2, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(title, style = if (serifTitle) DayCueTheme.type.headline else DayCueTheme.type.title, color = c.ink)
        Spacer(Modifier.height(4.dp))
        Row {
            Text(statusWord, style = DayCueTheme.type.body, color = statusColor)
            if (statusDetail != null) {
                Text(" · $statusDetail", style = DayCueTheme.type.body, color = c.ink2, modifier = Modifier.weight(1f, fill = false))
            }
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(DayCueSpacing.inRow))
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { a -> ActionButton(a, Modifier.fillMaxWidth()) }
                }
                if (moreActions.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { overflowButton() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    actions.forEachIndexed { i, a -> ActionButton(a, Modifier.weight(if (moreActions.isNotEmpty() && i > 0) 1.6f else 1f)) }
                    if (moreActions.isNotEmpty()) overflowButton()
                }
            }
        }
        if (textActions.isNotEmpty()) {
            FlowRow(verticalArrangement = Arrangement.Center) {
                textActions.forEach { a -> DayCueTextButton(a.label, a.onClick) }
            }
        }
        if (overflowLabel != null) {
            DayCueTextButton(overflowLabel, onOverflow)
        }
    }
}
@Composable
private fun ActionButton(a: CueAction, modifier: Modifier) {
    if (a.primary) PrimaryButton(a.label, a.onClick, modifier) else SecondaryButton(a.label, a.onClick, modifier)
}

enum class ContextLineKind { Normal, Override, Uncertain, Paused }

/**
 * `Place · Environment · Activity` summary; tap opens the context sheet. Wraps to as many lines as needed,
 * never an error color. Variants: normal, override (note "set by you"), uncertain ("Not sure" + Set it),
 * detection-paused (+ Resume).
 */
@Composable
fun ContextLine(
    text: String,
    kind: ContextLineKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    spoken: String = text,
    note: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = DayCueTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = stringResource(R.string.context_line_description, spoken)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DayCueSpacing.minTouch)
                .background(if (pressed) c.sunk else androidx.compose.ui.graphics.Color.Transparent)
                .dayCueClickable(interaction, onClick, Role.Button, onClickLabel = stringResource(R.string.context_line_click_label))
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron is inline, after the last word, so it never floats away when the line wraps.
            val density = LocalDensity.current
            val chevronSize = with(density) { 24.dp.toSp() }
            val annotated = remember(text) {
                buildAnnotatedString {
                    append(text.replace(" · ", NBSP + "· "))
                    append(NBSP)
                    appendInlineContent("chevron", "v")
                }
            }
            val inline = mapOf(
                "chevron" to InlineTextContent(
                    Placeholder(chevronSize, chevronSize, PlaceholderVerticalAlign.TextCenter),
                ) { GlyphIcon(Glyph.ChevronDown, c.ink2) },
            )
            Column(Modifier.weight(1f)) {
                Text(annotated, style = DayCueTheme.type.title, color = c.ink, inlineContent = inline)
                if (note != null) Text(note, style = DayCueTheme.type.bodySmall, color = c.ink2)
            }
        }
        if (actionLabel != null && (kind == ContextLineKind.Uncertain || kind == ContextLineKind.Paused)) {
            // Tucked under the line: the 48dp target overlaps the row slack so "Set it" sits ~8dp under the text.
            DayCueTextButton(actionLabel, onAction, Modifier.layout { m, cs ->
                val p = m.measure(cs)
                val pull = 16.dp.roundToPx()
                layout(p.width, p.height - pull) { p.place(0, -pull / 2) }
            })
        }
    }
}

/** Top bar: no color block, no elevation. Date/time over the context line, single trailing icon button. */
@Composable
fun TopHeader(
    dateText: String,
    trailingGlyph: Glyph,
    trailingDescription: String,
    onTrailingClick: () -> Unit,
    modifier: Modifier = Modifier,
    contextLine: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(dateText, style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            contextLine()
        }
        GlyphButton(trailingGlyph, trailingDescription, onTrailingClick)
    }
}

class QuickItem(val label: String, val onClick: () -> Unit)

/**
 * Contextual quick buttons (not chips): at most 4 controls plus "More", which is always rendered last. Controls
 * never show a "selected" state: the caller leaves out the one that matches the current state (Indoors while
 * indoors, "Start working" becomes "End session" during a session). 8dp gaps; wraps at large font scale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickControls(items: List<QuickItem>, more: QuickItem, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.take(4).forEach { SecondaryButton(it.label, it.onClick, compact = true) }
        SecondaryButton(more.label, more.onClick, compact = true)
    }
}

/** "Why now?" text button; expanded it shows the rule in plain words and the context sources. */
@Composable
fun WhyNow(
    rule: String,
    sources: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DayCueTheme.colors
    val whyDescription = expandedText(expanded)
    Column(modifier.fillMaxWidth()) {
        DayCueTextButton(
            stringResource(R.string.why_now),
            onToggle,
            Modifier.semantics { stateDescription = whyDescription },
        )
        if (expanded) {
            Text(rule, style = DayCueTheme.type.body, color = c.ink)
            sources.forEach { Text("· $it", style = DayCueTheme.type.bodySmall, color = c.ink2) }
        }
    }
}

/** Collapsed "More options (n changed)" with Reset. */
@Composable
fun AdvancedSection(
    changedCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = DayCueTheme.colors
    val expandedDescription = expandedText(expanded)
    val title = if (changedCount > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.advanced_more_changed, changedCount, changedCount)
    else stringResource(R.string.advanced_more)
    Column(modifier.fillMaxWidth()) {
        DayCueRow(
            primary = title,
            onClick = onToggle,
            divider = !expanded,
            semanticsExtra = { stateDescription = expandedDescription },
            trailing = { GlyphIcon(if (expanded) Glyph.ChevronDown else Glyph.Chevron, c.ink2) },
        )
        if (expanded) {
            content()
            DayCueTextButton(stringResource(R.string.advanced_reset), onReset)
        }
    }
}

enum class TimerKind { Running, Frozen, Pending, Test }

/**
 * Remaining time + progress slab, RTL-aware (fills from the start edge). The slab carries a 1.5dp `ink` edge.
 * TalkBack gets "Standing, 12 minutes left of 30" as a polite live region; callers should update
 * [description] at most once a minute.
 */
@Composable
fun ProgressTimer(
    remainingText: String,
    progress: Float,
    description: String,
    modifier: Modifier = Modifier,
    kind: TimerKind = TimerKind.Running,
    cue: CueType = CueType.Posture,
    statusText: String? = null,
) {
    val c = DayCueTheme.colors
    // Pending shows the next mode's EMPTY bar: a full bar would read as "done".
    val shownProgress = if (kind == TimerKind.Pending) 0f else progress.coerceIn(0f, 1f)
    val fillColor = if (kind == TimerKind.Frozen) c.paused.shape else c.tone(cue).shape
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo(shownProgress, 0f..1f)
            },
    ) {
        Text(remainingText, style = DayCueTheme.type.numeric, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(DayCueShapesSlab)
                .border(1.5.dp, c.tone(cue).ink, DayCueShapesSlab),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shownProgress).background(fillColor))
        }
        if (statusText != null) {
            Spacer(Modifier.height(4.dp))
            Text(statusText, style = DayCueTheme.type.bodySmall, color = if (kind == TimerKind.Frozen) c.paused.ink else c.ink2)
        }
    }
}

private val DayCueShapesSlab = app.daycue.ui.theme.DayCueShapes.slab

/** Why / without it / Allow / Not now. A plain block with its cue mark, not a card. */
@Composable
fun PermissionCard(
    cue: CueType,
    title: String,
    why: String,
    without: String,
    allowLabel: String,
    notNowLabel: String,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(DayCueSpacing.markSlot), contentAlignment = Alignment.Center) { CueMark(cue) }
            Spacer(Modifier.width(DayCueSpacing.inRow))
            Text(title, style = DayCueTheme.type.title, color = c.ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(why, style = DayCueTheme.type.body, color = c.ink)
        Spacer(Modifier.height(4.dp))
        Text(without, style = DayCueTheme.type.bodySmall, color = c.ink2)
        Spacer(Modifier.height(DayCueSpacing.inRow))
        PrimaryButton(allowLabel, onAllow, Modifier.fillMaxWidth())
        DayCueTextButton(notNowLabel, onNotNow)
    }
}

enum class StateBlockKind { Empty, Error, Offline, Uncertain }

/** Empty / error / offline / uncertain message with at most one action (UX section 2). */
@Composable
fun StateBlock(
    kind: StateBlockKind,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = DayCueTheme.colors
    when (kind) {
        StateBlockKind.Offline -> DayCueRow(
            primary = title,
            secondary = body,
            modifier = modifier,
            leading = { CueMark(CueType.Calendar, state = CueState.Scheduled) },
            trailing = if (actionLabel != null) ({ DayCueTextButton(actionLabel, onAction) }) else null,
        )
        StateBlockKind.Empty -> Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PlaneIllustration(height = 120.dp)
            Spacer(Modifier.height(DayCueSpacing.related))
            Text(title, style = DayCueTheme.type.title, color = c.ink, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(4.dp))
                Text(body, style = DayCueTheme.type.body, color = c.ink2, textAlign = TextAlign.Center)
            }
            if (actionLabel != null) {
                Spacer(Modifier.height(DayCueSpacing.related))
                PrimaryButton(actionLabel, onAction)
            }
        }
        StateBlockKind.Error, StateBlockKind.Uncertain -> Row(
            modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(DayCueSpacing.inRow),
        ) {
            Box(Modifier.size(DayCueSpacing.markSlot), contentAlignment = Alignment.Center) {
                if (kind == StateBlockKind.Error) StatusNotch() else HatchSwatch()
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = DayCueTheme.type.titleSmall, color = if (kind == StateBlockKind.Error) c.error.ink else c.unknown.ink)
                if (body != null) Text(body, style = DayCueTheme.type.bodySmall, color = c.ink2)
                if (actionLabel != null) DayCueTextButton(actionLabel, onAction)
            }
        }
    }
}

/** Small hatched square: "not sure". Used only for uncertainty (never decorative). */
@Composable
fun HatchSwatch(modifier: Modifier = Modifier, size: Dp = DayCueSpacing.markSize) {
    val c = DayCueTheme.colors
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            val p = Path().apply { addRoundRect(RoundRect(3f, 3f, 21f, 21f, 3f, 3f)) }
            clipPath(p) { drawHatch(c.unknown.shape) }
            drawPath(p, c.unknown.ink, style = Stroke(1.5f))
        }
    }
}

/**
 * Empty-state illustration: the context plane alone (no disc: "nothing scheduled"), at most 160dp tall.
 * [hatched] draws the unknown hatch instead of a context tone.
 */
@Composable
fun PlaneIllustration(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    context: FieldContext = FieldContext.Home,
    hatched: Boolean = false,
) {
    val c = DayCueTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fill = when (context) {
        FieldContext.Home -> c.planeHome
        FieldContext.Work -> c.planeWork
        FieldContext.Outdoors -> c.planeOutdoors
        FieldContext.Transit -> c.planeTransit
        FieldContext.Unknown -> c.planeHome
    }
    Canvas(modifier.width(height * 1.5f).height(height)) {
        val dp = 1.dp.toPx()
        val w = size.width * 0.8f
        val h = size.height * 0.72f
        val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
        val path = Path().apply { addRoundRect(RoundRect(tl.x, tl.y, tl.x + w, tl.y + h, CornerRadius(6 * dp))) }
        val edge = if (c.isDark) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.30f) else c.ink.copy(alpha = 0.08f)
        rotate(if (rtl) 3f else -3f, Offset(size.width / 2, size.height / 2)) {
            if (!hatched) translate((if (rtl) -1 else 1) * dp, 1.5f * dp) { drawPath(path, edge) }
            if (hatched) {
                clipPath(path) { withTransform({ scale(dp, dp, Offset.Zero) }) { drawHatch(c.unknown.shape, extent = 400f, pitch = 6f) } }
                drawPath(path, c.unknown.ink, style = Stroke(1.5f * dp))
            } else {
                drawPath(path, fill)
                drawGrain(path, c.isDark)
            }
        }
    }
}

/** Human-readable change preview with confirm (medication, import, remote change). */
@Composable
fun DiffReview(
    title: String,
    lines: List<String>,
    confirmLabel: String,
    backLabel: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(title, style = DayCueTheme.type.title, color = c.ink)
        Spacer(Modifier.height(8.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 10.dp).size(4.dp).background(c.ink2, CircleShape))
                Text(line, style = DayCueTheme.type.body, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(DayCueSpacing.related))
        PrimaryButton(confirmLabel, onConfirm, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton(backLabel, onBack, Modifier.fillMaxWidth())
    }
}

/** Plays a real cue as a test; the mark does the due animation once (seed scales 0 to 1, no repeat). */
@Composable
fun CuePreviewButton(
    cue: CueType,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val seed = remember { Animatable(0f) }
    var played by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton(label, {
            played = true
            onClick()
            scope.launch {
                if (reduce) seed.snapTo(1f) else {
                    seed.snapTo(0f)
                    seed.animateTo(1f, DayCueMotion.settle(false))
                }
            }
        })
        Box(Modifier.size(DayCueSpacing.markSlot), contentAlignment = Alignment.Center) {
            CueMark(cue, state = if (played) CueState.Due else CueState.Scheduled, seedScale = seed.value)
        }
    }
}

@Composable
private fun expandedText(expanded: Boolean): String =
    stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
