package app.daycue.domain.engine

import app.daycue.domain.config.Activity
import app.daycue.domain.config.CueType
import app.daycue.domain.config.PostureActiveWhen
import app.daycue.domain.config.PostureCycleConfig
import app.daycue.domain.config.PostureInterruptionPolicy
import app.daycue.domain.config.PostureMeetingPolicy
import app.daycue.domain.config.PostureMode
import app.daycue.domain.config.PostureTimerStartPolicy
import app.daycue.domain.context.SessionStatus
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Duration
import java.time.Instant

/** Posture cycle (PRODUCT ֲ§6). */
internal object PostureModule {
    const val KEY = "posture"

    private fun modes(pc: PostureCycleConfig) = pc.enabledModes
    private fun mode(pc: PostureCycleConfig, id: String?) = pc.modes.firstOrNull { it.id == id && it.enabled }

    /** Next enabled mode after [id] (POS-5: with one enabled mode, the same mode). */
    fun nextMode(pc: PostureCycleConfig, id: String?): PostureMode {
        val ms = modes(pc)
        val all = pc.modes
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return ms.first()
        for (i in 1..all.size) {
            val m = all[(idx + i) % all.size]
            if (m.enabled) return m
        }
        return ms.first()
    }

    private fun dur(m: PostureMode): Duration = Duration.ofMinutes(m.durationMin.toLong())

    private fun startMode(run: Run, s: PostureState, m: PostureMode, at: Instant = run.now): PostureState =
        s.copy(phase = PosturePhase.Running, modeId = m.id, modeStartedAt = at, modeEndsAt = at.plus(dur(m)), remainingMs = null,
            pendingModeId = null, snoozedUntil = null, interruptedAt = null, resumePhase = null)

    private fun clearCue(run: Run, s: PostureState, reason: String): PostureState {
        s.cue?.let { run.dismiss(KEY, it.cueId, reason) }
        return s.copy(cue = null)
    }

    /** POS-2 / POS-6 / meeting Freeze / quiet hours (P7 frozen) / pause. */
    private fun shouldRun(run: Run, pc: PostureCycleConfig, s: PostureState): Boolean {
        val base = when (val a = pc.activeWhen) {
            PostureActiveWhen.DuringSessions -> run.ctx.session?.status == SessionStatus.Active
            is PostureActiveWhen.ActiveHours -> TimeMath.inWindow(run.now, run.zone, a.window, a.days)
            PostureActiveWhen.ManualOnly -> s.manualStarted
        }
        if (!base) return false
        if (run.ctx.activity.value == Activity.Inactive) return false
        if (run.pauseInfo(pc.pause).paused || run.globalPause().paused) return false
        if (run.quietUntil() != null) return false
        if (pc.duringMeeting == PostureMeetingPolicy.Freeze && run.ctx.inMeeting) return false
        return true
    }

    private fun interrupt(s: PostureState, now: Instant, to: PosturePhase): PostureState {
        val remaining = when (s.phase) {
            PosturePhase.Running -> s.modeEndsAt?.let { Duration.between(now, it).toMillis().coerceAtLeast(0) } ?: 0
            else -> s.remainingMs ?: 0
        }
        val resume = if (s.phase == PosturePhase.Running || s.phase == PosturePhase.SwitchPending) s.phase else s.resumePhase ?: PosturePhase.Running
        return s.copy(phase = to, remainingMs = remaining, interruptedAt = s.interruptedAt ?: now, resumePhase = resume, modeEndsAt = null, snoozedUntil = null)
    }

    /** POS-6 for automatic freezes; a manual pause ([manual]) always continues the remaining time (POS-4). */
    private fun resume(run: Run, pc: PostureCycleConfig, s: PostureState, manual: Boolean = false): PostureState {
        val now = run.now
        val gap = Duration.between(s.interruptedAt ?: now, now)
        val short = manual || gap <= Duration.ofMinutes(pc.shortInterruptionMin.toLong())
        val current = mode(pc, s.modeId) ?: modes(pc).first()
        val policy = if (short) PostureInterruptionPolicy.ContinueRemaining else pc.longInterruption
        val base = s.copy(interruptedAt = null, resumePhase = null)
        return when (policy) {
            PostureInterruptionPolicy.ResetToFirst -> startMode(run, base, modes(pc).first())
            PostureInterruptionPolicy.RestartCurrent -> startMode(run, base, current)
            PostureInterruptionPolicy.ContinueRemaining ->
                if (s.resumePhase == PosturePhase.SwitchPending) base.copy(phase = PosturePhase.SwitchPending, remainingMs = null, cue = null)
                else base.copy(phase = PosturePhase.Running, modeId = current.id, modeEndsAt = now.plusMillis(s.remainingMs ?: 0), remainingMs = null)
        }
    }

    fun handle(run: Run, ev: Event.PostureControl) {
        val pc = run.config.postureCycle
        var s = run.st.posture
        if (!pc.enabled || modes(pc).isEmpty()) return
        val fromCue = ev.cueId != null
        if (fromCue && ev.cueId != s.cue?.cueId) return // stale notification action
        val now = run.now
        val before = s
        s = when (ev.action) {
            PostureAction.Start -> s.copy(manualStarted = true)
            PostureAction.Stop -> clearCue(run, PostureState(seq = s.seq), "stopped")
            PostureAction.Switched -> when (s.phase) {
                PosturePhase.SwitchPending -> clearCue(run, startMode(run, s, mode(pc, s.pendingModeId) ?: nextMode(pc, s.modeId)), "switched")
                else -> clearCue(run, s, "switched")
            }
            PostureAction.Snooze -> when {
                s.phase == PosturePhase.SwitchPending -> clearCue(run, s.copy(phase = PosturePhase.Running, pendingModeId = null, modeEndsAt = now.plusMin(pc.snoozeMin), snoozedUntil = now.plusMin(pc.snoozeMin)), "snoozed")
                pc.timerStart == PostureTimerStartPolicy.AtCue && s.cue != null && s.previousModeId != null ->
                    clearCue(run, s.copy(modeId = s.previousModeId, previousModeId = null, modeEndsAt = now.plusMin(pc.snoozeMin), snoozedUntil = now.plusMin(pc.snoozeMin)), "snoozed")
                else -> s
            }
            PostureAction.Skip -> when (s.phase) {
                PosturePhase.SwitchPending -> clearCue(run, startMode(run, s, nextMode(pc, s.pendingModeId ?: s.modeId)), "skipped")
                PosturePhase.Running -> clearCue(run, startMode(run, s, nextMode(pc, s.modeId)), "skipped")
                else -> s
            }
            PostureAction.Extend5, PostureAction.Extend10, PostureAction.Extend15 -> {
                val m = when (ev.action) { PostureAction.Extend5 -> 5; PostureAction.Extend10 -> 10; else -> 15 }
                when (s.phase) {
                    PosturePhase.SwitchPending -> clearCue(run, s.copy(phase = PosturePhase.Running, pendingModeId = null, modeEndsAt = now.plusMin(m)), "extended")
                    // POS-4 "extend current": +m from the planned end, or from now when that end has already passed
                    // (an overdue mode must not stay overdue after Extend).
                    PosturePhase.Running -> s.copy(modeEndsAt = maxOf(s.snoozedUntil ?: s.modeEndsAt ?: now, now).plusMin(m), snoozedUntil = null)
                    PosturePhase.Paused, PosturePhase.Frozen -> s.copy(remainingMs = (s.remainingMs ?: 0) + m * 60_000L)
                    PosturePhase.Off -> s
                }
            }
            PostureAction.SwitchNow -> if (s.phase == PosturePhase.Off) s else clearCue(run, startMode(run, s, if (s.phase == PosturePhase.SwitchPending) mode(pc, s.pendingModeId) ?: nextMode(pc, s.modeId) else nextMode(pc, s.modeId)), "switch_now")
            PostureAction.Pause -> if (s.phase == PosturePhase.Running || s.phase == PosturePhase.SwitchPending || s.phase == PosturePhase.Frozen) clearCue(run, interrupt(s, now, PosturePhase.Paused), "paused") else s
            // POS-4 manual pause keeps position and remaining time for as long as it lasts; POS-6 (shortInterruption /
            // longInterruption) governs only automatic freezes. Resuming while the cycle may not run turns into a freeze
            // that starts now.
            PostureAction.Resume -> if (s.phase == PosturePhase.Paused) {
                if (shouldRun(run, pc, s)) resume(run, pc, s, manual = true) else s.copy(phase = PosturePhase.Frozen, interruptedAt = now)
            } else s
            PostureAction.Reset -> {
                val first = modes(pc).first()
                if (s.phase == PosturePhase.Off) s
                else if (s.phase == PosturePhase.Running || s.phase == PosturePhase.SwitchPending) clearCue(run, startMode(run, s, first), "reset")
                else s.copy(modeId = first.id, remainingMs = dur(first).toMillis(), resumePhase = PosturePhase.Running, interruptedAt = now)
            }
        }
        run.st = run.st.copy(posture = s)
        if (s != before) run.history(KEY, HistoryKind.Acked, if (ev.action == PostureAction.Pause || ev.action == PostureAction.Resume) "POS-4" else "POS-3",
            ev.cueId, mapOf("action" to ev.action.name, "mode" to (s.modeId ?: "")))
    }

    fun evaluate(run: Run) {
        val pc = run.config.postureCycle
        var s = run.st.posture
        val now = run.now
        if (!pc.enabled || modes(pc).isEmpty()) {
            if (s.phase != PosturePhase.Off) run.st = run.st.copy(posture = clearCue(run, PostureState(seq = s.seq), "disabled"))
            return
        }
        // Config edits: a removed/disabled current mode moves on to the next enabled one.
        if (s.modeId != null && mode(pc, s.modeId) == null && s.phase != PosturePhase.Off) {
            s = if (s.phase == PosturePhase.Running) startMode(run, s, nextMode(pc, s.modeId)) else s.copy(modeId = nextMode(pc, s.modeId).id)
        }
        val runOk = shouldRun(run, pc, s)
        s = when (s.phase) {
            PosturePhase.Off -> if (runOk) startMode(run, s, modes(pc).first()) else s
            PosturePhase.Running, PosturePhase.SwitchPending -> if (!runOk) clearCue(run, interrupt(s, now, PosturePhase.Frozen), "frozen") else s
            PosturePhase.Frozen -> if (runOk) resume(run, pc, s) else s
            PosturePhase.Paused -> s
        }

        val meetingDefer = pc.duringMeeting == PostureMeetingPolicy.DeferCue && run.ctx.inMeeting
        val held = meetingDefer || run.routineActive
        when (s.phase) {
            PosturePhase.Running -> {
                val end = s.modeEndsAt
                val cue = s.cue
                if (end != null && (cue == null || pc.timerStart == PostureTimerStartPolicy.AtCue)) {
                    if (!end.isAfter(run.horizon)) {
                        if (held) run.wake(if (meetingDefer) run.ctx.meetingEndsAt else null, WakePrecision.Exact, "posture deferred")
                        else propose(run, pc, s, null, 0, if (end.isAfter(now)) end else now)
                    } else run.wake(end, WakePrecision.Exact, "posture mode end")
                }
            }
            PosturePhase.SwitchPending -> {
                val cue = s.cue
                if (cue == null) { if (!held) propose(run, pc, s, null, 0, now) }
                else if (!cue.exhausted) {
                    val at = cue.lastDeliveredAt.plusMin(pc.confirmRepeat.everyMin)
                    if (cue.repeatsDone < pc.confirmRepeat.maxRepeats) {
                        if (!at.isAfter(now)) { if (!held) propose(run, pc, s, cue.cueId, cue.repeatsDone + 1, now) }
                        else run.wake(at, WakePrecision.Exact, "posture repeat")
                    } else {
                        // POS-8: after the repeats, wait silently; the pending switch stays visible in-app.
                        s = s.copy(cue = cue.copy(exhausted = true))
                    }
                }
            }
            else -> {}
        }
        if (pc.activeWhen is PostureActiveWhen.ActiveHours) {
            val a = pc.activeWhen
            run.wake(TimeMath.currentWindowEnd(now, run.zone, a.window, a.days) ?: TimeMath.nextOpen(now, run.zone, a.window, a.days), WakePrecision.Inexact, "posture window")
        }
        run.quietUntil()?.let { run.wake(it, WakePrecision.Inexact, "posture quiet end") }
        run.st = run.st.copy(posture = s)
    }

    private fun propose(run: Run, pc: PostureCycleConfig, s: PostureState, cueId: String?, repeat: Int, dueAt: Instant) {
        val lang = run.config.settings.language
        val next = if (s.phase == PosturePhase.SwitchPending) mode(pc, s.pendingModeId) ?: nextMode(pc, s.modeId) else nextMode(pc, s.modeId)
        val single = modes(pc).size == 1
        val phrase = if (single) null else next.phrase?.get(lang)
        val now = run.now
        val atCue = pc.timerStart == PostureTimerStartPolicy.AtCue
        run.propose(Proposal(
            itemKey = KEY, notificationKey = KEY, type = CueType.Posture, dueAt = dueAt, cueId = cueId,
            title = Text(if (single) "cue.posture.keep_going.title" else "cue.posture.switch.title", mapOf("mode" to next.name.get(lang))),
            body = Text("cue.habit.body", mapOf("phrase" to (phrase ?: next.name.get(lang)))),
            // Product decision: notification actions are Switched / Snooze / +5 min; Skip is in-app only.
            actions = listOf(
                CueAction(ActionKind.Switched, Text("action.switched")),
                CueAction(ActionKind.Snooze, Text("action.snooze", mapOf("minutes" to pc.snoozeMin.toString())), pc.snoozeMin),
                CueAction(ActionKind.Extend, Text("action.extend", mapOf("minutes" to "5")), 5),
            ),
            why = WhyNow(if (single) "POS-5" else "POS-3", "why.posture", run.ctx.facts() + mapOf("mode" to (s.modeId ?: ""), "next" to next.id, "timerStart" to pc.timerStart.name)),
            repeatIndex = repeat, speech = Text("speech.phrase", mapOf("text" to (phrase ?: next.name.get(lang)))), shortName = Text("short.posture"),
            profileId = pc.cueProfileId, pullable = true,
            commit = { st, id ->
                val cur = st.posture
                val c = if (repeat == 0) ActiveCue(id, now, now) else (cur.cue ?: ActiveCue(id, now, now)).copy(lastDeliveredAt = now, repeatsDone = repeat)
                val ns = if (repeat > 0) cur.copy(cue = c)
                else if (atCue && cur.phase == PosturePhase.Running) cur.copy(cue = c, previousModeId = cur.modeId, modeId = next.id, modeStartedAt = now,
                    modeEndsAt = now.plus(dur(next)), snoozedUntil = null)
                else cur.copy(cue = c, phase = PosturePhase.SwitchPending, pendingModeId = next.id, snoozedUntil = null)
                st.copy(posture = ns)
            },
        ))
    }
}
