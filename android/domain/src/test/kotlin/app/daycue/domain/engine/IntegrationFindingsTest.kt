package app.daycue.domain.engine

import app.daycue.domain.config.Activity
import app.daycue.domain.config.CalendarDefaultPolicy
import app.daycue.domain.config.CueType
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.context.SessionStatus
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.query.Queries
import app.daycue.domain.signal.CompanionGone
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.signal.GeofenceSnapshot
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.signal.MotionActivity
import app.daycue.domain.signal.MotionKind
import app.daycue.domain.signal.MotionTransition
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.bottleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BTL-2 via snapshot (integration finding 3a). */
class SnapshotDepartureTest {
    private fun scenario() = Scenario().apply { enable(Defaults.WATER_BOTTLE) }
    private fun Scenario.cues() = deliveredFor(bottleKey())
    private fun Scenario.snapshot(vararg inside: String) = send(Event.SignalObserved(GeofenceSnapshot(inside.toSet(), now)))
    private fun Scenario.geofence(place: String, kind: GeofenceTransitionKind) = send(Event.SignalObserved(GeofenceTransition(place, kind, now)))

    @Test
    fun `a snapshot that no longer lists the place cues the bottle once, a late OS exit adds nothing`() {
        val s = scenario()
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.OFFICE, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("09:00")
        s.snapshot()
        assertEquals(listOf(s.t("09:00")), s.cues().map { it.deliveredAt }, "departure seen only through the snapshot")
        assertEquals("BTL-2", s.cues().single().why.rule)
        s.clear()
        s.advanceTo("09:03"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Exit)
        s.advanceTo("09:05"); s.snapshot()
        assertEquals(0, s.cues().size)
        assertTrue(s.history(HistoryKind.Skipped).isEmpty(), "already-seen departure is not even a pending cue: ${s.history(HistoryKind.Skipped)}")
    }

    @Test
    fun `exit first then snapshot - one cue, and BTL-4 cooldown applies to snapshot departures`() {
        val s = scenario()
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.OFFICE, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("09:00"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Exit)
        s.advanceTo("09:02"); s.snapshot()
        assertEquals(1, s.cues().size)
        s.send(Event.BottleAck(Defaults.WATER_BOTTLE, cueId = s.cues().single().id))
        s.advanceTo("09:35"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Enter)
        s.advanceTo("09:45"); s.snapshot()
        assertEquals(1, s.cues().size)
        assertTrue(s.history(HistoryKind.Skipped).any { it.rule == "BTL-4" })
        s.advanceTo("10:30"); s.geofence(Defaults.OFFICE, GeofenceTransitionKind.Enter)
        s.advanceTo("10:40"); s.snapshot()
        assertEquals(2, s.cues().size, "after the cooldown a snapshot departure cues again")
    }

    @Test
    fun `BTL-5 snapshot showing another saved place skips late, and a snapshot right after boot is not a departure`() {
        val s = scenario()
        s.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.HOME, GeofenceTransitionKind.Enter, s.t("08:00"))))
        s.advanceTo("08:30")
        s.snapshot(Defaults.OFFICE)
        assertEquals(0, s.cues().size)
        assertTrue(s.history(HistoryKind.SkippedLate).any { it.rule == "BTL-5" })

        val b = scenario()
        b.at("08:00", Event.SignalObserved(GeofenceTransition(Defaults.HOME, GeofenceTransitionKind.Enter, b.t("08:00"))))
        b.advanceTo("08:30"); b.reboot(5)
        b.snapshot()
        assertEquals(0, b.cues().size, "after boot we did not know we were inside")
    }
}

/** CTX-6 continuous walk (integration finding 3b). */
class ContinuousWalkTest {
    private fun Scenario.env() = Queries.todayView(config, state, clock).context.environment.value
    private fun Scenario.motion(kind: MotionKind, tr: MotionTransition) =
        send(Event.SignalObserved(MotionActivity(kind, now, now.plusSeconds(600), tr)))

    private fun walking(): Scenario = Scenario().apply {
        outsideAll()
        advanceTo("10:00"); motion(MotionKind.OnFoot, MotionTransition.Enter)
    }

    @Test
    fun `one ENTER transition keeps Outdoor through a long walk, then goes stale at the bound`() {
        val s = walking()
        s.advanceTo("10:06"); assertEquals(Environment.Outdoor, s.env())
        s.advanceTo("12:30"); assertEquals(Environment.Outdoor, s.env(), "no lapse after 45 min without new transitions")
        s.processRestart()
        s.advanceTo("13:05"); assertEquals(Environment.Outdoor, s.env(), "bound 180 min + exit dwell, survives process death")
        s.advanceTo("13:11"); assertEquals(Environment.Unknown, s.env(), "stale -> Unknown")
    }

    @Test
    fun `a supporting on-foot sample refreshes the bound`() {
        val s = walking()
        s.advanceTo("12:00"); s.motion(MotionKind.OnFoot, MotionTransition.Sample)
        s.advanceTo("14:30"); assertEquals(Environment.Outdoor, s.env())
        s.advanceTo("15:11"); assertEquals(Environment.Unknown, s.env())
    }

    @Test
    fun `contrary transitions end the walk - Still and on-foot EXIT hold 45 min from there, InVehicle ends it at once`() {
        for (contrary in listOf(MotionKind.Still to MotionTransition.Enter, MotionKind.OnFoot to MotionTransition.Exit)) {
            val s = walking()
            s.advanceTo("11:00"); s.motion(contrary.first, contrary.second)
            s.advanceTo("11:50"); assertEquals(Environment.Outdoor, s.env(), "$contrary: hold 45 + exit dwell 10")
            s.advanceTo("11:56"); assertEquals(Environment.Unknown, s.env(), "$contrary")
        }
        val v = walking()
        v.advanceTo("11:00"); v.motion(MotionKind.InVehicle, MotionTransition.Enter)
        assertEquals(Environment.Unknown, v.env(), "in-vehicle is never Outdoor (CTX-6), no hold")
    }

    @Test
    fun `point samples keep the PRODUCT 45 min hold (no ongoing state)`() {
        val s = Scenario()
        s.outsideAll()
        s.at("10:00", Event.SignalObserved(MotionActivity(MotionKind.OnFoot, s.t("10:00"))))
        s.advanceTo("10:44"); assertEquals(Environment.Outdoor, s.env())
        s.advanceTo("10:56"); assertEquals(Environment.Unknown, s.env())
    }
}

/** Companion "gone" retraction (integration finding 3d). */
class CompanionGoneTest {
    private fun Scenario.act() = Queries.todayView(config, state, clock).context.activity.value
    private fun Scenario.activeEveryMinute(from: String, to: String) {
        var t = t(from)
        while (!t.isAfter(t(to))) { advanceTo(t); companion(CompanionState.Active); t = t.plusSeconds(60) }
    }

    @Test
    fun `gone retracts a fresh idle report at once`() {
        val s = Scenario(start = "08:00")
        s.companion(CompanionState.Idle)
        assertEquals(Activity.Inactive, s.act())
        s.advance(1)
        s.send(Event.SignalObserved(CompanionGone(s.now)))
        assertEquals(Activity.Unknown, s.act(), "not after the 3 min expiry, now")
    }

    @Test
    fun `gone suspends an automatic session immediately, a later active report resumes it`() {
        val s = Scenario(start = "08:30")
        s.enter(Defaults.OFFICE)
        s.activeEveryMinute("08:31", "09:00")
        assertEquals(Activity.Working, s.act())
        s.advanceTo("09:00:30")
        s.send(Event.SignalObserved(CompanionGone(s.now)))
        assertEquals(SessionStatus.Suspended, s.state.context.session!!.status)
        assertEquals(s.t("09:00:30"), s.state.context.session!!.statusSince)
        assertEquals(Activity.Unknown, s.act())
        // An older report delivered late does not undo the retraction.
        s.send(Event.SignalObserved(app.daycue.domain.signal.CompanionActivity(CompanionState.Active, s.t("09:00"))))
        assertEquals(Activity.Unknown, s.act())
        s.advanceTo("09:05"); s.companion(CompanionState.Active)
        assertEquals(SessionStatus.Active, s.state.context.session!!.status)
        assertEquals(Activity.Working, s.act())
    }

    @Test
    fun `gone older than the last report is ignored, and manual sessions are unaffected`() {
        val s = Scenario(start = "08:00")
        s.companion(CompanionState.Idle)
        s.send(Event.SignalObserved(CompanionGone(s.now.minusSeconds(30))))
        assertEquals(Activity.Inactive, s.act())
        s.send(Event.StartSession(app.daycue.domain.config.SessionKind.Working))
        s.send(Event.SignalObserved(CompanionGone(s.now)))
        assertEquals(Activity.Working, s.act())
    }
}

/** Unmatched calendar events carry a text key, not an English word (integration finding 3c). */
class CalendarUnmatchedKindTest {
    @Test
    fun `default-policy cue uses the event key variants and kindKey, matched rules keep their kind text`() {
        val s = Scenario(start = "08:00")
        s.apply(ConfigOp.SetCalendarConfig(s.config.calendarRules.copy(defaultPolicy = CalendarDefaultPolicy.Cue(listOf(10)))))
        val lunch = CalendarEvent("lunch", calendarId = "work", title = "Lunch", start = s.t("12:00"), end = s.t("12:30"))
        val sync = CalendarEvent("sync", calendarId = "work", title = "Sync", start = s.t("11:00"), end = s.t("11:30"), otherAttendees = 2)
        s.send(Event.CalendarSynced(listOf(lunch, sync), s.now))
        s.advanceTo("12:01")
        val cues = s.delivered.filter { it.type == CueType.Calendar }
        val l = cues.single { it.itemKey == "cal:lunch" }
        assertEquals("cue.calendar.title.event", l.title.key)
        assertEquals("cue.calendar.generic.title.event", l.publicTitle?.key)
        assertEquals("speech.calendar.generic.event", l.speech?.lead?.key)
        for (t in listOfNotNull(l.title, l.body, l.publicTitle, l.speech?.lead)) {
            assertNull(t.args["kind"], "no kind word for $t")
            assertEquals(CalendarRules.UNMATCHED_KIND_KEY, t.args["kindKey"])
            assertTrue(t.args.values.none { it == "event" }, "no hard-coded English: $t")
        }
        val m = cues.single { it.itemKey == "cal:sync" }
        assertEquals("cue.calendar.title", m.title.key)
        assertEquals("meeting", m.title.args["kind"])
        assertNull(m.title.args["kindKey"])
    }
}
