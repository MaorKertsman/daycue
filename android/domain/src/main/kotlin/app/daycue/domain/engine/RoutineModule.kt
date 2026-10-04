package app.daycue.domain.engine

import app.daycue.domain.config.CueType
import app.daycue.domain.config.Routine
import app.daycue.domain.config.RoutineRecoveryPolicy
import app.daycue.domain.config.RoutineStartMode
import app.daycue.domain.config.RoutineTimingPolicy
import app.daycue.domain.config.RoutineTrigger
import app.daycue.domain.config.StepCompletion
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Duration
import java.time.Instant

/** Routine runs (PRODUCT §10). */
internal object RoutineModule {

    fun key(id: String) = "routine:$id"
    private fun promptKey(id: String) = "routine-prompt:$id"
    private const val HEARTBEAT_SEC = 60L
    private const val PROMPT_TIMEOUT_MIN = 30

    /** Flattened (step, repeat) list. */
    private fun subSteps(r: Routine) = r.steps.flatMapIndexed { i, s -> (0 until s.repeat).map { rep -> i to rep } }

    private fun durSec(run: RoutineRun, stepIndex: Int): Long {
        val s = run.routine.steps[stepIndex]
        return if (run.test == RoutineTestMode.Fast) (if (s.durationSec == 0) 0 else 10) else s.durationSec.toLong()
    }

    private fun isTimed(run: RoutineRun, stepIndex: Int) =
        run.routine.steps[stepIndex].completion == StepCompletion.Timed && durSec(run, stepIndex) > 0

    /** FollowSchedule planned [start, end) of flattened sub-step [flat]. */
    private fun planned(run: RoutineRun, flat: Int): Pair<Instant, Instant> {
        val subs = subSteps(run.routine)
        var t = run.startedAt.plusMillis(run.planShiftMs)
        for (j in 0 until flat) t = t.plusSeconds(durSec(run, subs[j].first))
        return t to t.plusSeconds(durSec(run, subs[flat].first))
    }

    private fun flatIndex(run: RoutineRun) = subSteps(run.routine).indexOf(run.stepIndex to run.repeatIndex)

    /** Begin sub-step [flat] at [at]. Returns null when the routine is past its last step. */
    private fun enter(run: RoutineRun, flat: Int, at: Instant, cue: Boolean): RoutineRun? {
        val subs = subSteps(run.routine)
        if (flat >= subs.size) return null
        val (si, rep) = subs[flat]
        val d = durSec(run, si)
        val end = when {
            d == 0L -> null
            run.routine.timing == RoutineTimingPolicy.FollowSchedule -> planned(run, flat).second
            else -> at.plusSeconds(d)
        }
        return run.copy(stepIndex = si, repeatIndex = rep, stepStartedAt = at, stepEndsAt = end, nudged = false, cueId = if (cue) null else run.cueId, seq = run.seq + if (cue) 1 else 0)
    }

    private fun startRun(r: Routine, run: Run, test: RoutineTestMode?): RoutineRun {
        val base = RoutineRun(r.id, r, run.config.version, startedAt = run.now, stepStartedAt = run.now, test = test)
        return enter(base, 0, run.now, cue = true)!!
    }

    private fun setRun(run: Run, rr: RoutineRun?) { run.st = run.st.copy(routine = run.st.routine.copy(run = rr)) }

    private fun finish(run: Run, rr: RoutineRun, kind: HistoryKind, rule: String) {
        run.dismiss(key(rr.routineId), rr.cueId, kind.name)
        run.history(key(rr.routineId), kind, rule, rr.cueId, test = rr.test != null)
        setRun(run, null)
    }

    fun start(run: Run, routineId: String, test: RoutineTestMode?, replace: Boolean, rule: String) {
        val r = run.config.routine(routineId) ?: return
        if (r.steps.isEmpty()) return
        val cur = run.st.routine.run
        if (cur != null) {
            if (!replace) { run.history(key(routineId), HistoryKind.Skipped, "RTN-3", detail = mapOf("reason" to "another_run_active", "active" to cur.routineId)); return }
            finish(run, cur, HistoryKind.RoutineCanceled, "RTN-3")
        }
        setRun(run, startRun(r, run, test))
        run.history(key(routineId), HistoryKind.RoutineStarted, rule, test = test != null, detail = mapOf("timing" to r.timing.name))
    }

    fun handle(run: Run, ev: Event.RoutineControl) {
        val now = run.now
        val a = ev.action
        val rr = run.st.routine.run
        if (ev.cueId != null && rr != null && a !is RoutineAction.PromptStart && a !is RoutineAction.PromptSkipToday && ev.cueId != rr.cueId) return // stale
        when (a) {
            is RoutineAction.Start -> start(run, a.routineId, a.test, a.replaceCurrent, "RTN-1")
            is RoutineAction.PromptStart -> {
                answerPrompt(run, a.routineId)
                start(run, a.routineId, null, false, "RTN-9")
            }
            is RoutineAction.PromptSkipToday -> {
                answerPrompt(run, a.routineId)
                run.history(key(a.routineId), HistoryKind.Skipped, "RTN-9", detail = mapOf("reason" to "skip_today"))
            }
            RoutineAction.Pause -> if (rr != null && rr.status == RunStatus.Running) {
                setRun(run, rr.copy(status = RunStatus.Paused, pausedAt = now, pausedRemainingMs = rr.stepEndsAt?.let { Duration.between(now, it).toMillis().coerceAtLeast(0) }))
                run.history(key(rr.routineId), HistoryKind.RoutinePaused, "RTN-1", test = rr.test != null)
            }
            RoutineAction.Resume -> if (rr != null && rr.status == RunStatus.Paused) {
                val gap = Duration.between(rr.pausedAt ?: now, now)
                val threshold = Duration.ofMinutes(rr.routine.recovery.thresholdMin.toLong())
                if (gap > threshold && rr.routine.recovery.policy == RoutineRecoveryPolicy.Cancel) { finish(run, rr, HistoryKind.RoutineCanceled, "RTN-6"); return }
                val resumed = if (gap > threshold) {
                    // RTN-6 after a long user pause: the explicit Resume is the answer -> restart the step with full duration.
                    enter(rr.copy(status = RunStatus.Running, pausedAt = null, pausedRemainingMs = null, planShiftMs = rr.planShiftMs + gap.toMillis()), flatIndex(rr), now, cue = true)
                } else rr.copy(status = RunStatus.Running, pausedAt = null, pausedRemainingMs = null, planShiftMs = rr.planShiftMs + gap.toMillis(),
                    stepEndsAt = rr.pausedRemainingMs?.let { now.plusMillis(it) })
                setRun(run, resumed)
                run.history(key(rr.routineId), HistoryKind.RoutineResumed, if (gap > threshold) "RTN-6" else "RTN-1", test = rr.test != null)
            }
            RoutineAction.Cancel -> if (rr != null) finish(run, rr, HistoryKind.RoutineCanceled, "RTN-1")
            RoutineAction.SkipStep -> if (rr != null && rr.status == RunStatus.Running) {
                run.history(key(rr.routineId), HistoryKind.Skipped, "RTN-1", rr.cueId, mapOf("step" to rr.routine.steps[rr.stepIndex].id), test = rr.test != null)
                advanceTo(run, rr, flatIndex(rr) + 1, now)
            }
            RoutineAction.Done -> if (rr != null && rr.status == RunStatus.Running) {
                run.history(key(rr.routineId), HistoryKind.RoutineStep, "RTN-1", rr.cueId, mapOf("step" to rr.routine.steps[rr.stepIndex].id, "done" to "true"), test = rr.test != null)
                advanceTo(run, rr, flatIndex(rr) + 1, now)
            }
            RoutineAction.BackStep -> if (rr != null && rr.status == RunStatus.Running) {
                setRun(run, enter(rr, (flatIndex(rr) - 1).coerceAtLeast(0), now, cue = true))
            }
            is RoutineAction.Extend -> if (rr != null) {
                when (rr.status) {
                    RunStatus.Running -> setRun(run, rr.copy(stepEndsAt = (rr.stepEndsAt ?: now).plusMin(a.minutes), nudged = false))
                    RunStatus.Paused -> setRun(run, rr.copy(pausedRemainingMs = (rr.pausedRemainingMs ?: 0) + a.minutes * 60_000L))
                    else -> {}
                }
            }
            is RoutineAction.Recover -> if (rr != null && rr.status == RunStatus.AwaitingRecovery) {
                run.dismiss(key(rr.routineId), rr.cueId, "recovery_answered")
                when (a.choice) {
                    RecoveryChoice.Resume -> setRun(run, enter(rr.copy(status = RunStatus.Running), flatIndex(rr), now, cue = true))
                    RecoveryChoice.Restart -> setRun(run, startRun(rr.routine, run, rr.test))
                    RecoveryChoice.Cancel -> finish(run, rr, HistoryKind.RoutineCanceled, "RTN-6")
                }
            }
        }
    }

    private fun answerPrompt(run: Run, routineId: String) {
        val rs = run.st.routine
        val p = rs.openPrompt
        if (p != null && p.routineId == routineId) run.dismiss(promptKey(routineId), p.cueId, "answered")
        val date = p?.date ?: TimeMath.localDate(run.now, run.zone)
        run.st = run.st.copy(routine = rs.copy(openPrompt = if (p?.routineId == routineId) null else p, promptHandled = rs.promptHandled + (routineId to date)))
    }

    /** Move to [flat] starting at [at]; FollowSchedule skips sub-steps whose whole window passed (no cue). */
    private fun advanceTo(run: Run, rr: RoutineRun, flat0: Int, at: Instant) {
        var flat = flat0
        val subs = subSteps(rr.routine)
        if (rr.routine.timing == RoutineTimingPolicy.FollowSchedule) {
            while (flat < subs.size && durSec(rr, subs[flat].first) > 0 && !planned(rr, flat).second.isAfter(run.now)) {
                run.history(key(rr.routineId), HistoryKind.RoutineSkippedBySchedule, "RTN-1", detail = mapOf("step" to rr.routine.steps[subs[flat].first].id), test = rr.test != null)
                flat++
            }
        }
        val next = enter(rr, flat, at, cue = true)
        if (next == null) finish(run, rr, HistoryKind.RoutineCompleted, "RTN-1") else setRun(run, next)
    }

    fun evaluate(run: Run) {
        val now = run.now
        val rr0 = run.st.routine.run
        if (rr0 != null) evaluateRun(run, rr0)
        evaluatePrompts(run)
        if (run.st.routine.run?.status == RunStatus.Running) run.wake(now.plusSeconds(HEARTBEAT_SEC), WakePrecision.Inexact, "routine heartbeat")
    }

    private fun evaluateRun(run: Run, rr0: RoutineRun) {
        val now = run.now
        var rr = rr0
        if (rr.status == RunStatus.Running && run.routineRunningAtStart && run.once.add("routine-gap")) {
            // RTN-5/RTN-6: interruption = time since the engine last ran (heartbeat keeps this <= ~1 min while running).
            val gap = run.prevEvaluatedAt?.let { Duration.between(it, now) } ?: Duration.ZERO
            if (gap > Duration.ofMinutes(rr.routine.recovery.thresholdMin.toLong())) {
                when (rr.routine.recovery.policy) {
                    RoutineRecoveryPolicy.Cancel -> { finish(run, rr, HistoryKind.RoutineCanceled, "RTN-6"); return }
                    RoutineRecoveryPolicy.ResumeCurrentStep -> { rr = enter(rr, flatIndex(rr), now, cue = true)!!; setRun(run, rr) }
                    RoutineRecoveryPolicy.AskToResume -> {
                        rr = rr.copy(status = RunStatus.AwaitingRecovery, cueId = null, seq = rr.seq + 1)
                        setRun(run, rr)
                        proposeRecovery(run, rr)
                        return
                    }
                }
            }
        }
        if (rr.status == RunStatus.AwaitingRecovery) { if (rr.cueId == null) proposeRecovery(run, rr); return }
        if (rr.status != RunStatus.Running) return
        // RTN-5: Timed steps that ended (possibly during a short gap) advance; only the step we land on gets a cue.
        var guard = 0
        while (guard++ < 200) {
            val end = rr.stepEndsAt ?: break
            if (!isTimed(rr, rr.stepIndex) || end.isAfter(now)) break
            val nextFlat = flatIndex(rr) + 1
            val skippedBySchedule = rr.routine.timing == RoutineTimingPolicy.FollowSchedule && !rr.stepStartedAt.isBefore(end)
            run.history(key(rr.routineId), if (skippedBySchedule) HistoryKind.RoutineSkippedBySchedule else HistoryKind.RoutineStep, if (skippedBySchedule) "RTN-1" else "RTN-2", rr.cueId, mapOf("step" to rr.routine.steps[rr.stepIndex].id, "ended" to end.toString()), test = rr.test != null)
            val next = enter(rr, nextFlat, end, cue = true)
            if (next == null) { finish(run, rr, HistoryKind.RoutineCompleted, "RTN-1"); return }
            rr = next
        }
        setRun(run, rr)
        if (rr.cueId == null) proposeStep(run, rr, nudge = false)
        else {
            val end = rr.stepEndsAt
            if (end != null && !isTimed(rr, rr.stepIndex)) {
                if (!end.isAfter(now)) { if (!rr.nudged) proposeStep(run, rr, nudge = true) }
                else run.wake(end, WakePrecision.Exact, "routine nudge")
            } else if (end != null) run.wake(end, WakePrecision.Exact, "routine step end")
        }
    }

    private fun stepActions(rr: RoutineRun): List<CueAction> {
        val step = rr.routine.steps[rr.stepIndex]
        return listOfNotNull(
            if (step.completion == StepCompletion.Explicit || durSec(rr, rr.stepIndex) == 0L) CueAction(ActionKind.Done, Text("action.done")) else null,
            CueAction(ActionKind.Skip, Text("action.skip")),
            CueAction(ActionKind.Extend, Text("action.extend", mapOf("minutes" to "1")), 1),
            CueAction(ActionKind.Extend, Text("action.extend", mapOf("minutes" to "5")), 5),
            CueAction(ActionKind.Pause, Text("action.pause")),
        )
    }

    private fun proposeStep(run: Run, rr: RoutineRun, nudge: Boolean) {
        val step = rr.routine.steps[rr.stepIndex]
        val now = run.now
        val flat = flatIndex(rr)
        run.propose(Proposal(
            itemKey = key(rr.routineId), notificationKey = key(rr.routineId), type = CueType.RoutineStep, dueAt = now,
            cueId = if (nudge) rr.cueId else null,
            title = Text(if (nudge) "cue.routine.nudge.title" else "cue.routine.step.title", mapOf("routine" to rr.routine.name, "step" to step.name,
                "index" to (rr.stepIndex + 1).toString(), "count" to rr.routine.steps.size.toString(), "repeat" to (rr.repeatIndex + 1).toString())),
            body = Text("cue.routine.step.body", mapOf("step" to step.name, "endsAt" to (rr.stepEndsAt?.toString() ?: ""))),
            actions = stepActions(rr),
            why = WhyNow(if (nudge) "RTN-1" else "RTN-2", "why.routine.step", mapOf("routine" to rr.routineId, "step" to step.id, "flat" to flat.toString(), "timing" to rr.routine.timing.name)),
            repeatIndex = if (nudge) 1 else 0, speech = Text("speech.phrase", mapOf("text" to step.spokenText)), shortName = Text("short.routine"),
            profileId = step.cueProfileId, ongoing = true, isTest = rr.test != null,
            commit = { st, id ->
                val cur = st.routine.run
                if (cur == null || cur.routineId != rr.routineId) st
                else st.copy(routine = st.routine.copy(run = if (nudge) cur.copy(nudged = true) else cur.copy(cueId = id)))
            },
        ))
    }

    private fun proposeRecovery(run: Run, rr: RoutineRun) {
        val step = rr.routine.steps[rr.stepIndex]
        run.propose(Proposal(
            itemKey = key(rr.routineId), notificationKey = key(rr.routineId), type = CueType.RoutineStep, dueAt = run.now, cueId = null,
            title = Text("cue.routine.recover.title", mapOf("routine" to rr.routine.name, "step" to step.name)),
            body = Text("cue.routine.recover.body", mapOf("step" to step.name)),
            actions = listOf(CueAction(ActionKind.Resume, Text("action.resume")), CueAction(ActionKind.Restart, Text("action.restart")), CueAction(ActionKind.Cancel, Text("action.cancel"))),
            why = WhyNow("RTN-6", "why.routine.recover", mapOf("routine" to rr.routineId, "step" to step.id)),
            silent = true, isTest = rr.test != null,
            commit = { st, id -> st.routine.run?.let { st.copy(routine = st.routine.copy(run = it.copy(cueId = id))) } ?: st },
        ))
    }

    /** RTN-9 scheduled triggers. Scheduled routines always ask (see DOMAIN.md: background audio constraint). */
    private fun evaluatePrompts(run: Run) {
        val now = run.now
        var rs = run.st.routine
        rs.openPrompt?.let { p ->
            if (!now.isBefore(p.postedAt.plusMin(PROMPT_TIMEOUT_MIN))) {
                run.dismiss(promptKey(p.routineId), p.cueId, "timeout")
                run.history(key(p.routineId), HistoryKind.Skipped, "RTN-9", p.cueId, mapOf("reason" to "no_answer"))
                rs = rs.copy(openPrompt = null, promptHandled = rs.promptHandled + (p.routineId to p.date))
                run.st = run.st.copy(routine = rs)
            } else run.wake(p.postedAt.plusMin(PROMPT_TIMEOUT_MIN), WakePrecision.Inexact, "routine prompt timeout")
        }
        for (r in run.config.routines) {
            val t = r.trigger as? RoutineTrigger.Schedule ?: continue
            if (!r.enabled) continue
            val zone = r.travelPolicy.zone(run.zone)
            val date = TimeMath.localDate(now, zone)
            val occ = TimeMath.resolveLocal(date, t.time, zone)
            if (date.dayOfWeek in t.days && !now.isBefore(occ) && rs.promptHandled[r.id] != date && rs.openPrompt?.routineId != r.id) {
                if (now.isBefore(occ.plusMin(PROMPT_TIMEOUT_MIN)) && rs.run == null) proposePrompt(run, r, date)
                else {
                    rs = rs.copy(promptHandled = rs.promptHandled + (r.id to date))
                    run.st = run.st.copy(routine = rs)
                    run.history(key(r.id), HistoryKind.Skipped, "RTN-9", detail = mapOf("reason" to if (rs.run != null) "run_active" else "missed"))
                }
            }
            var d = date
            for (i in 0..7) {
                val o = TimeMath.resolveLocal(d, t.time, zone)
                if (d.dayOfWeek in t.days && o.isAfter(now)) { run.wake(o, WakePrecision.Exact, "routine schedule"); break }
                d = d.plusDays(1)
            }
        }
    }

    private fun proposePrompt(run: Run, r: Routine, date: java.time.LocalDate) {
        val now = run.now
        run.propose(Proposal(
            itemKey = promptKey(r.id), notificationKey = promptKey(r.id), type = CueType.RoutineStep, dueAt = now, cueId = null,
            title = Text("cue.routine.prompt.title", mapOf("routine" to r.name)), body = Text("cue.routine.prompt.body", mapOf("routine" to r.name)),
            actions = listOf(CueAction(ActionKind.Start, Text("action.start")), CueAction(ActionKind.SkipToday, Text("action.skip_today"))),
            why = WhyNow("RTN-9", "why.routine.prompt", mapOf("routine" to r.id)),
            speech = Text("speech.routine.prompt", mapOf("routine" to r.name)), shortName = Text("short.routine"),
            silent = run.quietUntil() != null, // QH-4
            commit = { st, id -> st.copy(routine = st.routine.copy(openPrompt = RoutinePrompt(r.id, date, now, id))) },
        ))
    }

    /** ALM-3 follow-on routine (user stopped the alarm, so starting audio is allowed). */
    fun afterAlarm(run: Run, routineId: String) {
        val r = run.config.routine(routineId) ?: return
        // followOnRoutine starts on Stop unless the routine explicitly asks first.
        if (r.startMode != RoutineStartMode.AskToStart) start(run, routineId, null, false, "ALM-3")
        else proposePrompt(run, r, TimeMath.localDate(run.now, run.zone))
    }
}
