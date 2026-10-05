package app.daycue.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.config.CalendarCondition
import app.daycue.domain.config.CalendarDefaultPolicy
import app.daycue.domain.config.CalendarPreferenceMode
import app.daycue.domain.config.CalendarReminderSupplementPolicy
import app.daycue.domain.config.CalendarRule
import app.daycue.domain.config.EventAvailability
import app.daycue.domain.config.EventDecisionOverride
import app.daycue.domain.config.Language
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.OverrideScope
import app.daycue.domain.config.TentativeHandling
import app.daycue.facade.CalendarChoice
import app.daycue.facade.CalendarPreviewRow
import app.daycue.integrations.calendar.CalendarSyncStatus
import app.daycue.ui.components.ChoiceRow
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.PermissionCard
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.ReorderableList
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.clockText
import app.daycue.ui.util.clockIsolate
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.is24Hour
import app.daycue.ui.util.ltr
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- Calendar home ----------------------------------------------------------------------------------------

@Composable
fun CalendarScreen(onBack: () -> Unit, push: (String) -> Unit) {
    val vm: CalendarViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val syncedAt by vm.syncedAt.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    var modeFor by remember { mutableStateOf<CalendarChoice?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = DayCueTheme.colors
    val ctx = LocalContext.current

    SetupFrame(stringResource(R.string.su_cal_title), onBack) {
        Hint(stringResource(R.string.su_cal_intro))
        val config = cfg
        if (config == null) { repeat(3) { SkeletonRow() }; return@SetupFrame }

        if (!permission) {
            PermissionCard(
                cue = CueType.Calendar,
                title = stringResource(R.string.su_cal_perm_title),
                why = stringResource(R.string.su_cal_perm_why),
                without = stringResource(R.string.su_cal_perm_without),
                allowLabel = stringResource(R.string.su_perm_allow),
                notNowLabel = stringResource(R.string.su_not_now),
                onAllow = { launcher.launch(vm.permissionName) },
                onNotNow = onBack,
            )
            Hint(stringResource(R.string.su_cal_perm_readonly))
            DayCueTextButton(stringResource(R.string.su_open_app_settings), {
                ctx.startActivitySafely(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)))
            })
            return@SetupFrame
        }

        // Sync state with the honest latency note.
        val maxAge = config.calendarRules.maxCacheAgeHours
        val stale = syncedAt?.let { Duration.between(it, Instant.now()).toHours() >= maxAge } == true
        val failed = lastSync?.status == CalendarSyncStatus.Failed
        if (failed) {
            StateBlock(StateBlockKind.Error, stringResource(R.string.su_cal_read_failed), body = stringResource(R.string.su_cal_read_failed_body), actionLabel = stringResource(R.string.su_try_again), onAction = { vm.refresh() })
        } else if (stale) {
            StateBlock(StateBlockKind.Uncertain, pluralStringResource(R.plurals.su_cal_stale, ageHours(syncedAt), ageHours(syncedAt)), body = stringResource(R.string.su_cal_stale_body), actionLabel = stringResource(R.string.su_sync_now), onAction = { vm.refresh() })
        }
        DayCueRow(
            primary = stringResource(R.string.su_cal_sync_status),
            secondary = syncedAt?.let { stringResource(R.string.su_cal_synced_at, agoText(it)) } ?: stringResource(R.string.su_cal_never_synced),
            trailing = {
                DayCueTextButton(stringResource(if (syncing) R.string.su_syncing else R.string.su_sync_now), { vm.refresh() }, enabled = !syncing)
            },
        )
        LearnMore(stringResource(R.string.su_cal_latency_note))

        SectionHeader(stringResource(R.string.su_cal_calendars_header))
        val list = calendars
        when {
            list == null -> { repeat(2) { SkeletonRow() } }
            list.isEmpty() -> StateBlock(StateBlockKind.Empty, stringResource(R.string.su_cal_none), body = stringResource(R.string.su_cal_none_body))
            else -> list.forEach { choice ->
                DayCueRow(
                    primary = choice.calendar.displayName,
                    secondary = listOfNotNull(choice.calendar.accountName, modeWord(choice)).joinToString(" · "),
                    leading = { CueMark(CueType.Calendar, state = if (choice.selected) null else app.daycue.ui.marks.CueState.Paused) },
                    trailing = { GlyphIcon(Glyph.Chevron, c.ink2) },
                    onClick = { modeFor = choice },
                )
            }
        }

        SectionHeader(stringResource(R.string.su_cal_rules_header))
        val rules = config.calendarRules.rules
        if (rules.isEmpty()) {
            StateBlock(StateBlockKind.Empty, stringResource(R.string.su_cal_rules_empty), body = stringResource(R.string.su_cal_rules_empty_body))
        } else {
            ReorderableList(
                items = rules,
                keyOf = { it.id },
                onMove = { from, to ->
                    val ids = rules.map { it.id }.toMutableList()
                    ids.add(to, ids.removeAt(from))
                    vm.reorder(ids)
                },
            ) { rule, _, handle ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    handle()
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        DayCueRow(
                            primary = rule.name,
                            secondary = ruleSummary(rule),
                            onClick = { push(SetupRoutes.withName(SetupRoutes.CALENDAR_RULE, rule.id, rule.name)) },
                            divider = false,
                        )
                    }
                    DayCueSwitch(rule.enabled, { vm.setRuleEnabled(rule, it) })
                }
            }
        }
        DayCueTextButton(stringResource(R.string.su_rule_add), { push(SetupRoutes.CALENDAR_RULE + SetupRoutes.NEW) })

        SettingRow(stringResource(R.string.su_cal_preview), stringResource(R.string.su_cal_preview_hint), { push(SetupRoutes.CALENDAR_PREVIEW) })

        // More options: policies with a consequence sentence each.
        SectionHeader(stringResource(R.string.su_cal_more_header))
        CalendarPolicies(vm, config.calendarRules)
    }

    modeFor?.let { choice ->
        CalendarModeSheet(choice, onDismiss = { modeFor = null }, onSave = { mode, leads -> vm.setCalendarMode(choice, mode, leads); modeFor = null })
    }
}

@Composable
private fun CalendarPolicies(vm: CalendarViewModel, cal: app.daycue.domain.config.CalendarConfig) {
    val scope = rememberCoroutineScope()
    var supplementSheet by remember { mutableStateOf(false) }
    var defaultSheet by remember { mutableStateOf(false) }
    var tentativeSheet by remember { mutableStateOf(false) }
    var leadsText by remember(cal.defaultPolicy) { mutableStateOf((cal.defaultPolicy as? CalendarDefaultPolicy.Cue)?.leadsMin?.joinToString(", ").orEmpty()) }
    var leadsError by remember { mutableStateOf(false) }
    fun save(change: (app.daycue.domain.config.CalendarConfig) -> app.daycue.domain.config.CalendarConfig) { scope.launch { vm.saveCalendarConfig(change(cal)) } }

    SettingRow(stringResource(R.string.su_cal_supplement), supplementWord(cal.supplement), { supplementSheet = true })
    SwitchRow(
        stringResource(R.string.su_cal_speak_titles), cal.speakTitles, { on -> save { it.copy(speakTitles = on) } },
        secondary = stringResource(if (cal.speakTitles) R.string.su_cal_speak_titles_on else R.string.su_cal_speak_titles_off),
    )
    SwitchRow(
        stringResource(R.string.su_cal_lock_titles), cal.showTitlesOnLockScreen, { on -> save { it.copy(showTitlesOnLockScreen = on) } },
        secondary = stringResource(R.string.su_cal_lock_titles_hint),
    )
    SettingRow(stringResource(R.string.su_cal_default), defaultWord(cal.defaultPolicy), { defaultSheet = true })
    if (cal.defaultPolicy is CalendarDefaultPolicy.Cue) {
        DayCueTextField(
            leadsText, { leadsText = it; leadsError = false }, stringResource(R.string.su_leads_label),
            helper = stringResource(R.string.su_leads_hint),
            error = if (leadsError) stringResource(R.string.su_leads_error) else null,
            modifier = Modifier.padding(top = 8.dp),
        )
        DayCueTextButton(stringResource(R.string.su_leads_apply), {
            val leads = parseLeads(leadsText)
            if (leads == null) leadsError = true else save { it.copy(defaultPolicy = CalendarDefaultPolicy.Cue(leads)) }
        })
    }
    SettingRow(stringResource(R.string.su_cal_tentative), stringResource(if (cal.tentative == TentativeHandling.Accepted) R.string.su_cal_tentative_accepted else R.string.su_cal_tentative_exclude), { tentativeSheet = true })
    MinutesStepper(R.string.su_cal_snooze, cal.snoozeMin, 1, 15, R.string.su_cal_snooze_hint) { v -> save { it.copy(snoozeMin = v) } }
    StepperRow(
        label = stringResource(R.string.su_cal_horizon),
        valueText = pluralStringResource(R.plurals.su_days, cal.syncHorizonDays, cal.syncHorizonDays),
        valueDescription = pluralStringResource(R.plurals.su_days, cal.syncHorizonDays, cal.syncHorizonDays),
        onDecrease = { save { it.copy(syncHorizonDays = (it.syncHorizonDays - 1).coerceAtLeast(1)) } },
        onIncrease = { save { it.copy(syncHorizonDays = (it.syncHorizonDays + 1).coerceAtMost(30)) } },
        canDecrease = cal.syncHorizonDays > 1, canIncrease = cal.syncHorizonDays < 30,
        hint = stringResource(R.string.su_cal_horizon_hint),
    )
    StepperRow(
        label = stringResource(R.string.su_cal_max_age),
        valueText = pluralStringResource(R.plurals.su_hours, cal.maxCacheAgeHours, cal.maxCacheAgeHours),
        valueDescription = pluralStringResource(R.plurals.su_hours, cal.maxCacheAgeHours, cal.maxCacheAgeHours),
        onDecrease = { save { it.copy(maxCacheAgeHours = (it.maxCacheAgeHours - 6).coerceAtLeast(6)) } },
        onIncrease = { save { it.copy(maxCacheAgeHours = (it.maxCacheAgeHours + 6).coerceAtMost(72)) } },
        canDecrease = cal.maxCacheAgeHours > 6, canIncrease = cal.maxCacheAgeHours < 72,
        hint = stringResource(R.string.su_cal_max_age_hint),
    )

    if (supplementSheet) {
        val opts = listOf(CalendarReminderSupplementPolicy.SupplementOnMatch, CalendarReminderSupplementPolicy.OnlyWhenNoReminder, CalendarReminderSupplementPolicy.Ignore)
        ChoiceSheet(
            stringResource(R.string.su_cal_supplement),
            opts.map { PolicyOption(supplementWord(it), stringResource(supplementHint(it))) },
            opts.indexOf(cal.supplement),
            { i -> save { it.copy(supplement = opts[i]) } },
            { supplementSheet = false },
        )
    }
    if (defaultSheet) {
        val opts = listOf<CalendarDefaultPolicy>(CalendarDefaultPolicy.NoCue, CalendarDefaultPolicy.Cue(listOf(10)))
        ChoiceSheet(
            stringResource(R.string.su_cal_default),
            listOf(
                PolicyOption(stringResource(R.string.su_cal_default_none), stringResource(R.string.su_cal_default_none_hint)),
                PolicyOption(stringResource(R.string.su_cal_default_cue), stringResource(R.string.su_cal_default_cue_hint)),
            ),
            if (cal.defaultPolicy is CalendarDefaultPolicy.Cue) 1 else 0,
            { i -> if ((i == 1) != (cal.defaultPolicy is CalendarDefaultPolicy.Cue)) save { it.copy(defaultPolicy = opts[i]) } },
            { defaultSheet = false },
        )
    }
    if (tentativeSheet) {
        val opts = listOf(TentativeHandling.Accepted, TentativeHandling.Exclude)
        ChoiceSheet(
            stringResource(R.string.su_cal_tentative),
            listOf(
                PolicyOption(stringResource(R.string.su_cal_tentative_accepted), stringResource(R.string.su_cal_tentative_accepted_hint)),
                PolicyOption(stringResource(R.string.su_cal_tentative_exclude), stringResource(R.string.su_cal_tentative_exclude_hint)),
            ),
            opts.indexOf(cal.tentative),
            { i -> save { it.copy(tentative = opts[i]) } },
            { tentativeSheet = false },
        )
    }
}

@Composable
private fun supplementWord(p: CalendarReminderSupplementPolicy) = stringResource(
    when (p) {
        CalendarReminderSupplementPolicy.SupplementOnMatch -> R.string.su_supp_match
        CalendarReminderSupplementPolicy.OnlyWhenNoReminder -> R.string.su_supp_only
        CalendarReminderSupplementPolicy.Ignore -> R.string.su_supp_ignore
    },
)

private fun supplementHint(p: CalendarReminderSupplementPolicy): Int = when (p) {
    CalendarReminderSupplementPolicy.SupplementOnMatch -> R.string.su_supp_match_hint
    CalendarReminderSupplementPolicy.OnlyWhenNoReminder -> R.string.su_supp_only_hint
    CalendarReminderSupplementPolicy.Ignore -> R.string.su_supp_ignore_hint
}

@Composable
private fun defaultWord(p: CalendarDefaultPolicy): String = when (p) {
    CalendarDefaultPolicy.NoCue -> stringResource(R.string.su_cal_default_none)
    is CalendarDefaultPolicy.Cue -> stringResource(R.string.su_cal_default_cue_with, leadsText(p.leadsMin))
}

@Composable
private fun modeWord(choice: CalendarChoice): String = when {
    !choice.selected -> stringResource(R.string.su_mode_off)
    choice.mode == CalendarPreferenceMode.Always -> stringResource(R.string.su_mode_always)
    choice.mode == CalendarPreferenceMode.Never -> stringResource(R.string.su_mode_never)
    else -> stringResource(R.string.su_mode_rules)
}

@Composable
private fun ruleSummary(rule: CalendarRule): String {
    val what = when {
        rule.noCue -> stringResource(R.string.su_rule_no_cue)
        else -> stringResource(R.string.su_rule_cue_before, leadsText(rule.leadsMin))
    }
    return if (rule.enabled) what else stringResource(R.string.su_rule_off_with, what)
}

/** "10, 60 min" with the digits kept left to right inside Hebrew text. */
@Composable
internal fun leadsText(leads: List<Int>): String {
    val parts = leads.map { it.toString().ltr() }
    val joined = if (parts.size <= 1) parts.joinToString("") else stringResource(R.string.su_leads_and, parts.dropLast(1).joinToString(", "), parts.last())
    return stringResource(R.string.su_minutes_list, joined)
}

@Composable
private fun ageHours(at: Instant?): Int = at?.let { Duration.between(it, Instant.now()).toHours().toInt().coerceAtLeast(1) } ?: 0

@Composable
internal fun agoText(at: Instant): String {
    val mins = Duration.between(at, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        mins < 1 -> stringResource(R.string.su_ago_now)
        mins < 60 -> pluralStringResource(R.plurals.su_ago_minutes, mins.toInt(), mins.toInt())
        mins < 48 * 60 -> pluralStringResource(R.plurals.su_ago_hours, (mins / 60).toInt(), (mins / 60).toInt())
        else -> pluralStringResource(R.plurals.su_ago_days, (mins / 1440).toInt(), (mins / 1440).toInt())
    }
}

@Composable
private fun CalendarModeSheet(choice: CalendarChoice, onDismiss: () -> Unit, onSave: (CalendarPreferenceMode?, List<Int>) -> Unit) {
    var mode by remember { mutableStateOf<CalendarPreferenceMode?>(if (choice.selected) choice.mode ?: CalendarPreferenceMode.Rules else null) }
    var leads by remember { mutableStateOf(choice.leadsMin.ifEmpty { listOf(10) }.joinToString(", ")) }
    var leadsError by remember { mutableStateOf(false) }
    DayCueBottomSheet(
        onDismiss = onDismiss, title = choice.calendar.displayName, mark = CueType.Calendar,
        primaryLabel = stringResource(R.string.su_done),
        onPrimary = {
            val parsed = parseLeads(leads)
            if (mode == CalendarPreferenceMode.Always && parsed == null) leadsError = true
            else onSave(mode, parsed ?: listOf(10))
        },
    ) {
        Hint(stringResource(R.string.su_cal_mode_intro))
        ChoiceRow(stringResource(R.string.su_mode_rules), stringResource(R.string.su_mode_rules_hint), mode == CalendarPreferenceMode.Rules, { mode = CalendarPreferenceMode.Rules })
        ChoiceRow(stringResource(R.string.su_mode_always), stringResource(R.string.su_mode_always_hint), mode == CalendarPreferenceMode.Always, { mode = CalendarPreferenceMode.Always })
        ChoiceRow(stringResource(R.string.su_mode_never), stringResource(R.string.su_mode_never_hint), mode == CalendarPreferenceMode.Never, { mode = CalendarPreferenceMode.Never })
        ChoiceRow(stringResource(R.string.su_mode_off), stringResource(R.string.su_mode_off_hint), mode == null, { mode = null })
        if (mode == CalendarPreferenceMode.Always) {
            DayCueTextField(
                leads, { leads = it; leadsError = false }, stringResource(R.string.su_leads_label),
                helper = stringResource(R.string.su_leads_hint),
                error = if (leadsError) stringResource(R.string.su_leads_error) else null,
            )
        }
    }
}

// ---- Rule editor --------------------------------------------------------------------------------------------

@Composable
fun CalendarRuleScreen(ruleId: String, onBack: () -> Unit, nameHint: String? = null) {
    val vm: CalendarViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val isNew = ruleId == SetupRoutes.NEW
    val rule = cfg?.calendarRules?.rules?.firstOrNull { it.id == ruleId }
    if (cfg == null) { SetupFrame(if (isNew) stringResource(R.string.su_rule_new) else nameHint ?: stringResource(R.string.su_rule_title), onBack) { repeat(3) { SkeletonRow() } }; return }
    if (!isNew && rule == null) {
        SetupFrame(stringResource(R.string.su_rule_title), onBack) { StateBlock(StateBlockKind.Error, stringResource(R.string.su_rule_missing), actionLabel = stringResource(R.string.su_back), onAction = onBack) }
        return
    }
    val base = rule ?: CalendarRule("", "", anyOf = listOf(CalendarCondition.Keywords(emptyList())), leadsMin = listOf(10))
    var name by rememberSaveable(ruleId) { mutableStateOf(base.name) }
    var keywords by rememberSaveable(ruleId) { mutableStateOf(base.anyOf.filterIsInstance<CalendarCondition.Keywords>().flatMap { it.words }.joinToString(", ")) }
    var attendees by rememberSaveable(ruleId) { mutableStateOf(base.anyOf.any { it is CalendarCondition.Attendees }) }
    var conferencing by rememberSaveable(ruleId) { mutableStateOf(base.anyOf.any { it is CalendarCondition.ConferencingLink }) }
    var freeOnly by rememberSaveable(ruleId) { mutableStateOf(base.anyOf.any { it is CalendarCondition.Availability }) }
    var leads by rememberSaveable(ruleId) { mutableStateOf(base.leadsMin.joinToString(", ")) }
    var noCue by rememberSaveable(ruleId) { mutableStateOf(base.noCue) }
    var isMeeting by rememberSaveable(ruleId) { mutableStateOf(base.isMeeting) }
    var kindEn by rememberSaveable(ruleId) { mutableStateOf(base.kind.en) }
    var kindHe by rememberSaveable(ruleId) { mutableStateOf(base.kind.he) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    SetupFrame(if (isNew) stringResource(R.string.su_rule_new) else rule?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.su_rule_title), onBack) {
        DayCueTextField(name, { name = it; error = null }, stringResource(R.string.su_rule_name))
        SectionHeader(stringResource(R.string.su_rule_when_header))
        Hint(stringResource(R.string.su_rule_when_hint))
        DayCueTextField(keywords, { keywords = it; error = null }, stringResource(R.string.su_rule_keywords), helper = stringResource(R.string.su_rule_keywords_hint))
        SwitchRow(stringResource(R.string.su_rule_attendees), attendees, { attendees = it }, secondary = stringResource(R.string.su_rule_attendees_hint))
        SwitchRow(stringResource(R.string.su_rule_conferencing), conferencing, { conferencing = it }, secondary = stringResource(R.string.su_rule_conferencing_hint))
        SwitchRow(stringResource(R.string.su_rule_free), freeOnly, { freeOnly = it }, secondary = stringResource(R.string.su_rule_free_hint))

        SectionHeader(stringResource(R.string.su_rule_then_header))
        SwitchRow(stringResource(R.string.su_rule_nocue), noCue, { noCue = it }, secondary = stringResource(R.string.su_rule_nocue_hint))
        if (!noCue) {
            DayCueTextField(leads, { leads = it; error = null }, stringResource(R.string.su_leads_label), helper = stringResource(R.string.su_leads_hint))
            Gap(8)
            DayCueTextField(kindEn, { kindEn = it }, stringResource(R.string.su_rule_kind_en), helper = stringResource(R.string.su_rule_kind_hint))
            Gap(8)
            DayCueTextField(kindHe, { kindHe = it }, stringResource(R.string.su_rule_kind_he))
            SwitchRow(stringResource(R.string.su_rule_is_meeting), isMeeting, { isMeeting = it }, secondary = stringResource(R.string.su_rule_is_meeting_hint))
        }
        error?.let { FieldNote(stringResource(it)) }
        Gap(16)
        PrimaryButton(stringResource(R.string.su_rule_save), {
            val conditions = buildList<CalendarCondition> {
                val words = parseKeywords(keywords)
                if (words.isNotEmpty()) add(CalendarCondition.Keywords(words))
                if (attendees) add(CalendarCondition.Attendees(1))
                if (conferencing) add(CalendarCondition.ConferencingLink)
                if (freeOnly) add(CalendarCondition.Availability(EventAvailability.Free))
            }
            val parsedLeads = if (noCue) emptyList() else parseLeads(leads)
            when {
                name.isBlank() || name.length > 40 -> error = R.string.su_rule_err_name
                conditions.isEmpty() -> error = R.string.su_rule_err_conditions
                parsedLeads == null -> error = R.string.su_leads_error
                else -> scope.launch {
                    val id = rule?.id ?: newRuleId(name, cfg!!.calendarRules.rules.map { it.id }.toSet())
                    val r = vm.saveRule(
                        CalendarRule(id, name.trim(), rule?.enabled ?: true, conditions, parsedLeads, LocalizedText(kindEn.trim(), kindHe.trim()), noCue, isMeeting && !noCue),
                    )
                    when (r) {
                        EditResult.Applied -> onBack()
                        is EditResult.Invalid -> error = friendlyError(r.errors.first())
                        EditResult.Conflict -> error = R.string.su_err_conflict
                    }
                }
            }
        }, Modifier.fillMaxWidth())
        if (!isNew) {
            DayCueTextButton(stringResource(R.string.su_rule_delete), { confirmDelete = true }, color = DayCueTheme.colors.error.ink)
        }
    }
    if (confirmDelete && rule != null) {
        DayCueDialog(
            stringResource(R.string.su_rule_delete_title, rule.name), stringResource(R.string.su_rule_delete_body),
            stringResource(R.string.su_delete), stringResource(R.string.su_cancel),
            onConfirm = { confirmDelete = false; vm.deleteRule(rule.id); onBack() },
            onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

private fun newRuleId(name: String, existing: Set<String>): String {
    val base = newPlaceId(name, emptySet()).removePrefix("place-").let { "rule-$it" }
    var id = base
    var n = 2
    while (id in existing) { id = "$base-$n"; n++ }
    return id
}

// ---- Upcoming events preview --------------------------------------------------------------------------------

@Composable
fun CalendarPreviewScreen(onBack: () -> Unit) {
    val vm: CalendarViewModel = viewModel()
    val rows by vm.preview.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    val syncedAt by vm.syncedAt.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    var scopeFor by remember { mutableStateOf<Pair<CalendarPreviewRow, Boolean>?>(null) } // row to "always?"
    val c = DayCueTheme.colors

    val uiLang = if (currentLocale().language.let { it == "iw" || it == "he" }) Language.he else Language.en
    val zone = remember { ZoneId.systemDefault() }
    val locale = currentLocale()
    SetupFrame(stringResource(R.string.su_prev_title), onBack) {
        val config = cfg
        val list = rows
        val maxAge = config?.calendarRules?.maxCacheAgeHours ?: 24
        val stale = syncedAt?.let { Duration.between(it, Instant.now()).toHours() >= maxAge } == true
        if (stale) {
            StateBlock(StateBlockKind.Uncertain, pluralStringResource(R.plurals.su_cal_stale, ageHours(syncedAt), ageHours(syncedAt)), body = stringResource(R.string.su_cal_stale_body), actionLabel = stringResource(R.string.su_sync_now), onAction = { vm.refresh() })
        }
        when {
            !permission -> StateBlock(StateBlockKind.Empty, stringResource(R.string.su_cal_perm_title), body = stringResource(R.string.su_cal_perm_without))
            config == null || list == null -> repeat(3) { SkeletonRow(mark = true) }
            list.isEmpty() -> StateBlock(StateBlockKind.Empty, stringResource(R.string.su_prev_empty), body = stringResource(R.string.su_prev_empty_body))
            else -> {
                // Events grouped under day headers, not a date in every row (REVIEW-2 C18).
                val byDay = list.groupBy { it.event.start.atZone(zone).toLocalDate() }
                byDay.forEach { (day, dayRows) ->
                    SectionHeader(remember(day, locale) { dayHeader(day, locale) })
                    dayRows.forEach { row ->
                        PreviewRowView(row, vm.why(row, uiLang), vm, onAsk = { always -> scopeFor = row to always })
                    }
                }
            }
        }
        Hint(stringResource(R.string.su_prev_untrusted))
    }

    scopeFor?.let { (row, always) ->
        DayCueBottomSheet(onDismiss = { scopeFor = null }, title = stringResource(if (always) R.string.su_prev_always_q else R.string.su_prev_never_q), mark = CueType.Calendar) {
            // Titles are data: plain text, never interpreted.
            Text(row.event.title.ifBlank { stringResource(R.string.su_prev_untitled) }, style = DayCueTheme.type.body, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
            DayCueRow(primary = stringResource(R.string.su_prev_just_this), onClick = {
                if (always) vm.always(row, OverrideScope.Instance) else vm.never(row, OverrideScope.Instance); scopeFor = null
            })
            DayCueRow(primary = stringResource(R.string.su_prev_whole_series), onClick = {
                if (always) vm.always(row, OverrideScope.Series) else vm.never(row, OverrideScope.Series); scopeFor = null
            })
        }
    }
}

/** "Mon, 5 Oct" in the app language, from the locale's own skeleton. */
private fun dayHeader(day: java.time.LocalDate, locale: java.util.Locale): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
    return DateTimeFormatter.ofPattern(pattern, locale).format(day)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PreviewRowView(row: CalendarPreviewRow, why: String, vm: CalendarViewModel, onAsk: (always: Boolean) -> Unit) {
    val c = DayCueTheme.colors
    val locale = currentLocale()
    val is24 = is24Hour(LocalContext.current)
    val zone = remember { ZoneId.systemDefault() }
    val start = row.event.start.atZone(zone)
    val time = remember(row, locale, is24) { if (row.event.allDay) null else clockText(start.hour, start.minute, is24, locale) }
    val series = row.event.seriesId != null
    val override = row.instanceOverride ?: row.seriesOverride
    val overrideScope = if (row.instanceOverride != null) OverrideScope.Instance else OverrideScope.Series
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            CueMark(CueType.Calendar, state = if (row.decision.cue) null else app.daycue.ui.marks.CueState.Paused)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                // The title is untrusted text: shown as plain text only (a Text composable never links or interprets it).
                Text(row.event.title.ifBlank { stringResource(R.string.su_prev_untitled) }, style = DayCueTheme.type.titleSmall, color = c.ink, maxLines = 3)
                Text(time?.clockIsolate() ?: stringResource(R.string.su_prev_all_day), style = DayCueTheme.type.bodySmall, color = c.ink2)
                val cueLine = when {
                    override is EventDecisionOverride.Never -> stringResource(R.string.su_prev_no_cue_you)
                    override is EventDecisionOverride.Always -> stringResource(R.string.su_prev_cue_you, leadsText(override.leadsMin))
                    row.decision.cue -> stringResource(R.string.su_prev_cue, leadsText(row.decision.leadsMin))
                    else -> stringResource(R.string.su_prev_no_cue)
                }
                Text(cueLine, style = DayCueTheme.type.bodySmall, color = c.calendar.ink)
                // Why matched: the engine's sentence, resolved in the app language (not the config language).
                Text(why, style = DayCueTheme.type.bodySmall, color = c.ink2)
                row.calendarName?.let { Text(it, style = DayCueTheme.type.labelSmall, color = c.ink2) }
                // Actions wrap as whole phrases, never inside a word, and line up with the row text.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (override != null) {
                        DayCueTextButton(stringResource(R.string.su_prev_clear), { vm.clear(row, overrideScope) })
                    } else {
                        if (row.decision.cue) {
                            DayCueTextButton(stringResource(R.string.su_prev_never), { if (series) onAsk(false) else vm.never(row, OverrideScope.Instance) })
                        } else {
                            DayCueTextButton(stringResource(R.string.su_prev_always), { if (series) onAsk(true) else vm.always(row, OverrideScope.Instance) })
                        }
                        DayCueTextButton(stringResource(R.string.su_prev_never_calendar), { vm.neverForCalendar(row.event.calendarId) })
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(c.outline))
    }
}

