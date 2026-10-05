package app.daycue.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.daycue.R
import app.daycue.domain.engine.PostureAction
import app.daycue.domain.engine.SessionAnswer
import app.daycue.ui.components.CueAction
import app.daycue.ui.components.CueCard
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.util.durationText

/** What the Now card and the item detail can do; every callback ends in an engine event or a facade call. */
class CardHandlers(
    val vm: TodayViewModel,
    val confirm: () -> Unit,
    val startRoutine: (String) -> Unit,
    val pauseHabit: (HabitDue) -> Unit,
    val why: (String) -> Unit,
)

class CardSpec(
    val title: String,
    val statusWord: String,
    val statusDetail: String?,
    val actions: List<CueAction>,
    val textActions: List<CueAction>,
    val more: List<CueAction>,
)

@Composable
fun cardSpec(item: DueItem, m: TodayModel, h: CardHandlers): CardSpec {
    val vm = h.vm
    val due = stringResource(R.string.state_due)
    val whyLabel = stringResource(R.string.why_now)
    fun act(label: String, primary: Boolean = false, confirm: Boolean = true, block: () -> Unit) =
        CueAction(label, { if (confirm) h.confirm(); block() }, primary)
    return when (item) {
        is HabitDue -> {
            val detail = item.lastAck?.let { stringResource(R.string.app_last_at, clockOf(it, m.zone)) }
            val more = buildList {
                if (!item.bottle) add(CueAction(stringResource(R.string.app_menu_pause), { h.pauseHabit(item) }))
                add(CueAction(whyLabel, { h.why(item.key) }))
            }
            if (item.bottle) {
                CardSpec(
                    item.name.text(), due, detail,
                    listOf(
                        act(stringResource(R.string.app_act_got_it), primary = true) { vm.bottleAck(item.habitId, false) },
                        act(stringResource(R.string.app_act_not_needed)) { vm.bottleAck(item.habitId, true) },
                    ), emptyList(), more,
                )
            } else {
                val ackLabel = if (item.generic) R.string.app_act_done else when (item.mark) {
                    CueType.Sunscreen -> R.string.app_act_applied
                    CueType.Hydration -> R.string.app_act_drank
                    else -> R.string.app_act_done
                }
                CardSpec(
                    item.name.text(), due, detail,
                    listOf(
                        act(stringResource(ackLabel), primary = true) { vm.habitAck(item.habitId) },
                        act(stringResource(R.string.app_act_snooze_min, durationText(item.snoozeMin)), confirm = false) { vm.habitSnooze(item.habitId) },
                    ), emptyList(), more,
                )
            }
        }
        is DoseDue -> CardSpec(
            item.label,
            stringResource(if (item.notConfirmed) R.string.status_not_confirmed else R.string.state_due),
            stringResource(R.string.app_dose_at, clockOf(item.dueAt, m.zone)),
            buildList {
                add(act(stringResource(R.string.app_act_taken), primary = true) { vm.doseTaken(item.slot) })
                if (!item.notConfirmed) add(act(stringResource(R.string.app_act_snooze), confirm = false) { vm.doseSnooze(item.slot) })
            }, emptyList(), listOf(CueAction(whyLabel, { h.why(item.key) })),
        )
        is PostureDue -> CardSpec(
            dueTitle(item), due, stringResource(R.string.app_timer_starts_on_switch),
            listOf(act(stringResource(R.string.app_act_switched), primary = true) { vm.posture(PostureAction.Switched) }),
            listOf(
                act(stringResource(R.string.app_act_snooze_min, durationText(item.snoozeMin)), confirm = false) { vm.posture(PostureAction.Snooze) },
                act(stringResource(R.string.app_act_skip), confirm = false) { vm.posture(PostureAction.Skip) },
                act(stringResource(R.string.app_act_extend5), confirm = false) { vm.posture(PostureAction.Extend5) },
            ), listOf(CueAction(whyLabel, { h.why(item.key) })),
        )
        is RoutinePromptDue -> CardSpec(
            stringResource(R.string.app_routine_prompt, item.name.text()), due, null,
            listOf(
                act(stringResource(R.string.app_act_start), primary = true, confirm = false) { h.startRoutine(item.routineId) },
                act(stringResource(R.string.app_act_skip_today), confirm = false) { vm.routineSkipToday(item.routineId) },
            ), emptyList(), listOf(CueAction(whyLabel, { h.why(item.key) })),
        )
        is CalendarDue -> CardSpec(
            dueTitle(item), due, null,
            listOf(
                act(stringResource(R.string.app_act_got_it), primary = true) { vm.calendarAck(item.cueId) },
                act(stringResource(R.string.app_act_snooze_min, durationText(5)), confirm = false) { vm.calendarSnooze(item.cueId) },
            ), emptyList(), listOf(CueAction(whyLabel, { h.why(item.key) })),
        )
        is SessionPromptDue -> CardSpec(
            dueTitle(item), due, null,
            listOf(
                act(stringResource(R.string.app_act_start), primary = true, confirm = false) { vm.sessionAnswer(item.placeId, SessionAnswer.Start, item.cueId) },
                act(stringResource(R.string.app_act_not_now), confirm = false) { vm.sessionAnswer(item.placeId, SessionAnswer.NotNow, item.cueId) },
            ), emptyList(), listOf(CueAction(whyLabel, { h.why(item.key) })),
        )
    }
}

@Composable
fun DueCard(item: DueItem, m: TodayModel, h: CardHandlers, modifier: Modifier = Modifier) {
    val s = cardSpec(item, m, h)
    CueCard(
        title = s.title, statusWord = s.statusWord, modifier = modifier, cue = item.mark, state = CueState.Due,
        statusDetail = s.statusDetail, actions = s.actions, textActions = s.textActions, moreActions = s.more,
    )
}
