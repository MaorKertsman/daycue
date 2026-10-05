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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.daycue.BuildConfig
import app.daycue.R
import app.daycue.domain.config.AlarmSource
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.MorningAlarm
import app.daycue.domain.edit.ConfigOp
import app.daycue.integrations.spotify.AlarmMusicState
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.DurationUnit
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.PolicyChoiceList
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.durationSecondsText
import app.daycue.ui.util.durationText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle

/** The Spotify item field is only offered when the integration is available in this build (BuildConfig.SPOTIFY_SDK). */
private val spotifyAvailable: Boolean get() = BuildConfig.SPOTIFY_SDK

@Composable
private fun skipText(a: MorningAlarm): String? {
    val d = a.skipDate ?: return null
    if (d.isBefore(LocalDate.now())) return null
    return stringResource(R.string.cues_alarm_skipping, d.dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale()))
}

@Composable
internal fun AlarmListScreen(vm: CuesViewModel, onBack: () -> Unit, push: (String) -> Unit) {
    val config by vm.config.collectAsState()
    val today by vm.today.collectAsState()
    val cfg = config
    val newName = stringResource(R.string.cues_new_alarm_name)
    fun create() {
        val id = "alarm-" + System.currentTimeMillis().toString(36)
        vm.edit(ConfigOp.UpsertAlarm(MorningAlarm(id = id, name = newName, enabled = true, time = LocalTime.of(7, 0), days = null)), CuesMessage(R.string.cues_added))
        push("alarm/$id")
    }
    CuesScreen(stringResource(R.string.cues_alarms_title), onBack) {
        if (cfg == null) return@CuesScreen
        if (cfg.alarms.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_alarms_empty), body = stringResource(R.string.cues_alarms_empty_body), actionLabel = stringResource(R.string.cues_alarm_add), onAction = ::create)
            return@CuesScreen
        }
        cfg.alarms.forEach { a ->
            val on = stringResource(R.string.cues_state_on)
            val off = stringResource(R.string.cues_state_off)
            val toggle = stringResource(if (a.enabled) R.string.cues_turn_off else R.string.cues_turn_on)
            val nextAt = today?.upcoming?.firstOrNull { it.itemKey == "alarm:${a.id}" }?.at
            Column(Modifier.fillMaxWidth()) {
                // The time leads (sans, tabular); "Name · Sun–Thu" is the second line. One serif per screen: the title.
                DayCueRow(
                    primary = timeText(a.time),
                    secondary = "${alarmName(a.id, a.name)} · ${daysSummary(a.days ?: cfg.settings.workDays)}",
                    leading = { CueMark(CueType.Alarm, state = if (a.enabled) CueState.Scheduled else null) },
                    extra = {
                        val skip = skipText(a)
                        val status = skip ?: if (!a.enabled) null else nextAt?.let { stringResource(R.string.cues_next_at, instantTime(it)) }
                        if (status != null) Text(status, style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
                    },
                    trailing = { DayCueSwitch(a.enabled, { vm.edit(ConfigOp.SetAlarmEnabled(a.id, it)) }, Modifier.semantics { contentDescription = a.name }) },
                    onClick = { push("alarm/${a.id}") },
                    divider = true,
                    semanticsExtra = {
                        stateDescription = if (a.enabled) on else off
                        customActions = listOf(CustomAccessibilityAction(toggle) { vm.edit(ConfigOp.SetAlarmEnabled(a.id, !a.enabled)); true })
                    },
                )
                Row {
                    if (a.enabled) {
                        if (a.skipDate != null && !a.skipDate!!.isBefore(LocalDate.now())) {
                            DayCueTextButton(stringResource(R.string.cues_alarm_unskip), { vm.edit(ConfigOp.SkipNextAlarm(a.id, null)) })
                        } else if (nextAt != null) {
                            DayCueTextButton(stringResource(R.string.cues_alarm_skip_next), {
                                vm.edit(ConfigOp.SkipNextAlarm(a.id, nextAt.atZone(zoneNow).toLocalDate()))
                            })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DayCueTextButton(stringResource(R.string.cues_alarm_add), ::create)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlarmEditorScreen(vm: CuesViewModel, id: String, onBack: () -> Unit, push: (String) -> Unit) {
    val config by vm.config.collectAsState()
    val errors by vm.errors.collectAsState()
    val music by vm.facade.alarmMusic.collectAsState()
    val cfg = config
    val a = cfg?.alarm(id)
    if (cfg == null) { CuesScreen("", onBack) {}; return }
    if (a == null) { CuesScreen("", onBack) { StateBlock(StateBlockKind.Empty, stringResource(R.string.cues_item_gone)) }; return }
    val path = "alarms[${a.id}]"
    fun save(n: MorningAlarm) = vm.edit(ConfigOp.UpsertAlarm(n))
    var sheet by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val openSounds: () -> Unit = { context.startActivity(app.daycue.delivery.DeepLinks.intent(context, "setup", "sounds")) }
    val def = remember { MorningAlarm(id = "x") }
    val spotify = a.source as? AlarmSource.SpotifyItem
    val showSpotify = spotifyAvailable || spotify != null
    val selectedDays = a.days ?: cfg.settings.workDays
    val changed = listOf(a.volumeRampSec != def.volumeRampSec, a.snoozeMin != def.snoozeMin || a.maxSnoozes != def.maxSnoozes, a.ringTimeoutMin != def.ringTimeoutMin, a.vibrate != def.vibrate)

    CuesScreen(
        alarmName(a.id, a.name), onBack, mark = CueType.Alarm,
        trailing = { DayCueSwitch(a.enabled, { vm.edit(ConfigOp.SetAlarmEnabled(a.id, it)) }, Modifier.semantics { contentDescription = a.name }) },
    ) {
        CommitTextField(alarmName(a.id, a.name), { save(a.copy(name = it.take(40))) }, stringResource(R.string.cues_name))
        SettingRow(stringResource(R.string.cues_time), timeText(a.time), { sheet = "time" })
        Text(stringResource(R.string.cues_days), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        DayChips(selectedDays, { d -> val nd = if (d in selectedDays) selectedDays - d else selectedDays + d; save(a.copy(days = nd)) })
        FieldErrors(errors, "$path.days")
        if (a.days == null) Text(stringResource(R.string.cues_alarm_uses_workdays), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)

        if (showSpotify) {
            SettingRow(stringResource(R.string.cues_alarm_sound), if (spotify != null) stringResource(R.string.cues_alarm_src_spotify) else stringResource(R.string.cues_alarm_src_tone), { sheet = "source" })
            if (spotify != null) {
                CommitTextField(spotify.uri, { save(a.copy(source = spotify.copy(uri = it.trim()))) }, stringResource(R.string.cues_alarm_spotify_item), helper = stringResource(R.string.cues_alarm_spotify_help))
                FieldErrors(errors, "$path.source")
            }
        } else {
            DayCueRow(primary = stringResource(R.string.cues_alarm_sound), secondary = stringResource(R.string.cues_alarm_src_tone), trailing = { GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink2) }, onClick = openSounds)
        }
        DayCueRow(primary = stringResource(R.string.cues_alarm_backup), secondary = stringResource(R.string.cues_alarm_backup_value), trailing = { GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink2) }, onClick = openSounds)
        val routineLabel = a.followOnRoutineId?.let { rid -> cfg.routine(rid)?.let { routineName(it.id, it.name) } } ?: stringResource(R.string.cues_alarm_then_none)
        SettingRow(stringResource(R.string.cues_alarm_then), routineLabel, { sheet = "routine" })
        FieldErrors(errors, "$path.followOnRoutineId")

        Spacer(Modifier.height(16.dp))
        DayCueTextButton(stringResource(R.string.cues_alarm_test), { tested = true; vm.testAlarm(a.id) })
        Text(stringResource(R.string.cues_alarm_test_note), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 4.dp))
        if (tested && spotify != null) {
            val fell = music as? AlarmMusicState.FellBack
            Text(
                if (fell != null) stringResource(R.string.cues_alarm_test_backup) else stringResource(R.string.cues_alarm_test_started),
                style = DayCueTheme.type.body, color = DayCueTheme.colors.ink, modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        AdvancedSection(changed.count { it }, advanced, { advanced = !advanced }, onReset = {
            save(a.copy(volumeRampSec = def.volumeRampSec, snoozeMin = def.snoozeMin, maxSnoozes = def.maxSnoozes, ringTimeoutMin = def.ringTimeoutMin, vibrate = def.vibrate, spotifyStartTimeoutSec = def.spotifyStartTimeoutSec))
        }) {
            SettingRow(stringResource(R.string.cues_alarm_ramp), if (a.volumeRampSec == 0) stringResource(R.string.cues_alarm_ramp_off) else durationSecondsText(a.volumeRampSec), { sheet = "ramp" }, changed = changed[0])
            FieldErrors(errors, "$path.volumeRampSec")
            SettingRow(stringResource(R.string.cues_alarm_snooze), pluralStringResource(R.plurals.cues_alarm_snooze_value, a.maxSnoozes, durationText(a.snoozeMin), a.maxSnoozes), { sheet = "snooze" }, changed = changed[1])
            FieldErrors(errors, "$path.snoozeMin")
            FieldErrors(errors, "$path.maxSnoozes")
            SettingRow(stringResource(R.string.cues_alarm_timeout), durationText(a.ringTimeoutMin), { sheet = "timeout" }, changed = changed[2])
            FieldErrors(errors, "$path.ringTimeoutMin")
            SwitchRow(stringResource(R.string.cues_alarm_vibrate), a.vibrate, { save(a.copy(vibrate = it)) })
            if (spotify != null) {
                SettingRow(stringResource(R.string.cues_alarm_spotify_timeout), durationSecondsText(a.spotifyStartTimeoutSec), { sheet = "spotifyTimeout" })
                FieldErrors(errors, "$path.spotifyStartTimeoutSec")
            }
        }
        RecentActivity(vm, "alarm", a.id)
        Spacer(Modifier.height(16.dp))
        DestructiveButton(stringResource(R.string.cues_alarm_delete), { vm.edit(ConfigOp.DeleteAlarm(a.id), CuesMessage(R.string.cues_deleted_named, listOf(a.name))); onBack() }, Modifier.fillMaxWidth())
    }

    when (sheet) {
        "time" -> TimeSheet(stringResource(R.string.cues_time), a.time.hour * 60 + a.time.minute, { m -> save(a.copy(time = LocalTime.of(m / 60, m % 60))) }, { sheet = null }, CueType.Alarm)
        "source" -> PolicySheet(
            stringResource(R.string.cues_alarm_sound), listOf(
                PolicyOption(stringResource(R.string.cues_alarm_src_tone), stringResource(R.string.cues_alarm_src_tone_c)),
                PolicyOption(stringResource(R.string.cues_alarm_src_spotify), stringResource(R.string.cues_alarm_src_spotify_c)),
            ), if (spotify != null) 1 else 0,
            { i -> save(a.copy(source = if (i == 0) AlarmSource.LocalTone(((a.source as? AlarmSource.SpotifyItem)?.fallbackToneId) ?: "morning") else AlarmSource.SpotifyItem(uri = "", fallbackToneId = (a.source as? AlarmSource.LocalTone)?.toneId ?: "morning"))) },
            { sheet = null }, CueType.Alarm,
        )
        "routine" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_alarm_then), CueType.Alarm) {
            Text(stringResource(R.string.cues_alarm_then_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            val none = a.followOnRoutineId == null
            DayCueRow(stringResource(R.string.cues_alarm_then_none), onClick = { save(a.copy(followOnRoutineId = null)); sheet = null }, role = Role.RadioButton,
                trailing = { if (none) GlyphIcon(Glyph.Check, DayCueTheme.colors.ink) }, semanticsExtra = { selected = none })
            cfg.routines.forEach { r ->
                val on = a.followOnRoutineId == r.id
                DayCueRow(routineName(r.id, r.name), onClick = { save(a.copy(followOnRoutineId = r.id)); sheet = null }, role = Role.RadioButton,
                    trailing = { if (on) GlyphIcon(Glyph.Check, DayCueTheme.colors.ink) }, semanticsExtra = { selected = on })
            }
        }
        "ramp" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_alarm_ramp), CueType.Alarm) {
            Text(stringResource(R.string.cues_alarm_ramp_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(a.volumeRampSec, { save(a.copy(volumeRampSec = it)) }, unit = DurationUnit.Seconds, min = 0, max = 120, presets = listOf(0, 15, 30, 60))
            FieldErrors(errors, "$path.volumeRampSec")
        }
        "snooze" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_alarm_snooze), CueType.Alarm) {
            DurationField(a.snoozeMin, { save(a.copy(snoozeMin = it)) }, min = 1, max = 30, presets = listOf(5, 9, 10, 15))
            NumberRow(stringResource(R.string.cues_alarm_max_snoozes), a.maxSnoozes, 0, 10, { save(a.copy(maxSnoozes = it)) }, valueText = a.maxSnoozes.toString())
            FieldErrors(errors, "$path.snoozeMin")
        }
        "timeout" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_alarm_timeout), CueType.Alarm) {
            Text(stringResource(R.string.cues_alarm_timeout_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(a.ringTimeoutMin, { save(a.copy(ringTimeoutMin = it)) }, min = 1, max = 30, presets = listOf(5, 10, 15, 30))
        }
        "spotifyTimeout" -> DayCueBottomSheet({ sheet = null }, stringResource(R.string.cues_alarm_spotify_timeout), CueType.Alarm) {
            Text(stringResource(R.string.cues_alarm_spotify_timeout_c), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            DurationField(a.spotifyStartTimeoutSec, { save(a.copy(spotifyStartTimeoutSec = it)) }, unit = DurationUnit.Seconds, min = 5, max = 30, presets = listOf(5, 10, 20, 30))
        }
    }
}
