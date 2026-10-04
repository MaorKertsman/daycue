package app.daycue.integrations.location

import app.daycue.actions.CueActionReceiver
import app.daycue.data.repo.RoomEngineStore
import app.daycue.delivery.Templates
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Language
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.Event
import app.daycue.domain.signal.CompanionGone
import app.daycue.domain.signal.MotionKind
import app.daycue.domain.signal.MotionTransition
import app.daycue.engine.EngineHost
import app.daycue.engine.FakeScheduler
import app.daycue.engine.MemoryStore
import app.daycue.engine.NoopBootSnapshot
import app.daycue.engine.RecordingSink
import app.daycue.engine.SilentLog
import app.daycue.engine.TestClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Domain-signal adoption (CTX-6 continuous walk, WRK-5 companion gone, BTL-2 snapshot departure, calendar kindKey). */
class MotionAndGoneSignalsTest {
    private val now = Instant.parse("2026-10-05T07:00:00Z")
    private val ENTER = ActivityTransition.ACTIVITY_TRANSITION_ENTER
    private val EXIT = ActivityTransition.ACTIVITY_TRANSITION_EXIT

    private fun map(vararg e: RawTransition) = MotionSignals.map(e.toList(), now, 10_000_000_000L, 10)

    @Test fun walkingRunningCyclingEnterAreOngoingOnFootAndTheirExitEndsIt() {
        for (type in listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE)) {
            val (enter, exit) = map(RawTransition(type, ENTER, 9_000_000_000L), RawTransition(type, EXIT, 9_500_000_000L))
            assertEquals(MotionKind.OnFoot, enter.kind); assertEquals(MotionTransition.Enter, enter.transition)
            assertEquals(MotionKind.OnFoot, exit.kind); assertEquals(MotionTransition.Exit, exit.transition)
            assertTrue("the exit is later than the enter", exit.observedAt.isAfter(enter.observedAt))
        }
    }

    @Test fun stillAndInVehicleEnterCarryTheEnterTransitionAndOtherTransitionsAreIgnored() {
        val out = map(
            RawTransition(DetectedActivity.STILL, ENTER, 9_000_000_000L),
            RawTransition(DetectedActivity.IN_VEHICLE, ENTER, 9_100_000_000L),
            RawTransition(DetectedActivity.STILL, EXIT, 9_200_000_000L),
            RawTransition(DetectedActivity.IN_VEHICLE, EXIT, 9_300_000_000L),
            RawTransition(DetectedActivity.TILTING, ENTER, 9_400_000_000L),
        )
        assertEquals(listOf(MotionKind.Still, MotionKind.InVehicle), out.map { it.kind })
        assertTrue(out.all { it.transition == MotionTransition.Enter })
        assertEquals("expires after the configured minutes", out[0].observedAt.plus(Duration.ofMinutes(10)), out[0].expiresAt)
    }

    @Test fun startingAWalkOrADriveSuggestsAFixToConfirmALeftPlace() {
        val walk = map(RawTransition(DetectedActivity.WALKING, ENTER, 9_000_000_000L)).single()
        val drive = map(RawTransition(DetectedActivity.IN_VEHICLE, ENTER, 9_000_000_000L)).single()
        val still = map(RawTransition(DetectedActivity.STILL, ENTER, 9_000_000_000L)).single()
        val end = map(RawTransition(DetectedActivity.WALKING, EXIT, 9_000_000_000L)).single()
        assertTrue(MotionSignals.suggestsDeparture(walk)); assertTrue(MotionSignals.suggestsDeparture(drive))
        assertFalse(MotionSignals.suggestsDeparture(still)); assertFalse(MotionSignals.suggestsDeparture(end))
    }

    @Test fun companionGoneIsStoredAsACompanionSignal() {
        assertEquals("companion", RoomEngineStore.signalSource(CompanionGone(now)))
    }

    /** A location fix that is clearly outside, after a raw-inside state, cues the bottle although the OS EXIT was missed. */
    @Test fun snapshotDetectedDepartureFiresTheBottleCue() = runBlocking {
        val clock = TestClock(now)
        val store = MemoryStore(); val sink = RecordingSink(store)
        val host = EngineHost(store, clock, FakeScheduler(), sink, NoopBootSnapshot, SilentLog)
        val home = Defaults.HOME
        val cfg = host.ensureLoaded().config
        host.applyOps(listOf(
            ConfigOp.SetPlaceLocation(home, GeoPoint(10.0, 20.0), 150),
            ConfigOp.SetHabitEnabled(Defaults.WATER_BOTTLE, true),
            ConfigOp.UpsertPlace(host.ensureLoaded().config.place(home)!!.copy(center = GeoPoint(10.0, 20.0), bottleReminderOnLeave = true)),
        ), cfg.version, "ui")
        val known = setOf(home)
        GeofenceSignals.decode(null, Geofence.GEOFENCE_TRANSITION_DWELL, listOf(home), now.toEpochMilli(), clock.now(), known).signals.forEach { host.dispatch(Event.SignalObserved(it)) }
        clock.advance(Duration.ofMinutes(20)); host.dispatch(Event.Tick)
        sink.clear()
        // The user left; no EXIT arrived. One fix 3 km away -> snapshot with nothing inside.
        val plan = GeofencePlanner.plan(host.ensureLoaded().config)
        val snap = Geo.snapshot(plan, Fix(10.027, 20.0, 40f, clock.now()), clock.now().plus(Duration.ofMinutes(10)))!!
        assertTrue(snap.insidePlaceIds.isEmpty())
        host.dispatch(Event.SignalObserved(snap))
        val cues = sink.delivered()
        assertTrue("bottle cue from the snapshot departure: $cues", cues.any { it.notificationKey.contains(Defaults.WATER_BOTTLE) })
    }

    // ---- TextResolver kindKey ---------------------------------------------------------------------------

    @Test fun kindKeyResolvesToTheLocalizedKindWordAndAnExplicitKindWins() {
        val en = mapOf("dc_calendar_kind_event" to "event"); val he = mapOf("dc_calendar_kind_event" to "אירוע")
        val args = mapOf("kindKey" to "calendar.kind.event", "minutes" to "10")
        assertEquals("event", Templates.withResolvedKind(args) { en[it] }["kind"])
        assertEquals("אירוע", Templates.withResolvedKind(args) { he[it] }["kind"])
        assertEquals("meeting", Templates.withResolvedKind(args + ("kind" to "meeting")) { en[it] }["kind"])
        assertEquals("unknown key leaves args alone", args, Templates.withResolvedKind(args) { null })
        assertEquals("shape check", mapOf("kindKey" to "Bad Key!"), Templates.withResolvedKind(mapOf("kindKey" to "Bad Key!")) { "x" })
        val body = Templates.fill("{template}", mapOf("template" to "You have a {kind} in {minutes} minutes") + Templates.withResolvedKind(args) { en[it] }, ZoneId.of("UTC"), rtl = false)
        assertEquals("You have a event in 10 minutes", body)
    }

    @Test fun theKindStringExistsInEnglishAndHebrew() {
        val en = File("src/main/res/values/strings_engine.xml").readText()
        val he = File("src/main/res/values-iw/strings_engine.xml").readText()
        assertTrue(en.contains("""<string name="dc_calendar_kind_event">event</string>"""))
        assertTrue(he.contains("""<string name="dc_calendar_kind_event">אירוע</string>"""))
        assertEquals("calendar.kind.event", app.daycue.domain.engine.CalendarRules.UNMATCHED_KIND_KEY)
        assertEquals("dc_calendar_kind_event", Templates.resourceName(app.daycue.domain.engine.CalendarRules.UNMATCHED_KIND_KEY))
    }

    // ---- PendingIntent identity (L-14) --------------------------------------------------------------------

    @Test fun cueIdIsPartOfTheNotificationActionIdentity() {
        val a = CueActionReceiver.identitySegments("med:m1@0900", 10, "cue-1")
        val b = CueActionReceiver.identitySegments("med:m1@0900", 10, "cue-2")
        assertNotEquals("two live cues for one item must not share a PendingIntent", a, b)
        assertEquals(listOf("-1", "habit:x", "-"), CueActionReceiver.identitySegments("habit:x", null, null))
    }
}
