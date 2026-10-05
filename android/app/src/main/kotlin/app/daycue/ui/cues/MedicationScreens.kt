package app.daycue.ui.cues

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import app.daycue.domain.engine.DoseCorrection
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.SlotRef
import app.daycue.ui.components.TimeStepperPicker
import java.time.ZonedDateTime
import java.time.Duration
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.DoseView
import app.daycue.ui.components.DayChip
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
import app.daycue.ui.theme.DayCueSpacing
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
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    CuesScreen(stringResource(R.string.cues_med_title), onBack, mark = CueType.Medication) {
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
                    val open = d.status == DoseStatus.Due || d.status == DoseStatus.Upcoming
                    val takenButton = @Composable { m: Modifier ->
                        SecondaryButton(stringResource(R.string.cues_med_taken), { vm.dispatch(Event.MedicationTaken(d.slot)) }, m, compact = true)
                    }
                    DayCueRow(
                        primary = d.label,
                        leading = { CueMark(CueType.Medication) },
                        extra = {
                            // The status text wraps by word and owns the full column; at large font the button moves to its own line.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(instantTime(d.dueAt), style = DayCueTheme.type.numeric, color = DayCueTheme.colors.ink)
                                StatusText(d.status.kind(), detail = if (d.status == DoseStatus.Taken && d.takenAt != null) instantTime(d.takenAt!!) else null, cue = app.daycue.ui.marks.CueType.Medication)
                            }
                            if (open && largeText) takenButton(Modifier.padding(top = 8.dp))
                        },
                        trailing = if (open && !largeText) ({ takenButton(Modifier) }) else null,
                        onClick = { doseSheet = d },
                        divider = i < doses.lastIndex,
                    )
                }
            }
            SectionHeader(stringResource(R.string.cues_med_all))
            cfg.medications.forEach { m -> MedicationRow(m, doses.filter { it.slot.medicationId == m.id }) { push("med/${m.id}") } }
            Spacer(Modifier.height(8.dp))
            DayCueTextButton(stringResource(R.string.cues_med_add), { push("med/new") })
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
    val ended = m.endDate?.takeIf { it.isBefore(LocalDate.now()) }
    DayCueRow(
        primary = m.label,
        secondary = "$times · ${daysSummary(m.days)}",
        leading = { CueMark(CueType.Medication, state = if (ended != null) app.daycue.ui.marks.CueState.Paused else null) },
        extra = {
            m.endDate?.let { Text(stringResource(if (ended != null) R.string.cues_med_stopped_on else R.string.cues_med_stops_on, dateText(it)), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2) }
        },
        onClick = onClick,
    )
}

/** Dose detail: explicit Taken, Taken at, Snooze, and Skip this dose (secondary). Nothing is implied by opening it. */
@Composable
private fun DoseSheet(vm: CuesViewModel, d: DoseView, onDismiss: () -> Unit) {
    DayCueBottomSheet(onDismiss, d.label, CueType.Medication) {
        Text(instantTime(d.dueAt), style = DayCueTheme.type.headline, color = DayCueTheme.colors.ink)
        StatusText(d.status.kind(), detail = if (d.status == DoseStatus.Taken && d.takenAt != null) instantTime(d.takenAt!!) else null, cue = CueType.Medication)
        Spacer(Modifier.height(16.dp))
        val open = d.status == DoseStatus.Due || d.status == DoseStatus.Upcoming
        var pickingTime by remember { mutableStateOf(false) }
        if (pickingTime) {
            TakenAtPicker(d.slot, d.dueAt, initial = Instant.now(), onSave = { at -> vm.takenAt(d.slot, at); onDismiss() }, onCancel = { pickingTime = false })
        } else if (open) {
            PrimaryButton(stringResource(R.string.cues_med_taken), { vm.dispatch(Event.MedicationTaken(d.slot)); onDismiss() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FlowRowButtons {
                SecondaryButton(stringResource(R.string.cues_med_taken_at), { pickingTime = true })
                if (d.status == DoseStatus.Due) SecondaryButton(stringResource(R.string.cues_med_snooze), { vm.dispatch(Event.MedicationSnooze(d.slot)); onDismiss() })
                SecondaryButton(stringResource(R.string.cues_med_skip_dose), { vm.dispatch(Event.MedicationSkip(d.slot)); onDismiss() })
            }
        } else {
            // Taken / Skipped / Not confirmed: correcting goes through the same MED-5 path as the history entry.
            DoseCorrectionActions(vm, d.slot, d.dueAt, d.status, d.takenAt, onDone = onDismiss)
        }
        Text(stringResource(R.string.cues_med_disclaimer), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 12.dp))
    }
}

/** The time a "Taken at" pick means: the clock time on the day nearest the dose, never in the future. */
internal fun takenInstantFor(minutesOfDay: Int, slot: SlotRef, dueAt: Instant, now: Instant = Instant.now(), zone: java.time.ZoneId = zoneNow): Instant {
    val time = LocalTime.of(minutesOfDay / 60, minutesOfDay % 60)
    val candidates = (-1L..1L).map { slot.date.plusDays(it).atTime(time).atZone(zone).toInstant() }.filter { !it.isAfter(now) }
    return candidates.minByOrNull { Duration.between(it, dueAt).abs() } ?: now
}

@Composable
private fun TakenAtPicker(slot: SlotRef, dueAt: Instant, initial: Instant, onSave: (Instant) -> Unit, onCancel: () -> Unit) {
    val start = initial.atZone(zoneNow).toLocalTime()
    var minutes by remember { mutableStateOf(start.hour * 60 + (start.minute / 5) * 5) }
    Text(stringResource(R.string.cues_med_taken_at_title), style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2)
    Spacer(Modifier.height(8.dp))
    TimeStepperPicker(minutes, { minutes = it })
    Spacer(Modifier.height(12.dp))
    PrimaryButton(stringResource(R.string.cues_med_taken_at_save), { onSave(takenInstantFor(minutes, slot, dueAt)) }, Modifier.fillMaxWidth())
    DayCueTextButton(stringResource(R.string.cues_cancel), onCancel)
}

/** MED-5 corrections for a slot that already has an outcome: another time, skipped, or back to not confirmed. */
@Composable
internal fun DoseCorrectionActions(vm: CuesViewModel, slot: SlotRef, dueAt: Instant, status: DoseStatus, takenAt: Instant?, onDone: () -> Unit) {
    var pickingTime by remember { mutableStateOf(false) }
    if (pickingTime) {
        TakenAtPicker(slot, dueAt, initial = takenAt ?: Instant.now(), onSave = { at -> vm.correct(slot, DoseCorrection.Taken(at)); onDone() }, onCancel = { pickingTime = false })
        return
    }
    FlowRowButtons {
        SecondaryButton(stringResource(if (status == DoseStatus.Taken) R.string.cues_med_change_time else R.string.cues_med_correct_taken), { pickingTime = true })
        if (status != DoseStatus.Skipped) SecondaryButton(stringResource(R.string.cues_med_correct_skipped), { vm.correct(slot, DoseCorrection.Skipped); onDone() })
        if (status == DoseStatus.Taken || status == DoseStatus.Skipped) {
            SecondaryButton(stringResource(R.string.cues_med_correct_undo), { vm.correct(slot, DoseCorrection.Undo); onDone() })
        }
    }
    if (status == DoseStatus.Taken || status == DoseStatus.Skipped) {
        Text(stringResource(R.string.cues_med_correct_undo_hint), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

// ---- History ----------------------------------------------------------------------------------------

private val MED_KINDS = setOf("Taken", "Skipped", "NotConfirmed", "Snoozed", "Dismissed", "Delivered", "Corrected")
private val OUTCOME_KINDS = setOf("Taken", "Skipped", "NotConfirmed", "Corrected")

/** What a history row asks the owner to correct: the slot, its current outcome and when it was taken. */
private data class CorrectTarget(val slot: SlotRef, val dueAt: Instant, val status: DoseStatus, val takenAt: Instant?)

private fun slotOf(r: HistoryEventEntity): SlotRef? {
    val key = r.payloadJson?.let { runCatching { DayCueJson.decodeFromString(HistoryPayload.serializer(), it).itemKey }.getOrNull() } ?: return null
    val parts = key.substringAfter(':').split('|')
    if (parts.size != 3) return null
    return runCatching { SlotRef(parts[0], LocalDate.parse(parts[1]), LocalTime.parse(parts[2])) }.getOrNull()
}

private fun detailOf(r: HistoryEventEntity): Map<String, String> =
    r.payloadJson?.let { runCatching { DayCueJson.decodeFromString(HistoryPayload.serializer(), it).detail }.getOrNull() } ?: emptyMap()

/** Medication history grouped by day, filterable by item (UX 3.5). No percentages, streaks or scores. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MedicationHistoryScreen(vm: CuesViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val rows: List<HistoryEventEntity> by remember { vm.allHistory(600) }.collectAsState(emptyList())
    val cfg = config
    var filter by remember { mutableStateOf<String?>(null) }
    var correcting by remember { mutableStateOf<CorrectTarget?>(null) }
    CuesScreen(stringResource(R.string.cues_med_history), onBack) {
        if (cfg == null) return@CuesScreen
        if (cfg.medications.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val all = stringResource(R.string.cues_hist_all)
                DayChip("  $all  ", all, filter == null, { filter = null })
                cfg.medications.forEach { m -> DayChip("  ${m.label}  ", m.label, filter == m.id, { filter = m.id }) }
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
        val handledSlots = mutableSetOf<String>()
        val byDay = shown.groupBy { r ->
            Instant.ofEpochMilli(r.occurredAtMs).atZone(zone).minusHours(dayStart.hour.toLong()).minusMinutes(dayStart.minute.toLong()).toLocalDate()
        }
        byDay.forEach { (day, list) ->
            SectionHeader(dayHeaderText(day))
            list.forEachIndexed { i, r ->
                val label = cfg.medication(r.subjectId)?.label ?: stringResource(R.string.cues_med_removed)
                val slotRef = slotOf(r)
                val detail = detailOf(r)
                val at = Instant.ofEpochMilli(r.occurredAtMs).atZone(zone).toLocalTime()
                val word = when (r.kind) {
                    "Delivered" -> stringResource(R.string.cues_hist_reminder)
                    "Corrected" -> when (detail["to"]) {
                        "Taken" -> detail["takenAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { stringResource(R.string.cues_hist_corrected_taken, instantTime(it)) } ?: stringResource(R.string.cues_hist_corrected_taken_nt)
                        "Skipped" -> stringResource(R.string.cues_hist_corrected_skipped)
                        "Due", "Upcoming" -> stringResource(R.string.cues_hist_corrected_undo)
                        else -> stringResource(R.string.cues_hist_corrected)
                    }
                    else -> historyKindText(r.kind) ?: r.kind
                }
                val doseText = slotRef?.let { stringResource(R.string.cues_med_hist_dose, timeText(it.time)) }
                if (r.kind == "Delivered" || r.kind == "Dismissed") {
                    // Reminder sent / notification dismissed are context for the dose, not events of their own: ink2 secondary line.
                    Text(
                        listOfNotNull(label, doseText, "$word ${timeText(at)}").joinToString(" · "),
                        style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2,
                        modifier = Modifier.padding(start = DayCueSpacing.markSlot + DayCueSpacing.inRow, top = 2.dp, bottom = 6.dp),
                    )
                } else {
                    // The newest outcome of each slot from today or yesterday can be corrected (MED-5).
                    val canCorrect = r.kind in OUTCOME_KINDS && slotRef != null && handledSlots.add(slotRef.key) &&
                        !slotRef.date.isBefore(LocalDate.now(zone).minusDays(1))
                    val status = when (r.kind) {
                        "Taken" -> DoseStatus.Taken
                        "Skipped" -> DoseStatus.Skipped
                        "NotConfirmed" -> DoseStatus.NotConfirmed
                        else -> when (detail["to"]) { "Taken" -> DoseStatus.Taken; "Skipped" -> DoseStatus.Skipped; "Due" -> DoseStatus.Due; else -> DoseStatus.Upcoming }
                    }
                    val takenAtIso = if (r.kind == "Corrected") detail["takenAt"] else null
                    DayCueRow(
                        primary = label,
                        secondary = listOfNotNull(doseText, if (r.kind == "Corrected") word else "$word ${timeText(at)}").joinToString(" · "),
                        leading = { CueMark(CueType.Medication) },
                        trailing = if (canCorrect) ({
                            DayCueTextButton(stringResource(R.string.cues_med_correct), {
                                val due = slotRef!!.date.atTime(slotRef.time).atZone(zone).toInstant()
                                correcting = CorrectTarget(slotRef, due, status, takenAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: if (r.kind == "Taken") Instant.ofEpochMilli(r.occurredAtMs) else null)
                            })
                        }) else null,
                        divider = i < list.lastIndex,
                    )
                }
            }
        }
        Text(stringResource(R.string.cues_med_disclaimer), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 16.dp))
    }
    correcting?.let { c ->
        DayCueBottomSheet({ correcting = null }, stringResource(R.string.cues_med_correct_title), CueType.Medication) {
            DoseCorrectionActions(vm, c.slot, c.dueAt, c.status, c.takenAt, onDone = { correcting = null })
        }
    }
}
