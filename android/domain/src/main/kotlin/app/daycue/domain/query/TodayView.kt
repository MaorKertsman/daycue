@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, LocalTimeSerializer::class)

package app.daycue.domain.query

import app.daycue.domain.Clock
import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DuringMeeting
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.context.ContextEngine
import app.daycue.domain.context.InferredContext
import app.daycue.domain.context.SessionStatus
import app.daycue.domain.engine.AlarmState
import app.daycue.domain.engine.CalendarRules
import app.daycue.domain.engine.Engine
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.PosturePhase
import app.daycue.domain.engine.RunStatus
import app.daycue.domain.engine.SlotRef
import app.daycue.domain.engine.SlotStatus
import app.daycue.domain.time.InstantSerializer
import app.daycue.domain.time.LocalDateSerializer
import app.daycue.domain.time.LocalTimeSerializer
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/** UX "Next rows" waiting reasons (docs/design/UX.md): shown instead of a time. */
@Serializable
enum class WaitingReason { WhenConditionHolds, PausedUntil, AfterQuietHours, AfterMeeting, AfterRoutine, OutsideActiveHours, CoveredUntil, AfterFirstAck, Frozen, Pending }

@Serializable
data class UpcomingItem(
    val itemKey: String,
    val type: CueType,
    val name: String,
    /** When it will cue, if known. */
    val at: Instant? = null,
    val waiting: WaitingReason? = null,
    val waitingUntil: Instant? = null,
    /** Rule ID behind the timing ("Why now?" / "why later"). */
    val rule: String,
    val facts: Map<String, String> = emptyMap(),
)

@Serializable
data class ActiveItem(val itemKey: String, val kind: String, val detail: Map<String, String> = emptyMap())

/** Dose status words (UX StatusText): Upcoming, Due, Taken, Skipped, Not confirmed. */
@Serializable enum class DoseStatus { Upcoming, Due, Taken, Skipped, NotConfirmed }

@Serializable
data class DoseView(val slot: SlotRef, val label: String, val dueAt: Instant, val status: DoseStatus, val takenAt: Instant? = null, val cueId: String? = null)

@Serializable
data class TodayView(
    val now: Instant,
    val context: InferredContext,
    val active: List<ActiveItem>,
    val upcoming: List<UpcomingItem>,
    val doses: List<DoseView>,
    val nextWakeAt: Instant?,
)

/** Read-only query helpers for the UI. Pure; they never change persisted state. */
object Queries {

    fun todayView(config: DayCueConfig, state: EngineState, clock: Clock, limit: Int = 20): TodayView {
        // Advance a copy to `now` so the view reflects confirmations/expiries that happened since the last reduce.
        val st = Engine.reduce(config, state, Event.Tick, clock).state
        val now = clock.now(); val zone = clock.zone()
        val meeting = st.calendar.events.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) && CalendarRules.isMeeting(config.calendarRules, it) }
        val routineRunning = st.routine.run?.let { it.status == RunStatus.Running && it.test == null } == true
        val ctx = ContextEngine.infer(st.context, config, now, routineRunning, meeting?.key, meeting?.end)
        val quietEnd = config.settings.quietHours.takeIf { it.enabled }?.windows?.mapNotNull { TimeMath.windowContaining(now, zone, it.window, it.days)?.second }?.maxOrNull()
        val pauseAllUntil = (config.settings.pauseAll as? PauseSpec.Until)?.until?.takeIf { now.isBefore(it) }

        val active = mutableListOf<ActiveItem>()
        val up = mutableListOf<UpcomingItem>()

        st.context.session?.let { active += ActiveItem("session", "session", mapOf("kind" to it.kind.name, "status" to it.status.name, "manual" to it.manual.toString(), "since" to it.startedAt.toString())) }
        st.routine.run?.let { r -> active += ActiveItem("routine:${r.routineId}", "routine", mapOf("step" to r.routine.steps[r.stepIndex].name, "status" to r.status.name, "endsAt" to (r.stepEndsAt?.toString() ?: ""), "test" to (r.test != null).toString())) }
        st.alarms.forEach { (id, a: AlarmState) -> a.ring?.let { active += ActiveItem("alarm:$id", "alarm", mapOf("snoozedUntil" to (it.snoozedUntil?.toString() ?: ""), "snoozes" to it.snoozeCount.toString())) } }
        st.delivery.visible.values.forEach { active += ActiveItem(it.itemKey, "cue", mapOf("cueId" to it.cueId, "type" to it.type.name, "deliveredAt" to it.deliveredAt.toString())) }

        // Interval habits
        for (h in config.intervalHabits.filter { it.enabled }) {
            val s = st.intervals[h.id]
            val k = "habit:${h.id}"
            val at = s?.snoozedUntil ?: s?.dueAt
            val holds = h.condition.isAny || ContextEngine.matches(h.condition, ctx, now) != null
            val hPause = (h.pause as? PauseSpec.Until)?.until?.takeIf { now.isBefore(it) } ?: pauseAllUntil
            val covered = s?.lastAckAt?.plusMin(h.intervalMin)?.takeIf { now.isBefore(it) }
            val nextOpen = TimeMath.nextOpen(maxOf(now, at ?: now), zone, h.activeHours, h.days)
            val facts = mapOf("lastAck" to (s?.lastAckAt?.toString() ?: ""))
            up += when {
                h.pause != null && (hPause != null || h.pause !is PauseSpec.Until) -> UpcomingItem(k, h.cueType, h.name, waiting = WaitingReason.PausedUntil, waitingUntil = hPause, rule = "SUN-10", facts = facts)
                pauseAllUntil != null -> UpcomingItem(k, h.cueType, h.name, waiting = WaitingReason.PausedUntil, waitingUntil = pauseAllUntil, rule = "GEN-6", facts = facts)
                s?.cue != null -> UpcomingItem(k, h.cueType, h.name, at = s.cue.lastDeliveredAt, waiting = WaitingReason.Pending, rule = "GEN-4", facts = facts)
                !holds || s?.awaitingNewStretch == true -> UpcomingItem(k, h.cueType, h.name, waiting = WaitingReason.WhenConditionHolds, rule = "SUN-4", facts = facts + ("condition" to h.condition.toString()) + (covered?.let { mapOf("coveredUntil" to it.toString()) } ?: emptyMap()))
                at == null -> UpcomingItem(k, h.cueType, h.name, waiting = WaitingReason.AfterFirstAck, rule = "SUN-8", facts = facts)
                nextOpen != null && nextOpen.isAfter(maxOf(now, at)) -> UpcomingItem(k, h.cueType, h.name, at = nextOpen, waiting = WaitingReason.OutsideActiveHours, waitingUntil = nextOpen, rule = "GEN-6", facts = facts)
                quietEnd != null && !at.isAfter(quietEnd) -> UpcomingItem(k, h.cueType, h.name, at = quietEnd, waiting = WaitingReason.AfterQuietHours, waitingUntil = quietEnd, rule = "QH-1", facts = facts)
                ctx.inMeeting && h.duringMeeting == DuringMeeting.Defer && !at.isAfter(now) -> UpcomingItem(k, h.cueType, h.name, waiting = WaitingReason.AfterMeeting, waitingUntil = ctx.meetingEndsAt, rule = "DuringMeeting", facts = facts)
                routineRunning && !at.isAfter(now) -> UpcomingItem(k, h.cueType, h.name, waiting = WaitingReason.AfterRoutine, rule = "RTN-10", facts = facts)
                covered != null -> UpcomingItem(k, h.cueType, h.name, at = at, waiting = WaitingReason.CoveredUntil, waitingUntil = covered, rule = "SUN-3", facts = facts)
                else -> UpcomingItem(k, h.cueType, h.name, at = at, rule = if (s?.lastAckAt != null) "SUN-2" else "HYD-3", facts = facts)
            }
        }

        // Posture
        val pc = config.postureCycle
        if (pc.enabled) {
            val ps = st.posture
            when (ps.phase) {
                PosturePhase.Running -> up += UpcomingItem("posture", CueType.Posture, "Posture", at = ps.snoozedUntil ?: ps.modeEndsAt, rule = "POS-3", facts = mapOf("mode" to (ps.modeId ?: "")))
                PosturePhase.SwitchPending -> active += ActiveItem("posture", "posture_switch_pending", mapOf("next" to (ps.pendingModeId ?: "")))
                PosturePhase.Frozen, PosturePhase.Paused -> up += UpcomingItem("posture", CueType.Posture, "Posture", waiting = if (ps.phase == PosturePhase.Paused) WaitingReason.PausedUntil else WaitingReason.Frozen,
                    rule = "POS-2", facts = mapOf("mode" to (ps.modeId ?: ""), "remainingMs" to (ps.remainingMs?.toString() ?: ""), "session" to (st.context.session?.status?.name ?: "none")))
                PosturePhase.Off -> up += UpcomingItem("posture", CueType.Posture, "Posture", waiting = WaitingReason.WhenConditionHolds, rule = "POS-2")
            }
        }

        // Medication doses (today, by medication day)
        val doses = st.medication.slots.values.mapNotNull { s ->
            val m = config.medication(s.medicationId) ?: return@mapNotNull null
            val zoneM = m.travelPolicy.zone(zone)
            val today = TimeMath.dayOf(now, zoneM, config.settings.dayStartsAt)
            val day = TimeMath.dayOf(s.dueAt, zoneM, config.settings.dayStartsAt)
            if (day != today && !(day.isBefore(today) && s.status == SlotStatus.Due && day == today.minusDays(1))) return@mapNotNull null
            val status = when (s.status) {
                SlotStatus.Upcoming -> DoseStatus.Upcoming
                SlotStatus.Taken -> DoseStatus.Taken
                SlotStatus.Skipped -> DoseStatus.Skipped
                SlotStatus.Due -> if (day.isBefore(today)) DoseStatus.NotConfirmed else DoseStatus.Due
            }
            DoseView(s.ref, m.label, s.dueAt, status, s.takenAt, s.cue?.cueId)
        }.sortedBy { it.dueAt }
        doses.filter { it.status == DoseStatus.Upcoming }.forEach { up += UpcomingItem("med:${it.slot.key}", CueType.Medication, it.label, at = it.dueAt, rule = "MED-1") }

        // Alarms
        for (a in config.alarms.filter { it.enabled }) {
            val s = st.alarms[a.id] ?: AlarmState()
            if (s.ring != null) continue
            val days = a.days ?: config.settings.workDays
            var d = TimeMath.localDate(now, zone)
            for (i in 0..8) {
                val ok = (d.dayOfWeek in days || d == a.oneOffDate) && d != a.skipDate && (s.handledThrough == null || d.isAfter(s.handledThrough))
                val at = TimeMath.resolveLocal(d, a.time, zone)
                if (ok && at.isAfter(now)) { up += UpcomingItem("alarm:${a.id}", CueType.Alarm, a.name, at = at, rule = "ALM-1"); break }
                d = d.plusDays(1)
            }
        }

        // Calendar cues
        val cal = config.calendarRules
        for (ev in st.calendar.events) {
            if (!now.isBefore(ev.start)) continue
            val dec = CalendarRules.decide(cal, ev)
            if (!dec.cue) continue
            val consumed = st.calendar.records[ev.key]?.consumedLeads ?: emptySet()
            val next = dec.leadsMin.filter { it !in consumed }.map { ev.start.minusSeconds(it * 60L) }.filter { it.isAfter(now) }.minOrNull() ?: continue
            up += UpcomingItem("cal:${ev.key}", CueType.Calendar, ev.title, at = next, rule = "CAL-1",
                facts = mapOf("step" to dec.step.toString(), "rule" to (dec.ruleId ?: ""), "reason" to dec.reason, "matched" to (dec.matched ?: "")))
        }

        val sorted = up.sortedWith(compareBy<UpcomingItem>({ it.at == null }, { it.at })).take(limit)
        return TodayView(now, ctx, active, sorted, doses, st.nextWakeAt)
    }

    /** CAL-1 preview: every cached event with its decision and deciding step/rule. */
    fun calendarPreview(config: DayCueConfig, state: EngineState) =
        state.calendar.events.sortedBy { it.start }.map { it to CalendarRules.decide(config.calendarRules, it) }

    @Suppress("unused") private fun unused(i: Instant) = i
}
