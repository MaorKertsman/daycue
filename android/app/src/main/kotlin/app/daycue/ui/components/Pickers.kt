package app.daycue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationDescription
import app.daycue.ui.util.durationSecondsText
import app.daycue.ui.util.durationText
import app.daycue.ui.util.amPmMarkers
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.formatTime
import app.daycue.ui.util.formatTimeRaw
import app.daycue.ui.util.hour12
import app.daycue.ui.util.is24Hour
import app.daycue.ui.util.isolatedRange
import app.daycue.ui.util.ltr

/**
 * Minus / value / plus. The row mirrors with the layout direction (minus at the start side, plus at the end
 * side). The value sits in a box of at least [valueMinWidth], centred, so the + button does not jump as the text
 * changes width. Callers isolate only digit or clock runs inside the value, never a whole Hebrew phrase. No wheels.
 */
@Composable
fun Stepper(
    valueText: String,
    valueDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    valueMinWidth: Dp = 72.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Minus, stringResource(R.string.cd_decrease), onDecrease, outlined = true, enabled = canDecrease)
        Text(
            valueText,
            style = DayCueTheme.type.title.copy(fontFeatureSettings = "tnum"),
            color = DayCueTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = valueMinWidth)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = valueDescription; liveRegion = LiveRegionMode.Polite },
        )
        GlyphButton(Glyph.Plus, stringResource(R.string.cd_increase), onIncrease, outlined = true, enabled = canIncrease)
    }
}

enum class DurationUnit { Minutes, Seconds }

private fun stepFor(value: Int, unit: DurationUnit): Int = when {
    value < 10 -> 1
    value < 60 -> 5
    else -> if (unit == DurationUnit.Minutes) 15 else 15
}

/**
 * Duration: steppers (step 1 under 10, 5 under 60, 15 above), preset text buttons and "Custom" number fields.
 * [value] is minutes for [DurationUnit.Minutes] and seconds for [DurationUnit.Seconds].
 *
 * Presets are text buttons, not chips, because VISUAL.md allows at most one chip row per screen and reserves
 * chips for weekdays and context filters.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationField(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    unit: DurationUnit = DurationUnit.Minutes,
    min: Int = 1,
    max: Int = if (unit == DurationUnit.Minutes) 24 * 60 else 3600,
    presets: List<Int> = if (unit == DurationUnit.Minutes) listOf(30, 60, 120, 240) else listOf(10, 30, 60),
) {
    var custom by remember { mutableStateOf(false) }
    val text = if (unit == DurationUnit.Minutes) durationText(value) else durationSecondsText(value)
    val description = if (unit == DurationUnit.Minutes) durationDescription(value)
    else pluralSeconds(value)
    Column(modifier.fillMaxWidth()) {
        Stepper(
            valueText = text,
            valueDescription = description,
            onDecrease = { onChange((value - stepFor(value - 1, unit)).coerceAtLeast(min)) },
            onIncrease = { onChange((value + stepFor(value, unit)).coerceAtMost(max)) },
            canDecrease = value > min,
            canIncrease = value < max,
            valueMinWidth = 112.dp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            presets.forEach { preset ->
                PresetText(
                    text = if (unit == DurationUnit.Minutes) durationText(preset) else durationSecondsText(preset),
                    selected = !custom && preset == value,
                    onClick = { custom = false; onChange(preset) },
                )
            }
            PresetText(stringResource(R.string.duration_custom), custom, { custom = !custom })
        }
        if (custom) {
            Spacer(Modifier.height(8.dp))
            if (unit == DurationUnit.Minutes) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DayCueTextField(
                        value = (value / 60).toString(),
                        onValueChange = { onChange(((it.toIntOrNull() ?: 0) * 60 + value % 60).coerceIn(min, max)) },
                        label = stringResource(R.string.field_hours),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    DayCueTextField(
                        value = (value % 60).toString(),
                        onValueChange = { onChange(((value / 60) * 60 + (it.toIntOrNull() ?: 0).coerceIn(0, 59)).coerceIn(min, max)) },
                        label = stringResource(R.string.field_minutes),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                DayCueTextField(
                    value = value.toString(),
                    onValueChange = { onChange((it.toIntOrNull() ?: min).coerceIn(min, max)) },
                    label = stringResource(R.string.field_seconds),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
    }
}

@Composable
private fun pluralSeconds(value: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.desc_seconds, value, value)

@Composable
internal fun PresetText(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = DayCueTheme.colors
    Box(
        Modifier
            .defaultMinSize(minWidth = DayCueSpacing.minTouch, minHeight = DayCueSpacing.minTouch)
            .semantics { this.selected = selected }
            .selectable(selected = selected, role = Role.RadioButton, interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 10.dp)) {
            UnderlinedText(
                text,
                style = if (selected) DayCueTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                else DayCueTheme.type.label,
                color = if (selected) c.ink else c.ink2,
                underline = !selected,
            )
            if (selected) Box(Modifier.padding(top = 2.dp).size(width = 20.dp, height = 3.dp).background(c.ink))
        }
    }
}

/** Time row (label + value, opens a picker sheet). Value uses the device 24h/12h setting. */
@Composable
fun TimeField(
    label: String,
    minutesOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    SettingRow(label, formatTime(minutesOfDay / 60, minutesOfDay % 60), onClick, modifier, divider = divider)
}

/**
 * Time picker for sheets: hour and minute steppers. It follows the device 24-hour setting: 24h shows "07" and
 * "19"; 12h shows "7" plus an AM/PM toggle, so the stepper always agrees with the row above it ("7:30 PM").
 * The hour:minute order never changes in RTL (only the steppers inside mirror). Minutes move in 5 minute steps.
 * It matches the Material TimePicker's input mode in what it asks for (hour, minute, AM/PM) but keeps the
 * large no-keyboard targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeStepperPicker(
    minutesOfDay: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hour = minutesOfDay / 60
    val minute = minutesOfDay % 60
    val direction = LocalLayoutDirection.current
    val c = DayCueTheme.colors
    val is24 = is24Hour(LocalContext.current)
    val locale = currentLocale()
    val markers = remember(locale) { amPmMarkers(locale) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.field_hours), style = DayCueTheme.type.bodySmall, color = c.ink2)
                    Stepper(
                        valueText = (if (is24) "%02d".format(hour) else hour12(hour).toString()).ltr(),
                        valueDescription = androidx.compose.ui.res.pluralStringResource(R.plurals.desc_hours, hour, hour),
                        onDecrease = { onChange(((hour + 23) % 24) * 60 + minute) },
                        onIncrease = { onChange(((hour + 1) % 24) * 60 + minute) },
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.field_minutes), style = DayCueTheme.type.bodySmall, color = c.ink2)
                    Stepper(
                        valueText = "%02d".format(minute).ltr(),
                        valueDescription = androidx.compose.ui.res.pluralStringResource(R.plurals.desc_minutes, minute, minute),
                        onDecrease = { onChange(hour * 60 + ((minute - 5 + 60) % 60)) },
                        onIncrease = { onChange(hour * 60 + ((minute + 5) % 60)) },
                    )
                }
                if (!is24) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.field_ampm), style = DayCueTheme.type.bodySmall, color = c.ink2)
                        Row(Modifier.heightIn(min = DayCueSpacing.buttonHeight), verticalAlignment = Alignment.CenterVertically) {
                            PresetText(markers[0], selected = hour < 12, onClick = { if (hour >= 12) onChange((hour - 12) * 60 + minute) })
                            PresetText(markers[1], selected = hour >= 12, onClick = { if (hour < 12) onChange((hour + 12) * 60 + minute) })
                        }
                    }
                }
            }
        }
    }
}

/** From / To rows with an "ends next day" note when To is earlier than From. */
@Composable
fun TimeWindowField(
    fromMinutes: Int,
    toMinutes: Int,
    onFromClick: () -> Unit,
    onToClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One LTR isolate around the whole range, inner times plain (REVIEW-1 #30).
    val from = formatTimeRaw(fromMinutes / 60, fromMinutes % 60)
    val to = formatTimeRaw(toMinutes / 60, toMinutes % 60)
    Column(modifier.fillMaxWidth()) {
        TimeField(stringResource(R.string.field_from), fromMinutes, onFromClick)
        TimeField(stringResource(R.string.field_to), toMinutes, onToClick, divider = toMinutes >= fromMinutes)
        if (toMinutes < fromMinutes) {
            Text(
                stringResource(R.string.time_window_overnight, isolatedRange(from, to)),
                style = DayCueTheme.type.bodySmall,
                color = DayCueTheme.colors.ink2,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
