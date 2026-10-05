package app.daycue.ui.cues

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.LockScreenPresentation
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MedicationQuietHours
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.config.TravelPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.ValidationError
import app.daycue.engine.ApplyOutcome
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.ChoiceRow
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.DiffReview
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val MedSaver = Saver<Medication, String>(
    save = { DayCueJson.encodeToString(Medication.serializer(), it) },
    restore = { DayCueJson.decodeFromString(Medication.serializer(), it) },
)

/**
 * Medication editor (UX 3.5): a draft with Review & save, never autosave (MED-9). The travel/timezone choice is
 * required for a new item. Leaving with unsaved changes asks "Discard changes?".
 */
@Composable
internal fun MedicationEditorScreen(vm: CuesViewModel, id: String, onBack: () -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val config by vm.config.collectAsState()
    val cfg = config
    if (cfg == null) { CuesScreen(stringResource(R.string.cues_med_new), onBack) {}; return }
    val isNew = id == "new"
    val original = if (isNew) null else cfg.medication(id)
    if (!isNew && original == null) {
        CuesScreen("", onBack) { app.daycue.ui.components.StateBlock(app.daycue.ui.components.StateBlockKind.Empty, stringResource(R.string.cues_item_gone)) }
        return
    }
    MedicationEditorContent(vm, cfg, original, onBack, onOpenCueProfile)
}

@Composable
private fun MedicationEditorContent(vm: CuesViewModel, cfg: DayCueConfig, original: Medication?, onBack: () -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = remember { original ?: Medication(id = "med-" + System.currentTimeMillis().toString(36), label = "", times = listOf(LocalTime.of(8, 0))) }
    var draft by rememberSaveable(initial.id, stateSaver = MedSaver) { mutableStateOf(initial) }
    var travelChosen by rememberSaveable(initial.id) { mutableStateOf(original != null) }
    var advanced by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var timeEdit by remember { mutableStateOf<Int?>(null) } // index, or -1 = add
    var review by remember { mutableStateOf<List<ConfigOp>?>(null) }
    var localErrors by remember { mutableStateOf<List<ValidationError>>(emptyList()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var stopDate by remember { mutableStateOf(LocalDate.now()) }
    val storeErrors by vm.errors.collectAsState()
    val errors = localErrors + storeErrors
    val path = "medications[${draft.id}]"
    val base = original ?: initial
    val dirty = draft != base || (original == null && travelChosen)
    val def = remember { Medication(id = "x", label = "", times = emptyList()) }

    fun requestBack() { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = true) { requestBack() }

    val ready = draft.label.isNotBlank() && draft.times.isNotEmpty() && travelChosen
    val changedFlags = listOf(
        draft.repeat != def.repeat, draft.snoozeMin != def.snoozeMin, draft.lockScreen != def.lockScreen,
        draft.speakLabel != def.speakLabel, draft.quietHours != def.quietHours, draft.historyRetentionDays != def.historyRetentionDays,
    )

    CuesScreen(
        title = if (original == null) stringResource(R.string.cues_med_new) else original.label,
        onBack = ::requestBack,
    ) {
        DayCueTextField(
            draft.label, { draft = draft.copy(label = it.take(40)) }, stringResource(R.string.cues_med_name),
            helper = stringResource(R.string.cues_med_name_help),
        )
        FieldErrors(errors, "$path.label")

        Text(stringResource(R.string.cues_med_times), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 16.dp))
        draft.times.forEachIndexed { i, t ->
            DayCueRow(
                primary = timeText(t),
                onClick = { timeEdit = i },
                trailing = if (draft.times.size > 1) ({ DayCueTextButton(stringResource(R.string.cues_remove), { draft = draft.copy(times = draft.times.filterIndexed { j, _ -> j != i }) }) }) else null,
            )
        }
        FieldErrors(errors, "$path.times")
        DayCueTextButton(stringResource(R.string.cues_med_add_time), { timeEdit = -1 })

        Text(stringResource(R.string.cues_days), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        DayChips(draft.days, { d -> draft = draft.copy(days = if (d in draft.days) draft.days - d else draft.days + d) })
        FieldErrors(errors, "$path.days")

        Text(stringResource(R.string.cues_med_travel), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 20.dp))
        Text(stringResource(R.string.cues_med_travel_required), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
        val keeps = draft.travelPolicy as? TravelPolicy.KeepHomeTimezone
        ChoiceRow(
            stringResource(R.string.cues_med_travel_follow), stringResource(R.string.cues_med_travel_follow_c),
            selected = travelChosen && draft.travelPolicy is TravelPolicy.FollowLocalTime,
            onSelect = { travelChosen = true; draft = draft.copy(travelPolicy = TravelPolicy.FollowLocalTime) },
        )
        val homeZone = keeps?.zone ?: ZoneId.systemDefault()
        ChoiceRow(
            stringResource(R.string.cues_med_travel_home, homeZone.id), stringResource(R.string.cues_med_travel_home_c),
            selected = travelChosen && keeps != null,
            onSelect = { travelChosen = true; draft = draft.copy(travelPolicy = TravelPolicy.KeepHomeTimezone(homeZone)) },
        )
        if (!travelChosen) Text(stringResource(R.string.cues_med_travel_pick), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)

        Spacer(Modifier.height(8.dp))
        AdvancedSection(changedFlags.count { it }, advanced, { advanced = !advanced }, onReset = {
            draft = draft.copy(repeat = def.repeat, snoozeMin = def.snoozeMin, lockScreen = def.lockScreen, speakLabel = def.speakLabel, quietHours = def.quietHours, historyRetentionDays = def.historyRetentionDays)
        }) {
            SettingRow(stringResource(R.string.cues_med_repeat), repeatSummary(draft.repeat), { sheet = "repeat" }, changed = changedFlags[0])
            FieldErrors(errors, "$path.repeat")
            SettingRow(stringResource(R.string.cues_snooze_length), durationText(draft.snoozeMin), { sheet = "snooze" }, changed = changedFlags[1])
            FieldErrors(errors, "$path.snoozeMin")
            SettingRow(stringResource(R.string.cues_med_lock), lockLabel(draft.lockScreen), { sheet = "lock" }, changed = changedFlags[2])
            SwitchRow(stringResource(R.string.cues_med_speak), draft.speakLabel, { draft = draft.copy(speakLabel = it) }, secondary = stringResource(if (draft.speakLabel) R.string.cues_med_speak_on_c else R.string.cues_med_speak_off_c))
            SettingRow(stringResource(R.string.cues_med_quiet), quietLabel(draft.quietHours), { sheet = "quiet" }, changed = changedFlags[4])
            SettingRow(stringResource(R.string.cues_med_retention), durationDays(draft.historyRetentionDays), { sheet = "retention" }, changed = changedFlags[5])
            FieldErrors(errors, "$path.historyRetentionDays")
            SettingRow(stringResource(R.string.cues_sound_voice), stringResource(R.string.cues_sound_voice_value), { onOpenCueProfile(vm.profileId(app.daycue.domain.config.CueType.Medication, draft.cueProfileId)) })
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            stringResource(R.string.cues_med_review), enabled = ready,
            onClick = {
                val ops = listOf<ConfigOp>(ConfigOp.UpsertMedication(draft.copy(label = draft.label.trim())))
                scope.launch {
                    val p = vm.preview(ops)
                    if (p.errors.isNotEmpty()) localErrors = p.errors else { localErrors = emptyList(); review = ops }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!ready) Text(stringResource(R.string.cues_med_review_needs), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 4.dp))

        if (original != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(stringResource(R.string.cues_med_stop), { stopDate = original.endDate?.takeIf { !it.isBefore(LocalDate.now()) } ?: LocalDate.now(); sheet = "stop" }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            DestructiveButton(stringResource(R.string.cues_med_delete), { confirmDelete = true }, Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.cues_med_disclaimer), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 16.dp))
    }

    // ---- sheets over the draft ----
    timeEdit?.let { idx ->
        val current = if (idx >= 0) draft.times[idx] else LocalTime.of(20, 0)
        TimeSheet(stringResource(R.string.cues_med_time), current.hour * 60 + current.minute, onDone = { m ->
            val t = LocalTime.of(m / 60, m % 60)
            val list = if (idx >= 0) draft.times.mapIndexed { i, x -> if (i == idx) t else x } else draft.times + t
            draft = draft.copy(times = list.distinct().sorted())
        }, onDismiss = { timeEdit = null }, mark = CueType.Medication)
    }
    when (sheet) {
        "repeat" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_med_repeat), CueType.Medication) {
            RepeatEditor(draft.repeat, maxRepeats = 12, onChange = { draft = draft.copy(repeat = it) })
            Text(stringResource(R.string.cues_med_repeat_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
        }
        "snooze" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_snooze_length), CueType.Medication) {
            DurationField(draft.snoozeMin, { draft = draft.copy(snoozeMin = it) }, min = 5, max = 60, presets = listOf(5, 10, 15, 30))
        }
        "lock" -> PolicySheet(
            stringResource(R.string.cues_med_lock), listOf(
                PolicyOption(stringResource(R.string.cues_lock_generic), stringResource(R.string.cues_lock_generic_c)),
                PolicyOption(stringResource(R.string.cues_lock_full), stringResource(R.string.cues_lock_full_c)),
                PolicyOption(stringResource(R.string.cues_lock_hidden), stringResource(R.string.cues_lock_hidden_c)),
            ), draft.lockScreen.ordinal, { draft = draft.copy(lockScreen = LockScreenPresentation.entries[it]) }, { sheet = null }, CueType.Medication,
        )
        "quiet" -> PolicySheet(
            stringResource(R.string.cues_med_quiet), listOf(
                PolicyOption(stringResource(R.string.cues_quiet_normal), stringResource(R.string.cues_quiet_normal_c)),
                PolicyOption(stringResource(R.string.cues_quiet_silent), stringResource(R.string.cues_quiet_silent_c)),
            ), draft.quietHours.ordinal, { draft = draft.copy(quietHours = MedicationQuietHours.entries[it]) }, { sheet = null }, CueType.Medication,
        )
        "retention" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_med_retention), CueType.Medication) {
            NumberRow(
                stringResource(R.string.cues_med_retention), draft.historyRetentionDays, 30, 730,
                { draft = draft.copy(historyRetentionDays = it) }, valueText = durationDays(draft.historyRetentionDays), step = 30,
            )
            Text(stringResource(R.string.cues_med_retention_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
        }
        "stop" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_med_stop), CueType.Medication) {
            Text(stringResource(R.string.cues_med_stop_c), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2)
            Spacer(Modifier.height(8.dp))
            DateStepper(stopDate, { stopDate = it })
            Spacer(Modifier.height(16.dp))
            PrimaryButton(stringResource(R.string.cues_med_review), { review = listOf(ConfigOp.SetMedicationEndDate(draft.id, stopDate)); sheet = null }, Modifier.fillMaxWidth())
            if (original?.endDate != null) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.cues_med_resume_reminders), { review = listOf(ConfigOp.SetMedicationEndDate(draft.id, null)); sheet = null }, Modifier.fillMaxWidth())
            }
        }
    }

    review?.let { ops ->
        val lines = medicationDiffLines(original, ops, draft, travelChosen)
        DayCueBottomSheet({ review = null }, stringResource(R.string.cues_med_review_title), CueType.Medication) {
            DiffReview(
                title = stringResource(R.string.cues_med_review_sub), lines = lines,
                confirmLabel = stringResource(R.string.cues_med_save), backLabel = stringResource(R.string.cues_med_back),
                onConfirm = {
                    vm.edit(ops, CuesMessage(R.string.cues_saved, undo = false)) { out ->
                        if (out is ApplyOutcome.Applied) { review = null; onBack() } else review = null
                    }
                },
                onBack = { review = null },
            )
            FieldErrors(storeErrors, "medications")
        }
    }
    if (confirmDiscard) {
        DayCueDialog(
            title = stringResource(R.string.cues_discard_title), text = stringResource(R.string.cues_discard_body),
            confirmLabel = stringResource(R.string.cues_discard), dismissLabel = stringResource(R.string.cues_keep_editing),
            onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmDelete && original != null) {
        DayCueDialog(
            title = stringResource(R.string.cues_med_delete_q, original.label), text = stringResource(R.string.cues_med_delete_body),
            confirmLabel = stringResource(R.string.cues_delete), dismissLabel = stringResource(R.string.cues_cancel),
            onConfirm = {
                confirmDelete = false
                vm.edit(listOf(ConfigOp.DeleteMedication(original.id)), CuesMessage(R.string.cues_deleted_named, listOf(original.label))) { if (it is ApplyOutcome.Applied) onBack() }
            },
            onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

@Composable
private fun repeatSummary(r: RepeatPolicy) =
    if (r.maxRepeats == 0) stringResource(R.string.cues_repeat_none) else pluralStringResource(R.plurals.cues_repeat_summary, r.maxRepeats, r.maxRepeats, durationText(r.everyMin))

@Composable
private fun durationDays(d: Int) = pluralStringResource(R.plurals.su_days, d, d)

@Composable
private fun lockLabel(p: LockScreenPresentation) = stringResource(when (p) {
    LockScreenPresentation.Generic -> R.string.cues_lock_generic
    LockScreenPresentation.Full -> R.string.cues_lock_full
    LockScreenPresentation.Hidden -> R.string.cues_lock_hidden
})

@Composable
private fun quietLabel(p: MedicationQuietHours) = stringResource(if (p == MedicationQuietHours.DeliverNormally) R.string.cues_quiet_normal else R.string.cues_quiet_silent)

/** Plain-language diff for the review step (MED-9). No medical wording, only what changes in the schedule. */
@Composable
private fun medicationDiffLines(original: Medication?, ops: List<ConfigOp>, draft: Medication, travelChosen: Boolean): List<String> {
    val end = ops.singleOrNull() as? ConfigOp.SetMedicationEndDate
    if (end != null) {
        return listOf(
            if (end.endDate != null) stringResource(R.string.cues_diff_stops_after, dateText(end.endDate!!)) else stringResource(R.string.cues_diff_resumes),
        )
    }
    val lines = mutableListOf<String>()
    val travel = @Composable { p: TravelPolicy -> if (p is TravelPolicy.KeepHomeTimezone) stringResource(R.string.cues_diff_travel_home, p.zone.id) else stringResource(R.string.cues_diff_travel_follow) }
    if (original == null) {
        lines += stringResource(R.string.cues_diff_new, draft.label.trim())
        lines += stringResource(R.string.cues_diff_times, draft.times.sorted().map { timeText(it) }.joinToString(", "), daysSummary(draft.days))
        lines += travel(draft.travelPolicy)
        return lines
    }
    if (draft.label.trim() != original.label) lines += stringResource(R.string.cues_diff_name, draft.label.trim())
    (draft.times - original.times.toSet()).sorted().forEach { lines += stringResource(R.string.cues_diff_adds_time, timeText(it), daysSummary(draft.days)) }
    (original.times - draft.times.toSet()).sorted().forEach { lines += stringResource(R.string.cues_diff_removes_time, timeText(it)) }
    if (draft.days != original.days) lines += stringResource(R.string.cues_diff_days, daysSummary(draft.days))
    if (draft.travelPolicy != original.travelPolicy) lines += travel(draft.travelPolicy)
    if (draft.repeat != original.repeat) lines += stringResource(R.string.cues_diff_repeat, repeatSummary(draft.repeat))
    if (draft.snoozeMin != original.snoozeMin) lines += stringResource(R.string.cues_diff_snooze, durationText(draft.snoozeMin))
    if (draft.lockScreen != original.lockScreen) lines += stringResource(R.string.cues_diff_lock, lockLabel(draft.lockScreen))
    if (draft.speakLabel != original.speakLabel) lines += stringResource(if (draft.speakLabel) R.string.cues_diff_speak_on else R.string.cues_diff_speak_off)
    if (draft.quietHours != original.quietHours) lines += stringResource(R.string.cues_diff_quiet, quietLabel(draft.quietHours))
    if (draft.historyRetentionDays != original.historyRetentionDays) lines += stringResource(R.string.cues_diff_retention, durationDays(draft.historyRetentionDays))
    if (lines.isEmpty()) lines += stringResource(R.string.cues_diff_none)
    return lines
}
