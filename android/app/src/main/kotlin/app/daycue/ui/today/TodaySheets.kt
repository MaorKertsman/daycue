package app.daycue.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.Environment
import app.daycue.domain.config.SessionKind
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.engine.PauseChoice
import app.daycue.domain.engine.PauseTarget
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.SecondaryButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import app.daycue.ui.theme.DayCueTheme

sealed interface TodaySheet {
    data object Context : TodaySheet
    data object More : TodaySheet
    data object PauseDetection : TodaySheet
    data object Help : TodaySheet
    data class PauseHabit(val habitId: String, val name: ItemName, val untilConditionEnds: Boolean) : TodaySheet
}

/** How long a manual override or session lasts (UX 3.2: default "until I change place"). */
enum class DurationChoice(val labelRes: Int) {
    UntilChange(R.string.app_for_until_change), OneHour(R.string.app_for_1h), TwoHours(R.string.app_for_2h), RestOfToday(R.string.app_for_rest_of_today);

    fun forEnvironment(): OverrideDuration = when (this) {
        UntilChange -> OverrideDuration.UntilTransition
        OneHour -> OverrideDuration.For(60)
        TwoHours -> OverrideDuration.For(120)
        RestOfToday -> OverrideDuration.RestOfToday
    }

    fun forSession(): OverrideDuration = when (this) {
        UntilChange -> OverrideDuration.UntilChanged
        OneHour -> OverrideDuration.For(60)
        TwoHours -> OverrideDuration.For(120)
        RestOfToday -> OverrideDuration.RestOfToday
    }
}

@Composable
fun TodaySheets(sheet: TodaySheet, m: TodayModel, vm: TodayViewModel, nav: TodayNav, onDismiss: () -> Unit, onSwitch: (TodaySheet) -> Unit) {
    when (sheet) {
        TodaySheet.Context -> DayCueBottomSheet(onDismiss, stringResource(R.string.app_ctx_title)) {
            ContextSheetBody(m, vm, onDismiss, onPause = { onSwitch(TodaySheet.PauseDetection) }, onAdjustPlaces = { onDismiss(); nav.openSetup("places") })
        }
        TodaySheet.PauseDetection -> DayCueBottomSheet(onDismiss, stringResource(R.string.app_pause_detection_title)) {
            Column {
                Text(stringResource(R.string.app_pause_detection_body), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
                Spacer(Modifier.height(8.dp))
                listOf(
                    R.string.app_dur_30m to OverrideDuration.For(30),
                    R.string.app_dur_1h to OverrideDuration.For(60),
                    R.string.app_dur_2h to OverrideDuration.For(120),
                    R.string.app_dur_4h to OverrideDuration.For(240),
                    R.string.app_dur_rest_of_today to OverrideDuration.RestOfToday,
                    R.string.app_dur_until_resume to OverrideDuration.UntilChanged,
                ).forEachIndexed { i, (label, d) ->
                    DayCueRow(stringResource(label), onClick = { vm.pauseDetection(d); onDismiss() }, divider = i < 5)
                }
            }
        }
        TodaySheet.More -> DayCueBottomSheet(onDismiss, stringResource(R.string.app_more_title)) {
            Column {
                val inSession = m.context.session != null
                if (inSession) {
                    DayCueRow(stringResource(R.string.app_quick_end_session), onClick = { vm.endSession(); onDismiss() })
                } else {
                    DayCueRow(stringResource(R.string.app_quick_start_working), onClick = { vm.startSession(SessionKind.Working, OverrideDuration.UntilChanged); onDismiss() })
                    DayCueRow(stringResource(R.string.app_quick_start_studying), onClick = { vm.startSession(SessionKind.Studying, OverrideDuration.UntilChanged); onDismiss() })
                }
                if (!m.bottleEnabled) DayCueRow(stringResource(R.string.app_quick_leaving), onClick = { vm.leavingNow(); onDismiss() })
                if (m.context.envOverride != null || m.context.placeOverride != null) {
                    DayCueRow(stringResource(R.string.app_back_to_auto), onClick = { if (m.context.envOverride != null) vm.clearEnvironment(); if (m.context.placeOverride != null) vm.clearPlace(); onDismiss() })
                }
                if (m.context.detectionPaused) {
                    DayCueRow(stringResource(R.string.app_resume_detection), onClick = { vm.resumeDetection(); onDismiss() }, divider = false)
                } else {
                    DayCueRow(stringResource(R.string.app_pause_detection_title), onClick = { onSwitch(TodaySheet.PauseDetection) }, divider = false)
                }
            }
        }
        TodaySheet.Help -> DayCueBottomSheet(onDismiss, stringResource(R.string.app_help_title)) {
            Column {
                Text(stringResource(R.string.app_help_body), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink)
                Spacer(Modifier.height(8.dp))
                DayCueRow(
                    stringResource(R.string.app_rd_title), onClick = { onDismiss(); nav.openReadiness() }, divider = false,
                    trailing = { GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink2) },
                )
            }
        }
        is TodaySheet.PauseHabit -> DayCueBottomSheet(onDismiss, stringResource(R.string.app_pause_title, sheet.name.text())) {
            Column {
                val target = PauseTarget.Habit(sheet.habitId)
                val rows = buildList {
                    add(R.string.app_dur_1h to PauseChoice.For(60))
                    add(R.string.app_dur_2h to PauseChoice.For(120))
                    add(R.string.app_dur_rest_of_today to PauseChoice.RestOfToday)
                    if (sheet.untilConditionEnds) add(R.string.app_dur_until_indoors to PauseChoice.UntilConditionEnds)
                    add(R.string.app_dur_until_resume to PauseChoice.Indefinite)
                }
                rows.forEachIndexed { i, (label, choice) ->
                    DayCueRow(stringResource(label), onClick = { vm.pause(target, choice); onDismiss() }, divider = i < rows.lastIndex)
                }
            }
        }
    }
}

/** Which dimension the context sheet is choosing a value for. */
private enum class ContextPick { Place, Environment, Activity, Duration }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextSheetBody(m: TodayModel, vm: TodayViewModel, onDismiss: () -> Unit, onPause: () -> Unit, onAdjustPlaces: () -> Unit) {
    val c = m.context
    var pick by remember { mutableStateOf<ContextPick?>(null) }
    var duration by remember { mutableStateOf(DurationChoice.UntilChange) }
    val ink2 = DayCueTheme.colors.ink2
    val override = c.envOverride
    val session = c.session
    val placeOverride = c.placeOverride

    val choosing = pick
    if (choosing != null) {
        // A radio list for one dimension; picking applies it right away and returns to the overview.
        Column {
            val rows: List<Triple<String, Boolean, () -> Unit>> = when (choosing) {
                ContextPick.Place -> buildList {
                    add(Triple(stringResource(R.string.app_choice_auto), placeOverride == null) { vm.clearPlace() })
                    m.places.forEach { (id, name) ->
                        add(Triple(stringResource(R.string.app_place_im_at, name.text()), placeOverride?.value?.placeId == id) { vm.setPlace(id, duration.forEnvironment()) })
                    }
                    add(Triple(stringResource(R.string.app_place_not_saved), placeOverride != null && placeOverride.value.placeId == null) { vm.setPlace(null, duration.forEnvironment()) })
                }
                ContextPick.Duration -> DurationChoice.entries.map { d ->
                    Triple(stringResource(d.labelRes), duration == d) {
                        duration = d
                        if (placeOverride != null) vm.setPlace(placeOverride.value.placeId, d.forEnvironment())
                        else if (override != null) vm.setEnvironment(override.value, d.forEnvironment())
                        else if (session != null) vm.startSession(session.kind, d.forSession())
                    }
                }
                ContextPick.Environment -> listOf(
                    Triple(stringResource(R.string.app_choice_auto), override == null) { vm.clearEnvironment() },
                    Triple(stringResource(R.string.app_quick_indoors), override?.value == Environment.Indoor) { vm.setEnvironment(Environment.Indoor, duration.forEnvironment()) },
                    Triple(stringResource(R.string.app_quick_outdoors), override?.value == Environment.Outdoor) { vm.setEnvironment(Environment.Outdoor, duration.forEnvironment()) },
                )
                ContextPick.Activity -> listOf(
                    Triple(stringResource(R.string.app_choice_auto), session == null) { vm.endSession() },
                    Triple(stringResource(R.string.app_session_working), session?.kind == SessionKind.Working) { vm.startSession(SessionKind.Working, duration.forSession()) },
                    Triple(stringResource(R.string.app_session_studying), session?.kind == SessionKind.Studying) { vm.startSession(SessionKind.Studying, duration.forSession()) },
                )
            }
            rows.forEachIndexed { i, (label, selected, apply) ->
                DayCueRow(
                    primary = label, role = Role.RadioButton,
                    onClick = { if (!selected) apply(); pick = null },
                    trailing = { if (selected) GlyphIcon(Glyph.Check, DayCueTheme.colors.ink) },
                    divider = i < rows.lastIndex,
                    semanticsExtra = { this.selected = selected },
                )
            }
            DayCueTextButton(stringResource(R.string.app_back), { pick = null })
        }
        return
    }

    Column {

        DayCueRow(
            primary = stringResource(R.string.app_ctx_place),
            secondary = listOfNotNull(placeText(c.place), sourceAgo(c.placeSource, c.placeSince, m.now)).joinToString(" · "),
            trailing = { DayCueTextButton(stringResource(R.string.app_ctx_change), { pick = ContextPick.Place }) },
        )
        DayCueRow(
            primary = stringResource(R.string.app_ctx_environment),
            secondary = listOfNotNull(environmentText(c.environment), sourceAgo(c.environmentSource, c.environmentSince, m.now)).joinToString(" · "),
            trailing = { DayCueTextButton(stringResource(R.string.app_ctx_change), { pick = ContextPick.Environment }) },
        )
        DayCueRow(
            primary = stringResource(R.string.app_ctx_activity),
            secondary = listOfNotNull(
                activityText(c.activity, session != null) ?: stringResource(R.string.app_act_none),
                sourceAgo(c.activitySource, c.activitySince, m.now),
            ).joinToString(" · "),
            trailing = { DayCueTextButton(stringResource(R.string.app_ctx_change), { pick = ContextPick.Activity }) },
            divider = false,
        )

        // "Keep my choice for" only matters once the owner has set something by hand: one row, one radio sheet.
        if (override != null || session != null || placeOverride != null) {
            DayCueRow(
                primary = stringResource(R.string.app_ctx_for),
                secondary = stringResource(duration.labelRes),
                trailing = { DayCueTextButton(stringResource(R.string.app_ctx_change), { pick = ContextPick.Duration }) },
                divider = false,
            )
        }

        Spacer(Modifier.height(8.dp))
        if (c.detectionPaused) {
            DayCueTextButton(stringResource(R.string.app_resume_detection), { vm.resumeDetection(); onDismiss() })
        } else {
            DayCueTextButton(stringResource(R.string.app_pause_detection_button), onPause)
        }
        DayCueTextButton(stringResource(R.string.app_ctx_adjust_places), onAdjustPlaces)
    }
}
