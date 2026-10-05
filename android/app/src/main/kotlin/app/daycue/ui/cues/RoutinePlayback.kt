package app.daycue.ui.cues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import app.daycue.ui.marks.tone
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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

    // Playback has no back chevron (the system Back minimizes; the run continues). The serif belongs to the step name.
    CuesScreen(
        "", null,
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
        val timed = !(step.completion == StepCompletion.Explicit || (run.stepEndsAt == null && run.pausedRemainingMs == null))
        var progress = 0f
        var secs = 0
        if (timed) {
            val remainingMs = if (paused) (run.pausedRemainingMs ?: 0) else Duration.between(now, run.stepEndsAt ?: now).toMillis().coerceAtLeast(0)
            val totalMs = (if (run.stepEndsAt != null && !paused) Duration.between(run.stepStartedAt, run.stepEndsAt).toMillis() else step.durationSec * 1000L)
                .coerceAtLeast(remainingMs).coerceAtLeast(1)
            progress = (1f - remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
            if (reduce) progress = (progress * 60f).toInt() / 60f
            secs = (remainingMs / 1000).toInt()
        }
        val discDescription = if (timed) stringResource(R.string.cues_play_desc, stepTitle, durationDescription((secs + 59) / 60)) else stepTitle

        // The routine disc (VISUAL 9): half the width, filling bottom-up as the step runs; the step name below it.
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RoutineDisc(
                progress = if (timed) progress else 0f,
                frozen = paused,
                modifier = Modifier
                    .fillMaxWidth(0.5f).widthIn(max = 220.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = discDescription
                        if (timed) {
                            liveRegion = LiveRegionMode.Polite
                            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        }
                    },
            )
            Spacer(Modifier.height(16.dp))
            Text(stepTitle, style = DayCueTheme.type.display, color = DayCueTheme.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(
                buildString {
                    append(stringResource(R.string.cues_play_step_of, run.stepIndex + 1, steps.size))
                    if (step.repeat > 1) append(" · ").append(stringResource(R.string.cues_play_repeat_of, run.repeatIndex + 1, step.repeat))
                },
                style = DayCueTheme.type.label, color = DayCueTheme.colors.ink2,
            )
            Spacer(Modifier.height(8.dp))
            if (timed) {
                Text(stringResource(R.string.cues_play_left, clockDuration(secs / 60, secs % 60)), style = DayCueTheme.type.title.copy(fontFeatureSettings = "tnum"), color = DayCueTheme.colors.ink)
                if (paused) Text(stringResource(R.string.cues_play_paused), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.paused.ink)
            } else {
                Text(stringResource(R.string.cues_play_tap_done), style = DayCueTheme.type.title, color = DayCueTheme.colors.ink)
                if (step.durationSec > 0) Text(stringResource(R.string.cues_play_suggested, stepDuration(step.durationSec)), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2)
            }
        }

        // Upcoming steps recede: smaller outlined discs, then the next step in words.
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val upcoming = steps.drop(run.stepIndex + 1).take(3)
            upcoming.forEachIndexed { i, _ -> RecedingDisc(size = (22 - i * 5).dp) }
            Text(
                if (next != null) stringResource(R.string.cues_play_next, stepName(next.id, next.name), if (next.completion == StepCompletion.Explicit) stringResource(R.string.cues_step_waits_done) else stepDuration(next.durationSec))
                else stringResource(R.string.cues_play_last_step),
                style = DayCueTheme.type.body, color = DayCueTheme.colors.ink2, modifier = Modifier.weight(1f),
            )
        }

        // Done is the primary control (the acknowledgement); Pause is secondary; the rest are text buttons.
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.cues_play_done), { act(RoutineAction.Done) }, Modifier.fillMaxWidth().heightIn(min = 64.dp))
        Spacer(Modifier.height(8.dp))
        if (paused) SecondaryButton(stringResource(R.string.cues_play_resume), { act(RoutineAction.Resume) }, Modifier.fillMaxWidth().heightIn(min = 64.dp))
        else SecondaryButton(stringResource(R.string.cues_pause_now), { act(RoutineAction.Pause) }, Modifier.fillMaxWidth().heightIn(min = 64.dp))
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (step.completion == StepCompletion.Timed) {
                DayCueTextButton(plusMinutes(1), { act(RoutineAction.Extend(1)) })
                DayCueTextButton(plusMinutes(5), { act(RoutineAction.Extend(5)) })
            }
            DayCueTextButton(stringResource(R.string.cues_play_skip), { act(RoutineAction.SkipStep) })
            if (run.stepIndex > 0 || run.repeatIndex > 0) DayCueTextButton(stringResource(R.string.cues_play_back), { act(RoutineAction.BackStep) })
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

/** Routine disc: an outlined circle that fills from the bottom as the step's time passes. */
@Composable
private fun RoutineDisc(progress: Float, frozen: Boolean, modifier: Modifier = Modifier) {
    val c = DayCueTheme.colors
    val tone = c.tone(CueType.Routine)
    val fill = if (frozen) c.paused.shape else tone.shape
    androidx.compose.foundation.Canvas(modifier.aspectRatio(1f)) {
        val stroke = 2.dp.toPx()
        val r = size.minDimension / 2f - stroke / 2f
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val circle = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(center, r))
        }
        clipPath(circle) {
            val h = size.height * progress.coerceIn(0f, 1f)
            drawRect(fill, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
        }
        drawCircle(tone.ink, r, center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    }
}

@Composable
private fun RecedingDisc(size: androidx.compose.ui.unit.Dp) {
    val tone = DayCueTheme.colors.tone(CueType.Routine)
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawCircle(tone.ink, this.size.minDimension / 2f - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}
