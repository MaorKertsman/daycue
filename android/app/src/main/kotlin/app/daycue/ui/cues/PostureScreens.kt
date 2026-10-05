package app.daycue.ui.cues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Language
import app.daycue.domain.config.PostureActiveWhen
import app.daycue.domain.config.PostureCycleConfig
import app.daycue.domain.config.PostureInterruptionPolicy
import app.daycue.domain.config.PostureMeetingPolicy
import app.daycue.domain.config.PostureMode
import app.daycue.domain.config.PostureModeKind
import app.daycue.domain.config.PostureTimerStartPolicy
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.engine.PostureAction
import app.daycue.domain.engine.PosturePhase
import app.daycue.domain.engine.PostureState
import app.daycue.domain.time.ALL_DAYS
import app.daycue.domain.time.TimeWindow
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.ReorderableList
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.CuePreviewButton
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.TimerKind
import app.daycue.ui.components.ProgressTimer
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.PostureMode as MarkMode
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import app.daycue.ui.util.clockDuration
import app.daycue.ui.util.durationDescription
import app.daycue.ui.util.durationText
import app.daycue.ui.util.formatTimeRaw
import app.daycue.ui.util.isolatedRange
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

// ---- Editor ------------------------------------------------------------------------------------------

@Composable
internal fun PostureEditorScreen(vm: CuesViewModel, onBack: () -> Unit, push: (String) -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val config by vm.config.collectAsState()
    val cfg = config
    if (cfg == null) { CuesScreen(stringResource(R.string.cue_posture), onBack) {}; return }
    val pc = cfg.postureCycle
    val errors by vm.errors.collectAsState()
    val lang = uiLanguage()
    val def = remember { Defaults.postureCycle() }
    val postureName = stringResource(R.string.cue_posture)
    var sheet by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf<String?>(null) }
    val localizedNewName = stringResource(R.string.cues_posture_new_mode_name)
    var advanced by remember { mutableStateOf(false) }
    // Optimistic local order while a drag or move is in flight.
    var modes by remember(pc.modes) { mutableStateOf(pc.modes) }
    fun saveCycle(n: PostureCycleConfig) = vm.edit(ConfigOp.SetPostureCycle(n))
    val paused = pauseText(pc.pause, Instant.now())
    val changed = listOf(
        pc.timerStart != def.timerStart, pc.confirmRepeat != def.confirmRepeat, pc.duringMeeting != def.duringMeeting,
        pc.shortInterruptionMin != def.shortInterruptionMin || pc.longInterruption != def.longInterruption,
        pc.extendOptionsMin != def.extendOptionsMin, pc.snoozeMin != def.snoozeMin,
    )

    CuesScreen(
        stringResource(R.string.cue_posture), onBack, mark = CueType.Posture,
        trailing = { DayCueSwitch(pc.enabled, { vm.edit(ConfigOp.SetPostureEnabled(it)) }, Modifier.semantics { contentDescription = postureName }) },
    ) {
        if (paused != null) DayCueRow(primary = paused, trailing = { DayCueTextButton(stringResource(R.string.cues_resume), { vm.dispatch(Event.Resume(PauseTarget.Posture)) }) })
        DayCueTextButton(stringResource(R.string.cues_live_control), { push("posture/live") })
        FieldErrors(errors, "postureCycle.modes")

        SectionHeader(stringResource(R.string.cues_posture_modes))
        val enabledCount = modes.count { it.enabled }
        ReorderableList(
            items = modes, keyOf = { it.id },
            onMove = { from, to ->
                val n = modes.toMutableList().apply { add(to, removeAt(from)) }
                modes = n
                vm.edit(ConfigOp.SetPostureModes(n))
            },
        ) { m, _, handle ->
            val locked = m.enabled && enabledCount <= 1
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                handle()
                DayCueRow(
                    primary = m.name.shown(lang),
                    secondary = (if (locked) stringResource(R.string.cues_mode_keep_one, durationText(m.durationMin)) else durationText(m.durationMin)) + modePhraseSuffix(m, lang),
                    onClick = { editMode = m.id },
                    modifier = Modifier.weight(1f),
                    trailing = {
                        DayCueSwitch(
                            m.enabled,
                            { on -> val n = modes.map { if (it.id == m.id) it.copy(enabled = on) else it }; modes = n; vm.edit(ConfigOp.SetPostureModes(n)) },
                            Modifier.semantics { contentDescription = m.name.shown(lang) },
                            enabled = !locked,
                        )
                    },
                )
            }
        }

        DayCueTextButton(stringResource(R.string.cues_posture_add_mode), {
            val n = localizedNewName
            val mode = PostureMode(id = "mode-" + System.currentTimeMillis().toString(36), kind = PostureModeKind.Custom, name = app.daycue.domain.config.LocalizedText(n, n), durationMin = 20)
            val all = modes + mode
            modes = all
            vm.edit(ConfigOp.SetPostureModes(all))
            editMode = mode.id
        })

        SettingRow(stringResource(R.string.cues_posture_runs), activeWhenLabel(pc.activeWhen), { sheet = "runs" })

        Spacer(Modifier.height(8.dp))
        SettingRow(stringResource(R.string.cues_sound_voice), soundSummary(cfg, app.daycue.domain.config.CueType.Posture, pc.cueProfileId), { onOpenCueProfile(vm.profileId(app.daycue.domain.config.CueType.Posture, pc.cueProfileId)) })
        CuePreviewButton(CueType.Posture, stringResource(R.string.cues_test_cue), { vm.testReminder(app.daycue.domain.config.CueType.Posture) })
        DayCueTextButton(stringResource(R.string.cues_pause), { sheet = "pause" })

        AdvancedSection(changed.count { it }, advanced, { advanced = !advanced }, onReset = {
            saveCycle(pc.copy(timerStart = def.timerStart, confirmRepeat = def.confirmRepeat, duringMeeting = def.duringMeeting, shortInterruptionMin = def.shortInterruptionMin,
                longInterruption = def.longInterruption, extendOptionsMin = def.extendOptionsMin, snoozeMin = def.snoozeMin))
        }) {
            SettingRow(stringResource(R.string.cues_posture_timer_start), timerStartLabel(pc.timerStart), { sheet = "timer" }, changed = changed[0])
            SettingRow(stringResource(R.string.cues_posture_confirm_repeat), if (pc.confirmRepeat.maxRepeats == 0) stringResource(R.string.cues_repeat_none) else pluralStringResource(R.plurals.cues_repeat_summary, pc.confirmRepeat.maxRepeats, pc.confirmRepeat.maxRepeats, durationText(pc.confirmRepeat.everyMin)), { sheet = "confirm" }, changed = changed[1])
            FieldErrors(errors, "postureCycle.confirmRepeat")
            SettingRow(stringResource(R.string.cues_during_meetings), meetingPostureLabel(pc.duringMeeting), { sheet = "meeting" }, changed = changed[2])
            SettingRow(stringResource(R.string.cues_posture_breaks), "${durationText(pc.shortInterruptionMin)} · ${interruptionLabel(pc.longInterruption)}", { sheet = "breaks" }, changed = changed[3])
            FieldErrors(errors, "postureCycle.shortInterruptionMin")
            SettingRow(stringResource(R.string.cues_posture_extend), pc.extendOptionsMin.map { plusMinutes(it) }.joinToString(" · "), { sheet = "extend" }, changed = changed[4])
            SettingRow(stringResource(R.string.cues_snooze_length), durationText(pc.snoozeMin), { sheet = "snooze" }, changed = changed[5])
            FieldErrors(errors, "postureCycle.snoozeMin")
        }
    }

    editMode?.let { id ->
        val m = pc.modes.firstOrNull { it.id == id }
        if (m != null) ModeSheet(vm, pc, m, lang, errors) { editMode = null }
    }
    when (sheet) {
        "runs" -> RunsSheet(pc, cfg, onChange = { saveCycle(pc.copy(activeWhen = it)) }, onDismiss = { sheet = null })
        "pause" -> PauseSheet(vm, PauseTarget.Posture, canUntilLeave = false, onDismiss = { sheet = null })
        "timer" -> PolicySheet(
            stringResource(R.string.cues_posture_timer_start), listOf(
                PolicyOption(stringResource(R.string.cues_timer_confirm), stringResource(R.string.cues_timer_confirm_c)),
                PolicyOption(stringResource(R.string.cues_timer_cue), stringResource(R.string.cues_timer_cue_c)),
            ), pc.timerStart.ordinal, { saveCycle(pc.copy(timerStart = PostureTimerStartPolicy.entries[it])) }, { sheet = null }, CueType.Posture,
        )
        "meeting" -> PolicySheet(
            stringResource(R.string.cues_during_meetings), listOf(
                PolicyOption(stringResource(R.string.cues_pmeet_defer), stringResource(R.string.cues_pmeet_defer_c)),
                PolicyOption(stringResource(R.string.cues_pmeet_freeze), stringResource(R.string.cues_pmeet_freeze_c)),
                PolicyOption(stringResource(R.string.cues_pmeet_ignore), stringResource(R.string.cues_pmeet_ignore_c)),
            ), pc.duringMeeting.ordinal, { saveCycle(pc.copy(duringMeeting = PostureMeetingPolicy.entries[it])) }, { sheet = null }, CueType.Posture,
        )
        "confirm" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_posture_confirm_repeat), CueType.Posture) {
            RepeatEditor(pc.confirmRepeat, maxRepeats = 5, minEvery = 2, maxEvery = 30, onChange = { saveCycle(pc.copy(confirmRepeat = it)) })
            FieldErrors(errors, "postureCycle.confirmRepeat")
        }
        "snooze" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_snooze_length), CueType.Posture) {
            DurationField(pc.snoozeMin, { saveCycle(pc.copy(snoozeMin = it)) }, min = 1, max = 30, presets = listOf(1, 5, 10, 15))
        }
        "breaks" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_posture_breaks), CueType.Posture) {
            Text(stringResource(R.string.cues_posture_short_break), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
            Text(stringResource(R.string.cues_posture_short_break_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(pc.shortInterruptionMin, { saveCycle(pc.copy(shortInterruptionMin = it)) }, min = 0, max = 60, presets = listOf(0, 5, 15, 30))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cues_posture_long_break), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
            app.daycue.ui.components.PolicyChoiceList(
                listOf(
                    PolicyOption(stringResource(R.string.cues_int_reset), stringResource(R.string.cues_int_reset_c)),
                    PolicyOption(stringResource(R.string.cues_int_restart), stringResource(R.string.cues_int_restart_c)),
                    PolicyOption(stringResource(R.string.cues_int_continue), stringResource(R.string.cues_int_continue_c)),
                ),
                pc.longInterruption.ordinal, { saveCycle(pc.copy(longInterruption = PostureInterruptionPolicy.entries[it])) },
            )
        }
        "extend" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_posture_extend), CueType.Posture) {
            Text(stringResource(R.string.cues_posture_extend_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            pc.extendOptionsMin.forEachIndexed { i, v ->
                NumberRow(
                    stringResource(R.string.cues_posture_extend_n, i + 1), v, 1, 120,
                    { nv -> saveCycle(pc.copy(extendOptionsMin = pc.extendOptionsMin.mapIndexed { j, x -> if (j == i) nv else x })) },
                    valueText = plusMinutes(v), valueDescription = durationText(v),
                )
            }
            FieldErrors(errors, "postureCycle.extendOptionsMin")
        }
    }
}

@Composable
private fun ModeSheet(vm: CuesViewModel, pc: PostureCycleConfig, m: PostureMode, lang: Language, errors: List<app.daycue.domain.edit.ValidationError>, onDismiss: () -> Unit) {
    fun save(n: PostureMode) = vm.edit(ConfigOp.SetPostureModes(pc.modes.map { if (it.id == m.id) n else it }))
    val defaultPhrase = when (m.kind) {
        PostureModeKind.Sitting -> stringResource(R.string.cues_mode_phrase_sit)
        PostureModeKind.Standing -> stringResource(R.string.cues_mode_phrase_stand)
        PostureModeKind.Walking -> stringResource(R.string.cues_mode_phrase_walk)
        PostureModeKind.Custom -> ""
    }
    DayCueBottomSheet(onDismiss, m.name.shown(lang), CueType.Posture) {
        Text(stringResource(R.string.cues_mode_duration), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
        DurationField(m.durationMin, { save(m.copy(durationMin = it)) }, min = 5, max = 120, presets = listOf(15, 30, 45, 60))
        FieldErrors(errors, "postureCycle.modes[${m.id}].durationMin")
        Spacer(Modifier.height(8.dp))
        CommitTextField(
            value = m.phrase?.get(lang).orEmpty().ifBlank { defaultPhrase },
            onCommit = { t ->
                val base = m.phrase ?: app.daycue.domain.config.LocalizedText()
                save(m.copy(phrase = if (lang == Language.he) base.copy(he = t) else base.copy(en = t)))
            },
            label = stringResource(R.string.cues_phrase_label), helper = stringResource(R.string.cues_mode_phrase_help), singleLine = false,
        )
        if (pc.modes.size > 1) DayCueTextButton(stringResource(R.string.cues_posture_remove_mode), { vm.edit(ConfigOp.SetPostureModes(pc.modes.filter { it.id != m.id })); onDismiss() })
    }
}

/** " · “Time to sit”": the phrase a mode speaks, as a second fact on its row. */
@Composable
private fun modePhraseSuffix(m: PostureMode, lang: Language): String {
    val phrase = m.phrase?.get(lang)?.takeIf { it.isNotBlank() } ?: when (m.kind) {
        PostureModeKind.Sitting -> stringResource(R.string.cues_mode_phrase_sit)
        PostureModeKind.Standing -> stringResource(R.string.cues_mode_phrase_stand)
        PostureModeKind.Walking -> stringResource(R.string.cues_mode_phrase_walk)
        PostureModeKind.Custom -> return ""
    }
    return " · “$phrase”"
}

@Composable
private fun RunsSheet(pc: PostureCycleConfig, cfg: DayCueConfig, onChange: (PostureActiveWhen) -> Unit, onDismiss: () -> Unit) {
    var windowOpen by remember { mutableStateOf(false) }
    val index = when (pc.activeWhen) { PostureActiveWhen.DuringSessions -> 0; is PostureActiveWhen.ActiveHours -> 1; PostureActiveWhen.ManualOnly -> 2 }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_posture_runs), CueType.Posture) {
        app.daycue.ui.components.PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_runs_sessions), stringResource(R.string.cues_runs_sessions_c)),
                PolicyOption(stringResource(R.string.cues_runs_hours), stringResource(R.string.cues_runs_hours_c)),
                PolicyOption(stringResource(R.string.cues_runs_manual), stringResource(R.string.cues_runs_manual_c)),
            ),
            index,
            onSelect = {
                when (it) {
                    0 -> onChange(PostureActiveWhen.DuringSessions)
                    1 -> { onChange((pc.activeWhen as? PostureActiveWhen.ActiveHours) ?: PostureActiveWhen.ActiveHours(TimeWindow(LocalTime.of(9, 0), LocalTime.of(18, 0)), cfg.settings.workDays)); windowOpen = true }
                    else -> onChange(PostureActiveWhen.ManualOnly)
                }
            },
        )
        val ah = pc.activeWhen as? PostureActiveWhen.ActiveHours
        if (ah != null) {
            SettingRow(stringResource(R.string.cues_active_hours), isolatedRange(formatTimeRaw(ah.window.start.hour, ah.window.start.minute), formatTimeRaw(ah.window.end.hour, ah.window.end.minute)), { windowOpen = true })
            Text(stringResource(R.string.cues_days), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            app.daycue.ui.components.DayChips(ah.days, { d -> val nd = if (d in ah.days) ah.days - d else ah.days + d; if (nd.isNotEmpty()) onChange(ah.copy(days = nd)) })
            if (windowOpen) WindowSheet(
                stringResource(R.string.cues_active_hours),
                ah.window.start.hour * 60 + ah.window.start.minute, ah.window.end.hour * 60 + ah.window.end.minute, allowAllDay = false,
                onDone = { f, t -> onChange(ah.copy(window = TimeWindow(LocalTime.of(f / 60, f % 60), LocalTime.of(t / 60, t % 60)))) },
                onAllDay = {}, onDismiss = { windowOpen = false }, mark = CueType.Posture,
            )
        }
    }
}

@Composable
private fun activeWhenLabel(a: PostureActiveWhen): String = when (a) {
    PostureActiveWhen.DuringSessions -> stringResource(R.string.cues_runs_sessions)
    is PostureActiveWhen.ActiveHours -> stringResource(R.string.cues_runs_hours_value, isolatedRange(formatTimeRaw(a.window.start.hour, a.window.start.minute), formatTimeRaw(a.window.end.hour, a.window.end.minute)))
    PostureActiveWhen.ManualOnly -> stringResource(R.string.cues_runs_manual)
}

@Composable
private fun timerStartLabel(p: PostureTimerStartPolicy) = stringResource(if (p == PostureTimerStartPolicy.AtConfirmation) R.string.cues_timer_confirm else R.string.cues_timer_cue)

@Composable
private fun meetingPostureLabel(p: PostureMeetingPolicy) = stringResource(when (p) {
    PostureMeetingPolicy.DeferCue -> R.string.cues_pmeet_defer
    PostureMeetingPolicy.Freeze -> R.string.cues_pmeet_freeze
    PostureMeetingPolicy.Ignore -> R.string.cues_pmeet_ignore
})

@Composable
private fun interruptionLabel(p: PostureInterruptionPolicy) = stringResource(when (p) {
    PostureInterruptionPolicy.ResetToFirst -> R.string.cues_int_reset
    PostureInterruptionPolicy.RestartCurrent -> R.string.cues_int_restart
    PostureInterruptionPolicy.ContinueRemaining -> R.string.cues_int_continue
})

// ---- Live control -----------------------------------------------------------------------------------

/** Ticking "now", slower under reduced motion (the timer text still counts, progress moves per minute). */
@Composable
internal fun rememberNow(periodMs: Long = 1000): State<Instant> = produceState(Instant.now(), periodMs) {
    while (true) { value = Instant.now(); delay(periodMs) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PostureLiveScreen(vm: CuesViewModel, onBack: () -> Unit, push: (String) -> Unit) {
    val config by vm.config.collectAsState()
    val engine by vm.engine.collectAsState()
    val now by rememberNow(1000)
    val reduce = LocalReduceMotion.current
    val cfg = config
    val ps = engine?.posture
    val lang = uiLanguage()
    fun act(a: PostureAction) = vm.dispatch(Event.PostureControl(a))
    CuesScreen(stringResource(R.string.cues_posture_live_title), onBack, mark = CueType.Posture) {
        if (cfg == null || ps == null) return@CuesScreen
        val pc = cfg.postureCycle
        if (!pc.enabled) {
            StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_posture_off), actionLabel = stringResource(R.string.cues_turn_on), onAction = { vm.edit(ConfigOp.SetPostureEnabled(true)) })
            return@CuesScreen
        }
        val enabledModes = pc.enabledModes
        fun mode(id: String?) = pc.modes.firstOrNull { it.id == id }
        val current = mode(ps.modeId)
        val nextMode = run {
            val ids = enabledModes.map { it.id }
            val from = ids.indexOf(ps.modeId)
            if (ids.isEmpty()) null else enabledModes[(from + 1).mod(ids.size)]
        }
        val single = enabledModes.size == 1
        // The control follows the engine exactly: a pending switch is SwitchPending, and a Running mode whose end has
        // passed (the engine has not delivered the cue yet) is shown as the same "time to switch" state, never as 0:00.
        val modeEnd = ps.modeEndsAt
        val overdue = ps.phase == PosturePhase.Running && modeEnd != null && !now.isBefore(modeEnd)

        when {
            ps.phase == PosturePhase.Off -> {
                StateBlock(
                    StateBlockKind.Empty, stringResource(R.string.cues_posture_not_running),
                    body = stringResource(if (pc.activeWhen == PostureActiveWhen.ManualOnly) R.string.cues_posture_start_manual else R.string.cues_posture_start_auto),
                    actionLabel = stringResource(R.string.cues_start), onAction = { act(PostureAction.Start) },
                )
            }
            ps.phase == PosturePhase.SwitchPending || overdue -> {
                val pending = if (ps.phase == PosturePhase.SwitchPending) mode(ps.pendingModeId) ?: nextMode else nextMode
                val pendingPhrase = pending?.let { m ->
                    m.phrase?.get(lang)?.takeIf { it.isNotBlank() } ?: when (m.kind) {
                        PostureModeKind.Sitting -> stringResource(R.string.cues_mode_phrase_sit)
                        PostureModeKind.Standing -> stringResource(R.string.cues_mode_phrase_stand)
                        PostureModeKind.Walking -> stringResource(R.string.cues_mode_phrase_walk)
                        PostureModeKind.Custom -> m.name.shown(lang)
                    }
                }.orEmpty()
                Headline(if (single) stringResource(R.string.cues_posture_keep_going) else pendingPhrase)
                Spacer(Modifier.height(12.dp))
                if (pending != null) ProgressTimer(
                    remainingText = clockDuration(pending.durationMin, 0), progress = 0f,
                    description = stringResource(R.string.cues_posture_desc_pending, pending.name.shown(lang), durationDescription(pending.durationMin)),
                    kind = TimerKind.Pending, cue = CueType.Posture,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    stringResource(R.string.cues_posture_switched),
                    { act(if (ps.phase == PosturePhase.SwitchPending) PostureAction.Switched else PostureAction.SwitchNow) },
                    Modifier.fillMaxWidth().heightIn(min = 64.dp),
                )
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Snooze only exists for a delivered switch cue; an overdue Running mode can switch or skip.
                    if (ps.phase == PosturePhase.SwitchPending) DayCueTextButton(stringResource(R.string.cues_posture_snooze, pc.snoozeMin), { act(PostureAction.Snooze) })
                    DayCueTextButton(stringResource(R.string.cues_posture_skip), { act(PostureAction.Skip) })
                    if (ps.phase == PosturePhase.SwitchPending) DayCueTextButton(plusMinutes(5), { act(PostureAction.Extend5) })
                }
                if (pc.timerStart == PostureTimerStartPolicy.AtConfirmation) {
                    Text(stringResource(R.string.cues_posture_timer_starts_on_switched), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
                }
            }
            else -> {
                val paused = ps.phase == PosturePhase.Paused
                val frozen = ps.phase == PosturePhase.Frozen
                val remainingMs = when (ps.phase) {
                    PosturePhase.Running -> Duration.between(now, modeEnd ?: now).toMillis().coerceAtLeast(0)
                    else -> ps.remainingMs ?: 0
                }
                val totalMs = if (ps.phase == PosturePhase.Running && ps.modeStartedAt != null && modeEnd != null)
                    Duration.between(ps.modeStartedAt, modeEnd).toMillis().coerceAtLeast(1)
                else ((current?.durationMin ?: 30) * 60_000L).coerceAtLeast(remainingMs).coerceAtLeast(1)
                var progress = (1f - remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
                if (reduce) progress = (progress * 60f).toInt() / 60f // per-minute-ish steps, no sweep
                val secs = (remainingMs / 1000).toInt()
                val name = current?.name?.shown(lang).orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CueMark(CueType.Posture, size = 40.dp, postureMode = when (current?.kind) {
                        PostureModeKind.Sitting -> MarkMode.Sit; PostureModeKind.Standing -> MarkMode.Stand; PostureModeKind.Walking -> MarkMode.Walk; else -> null
                    })
                    Headline(name)
                }
                Spacer(Modifier.height(12.dp))
                val minutesLeft = (secs + 59) / 60
                ProgressTimer(
                    remainingText = stringResource(R.string.cues_posture_left, clockDuration(secs / 60, secs % 60)),
                    progress = progress,
                    description = stringResource(R.string.cues_posture_desc, name, durationDescription(minutesLeft), durationDescription(current?.durationMin ?: 0)),
                    kind = if (paused || frozen) TimerKind.Frozen else TimerKind.Running,
                    statusText = when {
                        paused -> stringResource(R.string.cues_posture_paused_by_you)
                        frozen -> stringResource(if (ps.interruptedAt != null) R.string.cues_posture_frozen_away else R.string.cues_posture_frozen_hours, durationText(minutesLeft))
                        ps.snoozedUntil != null -> stringResource(R.string.cues_posture_snoozed_until, instantTime(ps.snoozedUntil!!))
                        else -> null
                    },
                )
                if (nextMode != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (single) stringResource(R.string.cues_posture_then_break) else stringResource(R.string.cues_posture_next, nextMode.name.shown(lang), durationText(nextMode.durationMin)),
                        style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (paused) PrimaryButton(stringResource(R.string.cues_resume), { act(PostureAction.Resume) }, Modifier.fillMaxWidth().heightIn(min = 64.dp))
                else PrimaryButton(stringResource(R.string.cues_pause_now), { act(PostureAction.Pause) }, Modifier.fillMaxWidth().heightIn(min = 64.dp))
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.cues_posture_switch_now), { act(PostureAction.SwitchNow) }, Modifier.fillMaxWidth())
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DayCueTextButton(plusMinutes(5), { act(PostureAction.Extend5) })
                    DayCueTextButton(plusMinutes(10), { act(PostureAction.Extend10) })
                    DayCueTextButton(stringResource(R.string.cues_posture_skip_next), { act(PostureAction.Skip) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DayCueTextButton(stringResource(R.string.cues_posture_reset), { act(PostureAction.Reset) })
                    DayCueTextButton(stringResource(R.string.cues_posture_stop), { act(PostureAction.Stop) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DayCueTextButton(stringResource(R.string.cues_posture_edit_modes), { push("posture") })
    }
}

@Composable
internal fun Headline(text: String) {
    Text(text, style = DayCueTheme.type.headline, color = DayCueTheme.colors.ink, modifier = Modifier.semantics { heading() })
}
