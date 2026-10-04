package app.daycue.domain.engine

import app.daycue.domain.config.DayStartPolicy
import app.daycue.domain.config.DuringMeeting
import app.daycue.domain.config.FirstReminderPolicy
import app.daycue.domain.config.IntervalAnchor
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.LeaveConditionPolicy
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.ReentryPolicy
import app.daycue.domain.config.UnansweredPolicy
import app.daycue.domain.context.ContextEngine
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Instant

/** Interval habits: sunscreen, hydration, generic (PRODUCT §2–§4). */
internal object IntervalModule {

    fun key(h: IntervalHabit) = "habit:${h.id}"

    private fun Run.get(id: String) = st.intervals[id] ?: IntervalState()
    private fun Run.put(id: String, s: IntervalState) { st = st.copy(intervals = st.intervals + (id to s)) }

    private fun rule(h: IntervalHabit, sun: String, hyd: String) = if (h.kind == IntervalKind.Sunscreen) sun else hyd

    fun effectiveInterval(h: IntervalHabit, run: Run): Int =
        h.contextIntervals.firstOrNull { ContextEngine.matches(it.condition, run.ctx, run.now) != null }?.intervalMin ?: h.intervalMin

    fun handle(run: Run, ev: Event): Boolean {
        when (ev) {
            is Event.HabitAck -> {
                val h = run.config.habit(ev.habitId) as? IntervalHabit ?: return true
                val s = run.get(h.id)
                if (ev.cueId != null && ev.cueId == s.lastAckCueId) return true // duplicate broadcast
                val now = run.now
                val interval = effectiveInterval(h, run)
                val nextDue = when (h.anchor) {
                    IntervalAnchor.FromAck -> now.plusMin(interval)
                    IntervalAnchor.FromDue -> {
                        var d = s.dueAt ?: now
                        while (!d.isAfter(now)) d = d.plusMin(interval)
                        d
                    }
                }
                s.cue?.let { run.dismiss(key(h), it.cueId, "acked") }
                run.put(h.id, s.copy(lastAckAt = now, dueAt = nextDue, snoozedUntil = null, cue = null, heldDue = false, awaitingNewStretch = false,
                    lastAckCueId = ev.cueId ?: s.lastAckCueId, appliedIntervalMin = interval))
                run.history(key(h), HistoryKind.Acked, rule(h, "SUN-2", "HYD-1"), ev.cueId ?: s.cue?.cueId)
                return true
            }
            is Event.HabitSnooze -> {
                val h = run.config.habit(ev.habitId) as? IntervalHabit ?: return true
                val s = run.get(h.id)
                val cue = s.cue ?: return true
                if (ev.cueId != null && ev.cueId != cue.cueId) return true // stale
                run.dismiss(key(h), cue.cueId, "snoozed")
                run.put(h.id, s.copy(cue = null, snoozedUntil = run.now.plusMin(h.snoozeMin)))
                run.history(key(h), HistoryKind.Snoozed, "GEN-3", cue.cueId, mapOf("until" to run.now.plusMin(h.snoozeMin).toString()))
                return true
            }
            else -> return false
        }
    }

    fun evaluate(run: Run) {
        val live = run.config.intervalHabits.map { it.id }.toSet()
        // Deleted habits: retract their notification, drop state.
        (run.st.intervals.keys - live).forEach { id ->
            run.st.intervals[id]?.cue?.let { run.dismiss("habit:$id", it.cueId, "deleted") }
            run.st = run.st.copy(intervals = run.st.intervals - id)
        }
        for (h in run.config.intervalHabits) evaluateOne(run, h)
    }

    private fun evaluateOne(run: Run, h: IntervalHabit) {
        val now = run.now
        val k = key(h)
        var s = run.get(h.id)
        if (!h.enabled) {
            s.cue?.let { run.dismiss(k, it.cueId, "disabled") }
            if (s.cue != null || s.snoozedUntil != null || s.stretchStart != null) run.put(h.id, s.copy(cue = null, snoozedUntil = null, stretchStart = null, heldDue = false))
            return
        }
        val interval = effectiveInterval(h, run)
        // HYD-5: switching interval recomputes dueAt from the last ack, never earlier than now + 1 min.
        if (s.appliedIntervalMin != null && s.appliedIntervalMin != interval && s.lastAckAt != null && s.cue == null) {
            s = s.copy(dueAt = maxOf(s.lastAckAt!!.plusMin(interval), now.plusMin(1)))
        }
        s = s.copy(appliedIntervalMin = interval)

        val condSince = if (h.condition.isAny) now else ContextEngine.matches(h.condition, run.ctx, now)
        val holds = condSince != null

        // SUN-10 "until condition ends" pause.
        val p = h.pause
        if (p is PauseSpec.UntilConditionEnds && !holds && now.isAfter(p.setAt) && s.pauseEndedFor != p.setAt) s = s.copy(pauseEndedFor = p.setAt)
        val pause = run.pauseInfo(h.pause, holds, s.pauseEndedFor)

        if (!h.condition.isAny) {
            if (holds && s.stretchStart == null) s = stretchBegins(run, h, s, condSince!!, interval)
            else if (!holds && s.stretchStart != null) s = stretchEnds(run, h, s)
        } else {
            s = applyDayStart(run, h, s, interval)
        }

        // Pause retracts the visible cue (no cues while paused); due state is kept (GEN-6).
        if ((pause.paused || run.globalPause().paused) && s.cue != null) {
            run.dismiss(k, s.cue!!.cueId, "paused"); s = s.copy(cue = null, heldDue = true)
        }

        val inHours = TimeMath.inWindow(now, run.zone, h.activeHours, h.days)
        val gate = if (!inHours) Gate.Held(TimeMath.nextOpen(now, run.zone, h.activeHours, h.days), "active_hours")
        else run.softGate(pause, meetingDefer = h.duringMeeting == DuringMeeting.Defer, meetingSilent = h.duringMeeting == DuringMeeting.DeliverSilently)

        val cue = s.cue
        if (cue != null && !cue.exhausted) {
            val nextRepeat = cue.lastDeliveredAt.plusMin(h.repeat.everyMin)
            if (cue.repeatsDone < h.repeat.maxRepeats) {
                if (!nextRepeat.isAfter(run.horizon)) {
                    if ((holds || cue.ignoreCondition) && gate is Gate.Open) proposeCue(run, h, s, cue.cueId, if (nextRepeat.isAfter(now)) nextRepeat else now, cue.repeatsDone + 1, gate.silent, "GEN-4")
                    else if (gate is Gate.Held) run.wake(gate.until, WakePrecision.Exact, "gate $k")
                } else run.wake(nextRepeat, WakePrecision.Exact, "repeat $k")
            } else {
                // GEN-4 → unanswered after the last repeat interval elapses.
                if (!now.isBefore(nextRepeat)) {
                    run.history(k, HistoryKind.Unanswered, "GEN-5", cue.cueId)
                    s = when (h.unanswered) {
                        UnansweredPolicy.RollForward -> s.copy(cue = null, dueAt = (s.lastDeliveryAt ?: now).plusMin(interval))
                        UnansweredPolicy.StayDue -> s.copy(cue = null, awaitingNewStretch = true, heldDue = true)
                    }
                } else run.wake(nextRepeat, WakePrecision.Inexact, "unanswered $k")
            }
        }

        if (s.cue == null && !s.awaitingNewStretch) {
            val eff = s.snoozedUntil ?: s.dueAt
            if (eff != null) {
                if (!eff.isAfter(run.horizon)) {
                    if (!holds) {
                        // SUN-9 / SUN-4: due while the condition doesn't hold -> held due; re-entry decides.
                        if (!eff.isAfter(now)) s = s.copy(heldDue = true, snoozedUntil = null, dueAt = s.dueAt ?: eff)
                    } else when (gate) {
                        is Gate.Open -> proposeCue(run, h, s, null, if (eff.isAfter(now)) eff else now, 0, gate.silent, why(h, s))
                        is Gate.Held -> run.wake(gate.until, WakePrecision.Exact, "gate $k ${gate.reason}")
                    }
                } else run.wake(eff, WakePrecision.Exact, "due $k")
            }
        }
        run.put(h.id, s)
        if (h.condition.isAny && h.activeHours != null) run.wake(TimeMath.nextStart(now, run.zone, h.activeHours, h.days), WakePrecision.Inexact, "window $k")
        (h.pause as? PauseSpec.Until)?.let { run.wake(it.until, WakePrecision.Exact, "pause end $k") }
    }

    private fun why(h: IntervalHabit, s: IntervalState): String = when {
        s.snoozedUntil != null -> "GEN-3"
        h.kind == IntervalKind.Sunscreen -> if (s.lastAckAt == null) "SUN-8" else "SUN-3"
        else -> "HYD-1"
    }

    /** SUN-8. */
    private fun stretchBegins(run: Run, h: IntervalHabit, s0: IntervalState, since: Instant, interval: Int): IntervalState {
        val now = run.now
        // A KeepVisible cue (exhausted) is superseded by the next delivery (same notification key).
        var s = s0.copy(stretchStart = since, cue = s0.cue?.takeUnless { it.exhausted })
        val covered = s.lastAckAt?.let { now.isBefore(it.plusMin(interval)) } == true
        val held = (s.heldDue || s.awaitingNewStretch) && s.dueAt != null && !s.dueAt!!.isAfter(now)
        fun first(): IntervalState = when (val f = h.firstReminder) {
            FirstReminderPolicy.OnConditionStart -> s.copy(dueAt = since)
            is FirstReminderPolicy.AfterDelay -> s.copy(dueAt = since.plusMin(f.minutes))
            FirstReminderPolicy.AfterFullInterval -> s.copy(dueAt = since.plusMin(interval))
            FirstReminderPolicy.OnlyAfterApplied -> if (s.lastAckAt == null) s.copy(dueAt = null) else s.copy(dueAt = since)
        }
        s = when {
            covered -> s.copy(dueAt = s.dueAt?.takeIf { it.isAfter(now) } ?: s.lastAckAt!!.plusMin(interval)) // SUN-3
            held -> when (val r = h.reentry) {
                is ReentryPolicy.RemindOnReentry -> s.copy(dueAt = since.plusMin(r.graceMin), snoozedUntil = null)
                ReentryPolicy.TreatAsFirst -> first()
                ReentryPolicy.WaitNextInterval -> s.copy(dueAt = now.plusMin(interval), snoozedUntil = null)
            }
            s.snoozedUntil != null && s.snoozedUntil!!.isAfter(now) -> s
            s.dueAt != null && s.dueAt!!.isAfter(now) && s.lastAckAt != null -> s // wait for dueAt
            else -> first()
        }
        run.history(key(h), HistoryKind.ContextChanged, "SUN-8", detail = mapOf("stretch" to "start", "covered" to covered.toString(), "reentry" to held.toString()))
        return s.copy(heldDue = false, awaitingNewStretch = false)
    }

    private fun stretchEnds(run: Run, h: IntervalHabit, s0: IntervalState): IntervalState {
        var s = s0.copy(stretchStart = null)
        val cue = s.cue
        if (cue != null && !cue.ignoreCondition) {
            s = when (h.onLeaveCondition) {
                LeaveConditionPolicy.RetractAndHold -> {
                    run.dismiss(key(h), cue.cueId, "condition_lost")
                    run.history(key(h), HistoryKind.Retracted, "SUN-7", cue.cueId)
                    s.copy(cue = null, heldDue = true)
                }
                LeaveConditionPolicy.KeepVisible -> s.copy(cue = cue.copy(exhausted = true), heldDue = true)
                LeaveConditionPolicy.RemindAnyway -> s.copy(cue = cue.copy(ignoreCondition = true))
            }
        } else if (s.dueAt?.let { !it.isAfter(run.now) } == true || s.snoozedUntil != null) s = s.copy(heldDue = true)
        return s
    }

    /** HYD-3: first due of each active window follows dayStart; previous-day acks do not shift it. */
    private fun applyDayStart(run: Run, h: IntervalHabit, s: IntervalState, interval: Int): IntervalState {
        val now = run.now
        val window = h.activeHours
        if (window == null) {
            return if (s.dueAt == null) s.copy(dueAt = if (h.dayStart == DayStartPolicy.AtStart) now else now.plusMin(interval)) else s
        }
        val occ = TimeMath.windowContaining(now, run.zone, window, h.days) ?: return s
        val date = TimeMath.localDate(occ.first, run.zone)
        if (s.dayStartAppliedFor == date) return s
        val ws = occ.first
        val ackedInWindow = s.lastAckAt?.let { !it.isBefore(ws) } == true
        if (ackedInWindow) return s.copy(dayStartAppliedFor = date)
        s.cue?.let { run.dismiss(key(h), it.cueId, "new_window") }
        val due = if (h.dayStart == DayStartPolicy.AtStart) ws else ws.plusMin(interval)
        return s.copy(dayStartAppliedFor = date, dueAt = due, cue = null, snoozedUntil = null, heldDue = false, awaitingNewStretch = false)
    }

    private fun proposeCue(run: Run, h: IntervalHabit, s: IntervalState, cueId: String?, dueAt: Instant, repeat: Int, silent: Boolean, rule: String) {
        val k = key(h)
        val lang = run.config.settings.language
        val phrase = h.phrase?.get(lang) ?: run.config.profileFor(h.cueType, h.cueProfileId)?.phrase?.get(lang) ?: h.name
        val ackKind = when (h.kind) { IntervalKind.Sunscreen -> ActionKind.Applied; IntervalKind.Hydration -> ActionKind.Drank; IntervalKind.Generic -> ActionKind.Done }
        val facts = run.ctx.facts() + listOfNotNull(
            s.lastAckAt?.let { "lastAck" to it.toString() },
            s.dueAt?.let { "dueAt" to it.toString() },
            "intervalMin" to (s.appliedIntervalMin ?: h.intervalMin).toString(),
        )
        val now = run.now
        run.propose(Proposal(
            itemKey = k, notificationKey = k, type = h.cueType, dueAt = dueAt, cueId = cueId,
            title = Text("cue.${h.kind.name.lowercase()}.title", mapOf("name" to h.name)),
            body = Text("cue.habit.body", mapOf("phrase" to phrase)),
            actions = listOf(
                CueAction(ackKind, Text("action.${ackKind.name.lowercase()}")),
                CueAction(ActionKind.Snooze, Text("action.snooze", mapOf("minutes" to h.snoozeMin.toString())), h.snoozeMin),
                CueAction(ActionKind.Pause, Text("action.pause")),
            ),
            why = WhyNow(rule, "why.${h.kind.name.lowercase()}.${if (repeat > 0) "repeat" else "due"}", facts),
            repeatIndex = repeat, speech = Text("speech.phrase", mapOf("text" to phrase)),
            shortName = Text("short.${h.kind.name.lowercase()}", mapOf("name" to h.name)),
            silent = silent, profileId = h.cueProfileId, pullable = true,
            commit = { st, id ->
                val cur = st.intervals[h.id] ?: IntervalState()
                val c = if (repeat == 0) ActiveCue(id, now, now) else (cur.cue ?: ActiveCue(id, now, now)).copy(lastDeliveredAt = now, repeatsDone = repeat)
                st.copy(intervals = st.intervals + (h.id to cur.copy(cue = c, lastDeliveryAt = now, snoozedUntil = null, heldDue = false)))
            },
        ))
    }
}
