package app.daycue.ui.cues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.StepCompletion
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.RecoveryChoice
import app.daycue.domain.engine.RoutineAction
import app.daycue.domain.engine.RunStatus
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphButton
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.ProgressTimer
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.TimerKind
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import app.daycue.ui.util.clockDuration
import app.daycue.ui.util.durationDescription
import java.time.Duration

/**
 * Routine playback (UX 3.7): current step, next step, remaining time, back / pause / done, extend, skip, cancel.
 * Back minimizes (the run continues). The pre-start state calls `startRoutine()` from this visible screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutinePlaybackScreen(vm: CuesViewModel, routineId: String, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val engine by vm.engine.collectAsState()
    val now by rememberNow(1000)
    val reduce = LocalReduceMotion.current
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }
    val cfg = config
    val state = engine?.routine
    val run = state?.run
    fun act(a: RoutineAction) = vm.dispatch(Event.RoutineControl(a))

    CuesScreen(
        stringResource(R.string.cue_routine), onBack,
        trailing = {
            if (run != null) GlyphButton(Glyph.Close, stringResource(R.string.cues_play_cancel_cd), { confirmCancel = true })
        },
    ) {
        if (cfg == null || state == null) return@CuesScreen
        if (run == null) {
            val r = cfg.routine(routineId)
            val prompt = state.openPrompt?.takeIf { it.routineId == routineId }
            val name = r?.let { routineName(it.id, it.name) }.orEmpty()
            Spacer(Modifier.height(24.dp))
            StateBlock(
                StateBlockKind.Empty,
                title = if (prompt != null) stringResource(R.string.cues_play_prompt, name) else stringResource(R.string.cues_play_not_running),
                body = if (prompt == null && r != null) name else null,
                actionLabel = if (r != null) stringResource(R.string.cues_start) else null,
                onAction = { vm.facade.startRoutine(context.findActivity() ?: context, routineId, false) },
            )
            if (prompt != null) DayCueTextButton(stringResource(R.string.cues_play_skip_today), { act(RoutineAction.PromptSkipToday(routineId)) })
            return@CuesScreen
        }

        val steps = run.routine.steps
        val step = steps.getOrNull(run.stepIndex) ?: return@CuesScreen
        val next = steps.getOrNull(run.stepIndex + 1)
        val stepTitle = stepName(step.id, step.name)

        if (run.test != null) {
            Text(stringResource(R.string.cues_play_test_bar), style = DayCueTheme.type.label, color = DayCueTheme.colors.ink, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (run.status == RunStatus.AwaitingRecovery) {
            Headline(stringResource(R.string.cues_play_paused_at, stepTitle))
            Text(stringResource(R.string.cues_play_recovery_body), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            PrimaryButton(stringResource(R.string.cues_play_resume), { act(RoutineAction.Recover(RecoveryChoice.Resume)) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SecondaryButton(stringResource(R.string.cues_play_restart), { act(RoutineAction.Recover(RecoveryChoice.Restart)) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SecondaryButton(stringResource(R.string.cues_play_cancel_run), { act(RoutineAction.Recover(RecoveryChoice.Cancel)) }, Modifier.fillMaxWidth())
            return@CuesScreen
        }

        val paused = run.status == RunStatus.Paused
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CueMark(CueType.Routine, size = 40.dp)
            Text(stringResource(R.string.cues_play_step_of, run.stepIndex + 1, steps.size), style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2)
        }
        Spacer(Modifier.height(8.dp))
        Headline(stepTitle)
        if (step.repeat > 1) Text(stringResource(R.string.cues_play_repeat_of, run.repeatIndex + 1, step.repeat), style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2)
        Spacer(Modifier.height(12.dp))

        if (step.completion == StepCompletion.Explicit || (run.stepEndsAt == null && run.pausedRemainingMs == null)) {
            Text(stringResource(R.string.cues_play_tap_done), style = DayCueTheme.type.title, color = DayCueTheme.colors.ink)
            if (step.durationSec > 0) Text(stringResource(R.string.cues_play_suggested, stepDuration(step.durationSec)), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
        } else {
            val remainingMs = if (paused) (run.pausedRemainingMs ?: 0) else Duration.between(now, run.stepEndsAt ?: now).toMillis().coerceAtLeast(0)
            val totalMs = (if (run.stepEndsAt != null && !paused) Duration.between(run.stepStartedAt, run.stepEndsAt).toMillis() else step.durationSec * 1000L)
                .coerceAtLeast(remainingMs).coerceAtLeast(1)
            var progress = (1f - remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
            if (reduce) progress = (progress * 60f).toInt() / 60f
            val secs = (remainingMs / 1000).toInt()
            ProgressTimer(
                remainingText = stringResource(R.string.cues_play_left, clockDuration(secs / 60, secs % 60)),
                progress = progress,
                description = stringResource(R.string.cues_play_desc, stepTitle, durationDescription((secs + 59) / 60)),
                kind = when { run.test != null -> TimerKind.Test; paused -> TimerKind.Frozen; else -> TimerKind.Running },
                cue = CueType.Routine,
                statusText = if (paused) stringResource(R.string.cues_play_paused) else null,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (next != null) stringResource(R.string.cues_play_next, stepName(next.id, next.name), if (next.completion == StepCompletion.Explicit) stringResource(R.string.cues_step_waits_done) else stepDuration(next.durationSec))
            else stringResource(R.string.cues_play_last_step),
            style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2,
        )

        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.cues_play_back), { act(RoutineAction.BackStep) }, enabled = run.stepIndex > 0 || run.repeatIndex > 0)
            if (paused) PrimaryButton(stringResource(R.string.cues_play_resume), { act(RoutineAction.Resume) })
            else SecondaryButton(stringResource(R.string.cues_pause_now), { act(RoutineAction.Pause) })
            if (step.optional) SecondaryButton(stringResource(R.string.cues_play_skip), { act(RoutineAction.SkipStep) })
            PrimaryButton(stringResource(R.string.cues_play_done), { act(RoutineAction.Done) })
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step.completion == StepCompletion.Timed) {
                SecondaryButton("+1", { act(RoutineAction.Extend(1)) }, compact = true)
                SecondaryButton("+5", { act(RoutineAction.Extend(5)) }, compact = true)
            }
            if (!step.optional) SecondaryButton(stringResource(R.string.cues_play_skip), { act(RoutineAction.SkipStep) }, compact = true)
        }
    }

    if (confirmCancel) {
        DayCueDialog(
            stringResource(R.string.cues_play_cancel_q), stringResource(R.string.cues_play_cancel_body),
            stringResource(R.string.cues_play_cancel_run), stringResource(R.string.cues_play_keep_going),
            onConfirm = { confirmCancel = false; act(RoutineAction.Cancel) }, onDismiss = { confirmCancel = false }, destructive = true,
        )
    }
}
