package app.daycue.domain.engine

import app.daycue.domain.config.Activity
import app.daycue.domain.config.CollisionSettings
import app.daycue.domain.config.CueType
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.config.Medication
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.context.PlaceValue
import app.daycue.domain.context.SessionStatus
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.query.Queries
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.testing.Scenario
import app.daycue.domain.time.TimeWindow
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextTest {
    private fun Scenario.ctx() = Queries.todayView(config, state, clock).context
    private fun Scenario.activeEveryMinute(from: String, to: String, st: CompanionState = CompanionState.Active) {
        var t = t(from)
        while (!t.isAfter(t(to))) { advanceTo(t); companion(st); t = t.plusSeconds(60) }
    }

    @Test
    fun `CTX-2 place enter confirmed after 3 min dwell, CTX-3 exit after 5 min with re-entry hysteresis`() {
        val s = Scenario()
        s.at("08:00", Event.SignalObserved(app.daycue.domain.signal.GeofenceSnapshot(emptySet(), s.t("08:00"))))
        assertEquals(PlaceKind.Elsewhere, s.state.context.place.value.kind)
        s.enter(Defaults.OFFICE)
        s.advanceTo("08:02")
        assertEquals(PlaceKind.Elsewhere, s.state.context.place.value.kind)
        s.advanceTo("08:03")
        assertEquals(PlaceValue.saved(Defaults.OFFICE), s.state.context.place.value)
        assertEquals(s.t("08:03"), s.state.context.place.since)
        assertEquals(Environment.Indoor, s.state.context.env.value, "typicalEnvironment Indoor (Medium)")
        s.at("09:00", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.OFFICE, app.daycue.domain.signal.GeofenceTransitionKind.Exit, s.t("09:00"))))
        s.at("09:04", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.OFFICE, app.daycue.domain.signal.GeofenceTransitionKind.Enter, s.t("09:04"))))
        s.advanceTo("09:30")
        assertEquals(PlaceValue.saved(Defaults.OFFICE), s.state.context.place.value, "re-entry before confirmation cancels the exit")
        s.exit(Defaults.OFFICE)
        s.advanceTo("09:35")
        assertEquals(PlaceKind.Elsewhere, s.state.context.place.value.kind)
    }

    @Test
    fun `acceptance 7 - boundary flapping and stale signals do not cause repeated false transitions`() {
        val s = Scenario()
        s.enter(Defaults.OFFICE)
        s.advanceTo("06:10")
        s.clear()
        for (i in 0 until 10) { // GPS jitter: exit/enter every 2 minutes for 40 minutes
            s.advance(2); s.exit(Defaults.OFFICE)
            s.advance(2); s.enter(Defaults.OFFICE)
        }
        assertEquals(PlaceValue.saved(Defaults.OFFICE), s.state.context.place.value)
        assertTrue(s.history(HistoryKind.ContextChanged).none { it.rule == "CTX-3" }, "no transitions recorded")
        // Stale signals: a single on-foot reading while elsewhere holds Outdoor at most 45 min, then Unknown.
        val m = Scenario()
        m.outsideAll()
        m.at("10:00", Event.SignalObserved(app.daycue.domain.signal.MotionActivity(app.daycue.domain.signal.MotionKind.OnFoot, m.t("10:00"))))
        m.advanceTo("10:06")
        assertEquals(Environment.Outdoor, m.state.context.env.value)
        m.advanceTo("10:44")
        assertEquals(Environment.Outdoor, m.state.context.env.value, "CTX-5 held")
        m.advanceTo("10:56")
        assertEquals(Environment.Unknown, m.state.context.env.value, "expired -> Unknown, after exit dwell")
        // Companion expiry: one 'idle' report expires after 3 min -> Activity Unknown, not Inactive.
        m.companion(CompanionState.Idle)
        assertEquals(Activity.Inactive, m.ctx().activity.value)
        m.advance(4)
        assertEquals(Activity.Unknown, m.ctx().activity.value)
    }

    @Test
    fun `acceptance 8 - computer activity starts a session only under the configured rules (WRK-1, WRK-2, WRK-6)`() {
        // Office = AutoStart; permitted hours 08:00-19:00 on workdays (Mon).
        val s = Scenario(start = "07:40")
        s.at("07:45", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.OFFICE, app.daycue.domain.signal.GeofenceTransitionKind.Enter, s.t("07:45"))))
        s.activeEveryMinute("07:50", "08:10")
        val sess = s.state.context.session
        assertNotNull(sess)
        assertEquals(s.t("08:00"), sess.startedAt, "not before permitted hours although active since 07:50")
        assertTrue(s.delivered.any { it.itemKey == "session:office" && it.actions.any { a -> a.kind == ActionKind.NotWorking } && it.silent })
        assertEquals(Activity.Working, s.ctx().activity.value)

        // Gym = Off: never.
        val g = Scenario(start = "08:30")
        g.enter(Defaults.GYM)
        g.activeEveryMinute("08:31", "09:00")
        assertNull(g.state.context.session)

        // Home = Suggest: one quiet notification, nothing starts without an answer; cooldown 2 h.
        val h = Scenario(start = "08:30")
        h.enter(Defaults.HOME)
        h.activeEveryMinute("08:31", "09:30")
        assertNull(h.state.context.session)
        assertEquals(1, h.delivered.count { it.itemKey == "session:home" })
        h.send(Event.SessionPromptAnswer(Defaults.HOME, SessionAnswer.Start))
        assertEquals(SessionStatus.Active, h.state.context.session!!.status)

        // WRK-6: elsewhere, or outside permitted hours: never.
        val e = Scenario(start = "08:30")
        e.outsideAll()
        e.activeEveryMinute("08:31", "09:00")
        assertNull(e.state.context.session)
        val late = Scenario(start = "19:30")
        late.enter(Defaults.OFFICE)
        late.activeEveryMinute("19:31", "20:00")
        assertNull(late.state.context.session)
    }

    @Test
    fun `acceptance 9 - locked or disconnected computer never silently counts as continued work (WRK-3, WRK-4, WRK-5)`() {
        val s = Scenario(start = "08:30")
        s.enter(Defaults.OFFICE)
        s.activeEveryMinute("08:31", "09:00")
        assertEquals(SessionStatus.Active, s.state.context.session!!.status)
        s.activeEveryMinute("09:01", "09:05", CompanionState.Locked)
        assertEquals(SessionStatus.Paused, s.state.context.session!!.status, "locked >= 2 min pauses")
        assertTrue(s.ctx().activity.value != Activity.Working)
        s.activeEveryMinute("09:06", "09:07")
        assertEquals(SessionStatus.Active, s.state.context.session!!.status, "fresh active resumes")
        // Disconnect: no more signals.
        s.advanceTo("09:15")
        assertEquals(SessionStatus.Active, s.state.context.session!!.status)
        s.advanceTo("09:18")
        assertEquals(SessionStatus.Suspended, s.state.context.session!!.status, "stale 10 min -> Suspended")
        assertEquals(Activity.Unknown, s.ctx().activity.value)
        s.advanceTo("10:18")
        assertNull(s.state.context.session, "ends by pausedToEnd")
        assertTrue(s.history(HistoryKind.SessionEnded).isNotEmpty())
    }

    @Test
    fun `WRK-7 manual sessions ignore companion staleness, end on End session`() {
        val s = Scenario(start = "08:00")
        s.send(Event.StartSession(app.daycue.domain.config.SessionKind.Studying))
        s.advanceTo("12:00")
        assertEquals(Activity.Studying, s.ctx().activity.value)
        s.send(Event.EndSession)
        assertNull(s.state.context.session)
    }

    @Test
    fun `§1-4 overrides - For duration expires, UntilTransition ends at the next place change, pause detection makes automatic Unknown`() {
        val s = Scenario()
        s.outdoors(OverrideDuration.For(30))
        s.advance(29)
        assertEquals(Environment.Outdoor, s.ctx().environment.value)
        s.advance(2)
        assertEquals(Environment.Unknown, s.ctx().environment.value)

        val u = Scenario()
        u.enter(Defaults.HOME); u.advance(4)
        u.outdoors(OverrideDuration.UntilTransition)
        u.advance(60)
        assertEquals(Environment.Outdoor, u.ctx().environment.value)
        u.exit(Defaults.HOME); u.advance(6)
        assertEquals(Environment.Unknown, u.ctx().environment.value, "override ended by the meaningful transition")

        val p = Scenario()
        p.enter(Defaults.HOME); p.advance(4)
        p.send(Event.PauseAutoDetection(OverrideDuration.For(120)))
        assertEquals(PlaceKind.Unknown, p.ctx().place.value.kind)
        p.indoors()
        assertEquals(Environment.Indoor, p.ctx().environment.value, "manual overrides still apply")
        p.advance(121)
        assertEquals(PlaceValue.saved(Defaults.HOME), p.ctx().place.value)
    }
}

class CollisionTest {
    @Test
    fun `acceptance 12 - simultaneous cues form one group, nothing is lost (COL-1, COL-2, COL-3)`() {
        val s = Scenario(start = "06:00")
        s.apply(
            ConfigOp.UpsertMedication(Medication("m1", "Synthetic", listOf(LocalTime.of(10, 0)))),
            ConfigOp.SetHabitEnabled(Defaults.HYDRATION, true),
            ConfigOp.SetHabitEnabled(Defaults.SUNSCREEN, true),
        )
        s.at("09:50", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.send(Event.HabitAck(Defaults.SUNSCREEN))
        s.updateHabit(Defaults.SUNSCREEN) { it.copy(intervalMin = 123) } // due 12:01
        s.updateHabit(Defaults.HYDRATION) { it.copy(activeHours = TimeWindow(LocalTime.of(9, 1), LocalTime.of(21, 0))) } // first due 10:01
        s.advanceTo("10:00")
        val g = s.lastDelivered()
        assertEquals(setOf(CueType.Medication, CueType.Hydration), g.map { it.type }.toSet(), "hydration due 10:01 pulled into the 10:00 group (mergeWindow 2 min)")
        val lead = g.single { it.groupLead }
        assertEquals(CueType.Medication, lead.type)
        assertNotNull(lead.soundId)
        assertTrue(g.filter { !it.groupLead }.all { it.silent && it.speech == null })
        assertEquals("speech.medication.generic", lead.speech!!.lead.key)
        assertEquals(listOf("short.hydration"), lead.speech!!.also.map { it.key })
        assertEquals(1, g.map { it.groupKey }.toSet().size)
        assertTrue(g.all { it.actions.isNotEmpty() }, "each member keeps its own actions")
    }

    @Test
    fun `COL-3 audible gap delays a later soft cue, never drops it, P2 is never delayed`() {
        val s = Scenario(start = "06:00")
        s.apply(
            ConfigOp.SetCollisionSettings(CollisionSettings(mergeWindowMin = 0, minAudibleGapSec = 300)),
            ConfigOp.SetHabitEnabled(Defaults.HYDRATION, true),
            ConfigOp.UpsertMedication(Medication("m1", "Synthetic", listOf(LocalTime.of(10, 1)))),
        )
        s.updateHabit(Defaults.HYDRATION) { it.copy(activeHours = TimeWindow(LocalTime.of(9, 0), LocalTime.of(21, 0))) } // 10:00
        s.enable(Defaults.SUNSCREEN)
        s.updateHabit(Defaults.SUNSCREEN) { it.copy(activeHours = TimeWindow(LocalTime.of(6, 0), LocalTime.of(21, 0)), firstReminder = app.daycue.domain.config.FirstReminderPolicy.AfterDelay(2)) }
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged)) // sunscreen due 10:02
        s.advanceTo("10:10")
        val times = s.delivered.filter { it.repeatIndex == 0 }.associate { it.type to it.deliveredAt }
        assertEquals(s.t("10:00"), times[CueType.Hydration])
        assertEquals(s.t("10:01"), times[CueType.Medication], "P2 not delayed by the gap")
        assertEquals(s.t("10:06"), times[CueType.Sunscreen], "delayed to lastAudible (10:01) + 5 min, not dropped")
    }
}
