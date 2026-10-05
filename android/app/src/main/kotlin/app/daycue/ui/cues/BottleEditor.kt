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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.BottleTrigger
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.config.ScheduledDeparture
import app.daycue.domain.config.TransitionHabit
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.time.ALL_DAYS
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.CuePreviewButton
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationText
import java.time.LocalTime

/** Water bottle editor (UX 3.4): places, triggers, set departure times; cooldown and duplicate window in More. */
@Composable
internal fun BottleEditor(vm: CuesViewModel, cfg: DayCueConfig, h: TransitionHabit, onBack: () -> Unit, onOpenCueProfile: (String?) -> Unit) {
    val errors by vm.errors.collectAsState()
    val lang = uiLanguage()
    val def = remember { Defaults.waterBottle() }
    val path = "habits[${h.id}]"
    fun save(n: TransitionHabit) = vm.edit(ConfigOp.UpsertHabit(n))
    var sheet by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pausedText = pauseText(h.pause, java.time.Instant.now())
    val hName = habitName(h)
    val changed = listOf(h.cooldownPerPlaceMin != def.cooldownPerPlaceMin, h.dedupWindowMin != def.dedupWindowMin, h.repeat != def.repeat)

    CuesScreen(
        title = habitName(h), onBack = onBack,
        trailing = { DayCueSwitch(h.enabled, { vm.edit(ConfigOp.SetHabitEnabled(h.id, it)) }, Modifier.semantics { contentDescription = hName }) },
    ) {
        if (pausedText != null) {
            DayCueRow(primary = pausedText, trailing = { DayCueTextButton(stringResource(R.string.cues_resume), { vm.dispatch(Event.Resume(PauseTarget.Habit(h.id))) }) })
        }
        val placeSummary = if (h.placeIds.isEmpty()) stringResource(R.string.cues_bottle_places_default)
        else h.placeIds.mapNotNull { id -> cfg.place(id)?.let { placeName(it) } }.joinToString(", ")
        SettingRow(stringResource(R.string.cues_bottle_places), placeSummary, { sheet = "places" })
        FieldErrors(errors, "$path.placeIds")

        Text(stringResource(R.string.cues_bottle_triggers), style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 16.dp))
        SwitchRow(
            stringResource(R.string.cues_bottle_leaving_now), true, {},
            secondary = stringResource(R.string.cues_bottle_leaving_now_c),
            lockedReason = stringResource(R.string.cues_bottle_most_reliable),
        )
        SwitchRow(
            stringResource(R.string.cues_bottle_geofence), BottleTrigger.GeofenceExit in h.triggers,
            { on -> save(h.copy(triggers = if (on) h.triggers + BottleTrigger.GeofenceExit else h.triggers - BottleTrigger.GeofenceExit)) },
            secondary = stringResource(R.string.cues_bottle_geofence_c),
        )

        Text(stringResource(R.string.cues_bottle_scheduled), style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 16.dp))
        h.scheduledDepartures.forEachIndexed { i, d ->
            val place = cfg.place(d.placeId)?.let { placeName(it) } ?: d.placeId
            DayCueRow(
                primary = "${timeText(d.time)} · ${daysSummary(d.days)}",
                secondary = stringResource(R.string.cues_bottle_leaving_place, place),
                trailing = { DayCueTextButton(stringResource(R.string.cues_remove), { save(h.copy(scheduledDepartures = h.scheduledDepartures.filterIndexed { j, _ -> j != i })) }) },
            )
        }
        FieldErrors(errors, "$path.scheduledDepartures")
        DayCueTextButton(stringResource(R.string.cues_bottle_add_departure), { sheet = "departure" })

        Spacer(Modifier.height(8.dp))
        PhraseRow(h.phrase, Defaults.waterBottle().phrase?.get(lang).orEmpty(), lang, { sheet = "phrase" })
        SettingRow(stringResource(R.string.cues_sound_voice), stringResource(R.string.cues_sound_voice_value), { onOpenCueProfile(vm.profileId(domainCueType(h), h.cueProfileId)) })
        Spacer(Modifier.height(16.dp))
        CuePreviewButton(CueType.Bottle, stringResource(R.string.cues_test_cue), { vm.testReminder(domainCueType(h)) })
        DayCueTextButton(stringResource(R.string.cues_pause), { sheet = "pause" })

        Spacer(Modifier.height(8.dp))
        AdvancedSection(changedCount = changed.count { it }, expanded = advanced, onToggle = { advanced = !advanced },
            onReset = { save(h.copy(cooldownPerPlaceMin = def.cooldownPerPlaceMin, dedupWindowMin = def.dedupWindowMin, repeat = def.repeat)) }) {
            SettingRow(stringResource(R.string.cues_bottle_cooldown), durationText(h.cooldownPerPlaceMin), { sheet = "cooldown" }, changed = changed[0])
            FieldErrors(errors, "$path.cooldownPerPlaceMin")
            SettingRow(stringResource(R.string.cues_bottle_dedup), durationText(h.dedupWindowMin), { sheet = "dedup" }, changed = changed[1])
            FieldErrors(errors, "$path.dedupWindowMin")
            SettingRow(
                stringResource(R.string.cues_repeat),
                if (h.repeat.maxRepeats == 0) stringResource(R.string.cues_repeat_none) else stringResource(R.string.cues_repeat_summary, h.repeat.maxRepeats, durationText(h.repeat.everyMin)),
                { sheet = "repeat" }, changed = changed[2],
            )
        }

        RecentActivity(vm, "habit", h.id)
        Spacer(Modifier.height(16.dp))
        DestructiveButton(stringResource(R.string.cues_delete_reminder), { confirmDelete = true }, Modifier.fillMaxWidth())
    }

    when (sheet) {
        "places" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_bottle_places), CueType.Bottle) {
            Text(stringResource(R.string.cues_bottle_places_help), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            if (cfg.places.isEmpty()) Text(stringResource(R.string.cues_when_no_places), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
            cfg.places.forEach { p ->
                val on = p.id in h.placeIds
                DayCueRow(
                    primary = placeName(p),
                    onClick = { save(h.copy(placeIds = if (on) h.placeIds - p.id else h.placeIds + p.id)) },
                    role = Role.Checkbox,
                    trailing = { if (on) GlyphIcon(Glyph.Check, DayCueTheme.colors.ink) },
                    semanticsExtra = { selected = on },
                )
            }
        }
        "departure" -> DepartureSheet(cfg, onAdd = { save(h.copy(scheduledDepartures = h.scheduledDepartures + it)) }, onDismiss = { sheet = null })
        "phrase" -> PhraseSheet(h.phrase, Defaults.waterBottle().phrase?.get(lang).orEmpty(), lang, { save(h.copy(phrase = it)) }, { sheet = null })
        "pause" -> PauseSheet(vm, PauseTarget.Habit(h.id), canUntilLeave = false, onDismiss = { sheet = null })
        "cooldown" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_bottle_cooldown), CueType.Bottle) {
            Text(stringResource(R.string.cues_bottle_cooldown_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(h.cooldownPerPlaceMin, { save(h.copy(cooldownPerPlaceMin = it)) }, min = 0, max = 480, presets = listOf(0, 30, 60, 120))
            FieldErrors(errors, "$path.cooldownPerPlaceMin")
        }
        "dedup" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_bottle_dedup), CueType.Bottle) {
            Text(stringResource(R.string.cues_bottle_dedup_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(h.dedupWindowMin, { save(h.copy(dedupWindowMin = it)) }, min = 5, max = 120, presets = listOf(10, 30, 60))
            FieldErrors(errors, "$path.dedupWindowMin")
        }
        "repeat" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_repeat), CueType.Bottle) {
            RepeatEditor(h.repeat, maxRepeats = 1, onChange = { save(h.copy(repeat = it)) })
        }
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

@Composable
private fun DepartureSheet(cfg: DayCueConfig, onAdd: (ScheduledDeparture) -> Unit, onDismiss: () -> Unit) {
    var place by remember { mutableStateOf(cfg.places.firstOrNull()?.id) }
    var minutes by remember { mutableIntStateOf(8 * 60) }
    var days by remember { mutableStateOf(ALL_DAYS.toSet()) }
    DayCueBottomSheet(
        onDismiss, stringResource(R.string.cues_bottle_add_departure), CueType.Bottle,
        primaryLabel = if (place != null && days.isNotEmpty()) stringResource(R.string.cues_add) else null,
        onPrimary = { place?.let { onAdd(ScheduledDeparture(it, LocalTime.of(minutes / 60, minutes % 60), days)); onDismiss() } },
    ) {
        if (cfg.places.isEmpty()) {
            Text(stringResource(R.string.cues_when_no_places), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2)
            return@DayCueBottomSheet
        }
        Text(stringResource(R.string.cues_bottle_leaving_from), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
        cfg.places.forEach { p ->
            DayCueRow(
                primary = placeName(p), onClick = { place = p.id }, role = Role.RadioButton,
                trailing = { if (place == p.id) GlyphIcon(Glyph.Check, DayCueTheme.colors.ink) },
                semanticsExtra = { selected = place == p.id },
            )
        }
        Spacer(Modifier.height(8.dp))
        app.daycue.ui.components.TimeStepperPicker(minutes, { minutes = it })
        Spacer(Modifier.height(8.dp))
        DayChips(days, { d -> days = if (d in days) days - d else days + d })
    }
}
