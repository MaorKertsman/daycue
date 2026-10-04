package app.daycue.domain.engine

import app.daycue.domain.config.Activity
import app.daycue.domain.config.ContextCondition
import app.daycue.domain.config.ContextInterval
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.QuietHours
import app.daycue.domain.config.QuietWindow
import app.daycue.domain.config.ScheduledDeparture
import app.daycue.domain.config.SessionKind
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.bottleKey
import app.daycue.domain.testing.hydrationKey
import app.daycue.domain.time.TimeWindow
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationTest {
    private val H = Defaults.HYDRATION
    private fun scenario(start: String = "06:00") = Scenario(start = start).apply { enable(H) }
    private fun Scenario.cues() = deliveredFor(hydrationKey())

    @Test
    fun `HYD-3 first cue of the window is window start plus interval, and HYD-1 next due is ack plus interval`() {
        val s = scenario()
        s.advanceTo("10:01")
        assertEquals(listOf(s.t("10:00")), s.cues().map { it.deliveredAt })
        s.at("10:05", Event.HabitAck(H, s.cues().single().id))
        assertEquals(s.t("11:05"), s.state.intervals[H]!!.dueAt)
    }

    @Test
    fun `HYD-2 unacked - no repeat, next cue is delivery plus interval`() {
        val s = scenario()
        s.advanceTo("12:30")
        assertEquals(listOf(s.t("10:00"), s.t("11:00"), s.t("12:00")), s.cues().map { it.deliveredAt })
        assertTrue(s.cues().all { it.repeatIndex == 0 })
    }

    @Test
    fun `HYD-3 previous day ack does not shift today's first cue`() {
        val s = scenario()
        s.at("20:50", Event.HabitAck(H))
        s.advanceTo(s.t("10:30", s.date.plusDays(1)))
        assertEquals(s.t("10:00", s.date.plusDays(1)), s.cues().last().deliveredAt)
        assertTrue(s.cues().none { it.deliveredAt.isAfter(s.t("21:00")) && it.deliveredAt.isBefore(s.t("10:00", s.date.plusDays(1))) }, "nothing outside active hours")
    }

    @Test
    fun `QH-1 hydration is skipped in quiet hours and delivered at most once at quiet end`() {
        val s = scenario()
        s.config = s.config.copy(settings = s.config.settings.copy(quietHours = QuietHours(true, listOf(QuietWindow(TimeWindow(LocalTime.of(9, 30), LocalTime.of(12, 15)))))))
        s.send(Event.ConfigChanged)
        s.advanceTo("13:00")
        assertEquals(listOf(s.t("12:15")), s.cues().map { it.deliveredAt }, "one cue at quiet end, not three")
    }

    @Test
    fun `DuringMeeting Defer - held during a calendar meeting, delivered when it ends`() {
        val s = scenario()
        val ev = CalendarEvent("m1", calendarId = "c", title = "Sync", start = s.t("09:50"), end = s.t("10:40"), otherAttendees = 2)
        s.send(Event.CalendarSynced(listOf(ev), s.now))
        s.advanceTo("10:39")
        assertEquals(0, s.cues().size)
        assertEquals(Activity.Meeting, app.daycue.domain.query.Queries.todayView(s.config, s.state, s.clock).context.activity.value)
        s.advanceTo("10:41")
        assertEquals(listOf(s.t("10:40")), s.cues().map { it.deliveredAt })
    }

    @Test
    fun `HYD-5 per-context interval switch recomputes from last ack, never earlier than now plus 1 min`() {
        val s = scenario()
        s.updateHabit(H) { it.copy(contextIntervals = listOf(ContextInterval(ContextCondition(activities = setOf(Activity.Working)), 30))) }
        s.at("10:00", Event.HabitAck(H))
        assertEquals(s.t("11:00"), s.state.intervals[H]!!.dueAt)
        s.at("10:45", Event.StartSession(SessionKind.Working))
        assertEquals(s.t("10:46"), s.state.intervals[H]!!.dueAt, "10:00 + 30 is past -> now + 1 min")
    }

    @Test
    fun `pause for 1 h via notification - engine requests a ConfigOp, no cues while paused, one cue after`() {
        val s = scenario()
        s.advanceTo("10:01")
        val eff = s.send(Event.Pause(PauseTarget.Habit(H), PauseChoice.For(60), s.cues().single().id))
        val op = eff.filterIsInstance<Effect.ApplyConfigOps>().single().ops.single()
        assertTrue(op is ConfigOp.SetPause)
        s.apply(op)
        assertTrue(hydrationKey() in s.dismissedKeys())
        s.advanceTo("11:00")
        assertEquals(1, s.cues().size)
        s.advanceTo("11:02")
        assertEquals(2, s.cues().size, "GEN-6: one cue at pause end")
    }
}

class BottleTest {
    private val B = Defaults.WATER_BOTTLE
    private fun scenario() = Scenario().apply { enable(B) }
    private fun Scenario.cues() = deliveredFor(bottleKey())
    private fun Scenario.geofence(place: String, kind: GeofenceTransitionKind) = send(Event.SignalObserved(GeofenceTransition(place, kind, now)))

    @Test
    fun `BTL-1 leaving now cues immediately, BTL-3 the later geofence exit is deduplicated`() {
        val s = scenario()
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.HOME, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("08:10")
        s.send(Event.LeavingNow)
        assertEquals(listOf(s.t("08:10")), s.cues().map { it.deliveredAt })
        s.advanceTo("08:14")
        s.geofence(Defaults.HOME, GeofenceTransitionKind.Exit)
        assertEquals(1, s.cues().size)
        assertTrue(s.history(HistoryKind.Skipped).any { it.rule == "BTL-3" })
    }

    @Test
    fun `BTL-2 geofence exit from an enabled place cues without exit dwell, BTL-4 cooldown suppresses flapping`() {
        val s = scenario()
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.OFFICE, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("09:00")
        s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Exit)
        assertEquals(listOf(s.t("09:00")), s.cues().map { it.deliveredAt })
        s.send(Event.BottleAck(B, cueId = s.cues().single().id))
        s.advanceTo("09:35"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Enter)
        s.advanceTo("09:40"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Exit)
        assertEquals(1, s.cues().size)
        assertTrue(s.history(HistoryKind.Skipped).any { it.rule == "BTL-4" })
        s.advanceTo("10:30"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Enter)
        s.advanceTo("10:40"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Exit)
        assertEquals(2, s.cues().size)
    }

    @Test
    fun `BTL-5 exit processed after entering another saved place is skipped late`() {
        val s = scenario()
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.HOME, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("08:30")
        s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Enter)
        s.geofence(Defaults.HOME, GeofenceTransitionKind.Exit)
        assertEquals(0, s.cues().size)
        assertTrue(s.history(HistoryKind.SkippedLate).isNotEmpty())
    }

    @Test
    fun `BTL-6 scheduled departure fires when at the place, counts for dedup, and BTL-7 dismissal is not an ack`() {
        val s = scenario()
        s.updateBottle { it.copy(scheduledDepartures = listOf(ScheduledDeparture(Defaults.HOME, LocalTime.of(8, 30)))) }
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.HOME, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("08:31")
        assertEquals(listOf(s.t("08:30")), s.cues().map { it.deliveredAt })
        s.send(Event.CueDismissed(s.cues().single().id))
        assertTrue(s.state.bottles[B]!!.cue != null, "dismiss is logged only")
        s.advanceTo("08:40")
        s.send(Event.LeavingNow)
        assertEquals(1, s.cues().size, "within dedup window of the scheduled cue")
    }
}
