package app.daycue.ui.cues

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Routine
import app.daycue.domain.config.RoutineRecovery
import app.daycue.domain.config.RoutineRecoveryPolicy
import app.daycue.domain.config.RoutineStartMode
import app.daycue.domain.config.RoutineStep
import app.daycue.domain.config.RoutineTimingPolicy
import app.daycue.domain.config.RoutineTrigger
import app.daycue.domain.config.StepCompletion
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.RoutineTestMode
import app.daycue.domain.time.ALL_DAYS
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.DurationUnit
import app.daycue.ui.components.PolicyChoiceList
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.ReorderableList
import app.daycue.ui.components.RoutineStepRow
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationSecondsText
import app.daycue.ui.util.durationText
import java.time.LocalTime

private val ROUTINE_KINDS = setOf("RoutineStarted", "RoutineCompleted", "RoutineCanceled")

private fun Routine.totalSec(): Int = steps.sumOf { it.durationSec * it.repeat }

private fun minutesCeil(sec: Int) = (sec + 59) / 60

@Composable
internal fun triggerText(r: Routine, cfg: DayCueConfig): String = when (val t = r.trigger) {
    RoutineTrigger.Manual -> stringResource(R.string.cues_trig_manual)
    is RoutineTrigger.Schedule -> stringResource(
        if (r.effectiveStartMode == RoutineStartMode.AskToStart) R.string.cues_trig_schedule_ask else R.string.cues_trig_schedule_auto,
        daysSummary(t.days), timeText(t.time),
    )
    is RoutineTrigger.AfterAlarm -> stringResource(R.string.cues_trig_after_alarm, cfg.alarm(t.alarmId)?.let { timeText(it.time) } ?: "?")
}

// ---- List -------------------------------------------------------------------------------------------------

@Composable
internal fun RoutineListScreen(vm: CuesViewModel, onBack: () -> Unit, push: (String) -> Unit) {
    val config by vm.config.collectAsState()
    val engine by vm.engine.collectAsState()
    val cfg = config
    val context = LocalContext.current
    var menu by remember { mutableStateOf<Routine?>(null) }
    var testFor by remember { mutableStateOf<Routine?>(null) }
    var replaceFor by remember { mutableStateOf<Routine?>(null) }
    val newName = stringResource(R.string.cues_new_routine_name)
    val newStep = stringResource(R.string.cues_new_step_name)
    val copyFmt = stringResource(R.string.cues_copy_name)

    fun start(r: Routine, replace: Boolean) {
        vm.facade.startRoutine(context.findActivity() ?: context, r.id, replace)
        push("routine/${r.id}/play")
    }
    fun requestStart(r: Routine) {
        val running = engine?.routine?.run
        if (running != null && running.routineId != r.id) replaceFor = r else start(r, false)
    }
    fun createBlank() {
        val id = "routine-" + System.currentTimeMillis().toString(36)
        vm.edit(ConfigOp.UpsertRoutine(Routine(id = id, name = newName, steps = listOf(RoutineStep("step-1", newStep, durationSec = 120)))), CuesMessage(R.string.cues_added))
        push("routine/$id")
    }

    CuesScreen(stringResource(R.string.cues_routines_title), onBack) {
        if (cfg == null) return@CuesScreen
        if (cfg.routines.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            StateBlock(
                StateBlockKind.Empty, stringResource(R.string.cues_routines_empty), body = stringResource(R.string.cues_routines_empty_body),
                actionLabel = stringResource(R.string.cues_routines_from_template),
                onAction = { vm.edit(ConfigOp.UpsertRoutine(Defaults.morningRoutine()), CuesMessage(R.string.cues_added)); push("routine/${Defaults.MORNING_ROUTINE}") },
            )
            Spacer(Modifier.height(8.dp))
            DayCueTextButton(stringResource(R.string.cues_routines_new), ::createBlank)
        } else {
            cfg.routines.forEach { r ->
                val scheduled = r.trigger != RoutineTrigger.Manual
                ToggleNavRow(
                    cue = CueType.Routine, state = if (r.enabled && scheduled) CueState.Scheduled else null,
                    title = routineName(r.id, r.name),
                    summary = "${triggerText(r, cfg)} · ${durationText(maxOf(1, minutesCeil(r.totalSec())))}",
                    status = null,
                    checked = r.enabled, onCheckedChange = { vm.edit(ConfigOp.UpsertRoutine(r.copy(enabled = it))) },
                    onClick = { push("routine/${r.id}") },
                    showSwitch = scheduled,
                    extra = { DayCueTextButton(stringResource(R.string.cues_start), { requestStart(r) }) },
                    overflow = { menu = r },
                )
            }
            Spacer(Modifier.height(8.dp))
            DayCueTextButton(stringResource(R.string.cues_routines_new), ::createBlank)
            if (cfg.routine(Defaults.MORNING_ROUTINE) == null) {
                DayCueTextButton(stringResource(R.string.cues_routines_from_template), { vm.edit(ConfigOp.UpsertRoutine(Defaults.morningRoutine()), CuesMessage(R.string.cues_added)) })
            }
        }
    }

    menu?.let { r ->
        ActionsSheet(routineName(r.id, r.name), listOf(
            SheetAction(stringResource(R.string.cues_start)) { requestStart(r) },
            SheetAction(stringResource(R.string.cues_test)) { testFor = r },
            SheetAction(stringResource(R.string.cues_duplicate)) {
                vm.edit(ConfigOp.DuplicateRoutine(r.id, "routine-" + System.currentTimeMillis().toString(36), copyFmt.format(routineNameRaw(r))), CuesMessage(R.string.cues_added))
            },
            SheetAction(stringResource(R.string.cues_delete), destructive = true) {
                vm.edit(ConfigOp.DeleteRoutine(r.id), CuesMessage(R.string.cues_deleted_named, listOf(r.name)))
            },
        )) { menu = null }
    }
    testFor?.let { r -> TestSheet(vm, r, push) { testFor = null } }
    replaceFor?.let { r ->
        val running = engine?.routine?.run
        DayCueDialog(
            title = stringResource(R.string.cues_replace_title, running?.routine?.name?.let { routineName(running.routineId, it) } ?: ""),
            text = stringResource(R.string.cues_replace_body),
            confirmLabel = stringResource(R.string.cues_replace_confirm), dismissLabel = stringResource(R.string.cues_keep_running),
            onConfirm = { replaceFor = null; start(r, true) }, onDismiss = { replaceFor = null },
        )
    }
}

private fun routineNameRaw(r: Routine) = r.name

@Composable
private fun TestSheet(vm: CuesViewModel, r: Routine, push: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun go(mode: RoutineTestMode) {
        vm.facade.testRoutine(context.findActivity() ?: context, r.id, mode)
        push("routine/${r.id}/play")
    }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_test), CueType.Routine) {
        Text(stringResource(R.string.cues_test_note), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.cues_test_fast), { onDismiss(); go(RoutineTestMode.Fast) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton(stringResource(R.string.cues_test_normal), { onDismiss(); go(RoutineTestMode.X1) }, Modifier.fillMaxWidth())
    }
}

// ---- Editor -----------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutineEditorScreen(vm: CuesViewModel, id: String, onBack: () -> Unit, push: (String) -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val config by vm.config.collectAsState()
    val engine by vm.engine.collectAsState()
    val errors by vm.errors.collectAsState()
    val cfg = config
    val r = cfg?.routine(id)
    if (cfg == null) { CuesScreen("", onBack) {}; return }
    if (r == null) { CuesScreen("", onBack) { StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_item_gone)) }; return }
    val context = LocalContext.current
    val path = "routines[${r.id}]"
    var sheet by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var stepEdit by remember { mutableStateOf<String?>(null) } // step id, "" = new
    var stepMenu by remember { mutableStateOf<RoutineStep?>(null) }
    var steps by remember(r.steps) { mutableStateOf(r.steps) }
    val running = engine?.routine?.run?.takeIf { it.routineId == r.id }
    val newStep = stringResource(R.string.cues_new_step_name)
    fun save(n: Routine) = vm.edit(ConfigOp.UpsertRoutine(n))
    val def = RoutineRecovery()
    val changed = listOf(r.recovery != def, r.startMode != null)

    CuesScreen(
        routineName(r.id, r.name), onBack, mark = CueType.Routine,
        trailing = { if (r.trigger != RoutineTrigger.Manual) DayCueSwitch(r.enabled, { save(r.copy(enabled = it)) }, Modifier.semantics { contentDescription = r.name }) },
    ) {
        if (running != null) Text(stringResource(R.string.cues_changes_next_time), style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        CommitTextField(routineName(r.id, r.name), { save(r.copy(name = it.take(40))) }, stringResource(R.string.cues_name))
        FieldErrors(errors, "$path.name")
        SettingRow(stringResource(R.string.cues_starts), triggerText(r, cfg), { sheet = "trigger" })
        FieldErrors(errors, "$path.trigger")

        Text(
            stringResource(R.string.cues_steps_total, durationText(maxOf(1, minutesCeil(steps.sumOf { it.durationSec * it.repeat })))),
            style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 16.dp),
        )
        ReorderableList(
            items = steps, keyOf = { it.id },
            onMove = { from, to ->
                val n = steps.toMutableList().apply { add(to, removeAt(from)) }
                steps = n
                vm.edit(ConfigOp.ReorderRoutineSteps(r.id, n.map { it.id }))
            },
        ) { s, index, handle ->
            RoutineStepRow(
                index = index, name = stepName(s.id, s.name), secondary = stepSummary(s), handle = handle,
                onMore = { stepMenu = s },
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { stepEdit = s.id }),
                divider = index < steps.lastIndex,
            )
        }
        FieldErrors(errors, "$path.steps")
        DayCueTextButton(stringResource(R.string.cues_add_step), { stepEdit = "" })

        SettingRow(stringResource(R.string.cues_timing), timingLabel(r.timing), { sheet = "timing" })
        Spacer(Modifier.height(8.dp))
        if (running != null) PrimaryButton(stringResource(R.string.cues_open_running_routine), { push("routine/${r.id}/play") }, Modifier.fillMaxWidth())
        else PrimaryButton(stringResource(R.string.cues_start_now), {
            if (engine?.routine?.run != null && engine?.routine?.run?.routineId != r.id) sheet = "replace"
            else { vm.facade.startRoutine(context.findActivity() ?: context, r.id, false); push("routine/${r.id}/play") }
        }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DayCueTextButton(stringResource(R.string.cues_test_normal), { vm.facade.testRoutine(context.findActivity() ?: context, r.id, RoutineTestMode.X1); push("routine/${r.id}/play") })
            DayCueTextButton(stringResource(R.string.cues_test_fast), { vm.facade.testRoutine(context.findActivity() ?: context, r.id, RoutineTestMode.Fast); push("routine/${r.id}/play") })
        }
        Text(stringResource(R.string.cues_test_note), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 4.dp))

        AdvancedSection(changed.count { it }, advanced, { advanced = !advanced }, onReset = { save(r.copy(recovery = def, startMode = null)) }) {
            SettingRow(stringResource(R.string.cues_recovery), recoveryLabel(r.recovery.policy) + " · " + durationText(r.recovery.thresholdMin), { sheet = "recovery" }, changed = changed[0])
            FieldErrors(errors, "$path.recovery")
            if (r.trigger !is RoutineTrigger.Manual) {
                SettingRow(stringResource(R.string.cues_start_prompt), stringResource(if (r.effectiveStartMode == RoutineStartMode.AskToStart) R.string.cues_start_ask else R.string.cues_start_auto), { sheet = "trigger" }, changed = changed[1])
            }
        }
        RecentActivity(vm, "routine", r.id, onlyKinds = ROUTINE_KINDS)
        Spacer(Modifier.height(16.dp))
        DestructiveButton(stringResource(R.string.cues_routine_delete), {
            vm.edit(ConfigOp.DeleteRoutine(r.id), CuesMessage(R.string.cues_deleted_named, listOf(r.name))); onBack()
        }, Modifier.fillMaxWidth())
    }

    when (sheet) {
        "trigger" -> TriggerSheet(r, cfg, onChange = { t, m -> save(r.copy(trigger = t, startMode = m)) }, onDismiss = { sheet = null })
        "timing" -> TimingSheet(r, onChange = { save(r.copy(timing = it)) }, onDismiss = { sheet = null })
        "recovery" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_recovery), CueType.Routine) {
            PolicyChoiceList(
                listOf(
                    PolicyOption(stringResource(R.string.cues_rec_ask), stringResource(R.string.cues_rec_ask_c)),
                    PolicyOption(stringResource(R.string.cues_rec_resume), stringResource(R.string.cues_rec_resume_c)),
                    PolicyOption(stringResource(R.string.cues_rec_cancel), stringResource(R.string.cues_rec_cancel_c)),
                ),
                r.recovery.policy.ordinal, { save(r.copy(recovery = r.recovery.copy(policy = RoutineRecoveryPolicy.entries[it]))) },
            )
            Text(stringResource(R.string.cues_rec_threshold), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
            Text(stringResource(R.string.cues_rec_threshold_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(r.recovery.thresholdMin, { save(r.copy(recovery = r.recovery.copy(thresholdMin = it))) }, min = 2, max = 60, presets = listOf(5, 10, 15, 30))
            FieldErrors(errors, "$path.recovery.thresholdMin")
        }
        "replace" -> DayCueDialog(
            stringResource(R.string.cues_replace_title, engine?.routine?.run?.routine?.name.orEmpty()), stringResource(R.string.cues_replace_body),
            stringResource(R.string.cues_replace_confirm), stringResource(R.string.cues_keep_running),
            onConfirm = { sheet = null; vm.facade.startRoutine(context.findActivity() ?: context, r.id, true); push("routine/${r.id}/play") }, onDismiss = { sheet = null },
        )
    }
    stepEdit?.let { sid ->
        val existing = r.steps.firstOrNull { it.id == sid }
        StepSheet(
            vm, r, existing ?: RoutineStep(id = "step-" + System.currentTimeMillis().toString(36), name = newStep), isNew = existing == null, errors = errors,
            onOpenCueProfile = onOpenCueProfile, onDismiss = { stepEdit = null },
        )
    }
    stepMenu?.let { s ->
        val idx = r.steps.indexOfFirst { it.id == s.id }
        ActionsSheet(stepName(s.id, s.name), buildList {
            add(SheetAction(stringResource(R.string.cues_edit)) { stepEdit = s.id })
            if (idx > 0) add(SheetAction(stringResource(R.string.cd_move_up)) { reorder(vm, r, idx, idx - 1) })
            if (idx in 0 until r.steps.lastIndex) add(SheetAction(stringResource(R.string.cd_move_down)) { reorder(vm, r, idx, idx + 1) })
            add(SheetAction(stringResource(R.string.cues_duplicate)) {
                vm.edit(ConfigOp.UpsertRoutineStep(r.id, s.copy(id = "step-" + System.currentTimeMillis().toString(36)), idx + 1))
            })
            add(SheetAction(stringResource(R.string.cues_delete), destructive = true) { vm.edit(ConfigOp.DeleteRoutineStep(r.id, s.id), CuesMessage(R.string.cues_deleted_named, listOf(s.name))) })
        }) { stepMenu = null }
    }
}

private fun reorder(vm: CuesViewModel, r: Routine, from: Int, to: Int) {
    val ids = r.steps.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
    vm.edit(ConfigOp.ReorderRoutineSteps(r.id, ids))
}

@Composable
private fun stepSummary(s: RoutineStep): String {
    val ends = if (s.completion == StepCompletion.Explicit) stringResource(R.string.cues_step_waits_done)
    else if (s.durationSec == 0) stringResource(R.string.cues_step_no_timer)
    else stepDuration(s.durationSec)
    val parts = mutableListOf(ends)
    if (s.repeat > 1) parts += stringResource(R.string.cues_step_repeat_n, s.repeat)
    if (s.optional) parts += stringResource(R.string.cues_step_optional)
    return parts.joinToString(" · ")
}

@Composable
internal fun stepDuration(sec: Int): String = if (sec % 60 == 0) durationText(sec / 60) else if (sec < 60) durationSecondsText(sec) else "${durationText(sec / 60)} ${durationSecondsText(sec % 60)}"

@Composable
private fun timingLabel(p: RoutineTimingPolicy) = stringResource(if (p == RoutineTimingPolicy.FollowActualCompletion) R.string.cues_timing_actual else R.string.cues_timing_schedule)

@Composable
private fun recoveryLabel(p: RoutineRecoveryPolicy) = stringResource(when (p) {
    RoutineRecoveryPolicy.AskToResume -> R.string.cues_rec_ask
    RoutineRecoveryPolicy.ResumeCurrentStep -> R.string.cues_rec_resume
    RoutineRecoveryPolicy.Cancel -> R.string.cues_rec_cancel
})

/** Timing sheet with a live worked example built from this routine's own steps (UX 3.7, PRODUCT 10.1). */
@Composable
private fun TimingSheet(r: Routine, onChange: (RoutineTimingPolicy) -> Unit, onDismiss: () -> Unit) {
    val timed = r.steps.filter { it.completion == StepCompletion.Timed && it.durationSec > 0 }
    val first = timed.firstOrNull()
    val second = timed.getOrNull(1)
    val overrun = 3
    val total = minutesCeil(r.totalSec())
    val example = when {
        first == null -> stringResource(R.string.cues_timing_ex_none)
        r.timing == RoutineTimingPolicy.FollowActualCompletion ->
            stringResource(R.string.cues_timing_ex_actual, stepName(first.id, first.name), durationText(overrun), durationText(total + overrun))
        second == null -> stringResource(R.string.cues_timing_ex_schedule_last, stepName(first.id, first.name), durationText(overrun), durationText(total))
        minutesCeil(second.durationSec) <= overrun ->
            stringResource(R.string.cues_timing_ex_schedule_skip, stepName(first.id, first.name), durationText(overrun), stepName(second.id, second.name), durationText(total))
        else -> stringResource(
            R.string.cues_timing_ex_schedule, stepName(first.id, first.name), durationText(overrun), stepName(second.id, second.name),
            durationText(minutesCeil(second.durationSec) - overrun), durationText(minutesCeil(second.durationSec)), durationText(total),
        )
    }
    // Both options' examples are visible at once so the difference is clear before choosing.
    val actualExample = if (first == null) null else stringResource(R.string.cues_timing_ex_actual, stepName(first.id, first.name), durationText(overrun), durationText(total + overrun))
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_timing), CueType.Routine) {
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_timing_actual), stringResource(R.string.cues_timing_actual_c)),
                PolicyOption(stringResource(R.string.cues_timing_schedule), stringResource(R.string.cues_timing_schedule_c)),
            ),
            r.timing.ordinal, { onChange(RoutineTimingPolicy.entries[it]) }, example = example,
        )
        if (actualExample == null) Unit
    }
}

@Composable
private fun TriggerSheet(r: Routine, cfg: DayCueConfig, onChange: (RoutineTrigger, RoutineStartMode?) -> Unit, onDismiss: () -> Unit) {
    val t = r.trigger
    val index = when (t) { RoutineTrigger.Manual -> 0; is RoutineTrigger.Schedule -> 1; is RoutineTrigger.AfterAlarm -> 2 }
    var timeOpen by remember { mutableStateOf(false) }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_starts), CueType.Routine) {
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_trig_manual), stringResource(R.string.cues_trig_manual_c)),
                PolicyOption(stringResource(R.string.cues_trig_schedule), stringResource(R.string.cues_trig_schedule_c)),
                PolicyOption(stringResource(R.string.cues_trig_alarm), stringResource(R.string.cues_trig_alarm_c)),
            ),
            index,
            onSelect = {
                when (it) {
                    0 -> onChange(RoutineTrigger.Manual, null)
                    1 -> onChange(t as? RoutineTrigger.Schedule ?: RoutineTrigger.Schedule(LocalTime.of(7, 0), cfg.settings.workDays), r.startMode)
                    else -> cfg.alarms.firstOrNull()?.let { a -> onChange(t as? RoutineTrigger.AfterAlarm ?: RoutineTrigger.AfterAlarm(a.id), r.startMode) }
                }
            },
        )
        if (t is RoutineTrigger.Schedule) {
            SettingRow(stringResource(R.string.cues_time), timeText(t.time), { timeOpen = true })
            Text(stringResource(R.string.cues_days), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            DayChips(t.days, { d -> val nd = if (d in t.days) t.days - d else t.days + d; if (nd.isNotEmpty()) onChange(t.copy(days = nd), r.startMode) })
            if (timeOpen) TimeSheet(stringResource(R.string.cues_time), t.time.hour * 60 + t.time.minute, { m -> onChange(t.copy(time = LocalTime.of(m / 60, m % 60)), r.startMode) }, { timeOpen = false }, CueType.Routine)
        }
        if (t is RoutineTrigger.AfterAlarm) {
            Text(stringResource(R.string.cues_trig_pick_alarm), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 8.dp))
            if (cfg.alarms.isEmpty()) Text(stringResource(R.string.cues_alarms_none_summary), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            cfg.alarms.forEach { a ->
                DayCueRow(
                    primary = "${timeText(a.time)} ${alarmName(a.id, a.name)}",
                    onClick = { onChange(RoutineTrigger.AfterAlarm(a.id), r.startMode) },
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    trailing = { if (t.alarmId == a.id) app.daycue.ui.components.GlyphIcon(app.daycue.ui.components.Glyph.Check, DayCueTheme.colors.ink) },
                    semanticsExtra = { selected = t.alarmId == a.id },
                )
            }
        }
        if (t !is RoutineTrigger.Manual) {
            Text(stringResource(R.string.cues_start_prompt), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 8.dp))
            PolicyChoiceList(
                listOf(
                    PolicyOption(stringResource(R.string.cues_start_ask), stringResource(R.string.cues_start_ask_c)),
                    PolicyOption(stringResource(R.string.cues_start_auto), stringResource(R.string.cues_start_auto_c)),
                ),
                if (r.effectiveStartMode == RoutineStartMode.AskToStart) 0 else 1,
                onSelect = { onChange(t, RoutineStartMode.entries[it]) },
            )
        }
    }
}

/** Step sheet: edited as a draft, one `UpsertRoutineStep` on Done. */
@Composable
private fun StepSheet(
    vm: CuesViewModel, r: Routine, original: RoutineStep, isNew: Boolean,
    errors: List<app.daycue.domain.edit.ValidationError>, onOpenCueProfile: (String?) -> Unit, onDismiss: () -> Unit,
) {
    val shownName = stepName(original.id, original.name)
    var s by remember { mutableStateOf(original.copy(name = shownName)) }
    var timer by remember { mutableStateOf(original.durationSec > 0) }
    DayCueBottomSheet(
        onDismiss, if (isNew) stringResource(R.string.cues_add_step) else stringResource(R.string.cues_edit_step), CueType.Routine,
        primaryLabel = stringResource(R.string.cues_done),
        onPrimary = {
            val out = s.copy(name = s.name.trim().ifBlank { original.name }, durationSec = if (timer) s.durationSec.coerceAtLeast(1) else 0)
            vm.edit(ConfigOp.UpsertRoutineStep(r.id, out, if (isNew) null else r.steps.indexOfFirst { it.id == original.id }), CuesMessage(R.string.cues_saved))
            onDismiss()
        },
    ) {
        app.daycue.ui.components.DayCueTextField(s.name, { s = s.copy(name = it.take(40)) }, stringResource(R.string.cues_step_name))
        Spacer(Modifier.height(8.dp))
        app.daycue.ui.components.DayCueTextField(s.phrase, { s = s.copy(phrase = it.take(200)) }, stringResource(R.string.cues_says), helper = stringResource(R.string.cues_step_phrase_help), singleLine = false)
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.cues_step_has_timer), timer, { timer = it; if (it && s.durationSec == 0) s = s.copy(durationSec = 120) })
        if (timer) DurationField(s.durationSec, { s = s.copy(durationSec = it) }, unit = DurationUnit.Seconds, min = 5, max = 7200, presets = listOf(30, 60, 120, 300))
        FieldErrors(errors, "routines[${r.id}].steps[${original.id}].durationSec")
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_step_timed), stringResource(R.string.cues_step_timed_c)),
                PolicyOption(stringResource(R.string.cues_step_explicit), stringResource(R.string.cues_step_explicit_c)),
            ),
            s.completion.ordinal, { s = s.copy(completion = StepCompletion.entries[it]) },
        )
        NumberRow(stringResource(R.string.cues_step_repeat), s.repeat, 1, 10, { s = s.copy(repeat = it) }, valueText = s.repeat.toString())
        SwitchRow(stringResource(R.string.cues_step_optional_label), s.optional, { s = s.copy(optional = it) }, secondary = stringResource(R.string.cues_step_optional_c))
        SettingRow(stringResource(R.string.cues_sound_voice), vm.config.value?.let { soundSummary(it, app.daycue.domain.config.CueType.RoutineStep, s.cueProfileId) } ?: stringResource(R.string.cues_sound_voice), { onOpenCueProfile(vm.profileId(app.daycue.domain.config.CueType.RoutineStep, s.cueProfileId)) })
    }
}
