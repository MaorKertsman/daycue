package app.daycue.ui.cues

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Habit
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.PostureModeKind
import app.daycue.domain.config.TransitionHabit
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.engine.Event
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.TodayView
import app.daycue.domain.query.UpcomingItem
import app.daycue.domain.query.WaitingReason
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationText
import java.time.Instant

/** Cues tab home (UX 3.3): every reminder with its state as text, an enable switch, and entry rows. */
@Composable
internal fun CuesHome(vm: CuesViewModel, push: (String) -> Unit, onOpenCalendar: () -> Unit) {
    val config by vm.config.collectAsState()
    val today by vm.today.collectAsState()
    val cfg = config
    CuesScreen(stringResource(R.string.cues_title), onBack = null) {
        if (cfg == null) { Spacer(Modifier.height(96.dp)); return@CuesScreen }
        var addOpen by remember { mutableStateOf(false) }

        SectionHeader(stringResource(R.string.cues_section_habits))
        if (cfg.habits.isEmpty()) {
            StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_habits_empty), body = stringResource(R.string.cues_habits_empty_body),
                actionLabel = stringResource(R.string.cues_add_reminder), onAction = { addOpen = true })
        } else {
            cfg.habits.forEach { h -> HabitRow(h, cfg, today, vm, push) }
            DayCueTextButton(stringResource(R.string.cues_add_reminder), { addOpen = true })
        }

        SectionHeader(stringResource(R.string.cues_section_routines_more))
        PostureRow(cfg, today, vm, push)
        MedicationNavRow(cfg, today, push)
        NavRow(CueType.Routine, stringResource(R.string.cues_routines_title), routinesSummary(cfg), { push("routines") })
        NavRow(CueType.Alarm, stringResource(R.string.cues_alarms_title), alarmsSummary(cfg), { push("alarms") })
        NavRow(CueType.Calendar, stringResource(R.string.cues_calendar_cues), stringResource(R.string.cues_calendar_summary), onOpenCalendar)

        if (addOpen) AddReminderSheet(cfg, vm, push) { addOpen = false }
    }
}

@Composable
private fun AddReminderSheet(cfg: DayCueConfig, vm: CuesViewModel, push: (String) -> Unit, onDismiss: () -> Unit) {
    val newName = stringResource(R.string.cues_new_reminder_name)
    val actions = buildList {
        fun template(id: String, label: String, habit: () -> Habit) {
            if (cfg.habit(id) == null) add(SheetAction(label) {
                vm.edit(ConfigOp.UpsertHabit(habit()), CuesMessage(R.string.cues_added))
                push("habit/$id")
            })
        }
        template(Defaults.SUNSCREEN, stringResource(R.string.cue_sunscreen)) { Defaults.sunscreen().copy(enabled = true) }
        template(Defaults.HYDRATION, stringResource(R.string.cue_hydration)) { Defaults.hydration().copy(enabled = true) }
        template(Defaults.WATER_BOTTLE, stringResource(R.string.cue_bottle)) { Defaults.waterBottle().copy(enabled = true) }
        add(SheetAction(stringResource(R.string.cues_custom_reminder)) {
            val id = "habit-" + System.currentTimeMillis().toString(36)
            vm.edit(ConfigOp.UpsertHabit(IntervalHabit(id = id, kind = IntervalKind.Generic, name = newName, enabled = false, intervalMin = 60)), CuesMessage(R.string.cues_added))
            push("habit/$id")
        })
    }
    ActionsSheet(stringResource(R.string.cues_add_reminder), actions, onDismiss)
}

// ---- Rows ------------------------------------------------------------------------------------

/** A row with a title, summary, optional status line, an enable switch and a row tap target. */
@Composable
internal fun ToggleNavRow(
    cue: CueType,
    state: CueState?,
    title: String,
    summary: String,
    status: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    showSwitch: Boolean = true,
    overflow: (() -> Unit)? = null,
) {
    val on = stringResource(R.string.cues_state_on)
    val off = stringResource(R.string.cues_state_off)
    val toggleLabel = stringResource(if (checked) R.string.cues_turn_off else R.string.cues_turn_on)
    DayCueRow(
        primary = title,
        secondary = summary,
        leading = { CueMark(cue, state = state) },
        extra = {
            if (status != null) Text(status, style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            extra?.invoke()
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (overflow != null) app.daycue.ui.components.GlyphButton(Glyph.MoreVertical, stringResource(R.string.cues_more), overflow)
                if (showSwitch) DayCueSwitch(checked, onCheckedChange, Modifier.semantics { contentDescription = title })
            }
        },
        onClick = onClick,
        semanticsExtra = {
            if (showSwitch) {
                stateDescription = if (checked) on else off
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { onCheckedChange(!checked); true })
            }
        },
    )
}

@Composable
internal fun NavRow(cue: CueType, title: String, summary: String, onClick: () -> Unit) {
    DayCueRow(
        primary = title,
        secondary = summary,
        leading = { CueMark(cue) },
        trailing = { GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink2) },
        onClick = onClick,
    )
}

@Composable
private fun HabitRow(h: Habit, cfg: DayCueConfig, today: TodayView?, vm: CuesViewModel, push: (String) -> Unit) {
    val now = Instant.now()
    val pausedText = pauseText(h.pause, now)
    val summary = habitSummary(h, cfg)
    val status = when {
        pausedText != null -> pausedText
        !h.enabled -> null // the switch already says it is off
        else -> today?.upcoming?.firstOrNull { it.itemKey == "habit:${h.id}" }?.let { waitingText(it) }
    }
    ToggleNavRow(
        cue = markFor(h),
        state = when { pausedText != null -> CueState.Paused; h.enabled -> CueState.Scheduled; else -> null },
        title = habitName(h),
        summary = summary,
        status = status,
        checked = h.enabled,
        onCheckedChange = { vm.edit(ConfigOp.SetHabitEnabled(h.id, it)) },
        onClick = { push("habit/${h.id}") },
        extra = if (pausedText != null) ({ DayCueTextButton(stringResource(R.string.cues_resume), { vm.dispatch(Event.Resume(PauseTarget.Habit(h.id))) }) }) else null,
    )
}

@Composable
private fun PostureRow(cfg: DayCueConfig, today: TodayView?, vm: CuesViewModel, push: (String) -> Unit) {
    val pc = cfg.postureCycle
    val lang = uiLanguage()
    val sit = stringResource(R.string.cues_mode_sit)
    val stand = stringResource(R.string.cues_mode_stand)
    val walk = stringResource(R.string.cues_mode_walk)
    val noModes = stringResource(R.string.cues_no_modes)
    val summary = pc.modes.filter { it.enabled }.joinToString(" · ") { m ->
        val short = when (m.kind) {
            PostureModeKind.Sitting -> sit
            PostureModeKind.Standing -> stand
            PostureModeKind.Walking -> walk
            PostureModeKind.Custom -> m.name.shown(lang)
        }
        "$short ${m.durationMin}"
    }.ifEmpty { noModes }
    val paused = pauseText(pc.pause, Instant.now())
    val status = when {
        paused != null -> paused
        !pc.enabled -> null
        else -> today?.upcoming?.firstOrNull { it.itemKey == "posture" }?.let { waitingText(it) }
    }
    ToggleNavRow(
        cue = CueType.Posture,
        state = if (paused != null) CueState.Paused else if (pc.enabled) CueState.Scheduled else null,
        title = stringResource(R.string.cue_posture),
        summary = summary,
        status = status,
        checked = pc.enabled,
        onCheckedChange = { vm.edit(ConfigOp.SetPostureEnabled(it)) },
        onClick = { push("posture") },
    )
}

@Composable
private fun MedicationNavRow(cfg: DayCueConfig, today: TodayView?, push: (String) -> Unit) {
    val count = cfg.medications.size
    val next = today?.doses?.filter { it.status == DoseStatus.Due || it.status == DoseStatus.Upcoming }?.minByOrNull { it.dueAt }
    val summary = if (count == 0) stringResource(R.string.cues_med_none_summary)
    else if (next != null) pluralStringResource(R.plurals.cues_med_count_next, count, count, instantTime(next.dueAt))
    else pluralStringResource(R.plurals.cues_med_count_summary, count, count)
    NavRow(CueType.Medication, stringResource(R.string.cues_med_title), summary) { push("meds") }
}

@Composable
private fun routinesSummary(cfg: DayCueConfig): String =
    if (cfg.routines.isEmpty()) stringResource(R.string.cues_routines_none_summary)
    else pluralStringResource(R.plurals.cues_routines_count, cfg.routines.size, cfg.routines.size)

@Composable
private fun alarmsSummary(cfg: DayCueConfig): String {
    val a = cfg.alarms.filter { it.enabled }.minByOrNull { it.time } ?: return if (cfg.alarms.isEmpty()) stringResource(R.string.cues_alarms_none_summary) else stringResource(R.string.cues_alarms_all_off)
    val days = a.days ?: cfg.settings.workDays
    return "${timeText(a.time)} · ${daysSummary(days)}"
}

@Composable
internal fun habitSummary(h: Habit, cfg: DayCueConfig): String = when (h) {
    is IntervalHabit -> {
        val every = stringResource(R.string.cues_every, durationText(h.intervalMin))
        if (h.condition.isAny) every else "$every · ${conditionText(h.condition, cfg.places)}"
    }
    is TransitionHabit -> {
        val names = h.placeIds.mapNotNull { id -> cfg.place(id)?.let { placeName(it) } }
        if (names.isEmpty()) stringResource(R.string.cues_bottle_summary_any) else stringResource(R.string.cues_bottle_summary_places, names.joinToString(", "))
    }
}

/** "Paused until 15:00" / "Paused until you resume" or null when no pause is active at [now]. */
@Composable
internal fun pauseText(p: PauseSpec?, now: Instant): String? = when (p) {
    null -> null
    is PauseSpec.Until -> if (now.isBefore(p.until)) stringResource(R.string.cues_paused_until, instantTime(p.until)) else null
    is PauseSpec.UntilConditionEnds -> if (now.isBefore(p.cap)) stringResource(R.string.cues_paused_until_leave) else null
    is PauseSpec.Indefinite -> stringResource(R.string.cues_paused_indefinite)
}

/** Status text of an upcoming item: a time, or the reason it is waiting (UX "Next rows"). */
@Composable
internal fun waitingText(i: UpcomingItem): String? = when (i.waiting) {
    null -> i.at?.let { stringResource(R.string.cues_next_at, instantTime(it)) }
    WaitingReason.WhenConditionHolds -> stringResource(R.string.cues_wait_condition)
    WaitingReason.PausedUntil -> i.waitingUntil?.let { stringResource(R.string.cues_paused_until, instantTime(it)) } ?: stringResource(R.string.cues_paused_indefinite)
    WaitingReason.AfterQuietHours -> stringResource(R.string.cues_wait_quiet)
    WaitingReason.AfterMeeting -> stringResource(R.string.cues_wait_meeting)
    WaitingReason.AfterRoutine -> stringResource(R.string.cues_wait_routine)
    WaitingReason.OutsideActiveHours -> i.waitingUntil?.let { stringResource(R.string.cues_wait_hours, instantTime(it)) }
    WaitingReason.CoveredUntil -> i.waitingUntil?.let { stringResource(R.string.cues_wait_covered, instantTime(it)) }
    WaitingReason.AfterFirstAck -> stringResource(R.string.cues_wait_first_ack)
    WaitingReason.Frozen -> stringResource(R.string.cues_wait_frozen)
    WaitingReason.Pending -> stringResource(R.string.cues_wait_pending)
}
