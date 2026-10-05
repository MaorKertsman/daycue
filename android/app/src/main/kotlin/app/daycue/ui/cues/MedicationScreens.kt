package app.daycue.ui.cues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.data.db.HistoryEventEntity
import app.daycue.data.repo.HistoryPayload
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.Medication
import app.daycue.domain.engine.Event
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.DoseView
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.StatusKind
import app.daycue.ui.components.StatusText
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.ltr
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private fun DoseStatus.kind(): StatusKind = when (this) {
    DoseStatus.Upcoming -> StatusKind.Upcoming
    DoseStatus.Due -> StatusKind.Due
    DoseStatus.Taken -> StatusKind.Taken
    DoseStatus.Skipped -> StatusKind.Skipped
    DoseStatus.NotConfirmed -> StatusKind.NotConfirmed
}

/** Status as plain words: "Taken 08:04", "Upcoming", "Due now", "Not confirmed". Never "missed". */
@Composable
internal fun doseStatusText(d: DoseView): String {
    val word = stringResource(d.status.kind().wordRes)
    return if (d.status == DoseStatus.Taken && d.takenAt != null) "$word ${instantTime(d.takenAt!!)}" else word
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationListScreen(vm: CuesViewModel, onBack: () -> Unit, push: (String) -> Unit) {
    val config by vm.config.collectAsState()
    val today by vm.today.collectAsState()
    val cfg = config
    var doseSheet by remember { mutableStateOf<DoseView?>(null) }
    CuesScreen(stringResource(R.string.cue_medication), onBack) {
        if (cfg == null) return@CuesScreen
        if (cfg.medications.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_med_empty), body = stringResource(R.string.cues_med_empty_body),
                actionLabel = stringResource(R.string.cues_med_add), onAction = { push("med/new") })
            Spacer(Modifier.height(16.dp))
        } else {
            val doses = today?.doses.orEmpty()
            if (doses.isNotEmpty()) {
                SectionHeader(stringResource(R.string.cues_med_today))
                doses.forEachIndexed { i, d ->
                    DayCueRow(
                        primary = d.label,
                        leading = { CueMark(CueType.Medication) },
                        extra = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(instantTime(d.dueAt), style = DayCueTheme.type.numeric, color = DayCueTheme.colors.ink)
                                StatusText(d.status.kind(), detail = if (d.status == DoseStatus.Taken && d.takenAt != null) instantTime(d.takenAt!!) else null, cue = app.daycue.ui.marks.CueType.Medication)
                            }
                        },
                        trailing = if (d.status == DoseStatus.Due || d.status == DoseStatus.Upcoming) ({
                            SecondaryButton(stringResource(R.string.cues_med_taken), { vm.dispatch(Event.MedicationTaken(d.slot)) }, compact = true)
                        }) else null,
                        onClick = { doseSheet = d },
                        divider = i < doses.lastIndex,
                    )
                }
            }
            SectionHeader(stringResource(R.string.cues_med_all))
            cfg.medications.forEach { m -> MedicationRow(m, doses.filter { it.slot.medicationId == m.id }) { push("med/${m.id}") } }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(stringResource(R.string.cues_med_add), { push("med/new") }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        DayCueTextButton(stringResource(R.string.cues_med_history), { push("med/history") })
        Text(stringResource(R.string.cues_med_disclaimer), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
    }
    doseSheet?.let { d -> DoseSheet(vm, d, onDismiss = { doseSheet = null }) }
}

@Composable
private fun MedicationRow(m: Medication, doses: List<DoseView>, onClick: () -> Unit) {
    val times = m.times.sorted().map { timeText(it) }.joinToString(" · ")
    val todayText = doses.map { "${instantTime(it.dueAt)} ${doseStatusText(it)}" }.joinToString(" · ")
    val ended = m.endDate?.takeIf { it.isBefore(LocalDate.now()) }
    DayCueRow(
        primary = m.label,
        secondary = "$times · ${daysSummary(m.days)}",
        leading = { CueMark(CueType.Medication, state = if (ended != null) app.daycue.ui.marks.CueState.Paused else null) },
        extra = {
            if (todayText.isNotEmpty()) Text(todayText, style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            m.endDate?.let { Text(stringResource(if (ended != null) R.string.cues_med_stopped_on else R.string.cues_med_stops_on, dateText(it)), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2) }
        },
        onClick = onClick,
    )
}

/** Dose detail: explicit Taken, Snooze, and Skip this dose (secondary). Nothing is implied by opening it. */
@Composable
private fun DoseSheet(vm: CuesViewModel, d: DoseView, onDismiss: () -> Unit) {
    DayCueBottomSheet(onDismiss, d.label, CueType.Medication) {
        Text(instantTime(d.dueAt), style = DayCueTheme.type.headline, color = DayCueTheme.colors.ink)
        StatusText(d.status.kind(), detail = if (d.status == DoseStatus.Taken && d.takenAt != null) instantTime(d.takenAt!!) else null, cue = CueType.Medication)
        Spacer(Modifier.height(16.dp))
        val open = d.status == DoseStatus.Due || d.status == DoseStatus.Upcoming
        if (open) {
            PrimaryButton(stringResource(R.string.cues_med_taken), { vm.dispatch(Event.MedicationTaken(d.slot)); onDismiss() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FlowRowButtons {
                if (d.status == DoseStatus.Due) SecondaryButton(stringResource(R.string.cues_med_snooze), { vm.dispatch(Event.MedicationSnooze(d.slot)); onDismiss() })
                SecondaryButton(stringResource(R.string.cues_med_skip_dose), { vm.dispatch(Event.MedicationSkip(d.slot)); onDismiss() })
            }
        }
        Text(stringResource(R.string.cues_med_disclaimer), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

// ---- History ----------------------------------------------------------------------------------------

private val MED_KINDS = setOf("Taken", "Skipped", "NotConfirmed", "Snoozed", "Dismissed", "Delivered")

/** Medication history grouped by day, filterable by item (UX 3.5). No percentages, streaks or scores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationHistoryScreen(vm: CuesViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val rows: List<HistoryEventEntity> by remember { vm.allHistory(600) }.collectAsState(emptyList())
    val cfg = config
    var filter by remember { mutableStateOf<String?>(null) }
    CuesScreen(stringResource(R.string.cues_med_history), onBack) {
        if (cfg == null) return@CuesScreen
        if (cfg.medications.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.cues_hist_all), { filter = null }, compact = true, selected = filter == null)
                cfg.medications.forEach { m -> SecondaryButton(m.label, { filter = m.id }, compact = true, selected = filter == m.id) }
            }
        }
        val shown = rows.filter { it.subjectType == "medication" && !it.isTest && it.kind in MED_KINDS && (filter == null || it.subjectId == filter) }
        if (shown.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_med_hist_empty))
            return@CuesScreen
        }
        val dayStart = cfg.settings.dayStartsAt
        val zone = zoneNow
        val byDay = shown.groupBy { r ->
            Instant.ofEpochMilli(r.occurredAtMs).atZone(zone).minusHours(dayStart.hour.toLong()).minusMinutes(dayStart.minute.toLong()).toLocalDate()
        }
        byDay.forEach { (day, list) ->
            SectionHeader(dateText(day))
            list.forEachIndexed { i, r ->
                val label = cfg.medication(r.subjectId)?.label ?: stringResource(R.string.cues_med_removed)
                val slot = r.payloadJson?.let { runCatching { DayCueJson.decodeFromString(HistoryPayload.serializer(), it).itemKey.substringAfterLast('|') }.getOrNull() }
                val at = Instant.ofEpochMilli(r.occurredAtMs).atZone(zone).toLocalTime()
                val word = if (r.kind == "Delivered") stringResource(R.string.cues_hist_reminder) else (historyKindText(r.kind) ?: r.kind)
                DayCueRow(
                    primary = label,
                    secondary = buildString {
                        if (slot != null && slot.contains(':') && slot.length <= 5) append(stringResource(R.string.cues_med_hist_dose, runCatching { LocalTime.parse(slot) }.getOrNull()?.let { timeText(it) } ?: slot)).append(" · ")
                        append(word).append(" ").append(timeText(at))
                    },
                    divider = i < list.lastIndex,
                )
            }
        }
        Text(stringResource(R.string.cues_med_disclaimer), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 16.dp))
    }
}
