package app.daycue.domain.engine

import app.daycue.domain.Clock
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.SessionKind
import app.daycue.domain.context.ContextEngine
import app.daycue.domain.context.DetectionPause
import app.daycue.domain.context.EnvOverride
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.context.PlaceOverride
import app.daycue.domain.context.PlaceTrack
import app.daycue.domain.context.PlaceValue
import app.daycue.domain.context.Session
import app.daycue.domain.context.SessionNote
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.config.CueType
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Instant

data class Reduction(val state: EngineState, val effects: List<Effect>)

/**
 * The deterministic rules engine (ARCHITECTURE §3.1). Pure: no I/O, all time from [Clock].
 *
 * Every reduce: (1) recovery detection, (2) event-specific mutation, (3) context advance + inference,
 * (4) every item proposes cues, (5) the delivery policy groups/sends them and items commit, (6) one
 * `nextWakeAt` is computed and, if it changed, a `ScheduleWake` / `CancelWake` effect is emitted.
 */
object Engine {

    fun reduce(config: DayCueConfig, state: EngineState, event: Event, clock: Clock): Reduction {
        val run = Run(config, state, clock.now(), clock.zone(), clock.elapsedRealtime().toMillis(), event)
        val prevWake = state.nextWakeAt to state.nextWakePrecision

        if (run.isReboot) onReboot(run)

        // (2a) raw signals before context advance
        if (event is Event.SignalObserved) {
            BottleModule.onSignalBeforeApply(run, event.signal)
            run.st = run.st.copy(context = ContextEngine.applySignal(run.st.context, event.signal, config, run.now))
        }
        advanceContext(run)
        // (2b) actions
        handleEvent(run, event)
        advanceContext(run)

        // (4)+(5) evaluate; loop a few passes so state transitions settle at `now`.
        // The last (non-delivering) pass leaves the authoritative wake list.
        var passes = 0
        while (true) {
            run.wakes.clear()
            val before = run.st
            evaluateItems(run)
            val delivered = Delivery.deliver(run)
            passes++
            if ((!delivered && run.st == before) || passes >= 8) break
            advanceContext(run)
        }
        ContextEngine.wakeTimes(run.st.context, config, run.now, run.zone).forEach { (at, userFacing) ->
            run.wake(at, if (userFacing) WakePrecision.Exact else WakePrecision.Inexact, "context")
        }
        CalendarModule.meetingWakes(run)

        dropSupersededReposts(run)
        val chosen = chooseWake(run)
        run.st = run.st.copy(
            lastEvaluatedAt = run.now, lastElapsedRealtimeMs = run.elapsedMs, lastZone = run.zone, configVersion = config.version,
            nextWakeAt = chosen?.at, nextWakePrecision = chosen?.precision, nextWakeReason = chosen?.reason,
        )
        if (prevWake != (chosen?.at to chosen?.precision)) {
            run.effects += if (chosen == null) Effect.CancelWake else Effect.ScheduleWake(chosen.at, chosen.precision, chosen.reason)
        }
        return Reduction(run.st, run.effects.toList())
    }

    /**
     * One alarm only (ADR-0002). The earliest wake wins; an Inexact housekeeping wake that falls shortly
     * before a stronger one is dropped (the stronger one recomputes everything anyway), and an Inexact
     * earliest wake is upgraded to Exact when a stronger wake follows within an hour, so a late inexact
     * delivery can't push a medication or alarm late.
     */
    private fun chooseWake(run: Run): Wake? {
        val ws = run.wakes.filter { it.at.isAfter(run.now) }.sortedBy { it.at }
        if (ws.isEmpty()) return null
        val strong = ws.firstOrNull { it.precision != WakePrecision.Inexact }
        val first = ws.first()
        if (first.precision != WakePrecision.Inexact || strong == null) {
            val strongestAtSameTime = ws.filter { it.at == first.at }.minBy { it.precision.ordinal }
            return strongestAtSameTime
        }
        if (!first.at.plusMin(5).isBefore(strong.at)) return strong
        return if (first.at.plusMin(60).isAfter(strong.at)) Wake(first.at, WakePrecision.Exact, first.reason) else first
    }

    private fun onReboot(run: Run) {
        // After boot Place is Unknown until the OS initial trigger (PRODUCT §1.1).
        val cs = run.st.context
        run.st = run.st.copy(context = cs.copy(geofenceKnown = false, rawInside = emptyMap(), rawExitAt = emptyMap(),
            place = if (cs.place.value.kind == PlaceKind.Unknown) cs.place else PlaceTrack(PlaceValue.UNKNOWN, run.now)))
        run.history("engine", HistoryKind.ContextChanged, "GEN-7", detail = mapOf("reboot" to "true"))
        // Notifications never survive a reboot (VALIDATION D1): everything the state lists as visible is gone.
        lostNotifications(run, run.st.delivery.visible.keys.toList(), reboot = true)
    }

    /**
     * The platform no longer shows these notifications (reboot, force stop, notification reset) while the state still lists
     * them as visible. Re-post each one quietly with its last content and the same cue id, so its buttons keep working and
     * nothing alerts twice (GEN-7). On a reboot, medication notifications are instead re-delivered by MED-8 (one merged cue
     * for two or more of today's due doses, a normal re-delivery for one), so the merged state is reset here.
     * A visible entry without stored content (state from an older build) is dropped from the visible set.
     */
    /** A quiet re-post followed in the same reduce by a new delivery or a dismissal of that notification is pointless: drop it. */
    private fun dropSupersededReposts(run: Run) {
        for ((key, deliver, row) in run.reposts) {
            val idx = run.effects.indexOf(deliver)
            val later = run.effects.drop(idx + 1).any { (it is Effect.Deliver && it.cue.notificationKey == key) || (it is Effect.DismissCue && it.notificationKey == key) }
            if (later) { run.effects.remove(deliver); run.effects.remove(row) }
        }
    }

    internal fun lostNotifications(run: Run, keys: Collection<String>, reboot: Boolean) {
        if (keys.isEmpty()) return
        var vis = run.st.delivery.visible
        for (k in keys) {
            val v = vis[k] ?: continue
            val cue = v.cue
            if ((reboot && v.type == CueType.Medication) || cue == null) {
                vis = vis - k
                continue
            }
            val deliver = Effect.Deliver(cue.copy(silent = true, soundId = null, vibrationId = null, speech = null, groupKey = null, groupLead = true, fullScreen = false))
            val row = Effect.RecordHistory(HistoryEntry(run.now, v.itemKey, HistoryKind.Reposted, "GEN-7", v.cueId, mapOf("reason" to if (reboot) "reboot" else "not_shown")))
            run.effects += deliver; run.effects += row
            run.reposts += Triple(k, deliver, row)
        }
        run.st = run.st.copy(delivery = run.st.delivery.copy(visible = vis))
        if (reboot) {
            val ms = run.st.medication
            run.st = run.st.copy(medication = ms.copy(mergedCue = null, slots = ms.slots.mapValues { (_, s) -> if (s.mergedCueId == null) s else s.copy(mergedCueId = null) }))
        }
    }

    internal fun advanceContext(run: Run) {
        val meeting = CalendarModule.meetingNow(run)
        val adv = ContextEngine.advance(run.st.context, run.config, run.now, run.zone, meeting != null)
        run.st = run.st.copy(context = adv.state)
        adv.notes.forEach { SessionModule.onNote(run, it) }
        run.ctx = ContextEngine.infer(run.st.context, run.config, run.now, run.routineActive, meeting?.key, meeting?.end)
    }

    private fun evaluateItems(run: Run) {
        IntervalModule.evaluate(run)
        BottleModule.evaluate(run)
        PostureModule.evaluate(run)
        MedicationModule.evaluate(run)
        RoutineModule.evaluate(run)
        AlarmModule.evaluate(run)
        CalendarModule.evaluate(run)
        SessionModule.evaluate(run)
    }

    private fun handleEvent(run: Run, ev: Event) {
        val now = run.now
        val cs = run.st.context
        when (ev) {
            Event.Tick, Event.BootCompleted, Event.TimeChanged, Event.TimezoneChanged, Event.ConfigChanged, is Event.SignalObserved -> {}
            is Event.OverrideEnvironment -> {
                val cap = run.config.contextRules.environmentOverrideCapMin
                val exp = ContextEngine.overrideExpiry(ev.duration, now, cap, run.zone, run.config)
                run.st = run.st.copy(context = cs.copy(envOverride = EnvOverride(ev.value, now, exp, ev.duration == OverrideDuration.UntilTransition)))
                run.history("context", HistoryKind.ContextChanged, "CTX-1", detail = mapOf("override" to ev.value.name, "until" to exp.toString()))
            }
            Event.ClearEnvironmentOverride -> run.st = run.st.copy(context = cs.copy(envOverride = null))
            is Event.OverridePlace -> {
                val value = if (ev.placeId == null) PlaceValue.ELSEWHERE else PlaceValue.saved(ev.placeId)
                if (ev.placeId != null && run.config.place(ev.placeId) == null) {
                    run.history("context", HistoryKind.ContextChanged, "CTX-1", detail = mapOf("placeOverride" to "ignored", "reason" to "unknown_place"))
                } else {
                    val cap = run.config.contextRules.environmentOverrideCapMin
                    val exp = ContextEngine.overrideExpiry(ev.duration, now, cap, run.zone, run.config)
                    run.st = run.st.copy(context = cs.copy(placeOverride = PlaceOverride(value, now, exp, ev.duration == OverrideDuration.UntilTransition)))
                    run.history("context", HistoryKind.ContextChanged, "CTX-1", detail = mapOf("placeOverride" to value.toString(), "until" to exp.toString()))
                }
            }
            Event.ClearPlaceOverride -> if (cs.placeOverride != null) {
                run.st = run.st.copy(context = cs.copy(placeOverride = null))
                run.history("context", HistoryKind.ContextChanged, "CTX-1", detail = mapOf("placeOverride" to "cleared"))
            }
            is Event.NotificationsObserved -> lostNotifications(run, run.st.delivery.visible.keys - ev.shown, reboot = false)
            is Event.StartSession -> {
                val capMin = run.config.contextRules.sessions.manualSessionCapMin
                val cap = ContextEngine.overrideExpiry(ev.duration, now, capMin, run.zone, run.config)
                val placeId = cs.effectivePlace.value.takeIf { it.kind == PlaceKind.Saved }?.placeId
                run.st = run.st.copy(context = cs.copy(session = Session(ev.kind, placeId, manual = true, startedAt = now, statusSince = now, capAt = cap)))
                run.history("session", HistoryKind.SessionStarted, "WRK-7", detail = mapOf("kind" to ev.kind.name, "manual" to "true"))
            }
            Event.EndSession -> if (cs.session != null) {
                run.st = run.st.copy(context = cs.copy(session = null))
                run.history("session", HistoryKind.SessionEnded, "WRK-4", detail = mapOf("reason" to "end_session"))
            }
            is Event.SessionPromptAnswer -> SessionModule.answer(run, ev)
            is Event.PauseAutoDetection -> {
                val until = when (val d = ev.duration) {
                    is OverrideDuration.For -> now.plusMin(d.minutes)
                    OverrideDuration.RestOfToday -> TimeMath.nextDayBoundary(now, run.zone, run.config.settings.dayStartsAt)
                    else -> null
                }
                run.st = run.st.copy(context = cs.copy(detectionPause = DetectionPause(now, until)))
                run.history("context", HistoryKind.Paused, "§1.4", detail = mapOf("detection" to "paused", "until" to until.toString()))
            }
            Event.ResumeAutoDetection -> run.st = run.st.copy(context = cs.copy(detectionPause = null))
            Event.LeavingNow -> BottleModule.leavingNow(run)
            is Event.HabitAck, is Event.HabitSnooze -> IntervalModule.handle(run, ev)
            is Event.Pause -> {
                val spec = pauseSpec(run, ev.target, ev.choice)
                run.effects += Effect.ApplyConfigOps(listOf(ConfigOp.SetPause(ev.target, spec)), "pause")
                run.history(pauseKey(ev.target), HistoryKind.Paused, "SUN-10", ev.cueId, mapOf("spec" to spec.toString()))
            }
            is Event.Resume -> {
                run.effects += Effect.ApplyConfigOps(listOf(ConfigOp.SetPause(ev.target, null)), "resume")
                run.history(pauseKey(ev.target), HistoryKind.Resumed, "SUN-10")
            }
            is Event.BottleAck -> BottleModule.ack(run, ev)
            is Event.MedicationTaken, is Event.MedicationSnooze, is Event.MedicationSkip, is Event.MedicationCorrect -> MedicationModule.handle(run, ev)
            is Event.PostureControl -> PostureModule.handle(run, ev)
            is Event.RoutineControl -> RoutineModule.handle(run, ev)
            is Event.AlarmControl -> AlarmModule.handle(run, ev)
            is Event.CalendarSynced, is Event.CalendarAck, is Event.CalendarSnooze -> CalendarModule.handle(run, ev)
            is Event.CueDismissed -> {
                val v = run.st.delivery.visible.values.firstOrNull { it.cueId == ev.cueId }
                run.history(v?.itemKey ?: "unknown", HistoryKind.Dismissed, "GEN-1", ev.cueId)
                if (v != null) run.st = run.st.copy(delivery = run.st.delivery.copy(visible = run.st.delivery.visible - v.notificationKey))
            }
            is Event.SpeechFinished -> run.history("speech", HistoryKind.SpeechFinished, "SPK-1", ev.cueId)
            is Event.SpeechFailed -> {
                run.st = run.st.copy(readiness = run.st.readiness.copy(speechFailedAt = now, speechFailureReason = ev.reason))
                run.history("speech", HistoryKind.SpeechFailed, "SPK-3", ev.cueId, mapOf("reason" to ev.reason))
            }
            is Event.PreviewCue -> preview(run, ev.habitOrType)
        }
    }

    private fun pauseKey(t: PauseTarget) = when (t) { is PauseTarget.Habit -> "habit:${t.habitId}"; PauseTarget.Posture -> "posture"; PauseTarget.All -> "all" }

    internal fun pauseSpec(run: Run, target: PauseTarget, choice: PauseChoice): PauseSpec {
        val now = run.now
        return when (choice) {
            is PauseChoice.For -> PauseSpec.Until(now, now.plusMin(choice.minutes))
            is PauseChoice.Until -> PauseSpec.Until(now, choice.at)
            PauseChoice.RestOfToday -> PauseSpec.Until(now, TimeMath.nextDayBoundary(now, run.zone, run.config.settings.dayStartsAt))
            PauseChoice.Indefinite -> PauseSpec.Indefinite(now)
            PauseChoice.UntilConditionEnds -> PauseSpec.UntilConditionEnds(now, TimeMath.nextDayBoundary(now, run.zone, run.config.settings.dayStartsAt))
        }
    }

    /** GEN-10: preview a cue profile; never touches item state or counts. */
    private fun preview(run: Run, what: String) {
        val type = CueType.entries.firstOrNull { it.name.equals(what, true) }
            ?: (run.config.habit(what) as? app.daycue.domain.config.IntervalHabit)?.cueType ?: CueType.Habit
        val profile = run.config.profileFor(type)
        val lang = run.config.settings.language
        val phrase = profile?.phrase?.get(lang) ?: ""
        val cue = Cue(
            id = "preview:${type.name}#${run.now.toEpochMilli()}", notificationKey = "preview", itemKey = "preview:${type.name}", type = type,
            priority = type.priority, channelId = type.channelId, title = Text("cue.test.title", mapOf("inner" to "cue.${type.name.lowercase()}.title")),
            body = Text("cue.habit.body", mapOf("phrase" to phrase)), actions = emptyList(), lockScreen = LockScreenVisibility.Private,
            soundId = profile?.soundId, vibrationId = profile?.vibrationId, speech = if (profile?.speechEnabled == true && phrase.isNotBlank()) SpeechRequest(
                profile.language ?: lang, Text("speech.phrase", mapOf("text" to phrase)), overMedia = run.config.settings.speech.overMedia,
                output = run.config.settings.speech.output, createdAt = run.now, dropAfter = run.now.plusSeconds(run.config.settings.collision.speechMaxAgeSec.toLong())) else null,
            isTest = true, why = WhyNow("GEN-10", "why.preview"), dueAt = run.now, deliveredAt = run.now,
        )
        run.effects += Effect.Deliver(cue)
        run.history(cue.itemKey, HistoryKind.Test, "GEN-10", cue.id, test = true)
    }

    @Suppress("unused")
    private fun unusedKind(k: SessionKind) = k
}
