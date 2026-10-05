package app.daycue.ui.cues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.PolicyChoiceList
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.Stepper
import app.daycue.ui.components.TimeStepperPicker
import app.daycue.ui.components.TimeWindowField
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import java.time.LocalDate

/** Radio list with one consequence sentence per option (UX section 3.4). Choosing applies and closes. */
@Composable
internal fun PolicySheet(
    title: String,
    options: List<PolicyOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    mark: CueType? = null,
    example: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    DayCueBottomSheet(onDismiss = onDismiss, title = title, mark = mark) {
        PolicyChoiceList(options, selectedIndex, onSelect = { onSelect(it); onDismiss() }, example = example)
        extra?.invoke()
    }
}

/** A short list of actions in a bottom sheet (row overflow). */
internal class SheetAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
internal fun ActionsSheet(title: String, actions: List<SheetAction>, onDismiss: () -> Unit) {
    DayCueBottomSheet(onDismiss = onDismiss, title = title) {
        Column(Modifier.fillMaxWidth()) {
            actions.forEach { a ->
                DayCueRow(
                    primary = a.label,
                    onClick = { onDismiss(); a.onClick() },
                    secondaryColor = DayCueTheme.colors.ink2,
                )
            }
        }
    }
}

/** Time of day in a sheet: the large stepper picker, applied on Done. */
@Composable
internal fun TimeSheet(
    title: String,
    minutesOfDay: Int,
    onDone: (Int) -> Unit,
    onDismiss: () -> Unit,
    mark: CueType? = null,
) {
    var value by remember { mutableIntStateOf(minutesOfDay) }
    DayCueBottomSheet(onDismiss = onDismiss, title = title, mark = mark, primaryLabel = stringResource(R.string.cues_done), onPrimary = { onDone(value); onDismiss() }) {
        TimeStepperPicker(value, { value = it })
    }
}

/** From / To window in a sheet. [allDayLabel] adds an "all day" option that returns null. */
@Composable
internal fun WindowSheet(
    title: String,
    fromMinutes: Int,
    toMinutes: Int,
    allowAllDay: Boolean,
    onDone: (from: Int, to: Int)  -> Unit,
    onAllDay: () -> Unit,
    onDismiss: () -> Unit,
    mark: CueType? = null,
) {
    var from by remember { mutableIntStateOf(fromMinutes) }
    var to by remember { mutableIntStateOf(toMinutes) }
    var editing by remember { mutableIntStateOf(0) } // 0 = from, 1 = to
    DayCueBottomSheet(onDismiss = onDismiss, title = title, mark = mark, primaryLabel = stringResource(R.string.cues_done), onPrimary = { onDone(from, to); onDismiss() }) {
        TimeWindowField(from, to, onFromClick = { editing = 0 }, onToClick = { editing = 1 })
        Text(
            stringResource(if (editing == 0) R.string.cues_editing_from else R.string.cues_editing_to),
            style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp),
        )
        if (editing == 0) TimeStepperPicker(from, { from = it }) else TimeStepperPicker(to, { to = it })
        if (allowAllDay) DayCueTextButton(stringResource(R.string.cues_all_day), { onAllDay(); onDismiss() })
    }
}

/** A calendar day chosen with steppers (day by day, with week / month jumps): no date-picker widget in the set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateStepper(date: LocalDate, onChange: (LocalDate) -> Unit, minDate: LocalDate = LocalDate.now()) {
    Column(Modifier.fillMaxWidth()) {
        Stepper(
            valueText = dateText(date),
            valueDescription = dateText(date),
            onDecrease = { onChange(date.minusDays(1).coerceAtLeast(minDate)) },
            onIncrease = { onChange(date.plusDays(1)) },
            canDecrease = date.isAfter(minDate),
            valueMinWidth = 140.dp,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayCueTextButton(stringResource(R.string.cues_date_today), { onChange(LocalDate.now()) })
            DayCueTextButton(stringResource(R.string.cues_date_plus_week), { onChange(date.plusWeeks(1)) })
            DayCueTextButton(stringResource(R.string.cues_date_plus_month), { onChange(date.plusMonths(1)) })
        }
    }
}

private fun LocalDate.coerceAtLeast(min: LocalDate): LocalDate = if (isBefore(min)) min else this
