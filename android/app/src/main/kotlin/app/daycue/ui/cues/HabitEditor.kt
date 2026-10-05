package app.daycue.ui.cues

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.Confidence
import app.daycue.domain.config.ContextCondition
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.DayStartPolicy
import app.daycue.domain.config.DuringMeeting
import app.daycue.domain.config.Environment
import app.daycue.domain.config.FirstReminderPolicy
import app.daycue.domain.config.Activity as CtxActivity
import app.daycue.domain.config.IntervalAnchor
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.LeaveConditionPolicy
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.ReentryPolicy
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.config.UnansweredPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.PauseChoice
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.time.TimeWindow
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.CuePreviewButton
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.PolicyChoiceList
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationText
import app.daycue.ui.util.isolatedRange
import app.daycue.ui.util.formatTimeRaw
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Composable
internal fun HabitEditorScreen(vm: CuesViewModel, id: String, onBack: () -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val config by vm.config.collectAsState()
    val cfg = config
    val h = cfg?.habit(id)
    when {
        cfg == null -> CuesScreen("", onBack) {}
        h == null -> CuesScreen("", onBack) { StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_item_gone)) }
        h is IntervalHabit -> IntervalHabitEditor(vm, cfg, h, onBack, onOpenCueProfile)
        h is app.daycue.domain.config.TransitionHabit -> BottleEditor(vm, cfg, h, onBack, onOpenCueProfile)
    }
}

private fun defaultFor(h: IntervalHabit): IntervalHabit = when (h.kind) {
    IntervalKind.Sunscreen -> Defaults.sunscreen()
    IntervalKind.Hydration -> Defaults.hydration()
    IntervalKind.Generic -> IntervalHabit(id = h.id, name = h.name)
}

internal fun domainCueType(h: app.daycue.domain.config.Habit): app.daycue.domain.config.CueType = when (h) {
    is IntervalHabit -> h.cueType
    else -> app.daycue.domain.config.CueType.WaterBottle
}

@Composable
private fun IntervalHabitEditor(vm: CuesViewModel, cfg: DayCueConfig, h: IntervalHabit, onBack: () -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val errors by vm.errors.collectAsState()
    val lang = uiLanguage()
    val def = remember(h.id, h.kind) { defaultFor(h) }
    val path = "habits[${h.id}]"
    val (min, max, repMax) = when (h.kind) {
        IntervalKind.Sunscreen -> Triple(30, 360, 5)
        IntervalKind.Hydration -> Triple(15, 240, 3)
        IntervalKind.Generic -> Triple(5, 720, 5)
    }
    fun save(n: IntervalHabit) = vm.edit(ConfigOp.UpsertHabit(n))

    var sheet by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val now = Instant.now()
    val pausedText = pauseText(h.pause, now)
    val anyCond = h.condition.isAny
    val hName = habitName(h)

    // "Changed" bookkeeping for the advanced section.
    val changedFlags = buildList {
        if (!anyCond) { add(h.firstReminder != def.firstReminder); add(h.reentry != def.reentry); add(h.onLeaveCondition != def.onLeaveCondition) }
        else add(h.dayStart != def.dayStart)
        add(h.repeat != def.repeat); add(h.unanswered != def.unanswered); add(h.anchor != def.anchor)
        add(h.snoozeMin != def.snoozeMin); add(h.duringMeeting != def.duringMeeting)
        if (!anyCond) add(h.condition.minConfidence != def.condition.minConfidence || h.condition.unknownMatches != def.condition.unknownMatches)
    }

    CuesScreen(
        title = habitName(h),
        onBack = onBack,
        trailing = {
            DayCueSwitch(h.enabled, { vm.edit(ConfigOp.SetHabitEnabled(h.id, it)) }, Modifier.semantics { contentDescription = hName })
        },
    ) {
        if (pausedText != null) {
            DayCueRow(
                primary = pausedText,
                trailing = { DayCueTextButton(stringResource(R.string.cues_resume), { vm.dispatch(Event.Resume(PauseTarget.Habit(h.id))) }) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cues_remind_every), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
        DurationField(h.intervalMin, { vm.edit(ConfigOp.SetHabitInterval(h.id, it)) }, min = min, max = max,
            presets = if (h.kind == IntervalKind.Sunscreen) listOf(60, 120, 180, 240) else listOf(30, 60, 120, 240).filter { it in min..max })
        FieldErrors(errors, "$path.intervalMin")

        SettingRow(stringResource(R.string.cues_when), conditionText(h.condition, cfg.places), { sheet = "when" })
        FieldErrors(errors, "$path.condition")
        val window = h.activeHours
        SettingRow(
            stringResource(R.string.cues_active_hours),
            if (window == null) stringResource(R.string.cues_all_day)
            else isolatedRange(formatTimeRaw(window.start.hour, window.start.minute), formatTimeRaw(window.end.hour, window.end.minute)),
            { sheet = "hours" },
        )
        Text(stringResource(R.string.cues_days), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        DayChips(h.days, { d -> save(h.copy(days = if (d in h.days) h.days - d else h.days + d)) })
        FieldErrors(errors, "$path.days")
        Spacer(Modifier.height(8.dp))
        PhraseRow(h.phrase, defaultPhrase(h.kind, lang), lang, { sheet = "phrase" })
        SettingRow(stringResource(R.string.cues_sound_voice), stringResource(R.string.cues_sound_voice_value), { onOpenCueProfile(vm.profileId(domainCueType(h), h.cueProfileId)) })

        Spacer(Modifier.height(16.dp))
        CuePreviewButton(if (h.kind == IntervalKind.Sunscreen) CueType.Sunscreen else CueType.Hydration, stringResource(R.string.cues_test_cue), { vm.testReminder(h.cueType) })
        DayCueTextButton(stringResource(R.string.cues_pause), { sheet = "pause" })

        Spacer(Modifier.height(8.dp))
        AdvancedSection(
            changedCount = changedFlags.count { it }, expanded = advanced, onToggle = { advanced = !advanced },
            onReset = { save(def.copy(id = h.id, name = h.name, enabled = h.enabled, pause = h.pause, cueProfileId = h.cueProfileId, phrase = h.phrase, kind = h.kind, condition = h.condition)) },
        ) {
            val ctxName = conditionText(h.condition, cfg.places)
            if (!anyCond) {
                SettingRow(stringResource(R.string.cues_first_reminder), firstReminderLabel(h.firstReminder), { sheet = "first" }, changed = h.firstReminder != def.firstReminder)
                SettingRow(stringResource(R.string.cues_reentry), reentryLabel(h.reentry), { sheet = "reentry" }, changed = h.reentry != def.reentry)
                SettingRow(stringResource(R.string.cues_leave), leaveLabel(h.onLeaveCondition), { sheet = "leave" }, changed = h.onLeaveCondition != def.onLeaveCondition)
            } else {
                SettingRow(stringResource(R.string.cues_day_start), dayStartLabel(h.dayStart), { sheet = "daystart" }, changed = h.dayStart != def.dayStart)
            }
            SettingRow(stringResource(R.string.cues_repeat), repeatLabel(h.repeat), { sheet = "repeat" }, changed = h.repeat != def.repeat)
            FieldErrors(errors, "$path.repeat")
            SettingRow(stringResource(R.string.cues_unanswered), unansweredLabel(h.unanswered), { sheet = "unanswered" }, changed = h.unanswered != def.unanswered)
            SettingRow(stringResource(R.string.cues_anchor), anchorLabel(h.anchor), { sheet = "anchor" }, changed = h.anchor != def.anchor)
            SettingRow(stringResource(R.string.cues_snooze_length), durationText(h.snoozeMin), { sheet = "snooze" }, changed = h.snoozeMin != def.snoozeMin)
            FieldErrors(errors, "$path.snoozeMin")
            SettingRow(stringResource(R.string.cues_during_meetings), meetingLabel(h.duringMeeting), { sheet = "meeting" }, changed = h.duringMeeting != def.duringMeeting)
            if (!anyCond) SettingRow(stringResource(R.string.cues_context_needed), confidenceLabel(h.condition), { sheet = "confidence" },
                changed = h.condition.minConfidence != def.condition.minConfidence || h.condition.unknownMatches != def.condition.unknownMatches)
            if (ctxName.isEmpty()) Unit
        }

        RecentActivity(vm, "habit", h.id)
        Spacer(Modifier.height(16.dp))
        DestructiveButton(stringResource(R.string.cues_delete_reminder), { confirmDelete = true }, Modifier.fillMaxWidth())
    }

    // ---- Sheets ----
    when (sheet) {
        "when" -> ConditionSheet(cfg, h.condition, onChange = { save(h.copy(condition = it)) }, onDismiss = { sheet = null })
        "hours" -> {
            val w = h.activeHours
            WindowSheet(
                stringResource(R.string.cues_active_hours),
                (w?.start ?: LocalTime.of(9, 0)).let { it.hour * 60 + it.minute },
                (w?.end ?: LocalTime.of(21, 0)).let { it.hour * 60 + it.minute },
                allowAllDay = true,
                onDone = { f, t -> save(h.copy(activeHours = TimeWindow(LocalTime.of(f / 60, f % 60), LocalTime.of(t / 60, t % 60)))) },
                onAllDay = { save(h.copy(activeHours = null)) },
                onDismiss = { sheet = null }, mark = markFor(h),
            )
        }
        "phrase" -> PhraseSheet(h.phrase, defaultPhrase(h.kind, lang), lang, { save(h.copy(phrase = it)) }, { sheet = null })
        "pause" -> PauseSheet(vm, PauseTarget.Habit(h.id), canUntilLeave = !anyCond, onDismiss = { sheet = null })
        "first" -> FirstReminderSheet(h, cfg, onChange = { save(h.copy(firstReminder = it)) }, onDismiss = { sheet = null })
        "reentry" -> ReentrySheet(h, onChange = { save(h.copy(reentry = it)) }, onDismiss = { sheet = null })
        "leave" -> PolicySheet(
            stringResource(R.string.cues_leave), listOf(
                PolicyOption(stringResource(R.string.cues_leave_hold), stringResource(R.string.cues_leave_hold_c)),
                PolicyOption(stringResource(R.string.cues_leave_keep), stringResource(R.string.cues_leave_keep_c)),
                PolicyOption(stringResource(R.string.cues_leave_anyway), stringResource(R.string.cues_leave_anyway_c)),
            ), h.onLeaveCondition.ordinal, { save(h.copy(onLeaveCondition = LeaveConditionPolicy.entries[it])) }, { sheet = null }, markFor(h),
        )
        "daystart" -> PolicySheet(
            stringResource(R.string.cues_day_start), listOf(
                PolicyOption(stringResource(R.string.cues_daystart_after), stringResource(R.string.cues_daystart_after_c)),
                PolicyOption(stringResource(R.string.cues_daystart_at), stringResource(R.string.cues_daystart_at_c)),
            ), h.dayStart.ordinal, { save(h.copy(dayStart = DayStartPolicy.entries[it])) }, { sheet = null }, markFor(h),
        )
        "unanswered" -> PolicySheet(
            stringResource(R.string.cues_unanswered), listOf(
                PolicyOption(stringResource(R.string.cues_unans_roll), stringResource(R.string.cues_unans_roll_c)),
                PolicyOption(stringResource(R.string.cues_unans_stay), stringResource(R.string.cues_unans_stay_c)),
            ), h.unanswered.ordinal, { save(h.copy(unanswered = UnansweredPolicy.entries[it])) }, { sheet = null }, markFor(h),
        )
        "anchor" -> PolicySheet(
            stringResource(R.string.cues_anchor), listOf(
                PolicyOption(stringResource(R.string.cues_anchor_ack), stringResource(R.string.cues_anchor_ack_c)),
                PolicyOption(stringResource(R.string.cues_anchor_due), stringResource(R.string.cues_anchor_due_c)),
            ), h.anchor.ordinal, { save(h.copy(anchor = IntervalAnchor.entries[it])) }, { sheet = null }, markFor(h),
        )
        "meeting" -> PolicySheet(
            stringResource(R.string.cues_during_meetings), listOf(
                PolicyOption(stringResource(R.string.cues_meet_defer), stringResource(R.string.cues_meet_defer_c)),
                PolicyOption(stringResource(R.string.cues_meet_silent), stringResource(R.string.cues_meet_silent_c)),
                PolicyOption(stringResource(R.string.cues_meet_deliver), stringResource(R.string.cues_meet_deliver_c)),
            ), h.duringMeeting.ordinal, { save(h.copy(duringMeeting = DuringMeeting.entries[it])) }, { sheet = null }, markFor(h),
        )
        "snooze" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_snooze_length), markFor(h)) {
            DurationField(h.snoozeMin, { save(h.copy(snoozeMin = it)) }, min = 5, max = 120, presets = listOf(5, 15, 30, 60))
            FieldErrors(errors, "$path.snoozeMin")
        }
        "repeat" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_repeat), markFor(h)) {
            RepeatEditor(h.repeat, maxRepeats = repMax, onChange = { save(h.copy(repeat = it)) })
            FieldErrors(errors, "$path.repeat")
        }
        "confidence" -> ConfidenceSheet(h.condition, { save(h.copy(condition = it)) }, { sheet = null })
    }
    if (confirmDelete) {
        DayCueDialog(
            title = stringResource(R.string.cues_delete_reminder_q, habitName(h)),
            text = stringResource(R.string.cues_delete_reminder_body),
            confirmLabel = stringResource(R.string.cues_delete), dismissLabel = stringResource(R.string.cues_cancel),
            onConfirm = {
                confirmDelete = false
                vm.edit(ConfigOp.DeleteHabit(h.id), CuesMessage(R.string.cues_deleted_named, listOf(h.name)))
                onBack()
            },
            onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

// ---- Shared pieces -------------------------------------------------------------------------------

internal fun defaultPhrase(kind: IntervalKind, lang: app.daycue.domain.config.Language): String = when (kind) {
    IntervalKind.Sunscreen -> Defaults.sunscreen().phrase?.get(lang).orEmpty()
    IntervalKind.Hydration -> Defaults.hydration().phrase?.get(lang).orEmpty()
    IntervalKind.Generic -> ""
}

@Composable
internal fun PhraseRow(phrase: LocalizedText?, default: String, lang: app.daycue.domain.config.Language, onClick: () -> Unit) {
    val text = phrase?.get(lang)?.takeIf { it.isNotBlank() } ?: default.ifBlank { stringResource(R.string.cues_phrase_default) }
    SettingRow(stringResource(R.string.cues_says), "“$text”", onClick, changed = phrase != null && phrase.get(lang).isNotBlank() && phrase.get(lang) != default)
}

@Composable
internal fun PhraseSheet(
    phrase: LocalizedText?, default: String, lang: app.daycue.domain.config.Language,
    onChange: (LocalizedText?) -> Unit, onDismiss: () -> Unit,
) {
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_says)) {
        CommitTextField(
            value = phrase?.get(lang).orEmpty().ifBlank { default },
            onCommit = { t ->
                val base = phrase ?: LocalizedText()
                onChange(if (lang == app.daycue.domain.config.Language.he) base.copy(he = t) else base.copy(en = t))
            },
            label = stringResource(R.string.cues_phrase_label), helper = stringResource(R.string.cues_phrase_help), singleLine = false,
        )
        DayCueTextButton(stringResource(R.string.cues_phrase_reset), { onChange(null); onDismiss() })
    }
}

/** Pause options (SUN-10, HYD-4); sends the engine event, which writes the pause into the config. */
@Composable
internal fun PauseSheet(vm: CuesViewModel, target: PauseTarget, canUntilLeave: Boolean, onDismiss: () -> Unit) {
    fun go(c: PauseChoice) { vm.dispatch(Event.Pause(target, c)); onDismiss() }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_pause_title)) {
        DayCueRow(stringResource(R.string.cues_pause_1h), onClick = { go(PauseChoice.For(60)) })
        DayCueRow(stringResource(R.string.cues_pause_2h), onClick = { go(PauseChoice.For(120)) })
        if (canUntilLeave) DayCueRow(stringResource(R.string.cues_pause_leave), onClick = { go(PauseChoice.UntilConditionEnds) })
        DayCueRow(stringResource(R.string.cues_pause_today), onClick = { go(PauseChoice.RestOfToday) })
        DayCueRow(stringResource(R.string.cues_pause_indef), secondary = stringResource(R.string.cues_pause_indef_c), onClick = { go(PauseChoice.Indefinite) })
        Text(stringResource(R.string.cues_pause_note), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun RepeatEditor(r: RepeatPolicy, maxRepeats: Int, onChange: (RepeatPolicy) -> Unit, minEvery: Int = 5, maxEvery: Int = 60) {
    Column(Modifier.fillMaxWidth()) {
        NumberRow(
            stringResource(R.string.cues_repeat_count), r.maxRepeats, 0, maxRepeats, { onChange(r.copy(maxRepeats = it)) },
            valueText = if (r.maxRepeats == 0) stringResource(R.string.cues_never) else r.maxRepeats.toString(),
            valueDescription = if (r.maxRepeats == 0) stringResource(R.string.cues_never) else r.maxRepeats.toString(),
        )
        if (r.maxRepeats > 0) {
            Text(stringResource(R.string.cues_repeat_every), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
            DurationField(r.everyMin, { onChange(r.copy(everyMin = it)) }, min = minEvery, max = maxEvery, presets = listOf(5, 10, 20, 30).filter { it in minEvery..maxEvery })
        }
    }
}

@Composable
private fun repeatLabel(r: RepeatPolicy): String =
    if (r.maxRepeats == 0) stringResource(R.string.cues_repeat_none) else stringResource(R.string.cues_repeat_summary, r.maxRepeats, durationText(r.everyMin))

@Composable
internal fun firstReminderLabel(p: FirstReminderPolicy): String = when (p) {
    FirstReminderPolicy.OnConditionStart -> stringResource(R.string.cues_first_now)
    is FirstReminderPolicy.AfterDelay -> stringResource(R.string.cues_first_delay_label, durationText(p.minutes))
    FirstReminderPolicy.AfterFullInterval -> stringResource(R.string.cues_first_full)
    FirstReminderPolicy.OnlyAfterApplied -> stringResource(R.string.cues_first_after_ack)
}

@Composable
private fun reentryLabel(p: ReentryPolicy): String = when (p) {
    is ReentryPolicy.RemindOnReentry -> if (p.graceMin == 0) stringResource(R.string.cues_reentry_now) else stringResource(R.string.cues_reentry_after, durationText(p.graceMin))
    ReentryPolicy.TreatAsFirst -> stringResource(R.string.cues_reentry_first)
    ReentryPolicy.WaitNextInterval -> stringResource(R.string.cues_reentry_wait)
}

@Composable
private fun leaveLabel(p: LeaveConditionPolicy): String = stringResource(when (p) {
    LeaveConditionPolicy.RetractAndHold -> R.string.cues_leave_hold
    LeaveConditionPolicy.KeepVisible -> R.string.cues_leave_keep
    LeaveConditionPolicy.RemindAnyway -> R.string.cues_leave_anyway
})

@Composable
private fun dayStartLabel(p: DayStartPolicy): String = stringResource(if (p == DayStartPolicy.IntervalAfterStart) R.string.cues_daystart_after else R.string.cues_daystart_at)

@Composable
private fun unansweredLabel(p: UnansweredPolicy): String = stringResource(if (p == UnansweredPolicy.RollForward) R.string.cues_unans_roll else R.string.cues_unans_stay)

@Composable
private fun anchorLabel(p: IntervalAnchor): String = stringResource(if (p == IntervalAnchor.FromAck) R.string.cues_anchor_ack else R.string.cues_anchor_due)

@Composable
internal fun meetingLabel(p: DuringMeeting): String = stringResource(when (p) {
    DuringMeeting.Defer -> R.string.cues_meet_defer
    DuringMeeting.DeliverSilently -> R.string.cues_meet_silent
    DuringMeeting.Deliver -> R.string.cues_meet_deliver
})

@Composable
private fun confidenceLabel(c: ContextCondition): String {
    val base = stringResource(when (c.minConfidence) {
        Confidence.Low -> R.string.cues_conf_low
        Confidence.Medium -> R.string.cues_conf_medium
        Confidence.High -> R.string.cues_conf_high
    })
    return if (c.unknownMatches) "$base · ${stringResource(R.string.cues_conf_unknown_short)}" else base
}

@Composable
private fun FirstReminderSheet(h: IntervalHabit, cfg: DayCueConfig, onChange: (FirstReminderPolicy) -> Unit, onDismiss: () -> Unit) {
    val p = h.firstReminder
    val dwell = cfg.contextRules.outdoorEnterDwellMin
    val start = LocalTime.of(10, 0)
    val example = when (p) {
        FirstReminderPolicy.OnConditionStart -> stringResource(R.string.cues_first_example, timeText(start), timeText(start.plusMinutes(dwell.toLong())))
        is FirstReminderPolicy.AfterDelay -> stringResource(R.string.cues_first_example, timeText(start), timeText(start.plusMinutes((dwell + p.minutes).toLong())))
        FirstReminderPolicy.AfterFullInterval -> stringResource(R.string.cues_first_example, timeText(start), timeText(start.plusMinutes(h.intervalMin.toLong())))
        FirstReminderPolicy.OnlyAfterApplied -> stringResource(R.string.cues_first_example_none, timeText(start))
    }
    val index = when (p) { FirstReminderPolicy.OnConditionStart -> 0; is FirstReminderPolicy.AfterDelay -> 1; FirstReminderPolicy.AfterFullInterval -> 2; FirstReminderPolicy.OnlyAfterApplied -> 3 }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_first_reminder), markFor(h)) {
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_first_now), stringResource(R.string.cues_first_now_c)),
                PolicyOption(stringResource(R.string.cues_first_delay), stringResource(R.string.cues_first_delay_c)),
                PolicyOption(stringResource(R.string.cues_first_full), stringResource(R.string.cues_first_full_c)),
                PolicyOption(stringResource(R.string.cues_first_after_ack), stringResource(R.string.cues_first_after_ack_c)),
            ),
            index,
            onSelect = {
                onChange(when (it) {
                    0 -> FirstReminderPolicy.OnConditionStart
                    1 -> FirstReminderPolicy.AfterDelay((p as? FirstReminderPolicy.AfterDelay)?.minutes ?: 10)
                    2 -> FirstReminderPolicy.AfterFullInterval
                    else -> FirstReminderPolicy.OnlyAfterApplied
                })
            },
            example = example,
        )
        if (p is FirstReminderPolicy.AfterDelay) {
            DurationField(p.minutes, { onChange(FirstReminderPolicy.AfterDelay(it)) }, min = 0, max = 120, presets = listOf(5, 10, 30, 60))
        }
    }
}

@Composable
private fun ReentrySheet(h: IntervalHabit, onChange: (ReentryPolicy) -> Unit, onDismiss: () -> Unit) {
    val p = h.reentry
    val index = when (p) { is ReentryPolicy.RemindOnReentry -> 0; ReentryPolicy.TreatAsFirst -> 1; ReentryPolicy.WaitNextInterval -> 2 }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_reentry), markFor(h)) {
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_reentry_now), stringResource(R.string.cues_reentry_now_c)),
                PolicyOption(stringResource(R.string.cues_reentry_first), stringResource(R.string.cues_reentry_first_c)),
                PolicyOption(stringResource(R.string.cues_reentry_wait), stringResource(R.string.cues_reentry_wait_c)),
            ),
            index,
            onSelect = { onChange(when (it) { 0 -> ReentryPolicy.RemindOnReentry((p as? ReentryPolicy.RemindOnReentry)?.graceMin ?: 0); 1 -> ReentryPolicy.TreatAsFirst; else -> ReentryPolicy.WaitNextInterval }) },
        )
        if (p is ReentryPolicy.RemindOnReentry) {
            Text(stringResource(R.string.cues_reentry_grace), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
            DurationField(p.graceMin, { onChange(ReentryPolicy.RemindOnReentry(it)) }, min = 0, max = 30, presets = listOf(0, 5, 10, 30))
        }
    }
}

@Composable
private fun ConfidenceSheet(c: ContextCondition, onChange: (ContextCondition) -> Unit, onDismiss: () -> Unit) {
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_context_needed)) {
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_conf_low), stringResource(R.string.cues_conf_low_c)),
                PolicyOption(stringResource(R.string.cues_conf_medium), stringResource(R.string.cues_conf_medium_c)),
                PolicyOption(stringResource(R.string.cues_conf_high), stringResource(R.string.cues_conf_high_c)),
            ),
            c.minConfidence.ordinal, { onChange(c.copy(minConfidence = Confidence.entries[it])) },
        )
        app.daycue.ui.components.SwitchRow(
            stringResource(R.string.cues_conf_unknown), c.unknownMatches, { onChange(c.copy(unknownMatches = it)) },
            secondary = stringResource(R.string.cues_conf_unknown_c),
        )
    }
}

/** Simple "When" choices (UX 3.4): Outdoors / Indoors / At a place / Working / Any time. */
@Composable
private fun ConditionSheet(cfg: DayCueConfig, c: ContextCondition, onChange: (ContextCondition) -> Unit, onDismiss: () -> Unit) {
    fun base(env: Set<Environment>? = null, act: Set<CtxActivity>? = null, places: Set<String>? = null) =
        ContextCondition(env, places, act, c.minConfidence, c.unknownMatches)
    val mode = when {
        c.isAny -> 4
        c.environments == setOf(Environment.Outdoor) && c.places == null && c.activities == null -> 0
        c.environments == setOf(Environment.Indoor) && c.places == null && c.activities == null -> 1
        c.places != null && c.environments == null && c.activities == null -> 2
        c.activities == setOf(CtxActivity.Working) && c.environments == null && c.places == null -> 3
        else -> -1
    }
    DayCueBottomSheet(onDismiss, stringResource(R.string.cues_when)) {
        PolicyChoiceList(
            listOf(
                PolicyOption(stringResource(R.string.cues_when_outdoors), stringResource(R.string.cues_when_outdoors_c)),
                PolicyOption(stringResource(R.string.cues_when_indoors), stringResource(R.string.cues_when_indoors_c)),
                PolicyOption(stringResource(R.string.cues_when_place), stringResource(R.string.cues_when_place_c)),
                PolicyOption(stringResource(R.string.cues_when_working), stringResource(R.string.cues_when_working_c)),
                PolicyOption(stringResource(R.string.cues_when_any), stringResource(R.string.cues_when_any_c)),
            ),
            mode,
            onSelect = {
                onChange(when (it) {
                    0 -> base(env = setOf(Environment.Outdoor))
                    1 -> base(env = setOf(Environment.Indoor))
                    2 -> base(places = c.places ?: cfg.places.firstOrNull()?.let { p -> setOf(p.id) } ?: emptySet())
                    3 -> base(act = setOf(CtxActivity.Working))
                    else -> ContextCondition.ANY.copy(minConfidence = c.minConfidence)
                })
            },
        )
        if (mode == 2) {
            if (cfg.places.isEmpty()) Text(stringResource(R.string.cues_when_no_places), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            cfg.places.forEach { p ->
                val on = p.id in (c.places ?: emptySet())
                DayCueRow(
                    primary = placeName(p),
                    onClick = {
                        val cur = c.places ?: emptySet()
                        val next = if (on) cur - p.id else cur + p.id
                        if (next.isNotEmpty()) onChange(base(places = next))
                    },
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                    trailing = { if (on) app.daycue.ui.components.GlyphIcon(app.daycue.ui.components.Glyph.Check, DayCueTheme.colors.ink) },
                    semanticsExtra = { selected = on },
                )
            }
        }
        if (mode == -1) Text(stringResource(R.string.cues_when_custom_note), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
    }
}
