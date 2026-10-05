package app.daycue.ui.today

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.engine.PostureAction
import app.daycue.domain.engine.SessionAnswer
import app.daycue.system.ReadinessStatus
import app.daycue.ui.app.rememberFacade
import app.daycue.ui.components.ContextLine
import app.daycue.ui.components.ContextLineKind
import app.daycue.ui.components.CueAction
import app.daycue.ui.components.CueCard
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSnackbarHost
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.NextRow
import app.daycue.ui.components.QuickControls
import app.daycue.ui.components.QuickItem
import app.daycue.ui.components.ReadinessRow
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.TopHeader
import app.daycue.ui.components.showUndo
import app.daycue.ui.components.rememberTalkBackOn
import app.daycue.ui.field.Field
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.readiness.ReadinessCopy
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.clockDuration
import app.daycue.ui.util.durationText
import java.time.Duration
import app.daycue.ui.components.ReadinessStatus as UiReadinessStatus

/** Where Today sends the owner when a row belongs to another tab or screen. */
class TodayNav(
    val openCues: (startItem: String?) -> Unit,
    val openSetup: (startItem: String?) -> Unit,
    val openReadiness: () -> Unit,
)

/** What a deep link asked Today to show first. [nonce] makes a repeated link act again. */
data class TodayInitial(val detailKey: String? = null, val openContext: Boolean = false, val nonce: Int = 0)

/**
 * Today (UX 3.2): where the app thinks you are, what is due, what runs, what is next, what you can do. All state
 * comes from [TodayViewModel] over the facade; every tap is an engine event or a facade call. The whole screen is
 * one vertical scroll with [bottomPadding] below, so nothing sits under the navigation.
 */
@Composable
fun TodayScreen(nav: TodayNav, initial: TodayInitial, modifier: Modifier = Modifier, bottomPadding: Dp = 16.dp) {
    val facade = rememberFacade()
    val vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory(facade))
    val model by vm.model.collectAsState()
    val report by vm.readiness.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val talkBack = rememberTalkBackOn()
    val haptic = LocalHapticFeedback.current
    val activity = LocalActivity.current

    LifecycleResumeEffect(Unit) {
        vm.refreshReadiness()
        onPauseOrDispose { }
    }

    var detail by remember(initial.nonce) { mutableStateOf(initial.detailKey) }
    var whyExpanded by remember(initial.nonce) { mutableStateOf(initial.detailKey != null) }
    var sheet by remember(initial.nonce) { mutableStateOf<TodaySheet?>(if (initial.openContext) TodaySheet.Context else null) }
    var showAllNext by remember { mutableStateOf(false) }
    var showAllDue by remember { mutableStateOf(false) }

    val resources by androidx.compose.runtime.rememberUpdatedState(androidx.compose.ui.platform.LocalResources.current)
    val talkBackNow = androidx.compose.runtime.rememberUpdatedState(talkBack)
    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            // One snackbar at a time; a newer message replaces the one on screen.
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val result = snackbar.showUndo(resources.getString(msg.text), resources.getString(R.string.app_undo), talkBackNow.value)
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) msg.undo?.invoke()
            }
        }
    }

    val handlers = remember(vm, activity) {
        CardHandlers(
            vm = vm,
            confirm = { haptic.performHapticFeedback(HapticFeedbackType.Confirm) },
            startRoutine = { id -> activity?.let { facade.startRoutine(it, id) } },
            pauseHabit = { due -> sheet = TodaySheet.PauseHabit(due.habitId, due.name, due.canPauseUntilConditionEnds) },
            why = { key -> detail = key; whyExpanded = true },
        )
    }

    val c = DayCueTheme.colors
    val gutter = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    val m = model

    if (detail != null && m != null) {
        BackHandler { detail = null }
        ItemDetailScreen(
            model = m, itemKey = detail!!, vm = vm, handlers = handlers, whyExpanded = whyExpanded,
            onToggleWhy = { whyExpanded = !whyExpanded }, onBack = { detail = null },
            onPause = { due -> sheet = TodaySheet.PauseHabit(due.habitId, due.name, due.canPauseUntilConditionEnds) },
        )
        sheet?.let { TodaySheets(it, m, vm, nav, { sheet = null }, { sheet = it }) }
        return
    }

    Box(modifier.fillMaxSize().background(c.paper)) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = DayCueSpacing.contentMaxWidth).fillMaxWidth().padding(bottom = bottomPadding)) {
                if (m == null) {
                    TodaySkeleton(gutter)
                } else {
                    TodayContent(
                        m = m, gutter = gutter, nav = nav, vm = vm, handlers = handlers,
                        problem = ReadinessCopy.todayProblem(report, m.alarmEnabled),
                        showAllNext = showAllNext, onShowAllNext = { showAllNext = it },
                        showAllDue = showAllDue, onShowAllDue = { showAllDue = it },
                        onSheet = { sheet = it }, onDetail = { detail = it; whyExpanded = false },
                    )
                }
            }
        }
        DayCueSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
    }
    if (m != null) sheet?.let { TodaySheets(it, m, vm, nav, { sheet = null }, { sheet = it }) }
}

@Composable
private fun TodayContent(
    m: TodayModel,
    gutter: Dp,
    nav: TodayNav,
    vm: TodayViewModel,
    handlers: CardHandlers,
    problem: app.daycue.system.ReadinessItem?,
    showAllNext: Boolean,
    onShowAllNext: (Boolean) -> Unit,
    showAllDue: Boolean,
    onShowAllDue: (Boolean) -> Unit,
    onSheet: (TodaySheet) -> Unit,
    onDetail: (String) -> Unit,
) {
    val ctx = m.context
    TopHeader(
        dateText = dateLine(m.now, m.zone),
        trailingGlyph = Glyph.Help,
        trailingDescription = stringResource(R.string.app_help),
        onTrailingClick = { onSheet(TodaySheet.Help) },
        modifier = Modifier.padding(horizontal = gutter),
    ) {
        val resumeLabel = stringResource(R.string.app_resume)
        val setLabel = stringResource(R.string.app_set_it)
        when (ctx.lineKind) {
            ContextLineKind.Paused -> {
                val until = ctx.detectionPausedUntil
                val text = if (until != null) stringResource(R.string.app_detection_paused_until, dayAwareClock(until, m.now, m.zone)) else stringResource(R.string.app_detection_paused)
                ContextLine(text, ContextLineKind.Paused, { onSheet(TodaySheet.Context) }, actionLabel = resumeLabel, onAction = { vm.resumeDetection() })
            }
            ContextLineKind.Uncertain -> ContextLine(
                contextLine(ctx), ContextLineKind.Uncertain, { onSheet(TodaySheet.Context) },
                spoken = contextSpoken(ctx), actionLabel = setLabel, onAction = { onSheet(TodaySheet.Context) },
            )
            ContextLineKind.Override -> ContextLine(
                contextLine(ctx), ContextLineKind.Override, { onSheet(TodaySheet.Context) },
                spoken = contextSpoken(ctx), note = stringResource(R.string.app_set_by_you),
            )
            ContextLineKind.Normal -> ContextLine(contextLine(ctx), ContextLineKind.Normal, { onSheet(TodaySheet.Context) }, spoken = contextSpoken(ctx))
        }
    }

    // The Field, or a calm empty state when there is nothing to show yet.
    val f = m.field
    if (f != null) {
        Field(
            context = f.context, active = f.active, nextCue = f.nextMark, remainingMinutes = f.remainingMinutes,
            horizonMinutes = f.horizonMinutes, description = fieldDescription(m), overdue = f.overdue,
        )
    }

    Column(Modifier.padding(horizontal = gutter).padding(top = 12.dp)) {
        if (m.nothingEnabled) {
            StateBlock(
                StateBlockKind.Empty, stringResource(R.string.app_empty_nothing_enabled),
                body = stringResource(R.string.app_empty_nothing_enabled_body),
                actionLabel = stringResource(R.string.app_empty_choose), onAction = { nav.openCues(null) },
            )
        }

        // NOW: due items in priority order; beyond two they collapse.
        if (m.due.isNotEmpty()) {
            SectionHeader(stringResource(R.string.app_now), Modifier.padding(top = if (f == null) 8.dp else 0.dp))
            val shown = if (showAllDue) m.due else m.due.take(2)
            shown.forEach { item -> DueCard(item, m, handlers, Modifier.padding(bottom = 8.dp)) }
            if (m.due.size > 2 && !showAllDue) {
                DayCueTextButton(androidx.compose.ui.res.pluralStringResource(R.plurals.app_more_due, m.due.size - 2, m.due.size - 2), { onShowAllDue(true) })
            }
        }

        // RUNNING
        if (m.running.isNotEmpty()) {
            SectionHeader(stringResource(R.string.app_running))
            m.running.forEachIndexed { i, r ->
                RunningRowFor(r, m, nav, divider = i < m.running.lastIndex, onContext = { onSheet(TodaySheet.Context) })
            }
        }

        // NEXT (max 3, then "See today"); the readiness problem is always the last row.
        val nextShown = if (showAllNext) m.next else m.next.take(3)
        if (nextShown.isNotEmpty() || problem != null) {
            SectionHeader(stringResource(R.string.app_next))
            nextShown.forEachIndexed { i, n ->
                NextRow(
                    cue = n.mark, title = n.name.text(), detail = waitingText(n, m.now, m.zone),
                    state = if (n.waiting == null) CueState.Scheduled else if (n.waiting == app.daycue.domain.query.WaitingReason.PausedUntil) CueState.Paused else CueState.Scheduled,
                    onClick = { openNext(n, nav, onDetail) },
                    divider = i < nextShown.lastIndex || problem != null || (m.next.size > 3 && !showAllNext),
                )
            }
            if (m.next.size > 3 && !showAllNext) {
                DayCueTextButton(stringResource(R.string.app_see_today), { onShowAllNext(true) })
            }
            if (problem != null) {
                ReadinessRow(
                    name = stringResource(ReadinessCopy.name(problem.id)),
                    status = if (problem.status == ReadinessStatus.Off) UiReadinessStatus.Off else UiReadinessStatus.Limited,
                    consequence = stringResource(ReadinessCopy.consequence(problem)),
                    onFix = nav.openReadiness,
                    divider = false,
                )
            }
        } else if (!m.nothingEnabled && m.due.isEmpty() && m.running.isEmpty()) {
            Text2(stringResource(R.string.app_nothing_else))
        }

        QuickControlsRow(m, vm, onMore = { onSheet(TodaySheet.More) }, modifier = Modifier.padding(top = 20.dp))
    }
}

@Composable
private fun Text2(text: String) {
    androidx.compose.material3.Text(text, style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(vertical = 16.dp))
}

private fun openNext(n: NextItem, nav: TodayNav, onDetail: (String) -> Unit) {
    when {
        n.key.startsWith("habit:") -> onDetail(n.key)
        n.key == "posture" || n.key.startsWith("med:") || n.key.startsWith("alarm:") || n.key.startsWith("routine:") -> nav.openCues(n.key)
        n.key.startsWith("cal:") -> nav.openSetup(n.key)
    }
}

@Composable
private fun QuickControlsRow(m: TodayModel, vm: TodayViewModel, onMore: () -> Unit, modifier: Modifier) {
    val env = m.context.environment
    val inSession = m.context.session != null
    val items = buildList {
        // The control matching the current state is left out; with an unknown state both are offered.
        if (env != app.daycue.domain.config.Environment.Outdoor) {
            add(QuickItem(stringResource(R.string.app_quick_outdoors)) { vm.setEnvironment(app.daycue.domain.config.Environment.Outdoor, app.daycue.domain.engine.OverrideDuration.UntilTransition) })
        }
        if (env != app.daycue.domain.config.Environment.Indoor) {
            add(QuickItem(stringResource(R.string.app_quick_indoors)) { vm.setEnvironment(app.daycue.domain.config.Environment.Indoor, app.daycue.domain.engine.OverrideDuration.UntilTransition) })
        }
        if (inSession) add(QuickItem(stringResource(R.string.app_quick_end_session)) { vm.endSession() })
        else add(QuickItem(stringResource(R.string.app_quick_start_working)) { vm.startSession(app.daycue.domain.config.SessionKind.Working, app.daycue.domain.engine.OverrideDuration.UntilChanged) })
        if (m.bottleEnabled) add(QuickItem(stringResource(R.string.app_quick_leaving)) { vm.leavingNow() })
    }
    QuickControls(items, QuickItem(stringResource(R.string.app_quick_more), onMore), modifier)
}

@Composable
private fun RunningRowFor(r: RunningItem, m: TodayModel, nav: TodayNav, divider: Boolean, onContext: () -> Unit) {
    when (r) {
        is PostureRunning -> {
            val parts = mutableListOf<String>()
            if (r.paused) {
                parts += stringResource(if (r.frozenBySession) R.string.app_posture_frozen else R.string.app_posture_paused)
                r.keptMinutes?.let { parts += stringResource(R.string.app_kept_min, durationText(it)) }
            } else {
                parts += r.modeName
                r.endsAt?.let { end ->
                    val left = Duration.between(m.now, end).toMinutes().toInt().coerceAtLeast(0)
                    parts += stringResource(R.string.app_left_min, durationText(left))
                }
                if (r.nextName != null) parts += stringResource(R.string.app_then, r.nextName, r.nextMinutes?.let { durationText(it) }.orEmpty()).trim()
            }
            app.daycue.ui.components.RunningRow(
                CueType.Posture, stringResource(R.string.cue_posture), parts.joinToString(" · "),
                postureMode = r.mark, paused = r.paused, onClick = { nav.openCues("posture") }, divider = divider,
            )
        }
        is RoutineRunning -> {
            val left = r.endsAt?.let { Duration.between(m.now, it) }?.takeIf { !it.isNegative && !r.paused }
            val detail = buildList {
                if (r.test) add(stringResource(R.string.app_test_run))
                add(stringResource(R.string.app_step_of, r.stepNumber, r.stepCount))
                if (r.stepName.isNotBlank()) add(r.stepName)
                if (r.paused) add(stringResource(R.string.state_paused))
                else if (left != null) add(stringResource(R.string.app_left_min, clockDuration(left.toMinutes().toInt(), (left.seconds % 60).toInt())))
            }.joinToString(" · ")
            app.daycue.ui.components.RunningRow(
                CueType.Routine, r.name.text(), detail, paused = r.paused, onClick = { nav.openCues(r.key) }, divider = divider,
            )
        }
        is SessionRunning -> {
            val paused = r.status != app.daycue.domain.context.SessionStatus.Active
            val kind = stringResource(if (r.kind == app.daycue.domain.config.SessionKind.Working) R.string.app_session_working else R.string.app_session_studying)
            val detail = if (paused) stringResource(R.string.app_session_paused_since, clockOf(r.since, m.zone)) else stringResource(R.string.app_session_since, clockOf(r.since, m.zone))
            DayCueRow(
                primary = kind, secondary = detail, secondaryColor = if (paused) DayCueTheme.colors.paused.ink else DayCueTheme.colors.ink2,
                leading = { GlyphIcon(Glyph.Today, DayCueTheme.colors.ink2) },
                trailing = { GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink2) }, onClick = onContext, divider = divider,
            )
        }
        is OverrideRunning -> {
            val until = if (r.untilTransition) stringResource(R.string.app_until_place_change) else stringResource(R.string.app_until_time, dayAwareClock(r.expiresAt, m.now, m.zone))
            DayCueRow(
                primary = stringResource(R.string.app_override_title, environmentText(r.environment)),
                secondary = until,
                leading = { GlyphIcon(Glyph.Today, DayCueTheme.colors.ink2) },
                trailing = { GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink2) }, onClick = onContext, divider = divider,
            )
        }
    }
}

@Composable
private fun TodaySkeleton(gutter: Dp) {
    val c = DayCueTheme.colors
    Column(Modifier.padding(horizontal = gutter).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(0.5f, 0.8f, 0.9f, 0.7f, 0.85f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(18.dp).background(c.sunk, DayCueTheme.let { app.daycue.ui.theme.DayCueShapes.chip }))
        }
    }
}

/** The single TalkBack sentence for the Field: context, what runs, what is next. */
@Composable
fun fieldDescription(m: TodayModel): String {
    val parts = mutableListOf(contextSpoken(m.context))
    m.running.filterIsInstance<PostureRunning>().firstOrNull()?.let { p ->
        if (!p.paused && p.endsAt != null) {
            val left = Duration.between(m.now, p.endsAt).toMinutes().toInt().coerceAtLeast(0)
            parts += stringResource(R.string.app_fd_posture, p.modeName, app.daycue.ui.util.durationDescription(left))
        }
    }
    m.running.filterIsInstance<RoutineRunning>().firstOrNull()?.let { parts += stringResource(R.string.app_fd_routine, it.name.text()) }
    m.due.firstOrNull()?.let { due ->
        parts += stringResource(R.string.app_fd_due, dueTitle(due))
    }
    m.field?.nextName?.let { name ->
        val f = m.field
        parts += stringResource(R.string.app_fd_next, name.text(), app.daycue.ui.util.durationDescription(f.remainingMinutes))
    }
    return parts.joinToString(". ")
}

/** Used by TalkBack text and the detail screen; the visible card builds its own title. */
@Composable
fun dueTitle(d: DueItem): String = when (d) {
    is HabitDue -> d.name.text()
    is DoseDue -> d.label
    is PostureDue -> d.nextName?.let { stringResource(R.string.app_time_to, it) } ?: stringResource(R.string.app_time_to_switch)
    is RoutinePromptDue -> d.name.text()
    is CalendarDue -> d.title.ifBlank { stringResource(R.string.app_calendar_cue) }
    is SessionPromptDue -> d.place?.let { stringResource(R.string.app_session_prompt_at, it.text()) } ?: stringResource(R.string.app_session_prompt)
}

