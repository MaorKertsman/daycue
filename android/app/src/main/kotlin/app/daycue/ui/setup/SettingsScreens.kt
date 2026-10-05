package app.daycue.ui.setup

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DiffReview
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.components.TimeField
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.friendlyDiffLines
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// ---- Setup home --------------------------------------------------------------------------------------------------

class SetupHomeViewModel(app: Application) : SetupViewModel(app) {
    val config: StateFlow<DayCueConfig?> = facade.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), facade.snapshot.value?.config)
    val paired: StateFlow<Boolean> = facade.remote.paired
}

@Composable
fun SetupHomeScreen(push: (String) -> Unit) {
    val vm: SetupHomeViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    SetupFrame(stringResource(R.string.su_home_title), onBack = null) {
        val config = cfg
        val placeCount = config?.places?.count { it.active } ?: 0
        val calendars = config?.calendarRules?.calendars?.size ?: 0

        SectionHeader(stringResource(R.string.su_home_sense_header), topPadding = 8.dp)
        SettingRow(
            stringResource(R.string.su_places_title),
            if (config == null) stringResource(R.string.su_places_home_hint) else pluralStringResource(R.plurals.su_places_active, placeCount, placeCount),
            { push(SetupRoutes.PLACES) },
        )
        SettingRow(
            stringResource(R.string.su_cal_title),
            if (calendars == 0) stringResource(R.string.su_cal_home_none) else pluralStringResource(R.plurals.su_cal_selected, calendars, calendars),
            { push(SetupRoutes.CALENDAR) },
        )

        SectionHeader(stringResource(R.string.su_home_deliver_header))
        SettingRow(stringResource(R.string.su_sounds_title), stringResource(R.string.su_sounds_home_hint), { push(SetupRoutes.SOUNDS) })
        SettingRow(stringResource(R.string.su_quiet_title), stringResource(R.string.su_quiet_home_hint), { push(SetupRoutes.QUIET) })

        SectionHeader(stringResource(R.string.su_home_app_header))
        SettingRow(
            stringResource(R.string.su_integrations_title),
            stringResource(if (paired) R.string.su_integrations_home_paired else R.string.su_integrations_home_hint),
            { push(SetupRoutes.INTEGRATIONS) },
        )
        SettingRow(stringResource(R.string.su_readiness_title), stringResource(R.string.su_readiness_home_hint), { push(SetupRoutes.READINESS) })
        SettingRow(stringResource(R.string.su_activity_title), stringResource(R.string.su_activity_home_hint), { push(SetupRoutes.ACTIVITY) })
        SettingRow(stringResource(R.string.su_settings_title), stringResource(R.string.su_settings_home_hint), { push(SetupRoutes.SETTINGS) })
    }
}

/** Stand-in for the shell's Reminder readiness screen until it is passed to [SetupRoot]. */
@Composable
fun ReadinessPlaceholder(onBack: () -> Unit) {
    SetupFrame(stringResource(R.string.su_readiness_title), onBack) {
        StateBlock(StateBlockKind.Empty, stringResource(R.string.su_readiness_placeholder), body = stringResource(R.string.su_readiness_placeholder_body))
    }
}

// ---- Settings ----------------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(onBack: () -> Unit, push: (String) -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val reduce by vm.reduceMotion.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    var langSheet by remember { mutableStateOf(false) }
    var dayStartPicker by remember { mutableStateOf(false) }
    var includeHistory by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.writeExport(it) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.readImport(uri); push(SetupRoutes.IMPORT) }
    }

    SetupFrame(stringResource(R.string.su_settings_title), onBack) {
        val config = cfg
        if (config == null) { repeat(4) { SkeletonRow() }; return@SetupFrame }

        val tag = vm.currentLanguageTag()
        SettingRow(stringResource(R.string.su_set_language), languageName(tag), { langSheet = true })
        TimeField(stringResource(R.string.su_set_day_starts), config.settings.dayStartsAt.hour * 60 + config.settings.dayStartsAt.minute, { dayStartPicker = true })
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.su_set_workdays), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
            Gap(4)
            DayChips(config.settings.workDays, { day ->
                val next = if (day in config.settings.workDays) config.settings.workDays - day else config.settings.workDays + day
                vm.setWorkDays(next)
            })
        }
        SwitchRow(stringResource(R.string.su_set_reduce_motion), reduce, { vm.setReduceMotion(it) }, secondary = stringResource(R.string.su_set_reduce_motion_hint))

        SectionHeader(stringResource(R.string.su_set_backup_header))
        Hint(stringResource(R.string.su_set_backup_status))
        SwitchRow(stringResource(R.string.su_set_include_history), includeHistory, { includeHistory = it }, secondary = stringResource(R.string.su_set_include_history_hint))
        if (config.medications.isNotEmpty()) FieldNote(stringResource(R.string.su_set_export_medication), error = false)
        if (includeHistory) FieldNote(stringResource(R.string.su_set_export_history_note), error = false)
        Gap(8)
        PrimaryButton(
            stringResource(R.string.su_set_export),
            { vm.prepareExport(includeHistory) { exportLauncher.launch(vm.suggestedFileName()) } },
            Modifier.fillMaxWidth(), enabled = !exporting,
        )
        DayCueTextButton(stringResource(R.string.su_set_import), { importLauncher.launch(arrayOf("*/*")) })

        SectionHeader(stringResource(R.string.su_set_about_header))
        SettingRow(stringResource(R.string.su_about_title), stringResource(R.string.su_about_hint), { push(SetupRoutes.ABOUT) })
        SettingRow(stringResource(R.string.su_privacy_title), stringResource(R.string.su_privacy_hint), { push(SetupRoutes.PRIVACY) })
    }

    if (langSheet) {
        val tags = listOf<String?>(null, "en", "he")
        ChoiceSheet(
            stringResource(R.string.su_set_language),
            tags.map { PolicyOption(languageName(it), stringResource(if (it == null) R.string.su_lang_phone_hint else R.string.su_lang_applies_hint)) },
            tags.indexOf(vm.currentLanguageTag()).coerceAtLeast(0),
            { vm.setLanguage(tags[it]) },
            { langSheet = false },
        )
    }
    if (dayStartPicker && cfg != null) {
        val s = cfg!!.settings.dayStartsAt
        TimePickerSheet(R.string.su_set_day_starts, s.hour * 60 + s.minute, { vm.setDayStart(LocalTime.of(it / 60, it % 60)) }, { dayStartPicker = false })
    }
}

/** Language names are shown in their own language, never translated. */
@Composable
private fun languageName(tag: String?): String = when (tag) {
    "en" -> "English"
    "he" -> "עברית"
    else -> stringResource(R.string.su_lang_phone)
}

// ---- Import review -----------------------------------------------------------------------------------------------------

@Composable
fun ImportScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val state by vm.importState.collectAsStateWithLifecycle()
    val back = { vm.resetImport(); onBack() }

    SetupFrame(stringResource(R.string.su_import_title), back) {
        when (val s = state) {
            ImportUi.None -> StateBlock(StateBlockKind.Empty, stringResource(R.string.su_import_none), body = stringResource(R.string.su_import_none_body), actionLabel = stringResource(R.string.su_back), onAction = back)
            ImportUi.Reading -> { repeat(3) { SkeletonRow() }; Hint(stringResource(R.string.su_import_reading)) }
            is ImportUi.NotDayCue -> StateBlock(StateBlockKind.Error, stringResource(R.string.su_import_not_daycue), body = stringResource(R.string.su_import_not_daycue_body), actionLabel = stringResource(R.string.su_back), onAction = back)
            is ImportUi.TooNew -> StateBlock(StateBlockKind.Error, stringResource(R.string.su_import_too_new), body = stringResource(R.string.su_import_too_new_body), actionLabel = stringResource(R.string.su_back), onAction = back)
            ImportUi.Unreadable -> StateBlock(StateBlockKind.Error, stringResource(R.string.su_import_unreadable), body = stringResource(R.string.su_import_unreadable_body), actionLabel = stringResource(R.string.su_back), onAction = back)
            ImportUi.Applied -> {
                StateBlock(StateBlockKind.Empty, stringResource(R.string.su_import_done), body = stringResource(R.string.su_import_done_body))
                Gap(16)
                PrimaryButton(stringResource(R.string.su_done), back, Modifier.fillMaxWidth())
            }
            is ImportUi.Failed -> StateBlock(
                StateBlockKind.Error,
                stringResource(if (s.conflict) R.string.su_import_conflict else R.string.su_import_failed),
                body = stringResource(if (s.conflict) R.string.su_import_conflict_body else R.string.su_import_failed_body),
                actionLabel = stringResource(R.string.su_back), onAction = back,
            )
            is ImportUi.Review -> {
                val plan = s.plan
                Hint(stringResource(R.string.su_import_intro))
                if (plan.containsMedication) FieldNote(stringResource(R.string.su_import_medication), error = false)
                if (s.hadHistory) FieldNote(stringResource(R.string.su_import_history_ignored), error = false)
                when {
                    !plan.valid -> {
                        FieldNote(stringResource(R.string.su_import_invalid))
                        plan.preview.errors.take(20).forEach { e ->
                            DayCueRow(primary = importErrorSubject(e.path), secondary = stringResource(friendlyError(e)))
                        }
                        SecondaryButton(stringResource(R.string.su_back), back, Modifier.fillMaxWidth())
                    }
                    plan.noChanges -> {
                        StateBlock(StateBlockKind.Empty, stringResource(R.string.su_import_no_changes), body = stringResource(R.string.su_import_no_changes_body), actionLabel = stringResource(R.string.su_back), onAction = back)
                    }
                    else -> {
                        DiffReview(
                            title = pluralStringResource(R.plurals.su_import_changes, plan.preview.lines.size, plan.preview.lines.size),
                            lines = friendlyDiffLines(plan.preview.lines.map { it.text }),
                            confirmLabel = stringResource(R.string.su_import_apply),
                            backLabel = stringResource(R.string.su_cancel),
                            onConfirm = { vm.applyImport(plan) },
                            onBack = back,
                        )
                    }
                }
            }
        }
    }
}

// ---- About, licenses, privacy -----------------------------------------------------------------------------------------

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val c = DayCueTheme.colors
    SetupFrame(stringResource(R.string.su_about_title), onBack) {
        DayCueRow(primary = stringResource(R.string.su_about_app), secondary = stringResource(R.string.su_about_version, version))
        Para(stringResource(R.string.su_about_body))
        Hint(stringResource(R.string.su_about_no_advice))

        SectionHeader(stringResource(R.string.su_licenses_header))
        for ((id, file) in listOf("rubik" to "OFL-Rubik.txt", "frank" to "OFL-FrankRuhlLibre.txt")) {
            val title = stringResource(if (id == "rubik") R.string.su_license_rubik else R.string.su_license_frank)
            DayCueRow(
                primary = title,
                secondary = stringResource(R.string.su_license_ofl),
                onClick = { open = if (open == id) null else id },
                trailing = { GlyphIcon(if (open == id) Glyph.ChevronDown else Glyph.Chevron, c.ink2) },
            )
            if (open == id) {
                val text = remember(file) { runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("") }
                Text(text, style = DayCueTheme.type.bodySmall, color = c.ink2, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        DayCueRow(primary = stringResource(R.string.su_license_libs), secondary = stringResource(R.string.su_license_libs_body))
        DayCueRow(primary = stringResource(R.string.su_license_sounds), secondary = stringResource(R.string.su_license_sounds_body))
        DayCueRow(primary = stringResource(R.string.su_license_play), secondary = stringResource(R.string.su_license_play_body))
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    SetupFrame(stringResource(R.string.su_privacy_title), onBack) {
        Para(stringResource(R.string.su_privacy_lead))
        SectionHeader(stringResource(R.string.su_privacy_stays_header))
        Para(stringResource(R.string.su_privacy_stays))
        SectionHeader(stringResource(R.string.su_privacy_leaves_header))
        Para(stringResource(R.string.su_privacy_leaves))
        Hint(stringResource(R.string.su_privacy_remote))
        SectionHeader(stringResource(R.string.su_privacy_perms_header))
        Para(stringResource(R.string.su_privacy_perms))
        SectionHeader(stringResource(R.string.su_privacy_control_header))
        Para(stringResource(R.string.su_privacy_control))
    }
}

/** The validation path of an import problem as a short noun ("places[home].radiusM" -> "Places"). Never the raw path. */
@Composable
private fun importErrorSubject(path: String): String = stringResource(
    when (path.substringBefore('[').substringBefore('.')) {
        "habits" -> R.string.diff_sec_habits
        "places" -> R.string.diff_sec_places
        "routines" -> R.string.diff_sec_routines
        "alarms" -> R.string.diff_sec_alarms
        "medications" -> R.string.diff_sec_medications
        "cueProfiles" -> R.string.diff_sec_profiles
        "calendarRules" -> R.string.diff_sec_calendar
        "contextRules" -> R.string.diff_sec_context
        "postureCycle" -> R.string.diff_sec_posture
        else -> R.string.diff_sec_settings
    },
)
