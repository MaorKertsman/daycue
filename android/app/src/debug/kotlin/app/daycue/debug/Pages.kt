package app.daycue.debug

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.ChoiceRow
import app.daycue.ui.components.ContextLine
import app.daycue.ui.components.ContextLineKind
import app.daycue.ui.components.CueAction
import app.daycue.ui.components.CueCard
import app.daycue.ui.components.CuePreviewButton
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DayCueBottomNav
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSnackbar
import app.daycue.ui.components.DayCueSnackbarHost
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.DialogContent
import app.daycue.ui.components.DiffReview
import app.daycue.ui.components.DurationField
import app.daycue.ui.components.DurationUnit
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphButton
import app.daycue.ui.components.NavItem
import app.daycue.ui.components.NextRow
import app.daycue.ui.components.PermissionCard
import app.daycue.ui.components.PlaneIllustration
import app.daycue.ui.components.PolicyChoiceList
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.ProgressTimer
import app.daycue.ui.components.QuickControls
import app.daycue.ui.components.QuickItem
import app.daycue.ui.components.ReadinessRow
import app.daycue.ui.components.ReadinessStatus
import app.daycue.ui.components.ReorderableList
import app.daycue.ui.components.RoutineStepRow
import app.daycue.ui.components.RunningRow
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.SheetPreview
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.StatusKind
import app.daycue.ui.components.StatusText
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.components.TimeField
import app.daycue.ui.components.TimeStepperPicker
import app.daycue.ui.components.TimeWindowField
import app.daycue.ui.components.TimerKind
import app.daycue.ui.components.WhyNow
import app.daycue.ui.components.showUndo
import app.daycue.ui.field.Field
import app.daycue.ui.field.FieldActive
import app.daycue.ui.field.FieldContext
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.PostureMode
import app.daycue.ui.marks.stateInk
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import java.time.DayOfWeek

private val Cues = listOf(
    CueType.Sunscreen, CueType.Hydration, CueType.Bottle, CueType.Posture,
    CueType.Medication, CueType.Calendar, CueType.Routine, CueType.Alarm,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarksPage() {
    val c = DayCueTheme.colors
    SectionHeader(stringResource(R.string.gal_marks_small))
    Caption(stringResource(R.string.gal_marks_small_note))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cues.forEach { cue ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CueMark(cue) }
                Text(stringResource(cue.nameRes), style = DayCueTheme.type.bodySmall, color = c.ink)
            }
        }
    }
    SectionHeader(stringResource(R.string.gal_marks_large))
    Caption(stringResource(R.string.gal_marks_large_note))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Cues.forEach { cue ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CueMark(cue, size = 72.dp, hydrationLevel = if (cue == CueType.Hydration) 0.5f else null,
                    postureMode = if (cue == CueType.Posture) PostureMode.Stand else null)
                Text(stringResource(cue.nameRes), style = DayCueTheme.type.labelSmall, color = c.ink2)
            }
        }
    }
    SectionHeader(stringResource(R.string.gal_states))
    Caption(stringResource(R.string.gal_states_note))
    val states = listOf(CueState.Due, CueState.Scheduled, CueState.Snoozed, CueState.Paused, CueState.Unknown, CueState.Error)
    states.forEach { s ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CueMark(CueType.Hydration, size = 56.dp, state = s)
            CueMark(CueType.Sunscreen, size = 28.dp, state = s)
            CueMark(CueType.Medication, size = 28.dp, state = s)
            CueMark(CueType.Routine, size = 28.dp, state = s)
            Text(
                if (s == CueState.Snoozed) stringResource(R.string.gal_state_snoozed_until) else stringResource(s.wordRes),
                style = DayCueTheme.type.body,
                color = c.stateInk(CueType.Hydration, s),
            )
        }
    }
    SectionHeader(stringResource(R.string.gal_variants))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CueMark(CueType.Hydration, size = 48.dp, hydrationLevel = 0f)
        CueMark(CueType.Hydration, size = 48.dp, hydrationLevel = 0.5f)
        CueMark(CueType.Hydration, size = 48.dp, hydrationLevel = 1f)
        Spacer(Modifier.width(8.dp))
        CueMark(CueType.Posture, size = 48.dp, postureMode = PostureMode.Sit)
        CueMark(CueType.Posture, size = 48.dp, postureMode = PostureMode.Stand)
        CueMark(CueType.Posture, size = 48.dp, postureMode = PostureMode.Walk)
        CueMark(CueType.Posture, size = 48.dp)
    }
    Caption(stringResource(R.string.gal_variants_note))
}

@Composable
fun FieldPage() {
    val reduce = LocalReduceMotion.current
    @Composable
    fun Entry(@androidx.annotation.StringRes caption: Int, content: @Composable () -> Unit) {
        Caption(stringResource(caption), Modifier.padding(horizontal = 20.dp).padding(top = 12.dp))
        if (caption == R.string.gal_field_home) {
            // The caption reflects the real state (REVIEW-1 #42).
            Caption(
                stringResource(if (reduce) R.string.gal_field_ambient_off else R.string.gal_field_ambient_on),
                Modifier.padding(horizontal = 20.dp),
            )
        }
        content()
    }
    Entry(R.string.gal_field_home) {
        Field(FieldContext.Home, FieldActive.Posture(PostureMode.Stand, 0.6f, PostureMode.Walk), CueType.Sunscreen, 40, 120,
            stringResource(R.string.today_field_description))
    }
    Entry(R.string.gal_field_work) {
        Field(FieldContext.Work, FieldActive.Routine(0.3f), CueType.Medication, 100, 120, stringResource(R.string.gal_field_desc_work), ambient = false)
    }
    Entry(R.string.gal_field_outdoors) {
        Field(FieldContext.Outdoors, FieldActive.None, CueType.Hydration, 110, 120, stringResource(R.string.gal_field_desc_outdoors), ambient = false)
    }
    Entry(R.string.gal_field_transit) {
        Field(FieldContext.Transit, FieldActive.Posture(PostureMode.Sit, 0.2f, PostureMode.Stand), CueType.Calendar, 15, 60,
            stringResource(R.string.gal_field_desc_transit), ambient = false)
    }
    Entry(R.string.gal_field_due) {
        Field(FieldContext.Home, FieldActive.Posture(PostureMode.Walk, 0.9f), CueType.Hydration, 0, 60,
            stringResource(R.string.gal_field_desc_due), ambient = false)
    }
    Entry(R.string.gal_field_overdue) {
        Field(FieldContext.Home, FieldActive.None, CueType.Sunscreen, 0, 120, stringResource(R.string.gal_field_desc_overdue), overdue = true, ambient = false)
    }
    Entry(R.string.gal_field_unknown) {
        Field(FieldContext.Unknown, FieldActive.None, CueType.Alarm, 70, 120, stringResource(R.string.gal_field_desc_unknown), ambient = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsPage() {
    val c = DayCueTheme.colors
    var switchOn by remember { mutableStateOf(true) }
    var rowSwitch by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) }
    var text by remember { mutableStateOf("") }
    var choice by remember { mutableIntStateOf(0) }
    var whyOpen by remember { mutableStateOf(false) }
    var advOpen by remember { mutableStateOf(true) }

    SectionHeader(stringResource(R.string.gal_buttons))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(stringResource(R.string.gal_btn_primary), {}, Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.gal_btn_secondary), {}, Modifier.fillMaxWidth())
        DestructiveButton(stringResource(R.string.gal_btn_destructive), {}, Modifier.fillMaxWidth())
        PrimaryButton(stringResource(R.string.gal_btn_disabled), {}, Modifier.fillMaxWidth(), enabled = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.gal_btn_compact), {}, compact = true)
            SecondaryButton(stringResource(R.string.gal_btn_compact_selected), {}, compact = true, selected = true)
            SecondaryButton(stringResource(R.string.gal_btn_compact_off), {}, compact = true, selected = false)
            DayCueTextButton(stringResource(R.string.gal_btn_text), {})
            DayCueTextButton(stringResource(R.string.action_fix), {})
            GlyphButton(Glyph.Help, stringResource(R.string.today_help), {})
            GlyphButton(Glyph.Settings, stringResource(R.string.gal_settings), {}, outlined = true)
        }
    }

    SectionHeader(stringResource(R.string.gal_switches))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayCueSwitch(switchOn, { switchOn = it })
        DayCueSwitch(!switchOn, { switchOn = !it })
        Caption(stringResource(R.string.gal_switch_note))
    }
    SwitchRow(stringResource(R.string.cue_hydration), rowSwitch, { rowSwitch = it }, secondary = stringResource(R.string.gal_row_every_60),
        leading = { CueMark(CueType.Hydration) })
    SwitchRow(stringResource(R.string.cue_sunscreen), true, {}, secondary = stringResource(R.string.gal_row_every_2h), leading = { CueMark(CueType.Sunscreen) })
    SwitchRow(stringResource(R.string.cue_posture), true, {}, secondary = stringResource(R.string.gal_keep_one), enabled = false,
        lockedReason = stringResource(R.string.gal_keep_one), leading = { CueMark(CueType.Posture) }, divider = false)

    SectionHeader(stringResource(R.string.gal_setting_rows))
    SettingRow(stringResource(R.string.gal_remind_every), stringResource(R.string.gal_value_2h), {})
    SettingRow(stringResource(R.string.gal_snooze_length), stringResource(R.string.gal_value_15), {}, changed = true)
    SettingRow(stringResource(R.string.gal_speak_name), stringResource(R.string.gal_value_off), {}, disabledReason = stringResource(R.string.gal_reason_voice))

    SectionHeader(stringResource(R.string.gal_days))
    DayChips(days, { d -> days = if (d in days) days - d else days + d })
    Caption(stringResource(R.string.gal_days_note))

    SectionHeader(stringResource(R.string.gal_text_fields))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DayCueTextField(text, { text = it }, stringResource(R.string.gal_field_name), helper = stringResource(R.string.gal_field_helper))
        DayCueTextField(stringResource(R.string.sample_medication), {}, stringResource(R.string.gal_field_name), error = stringResource(R.string.gal_field_error))
    }

    SectionHeader(stringResource(R.string.gal_choice))
    ChoiceRow(stringResource(R.string.gal_choice_a), stringResource(R.string.gal_choice_a_desc), choice == 0, { choice = 0 })
    ChoiceRow(stringResource(R.string.gal_choice_b), stringResource(R.string.gal_choice_b_desc), choice == 1, { choice = 1 })

    SectionHeader(stringResource(R.string.gal_status_text))
    StatusKind.entries.forEach { StatusText(it, detail = if (it == StatusKind.Taken) "08:04" else null, modifier = Modifier.padding(vertical = 2.dp)) }

    SectionHeader(stringResource(R.string.gal_timers))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProgressTimer(stringResource(R.string.gal_timer_remaining), 0.6f, stringResource(R.string.gal_timer_desc))
        ProgressTimer(stringResource(R.string.gal_timer_remaining), 0.6f, stringResource(R.string.gal_timer_desc), kind = TimerKind.Frozen,
            statusText = stringResource(R.string.gal_timer_frozen))
        ProgressTimer(stringResource(R.string.gal_timer_zero), 0f, stringResource(R.string.gal_timer_desc), kind = TimerKind.Pending,
            statusText = stringResource(R.string.gal_timer_pending))
        ProgressTimer(stringResource(R.string.gal_timer_remaining), 0.3f, stringResource(R.string.gal_timer_desc), kind = TimerKind.Test, cue = CueType.Routine,
            statusText = stringResource(R.string.gal_timer_test))
    }

    SectionHeader(stringResource(R.string.gal_misc))
    CuePreviewButton(CueType.Hydration, stringResource(R.string.gal_test_now), {})
    WhyNow(stringResource(R.string.gal_why_rule), listOf(stringResource(R.string.gal_why_src1), stringResource(R.string.gal_why_src2)), whyOpen, { whyOpen = !whyOpen })
    AdvancedSection(changedCount = 1, expanded = advOpen, onToggle = { advOpen = !advOpen }, onReset = {}) {
        SettingRow(stringResource(R.string.gal_snooze_length), stringResource(R.string.gal_value_15), {}, changed = true)
        SettingRow(stringResource(R.string.gal_repeat_ignored), stringResource(R.string.gal_value_once), {})
    }
    DayCueBottomNav(
        listOf(
            NavItem(stringResource(R.string.nav_today), Glyph.Today),
            NavItem(stringResource(R.string.nav_cues), Glyph.Cues),
            NavItem(stringResource(R.string.nav_setup), Glyph.Setup),
        ), 1, {}, Modifier.padding(top = 16.dp),
    )
}

private class Step(val id: Int, val name: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RowsPage() {
    var order by remember {
        mutableStateOf(listOf(Step(1, ""), Step(2, ""), Step(3, ""), Step(4, "")))
    }
    val stepNames = listOf(R.string.gal_step_1, R.string.gal_step_2, R.string.gal_step_3, R.string.gal_step_4).map { stringResource(it) }
    SectionHeader(stringResource(R.string.gal_now_cards))
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        CueCard(stringResource(R.string.today_now_title), stringResource(R.string.state_due), statusDetail = stringResource(R.string.today_now_detail),
            actions = listOf(CueAction(stringResource(R.string.today_action_done), {}, true), CueAction(stringResource(R.string.today_action_snooze), {})),
            moreActions = listOf(CueAction(stringResource(R.string.today_menu_pause), {}), CueAction(stringResource(R.string.why_now), {})))
        CueCard(stringResource(R.string.sample_medication), stringResource(R.string.state_due), cue = CueType.Medication,
            statusDetail = stringResource(R.string.gal_med_slot), serifTitle = false,
            actions = listOf(CueAction(stringResource(R.string.gal_taken), {}, true), CueAction(stringResource(R.string.gal_snooze_10), {})),
            overflowLabel = stringResource(R.string.gal_and_2_more))
        CueCard(stringResource(R.string.gal_time_to_walk), stringResource(R.string.state_due), cue = CueType.Posture, serifTitle = false,
            statusDetail = stringResource(R.string.gal_posture_pending),
            actions = listOf(CueAction(stringResource(R.string.gal_switched), {}, true)),
            textActions = listOf(CueAction(stringResource(R.string.gal_snooze_5), {}), CueAction(stringResource(R.string.gal_skip), {}),
                CueAction(stringResource(R.string.gal_plus_5), {})))
        CueCard(stringResource(R.string.cue_sunscreen), stringResource(R.string.state_due), cue = CueType.Sunscreen, serifTitle = false,
            testLabel = stringResource(R.string.gal_test_label),
            actions = listOf(CueAction(stringResource(R.string.gal_applied), {}, true), CueAction(stringResource(R.string.today_action_snooze), {})))
    }

    SectionHeader(stringResource(R.string.gal_context_lines))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ContextLine(stringResource(R.string.today_context), ContextLineKind.Normal, {}, spoken = stringResource(R.string.today_context_spoken))
        ContextLine(stringResource(R.string.today_context), ContextLineKind.Override, {}, note = stringResource(R.string.gal_set_by_you))
        ContextLine(stringResource(R.string.gal_ctx_unsure), ContextLineKind.Uncertain, {}, note = stringResource(R.string.gal_ctx_age),
            actionLabel = stringResource(R.string.gal_set_it))
        ContextLine(stringResource(R.string.gal_ctx_paused), ContextLineKind.Paused, {}, actionLabel = stringResource(R.string.gal_resume))
    }

    SectionHeader(stringResource(R.string.gal_next_rows))
    NextRow(CueType.Sunscreen, stringResource(R.string.cue_sunscreen), stringResource(R.string.gal_next_reason))
    NextRow(CueType.Calendar, stringResource(R.string.gal_meeting_cue), stringResource(R.string.gal_next_time))
    NextRow(CueType.Hydration, stringResource(R.string.cue_hydration), stringResource(R.string.gal_next_paused), state = CueState.Paused)
    NextRow(CueType.Alarm, stringResource(R.string.cue_alarm), stringResource(R.string.gal_next_snoozed), state = CueState.Snoozed)
    NextRow(CueType.Medication, stringResource(R.string.sample_medication), stringResource(R.string.gal_next_unsure), state = CueState.Unknown, divider = false)

    SectionHeader(stringResource(R.string.gal_running_rows))
    RunningRow(CueType.Posture, stringResource(R.string.cue_posture), stringResource(R.string.today_running_detail), postureMode = PostureMode.Stand)
    RunningRow(CueType.Routine, stringResource(R.string.gal_morning_routine), stringResource(R.string.gal_routine_detail))
    RunningRow(CueType.Hydration, stringResource(R.string.cue_hydration), stringResource(R.string.gal_paused_until), paused = true, divider = false)

    SectionHeader(stringResource(R.string.gal_readiness))
    ReadinessRow(stringResource(R.string.gal_ready_notifications), ReadinessStatus.Ready)
    ReadinessRow(stringResource(R.string.today_readiness_name), ReadinessStatus.Off, consequence = stringResource(R.string.today_readiness_consequence), onFix = {})
    ReadinessRow(stringResource(R.string.gal_ready_location), ReadinessStatus.Limited, consequence = stringResource(R.string.gal_ready_location_consequence), onFix = {})
    ReadinessRow(stringResource(R.string.gal_ready_activity), ReadinessStatus.NotNeeded)
    ReadinessRow(stringResource(R.string.gal_ready_calendar), ReadinessStatus.Checking, divider = false)

    SectionHeader(stringResource(R.string.gal_reorder))
    ReorderableList(order, { it.id }, { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } }) { step, index, handle ->
        RoutineStepRow(
            index = index,
            name = stepNames[step.id - 1],
            secondary = stringResource(R.string.gal_step_timed),
            handle = handle,
            onMore = {},
            divider = index < order.lastIndex,
        )
    }

    SectionHeader(stringResource(R.string.gal_permission))
    PermissionCard(CueType.Alarm, stringResource(R.string.gal_perm_title), stringResource(R.string.gal_perm_why), stringResource(R.string.gal_perm_without),
        stringResource(R.string.gal_allow), stringResource(R.string.gal_not_now), {}, {})

    SectionHeader(stringResource(R.string.gal_quick))
    // Current state is outdoors: the Outdoors control is hidden, Indoors is offered.
    QuickControls(
        listOf(
            QuickItem(stringResource(R.string.today_quick_indoors), {}),
            QuickItem(stringResource(R.string.today_quick_working), {}),
            QuickItem(stringResource(R.string.today_quick_leaving), {}),
        ),
        more = QuickItem(stringResource(R.string.today_quick_more), {}),
    )
}

@Composable
fun PickersPage() {
    var minutes by remember { mutableIntStateOf(135) }
    var seconds by remember { mutableIntStateOf(30) }
    var time by remember { mutableIntStateOf(7 * 60 + 30) }
    var days by remember { mutableStateOf(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY)) }
    SectionHeader(stringResource(R.string.gal_duration))
    DurationField(minutes, { minutes = it })
    SectionHeader(stringResource(R.string.gal_duration_seconds))
    DurationField(seconds, { seconds = it }, unit = DurationUnit.Seconds)
    SectionHeader(stringResource(R.string.gal_time))
    TimeField(stringResource(R.string.gal_alarm_time), time, {})
    TimeStepperPicker(time, { time = it }, Modifier.padding(vertical = 8.dp))
    SectionHeader(stringResource(R.string.gal_time_window))
    TimeWindowField(7 * 60, 19 * 60, {}, {})
    TimeWindowField(22 * 60 + 30, 7 * 60, {}, {})
    SectionHeader(stringResource(R.string.gal_days))
    DayChips(days, { d -> days = if (d in days) days - d else days + d })
}

@Composable
fun OverlaysPage(openSheet: Boolean, openDialog: Boolean) {
    var sheet by remember { mutableStateOf(openSheet) }
    var dialog by remember { mutableStateOf(openDialog) }
    var choice by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.gal_undo)
    val undoMessage = stringResource(R.string.gal_undo_message)

    val options = listOf(
        PolicyOption(stringResource(R.string.gal_policy_a), stringResource(R.string.gal_policy_a_desc)),
        PolicyOption(stringResource(R.string.gal_policy_b), stringResource(R.string.gal_policy_b_desc)),
        PolicyOption(stringResource(R.string.gal_policy_c), stringResource(R.string.gal_policy_c_desc)),
    )
    SectionHeader(stringResource(R.string.gal_sheet))
    SheetPreview(stringResource(R.string.gal_policy_title), mark = CueType.Sunscreen, primaryLabel = stringResource(R.string.gal_done)) {
        PolicyChoiceList(options, choice, { choice = it }, example = stringResource(R.string.gal_policy_example))
    }
    SectionHeader(stringResource(R.string.gal_dialog))
    DialogContent(stringResource(R.string.gal_dialog_title), stringResource(R.string.gal_dialog_text),
        stringResource(R.string.gal_dialog_confirm), stringResource(R.string.gal_keep_editing), {}, {}, destructive = true)
    SectionHeader(stringResource(R.string.gal_diff))
    DiffReview(stringResource(R.string.gal_diff_title), listOf(stringResource(R.string.gal_diff_1), stringResource(R.string.gal_diff_2)),
        stringResource(R.string.gal_save), stringResource(R.string.gal_back), {}, {})
    SectionHeader(stringResource(R.string.gal_snackbar))
    DayCueSnackbar(undoMessage, actionLabel = undoLabel)
    SectionHeader(stringResource(R.string.gal_live))
    Caption(stringResource(R.string.gal_live_note))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.gal_open_sheet), { sheet = true }, Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.gal_open_dialog), { dialog = true }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    SecondaryButton(stringResource(R.string.gal_show_snackbar), {
        scope.launch { snackbar.showUndo(undoMessage, undoLabel, talkBackOn = false) }
    }, Modifier.fillMaxWidth())
    DayCueSnackbarHost(snackbar)
    if (sheet) {
        DayCueBottomSheet({ sheet = false }, stringResource(R.string.gal_policy_title), CueType.Sunscreen, stringResource(R.string.gal_done), { sheet = false }) {
            PolicyChoiceList(options, choice, { choice = it }, example = stringResource(R.string.gal_policy_example))
        }
    }
    if (dialog) {
        DayCueDialog(stringResource(R.string.gal_dialog_title), stringResource(R.string.gal_dialog_text), stringResource(R.string.gal_dialog_confirm),
            stringResource(R.string.gal_keep_editing), { dialog = false }, { dialog = false }, destructive = true)
    }
}

@Composable
fun StatesPage() {
    SectionHeader(stringResource(R.string.gal_state_empty))
    StateBlock(StateBlockKind.Empty, stringResource(R.string.gal_empty_title), body = stringResource(R.string.gal_empty_body), actionLabel = stringResource(R.string.gal_empty_action))
    SectionHeader(stringResource(R.string.gal_state_error))
    StateBlock(StateBlockKind.Error, stringResource(R.string.gal_error_title), body = stringResource(R.string.gal_error_body), actionLabel = stringResource(R.string.gal_try_again))
    SectionHeader(stringResource(R.string.gal_state_offline))
    StateBlock(StateBlockKind.Offline, stringResource(R.string.gal_offline_title), )
    SectionHeader(stringResource(R.string.gal_state_uncertain))
    StateBlock(StateBlockKind.Uncertain, stringResource(R.string.gal_ctx_unsure), body = stringResource(R.string.gal_ctx_age), actionLabel = stringResource(R.string.gal_set_it))
    SectionHeader(stringResource(R.string.gal_state_illustration))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaneIllustration(height = 96.dp)
        PlaneIllustration(height = 96.dp, hatched = true)
    }
    Caption(stringResource(R.string.gal_state_illustration_note))
    SectionHeader(stringResource(R.string.gal_icon))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconPreview(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground, 88.dp)
        IconPreview(R.drawable.ic_launcher_background, R.drawable.ic_launcher_monochrome, 88.dp, mono = true)
        Box(Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF6B6B6B)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_stat_daycue), null, Modifier.size(24.dp))
        }
    }
    Caption(stringResource(R.string.gal_icon_note))
}

@Composable
private fun IconPreview(bg: Int, fg: Int, size: Dp, mono: Boolean = false) {
    // Adaptive layers are 108dp with a 72dp visible window; scale the stack so the mask is `size`.
    val scale = size.value / 72f
    Box(
        Modifier.size(size).clip(CircleShape).background(if (mono) Color(0xFFDCD6EA) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val layer = Modifier.size((108f * scale).dp)
        if (!mono) Image(painterResource(bg), null, layer)
        Image(
            painterResource(fg), null, layer,
            colorFilter = if (mono) androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF2A2640)) else null,
        )
    }
}
