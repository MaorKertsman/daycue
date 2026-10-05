package app.daycue.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.data.db.HistoryEventEntity
import app.daycue.domain.query.WaitingReason
import app.daycue.ui.app.ScrollPage
import app.daycue.ui.components.CueAction
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.WhyNow
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.tone
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import java.time.Instant

/** History kinds that read well to the owner; others (internal bookkeeping) are not listed. */
@StringRes
fun historyKindRes(kind: String): Int? = when (kind) {
    "Delivered" -> R.string.app_hist_reminded
    "Repeated" -> R.string.app_hist_reminded_again
    "Acked" -> R.string.app_hist_confirmed
    "Taken" -> R.string.status_taken
    "Snoozed" -> R.string.state_snoozed
    "Paused" -> R.string.state_paused
    "Resumed" -> R.string.app_resume
    "Skipped", "SkippedLate" -> R.string.status_skipped
    "Unanswered" -> R.string.app_hist_unanswered
    "Retracted" -> R.string.app_hist_withdrawn
    "NotConfirmed" -> R.string.status_not_confirmed
    else -> null
}

/**
 * Item detail (UX 3.2): name, state, last confirmation, next due, actions, "Why now?" with the context values
 * and their sources [GEN-9], and the last five history entries.
 */
@Composable
fun ItemDetailScreen(
    model: TodayModel,
    itemKey: String,
    vm: TodayViewModel,
    handlers: CardHandlers,
    whyExpanded: Boolean,
    onToggleWhy: () -> Unit,
    onBack: () -> Unit,
    onPause: (HabitDue) -> Unit,
) {
    val c = DayCueTheme.colors
    val due = model.due.firstOrNull { it.key == itemKey }
    val next = model.next.firstOrNull { it.key == itemKey }
    val id = itemKey.substringAfter(':', itemKey)
    val name = when {
        due != null -> dueTitle(due)
        next != null -> next.name.text()
        else -> defaultName(id, id).text()
    }
    val mark = due?.mark ?: next?.mark ?: CueType.Hydration
    val rule = (due as? HabitDue)?.rule ?: next?.rule ?: "GEN-4"
    val lastAck = (due as? HabitDue)?.lastAck ?: next?.lastAck
    val history by remember(itemKey) { vm.history(itemKey) }.collectAsState(emptyList())

    ScrollPage(name, onBack, mark = { CueMark(mark, state = if (due != null) CueState.Due else CueState.Scheduled) }) {
        // State, last confirmation, next due: plain words, the state in its own ink.
        DayCueRow(
            primary = stringResource(R.string.app_detail_state),
            secondary = when {
                due != null -> stringResource(R.string.state_due)
                next != null -> waitingText(next, model.now, model.zone)
                else -> stringResource(R.string.app_detail_no_schedule)
            },
            secondaryColor = if (due != null) c.tone(mark).ink else c.ink2,
            divider = true,
        )
        if (lastAck != null) {
            DayCueRow(stringResource(R.string.app_detail_last), secondary = dayAwareClock(lastAck, model.now, model.zone), divider = true)
        }
        if (next?.at != null && next.waiting == null) {
            DayCueRow(stringResource(R.string.app_detail_next), secondary = "${dayAwareClock(next.at, model.now, model.zone)} · ${minutesUntil(next.at, model.now)}", divider = false)
        }

        if (due != null) {
            Spacer(Modifier.height(16.dp))
            val spec = cardSpec(due, model, handlers)
            // Side by side like the Today card; they stack only at very large text.
            if (LocalDensity.current.fontScale >= 1.5f) {
                spec.actions.forEach { a -> ActionBlock(a, Modifier.padding(bottom = 8.dp)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.actions.forEach { a -> ActionBlock(a, Modifier.weight(1f)) }
                }
            }
            Row { spec.textActions.forEach { a -> DayCueTextButton(a.label, a.onClick) } }
        }
        val habit = due as? HabitDue
        if (habit != null && !habit.bottle) DayCueTextButton(stringResource(R.string.app_menu_pause), { onPause(habit) })

        // Why now: a section, not a button inside an already expanded block. The rule names its source.
        SectionHeader(stringResource(R.string.why_now))
        Text(ruleText(rule), style = DayCueTheme.type.body, color = c.ink)
        if (lastAck != null) {
            Text(
                stringResource(R.string.app_why_last, dayAwareClock(lastAck, model.now, model.zone)),
                style = DayCueTheme.type.body, color = c.ink, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        buildList {
            val ctx = model.context
            add(stringResource(R.string.app_src_line, stringResource(R.string.app_ctx_place), placeText(ctx.place), sourceText(ctx.placeSource).orEmpty()))
            add(stringResource(R.string.app_src_line, stringResource(R.string.app_ctx_environment), environmentText(ctx.environment), sourceText(ctx.environmentSource).orEmpty()))
            activityText(ctx.activity, ctx.session != null)?.let {
                add(stringResource(R.string.app_src_line, stringResource(R.string.app_ctx_activity), it, sourceText(ctx.activitySource).orEmpty()))
            }
        }.map { it.trimEnd(' ', '·', '(', ')') }.forEach { Text("· $it", style = DayCueTheme.type.bodySmall, color = c.ink2) }

        SectionHeader(stringResource(R.string.app_detail_history))
        val rows = history.mapNotNull { h -> historyKindRes(h.kind)?.let { h to it } }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.app_detail_history_empty), style = DayCueTheme.type.bodySmall, color = c.ink2)
        } else {
            rows.forEachIndexed { i, (h, res) ->
                DayCueRow(
                    primary = stringResource(res),
                    secondary = dayAwareClock(Instant.ofEpochMilli(h.occurredAtMs), model.now, model.zone),
                    divider = i < rows.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun ActionBlock(a: CueAction, modifier: Modifier) {
    if (a.primary) PrimaryButton(a.label, a.onClick, modifier.fillMaxWidth()) else SecondaryButton(a.label, a.onClick, modifier.fillMaxWidth())
}

