package app.daycue.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.daycue.ui.theme.DayCueMotion
import app.daycue.ui.theme.DayCueShapes
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import app.daycue.ui.util.currentLocale
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * Switch: track 52x32, checked = `ink` track and `paper` thumb, unchecked = `sunk` track with a 1.5dp
 * `outlineStrong` border and thumb. No icons. 48dp touch. Pass [onCheckedChange] = null when a parent
 * row owns the toggle (so TalkBack announces the row label, not "Switch").
 */
@Composable
fun DayCueSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = DayCueTheme.colors
    val reduce = LocalReduceMotion.current
    val thumbStart by animateDpAsState(
        targetValue = if (checked) 25.dp else 5.dp,
        animationSpec = if (checked) DayCueMotion.quickEnter(reduce) else DayCueMotion.quickExit(reduce),
        label = "thumb",
    )
    val track = if (checked) c.ink else c.sunk
    val thumb = if (checked) c.paper else c.outlineStrong
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.38f)
            .defaultMinSize(minWidth = 52.dp, minHeight = DayCueSpacing.minTouch)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = null,
                        indication = null,
                        onValueChange = onCheckedChange,
                    )
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .background(track)
                .then(if (!checked) Modifier.border(1.5.dp, c.outlineStrong, CircleShape) else Modifier),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = thumbStart)
                    .size(22.dp)
                    .background(thumb, CircleShape),
            )
        }
    }
}

/**
 * One weekday toggle: 36dp visual in a 48dp touch box, radius 8. Selected = `ink` fill, `paper` text.
 * Chips are only for weekday pickers and context filters, at most one row per screen.
 */
@Composable
fun DayChip(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DayCueTheme.colors
    Box(
        modifier
            .heightIn(min = DayCueSpacing.minTouch)
            .defaultMinSize(minWidth = 40.dp)
            .selectable(selected = selected, role = Role.Checkbox, interactionSource = null, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .fillMaxWidth()
                .clip(DayCueShapes.chip)
                .background(if (selected) c.ink else Color.Transparent)
                .then(if (!selected) Modifier.border(1.dp, c.outlineStrong, DayCueShapes.chip) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = DayCueTheme.type.label, color = if (selected) c.paper else c.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

/** Weekday multi-select in locale week order (Sunday first for he-IL), full-name content descriptions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayChips(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val first = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val days = remember(first) { List(7) { first.plus(it.toLong()) } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        @Composable
        fun chip(day: DayOfWeek, m: Modifier) {
            DayChip(
                label = day.getDisplayName(TextStyle.NARROW, locale),
                description = day.getDisplayName(TextStyle.FULL, locale),
                selected = day in selected,
                onClick = { onToggle(day) },
                modifier = m,
            )
        }
        if (maxWidth >= 48.dp * 7 + 4.dp * 6) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { days.forEach { chip(it, Modifier.weight(1f)) } }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 4) { days.forEach { chip(it, Modifier.width(48.dp)) } }
        }
    }
}

/** Radio option with a one-sentence consequence line, for [PolicyChoiceSheet]. */
@Composable
fun ChoiceRow(
    label: String,
    consequence: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DayCueTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = DayCueSpacing.rowMin)
            .selectable(selected = selected, role = Role.RadioButton, interactionSource = null, indication = null, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(DayCueSpacing.inRow),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 12.dp)
                .size(24.dp)
                .border(1.5.dp, if (selected) c.ink else c.outlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).background(c.ink, CircleShape))
        }
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(label, style = DayCueTheme.type.titleSmall, color = c.ink)
            Text(consequence, style = DayCueTheme.type.bodySmall, color = c.ink2)
        }
    }
}

/**
 * Filled text field on `surface`, radius 8 on the top corners only, 1.5dp `outlineStrong` bottom line,
 * 2dp `ink` when focused. Helper or error text below in `bodySmall`.
 */
@Composable
fun DayCueTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    val c = DayCueTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lineColor = when {
        error != null -> c.error.ink
        focused -> c.ink
        else -> c.outlineStrong
    }
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            interactionSource = interaction,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            textStyle = DayCueTheme.type.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(DayCueShapes.textField)
                        .background(c.surface),
                ) {
                    Column(
                        Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(label, style = DayCueTheme.type.labelSmall, color = c.ink2)
                        inner()
                    }
                    Box(Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.5.dp).background(lineColor))
                }
            },
        )
        val note = error ?: helper
        if (note != null) {
            Text(
                note,
                style = DayCueTheme.type.bodySmall,
                color = if (error != null) c.error.ink else c.ink2,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        }
    }
}
