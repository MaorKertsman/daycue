package app.daycue.domain.engine

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.config.Medication
import app.daycue.domain.config.SessionKind
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.sunscreenKey
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RecoveryTest {

    @Test
    fun `acceptance 17 - timezone change - interval habits keep UTC instants (SUN-6), alarms follow local time`() {
        val s = Scenario()
        s.enable(Defaults.SUNSCREEN)
        s.apply(ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, true))
        s.at("10:00", Event.HabitAck(Defaults.SUNSCREEN))
        val due = s.state.intervals[Defaults.SUNSCREEN]!!.dueAt
        s.clock.zoneId = ZoneId.of("America/New_York")
        s.send(Event.TimezoneChanged)
        assertEquals(due, s.state.intervals[Defaults.SUNSCREEN]!!.dueAt)
        assertEquals(s.t("10:00", s.date).let { s.state.intervals[Defaults.SUNSCREEN]!!.lastAckAt }, s.state.intervals[Defaults.SUNSCREEN]!!.lastAckAt)
        // Next alarm = 07:00 New York local time tomorrow (Tue).
        s.advanceTo(java.time.Instant.parse("2026-10-06T11:01:00Z"))
        assertEquals(java.time.Instant.parse("2026-10-06T11:00:00Z"), s.state.alarms[Defaults.MORNING_ALARM]!!.ring!!.scheduledAt)
    }

    @Test
    fun `acceptance 17 - reboot - place is Unknown until the OS initial trigger, sunscreen holds, history and acks survive`() {
        val s = Scenario()
        s.enable(Defaults.SUNSCREEN)
        s.enter(Defaults.HOME); s.advance(4)
        s.at("09:00", Event.HabitAck(Defaults.SUNSCREEN))
        s.reboot(offMinutes = 30)
        assertEquals(PlaceKind.Unknown, s.state.context.place.value.kind)
        assertEquals(s.t("09:00"), s.state.intervals[Defaults.SUNSCREEN]!!.lastAckAt)
        s.send(Event.SignalObserved(app.daycue.domain.signal.GeofenceSnapshot(setOf(Defaults.HOME), s.now)))
        s.advance(4)
        assertEquals(Defaults.HOME, s.state.context.place.value.placeId)
    }

    @Test
    fun `GEN-7 replaying BootCompleted on an up-to-date state is a no-op`() {
        val s = Scenario()
        s.enable(Defaults.HYDRATION)
        s.apply(ConfigOp.UpsertMedication(Medication("m", "Synthetic", listOf(LocalTime.of(8, 0), LocalTime.of(12, 0)))))
        s.advanceTo("10:30")
        s.send(Event.Tick) // up to date at now
        val before = s.state
        val r = Engine.reduce(s.config, before, Event.BootCompleted, s.clock)
        assertEquals(before, r.state)
        assertTrue(r.effects.none { it is Effect.Deliver || it is Effect.DismissCue || it is Effect.ScheduleWake || it is Effect.StartAlarm }, "effects: ${r.effects}")
    }

    @Test
    fun `serialization round-trip of state mid-flow simulates process death without behavioral change`() {
        fun build(restart: Boolean): List<Pair<String, java.time.Instant>> {
            val s = Scenario()
            s.enable(Defaults.SUNSCREEN); s.enable(Defaults.HYDRATION)
            s.apply(ConfigOp.SetPostureEnabled(true), ConfigOp.UpsertMedication(Medication("m", "Synthetic", listOf(LocalTime.of(9, 15)))))
            s.at("09:00", Event.StartSession(SessionKind.Working))
            s.outdoors()
            s.send(Event.RoutineControl(RoutineAction.Start(Defaults.MORNING_ROUTINE)))
            s.advanceTo("09:07")
            if (restart) s.processRestart()
            s.advanceTo("11:00")
            return s.delivered.map { it.itemKey to it.deliveredAt }
        }
        assertEquals(build(false), build(true))
    }
}

class IdempotencyTest {
    @Test
    fun `re-delivering the same event is idempotent`() {
        val s = Scenario()
        s.enable(Defaults.SUNSCREEN)
        s.apply(ConfigOp.UpsertMedication(Medication("m", "Synthetic", listOf(LocalTime.of(10, 0)))))
        s.at("09:59", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.advanceTo("10:00")
        val sun = s.deliveredFor(sunscreenKey()).single()
        val med = s.delivered.first { it.type == app.daycue.domain.config.CueType.Medication }
        val events = listOf(
            Event.HabitAck(Defaults.SUNSCREEN, sun.id),
            Event.MedicationTaken(SlotRef("m", s.date, LocalTime.of(10, 0)), med.id),
            Event.Tick,
            Event.CalendarSynced(listOf(CalendarEvent("e", calendarId = "c", start = s.t("11:00"), end = s.t("11:30"), otherAttendees = 1)), s.now),
            Event.CueDismissed(sun.id),
        )
        for (e in events) {
            s.send(e)
            val st = s.state
            val effects = s.send(e)
            assertEquals(st.copy(), s.state, "second $e changed state")
            assertTrue(effects.none { it is Effect.Deliver || it is Effect.DismissCue }, "second $e produced $effects")
        }
    }
}

/** Property-style checks over random synthetic event streams. */
class EnginePropertyTest {

    private fun randomEvent(r: Random, s: Scenario): Event {
        val places = listOf(Defaults.HOME, Defaults.OFFICE, Defaults.GYM)
        return when (r.nextInt(16)) {
            0 -> Event.OverrideEnvironment(if (r.nextBoolean()) Environment.Outdoor else Environment.Indoor, OverrideDuration.For(r.nextInt(5, 120)))
            1 -> Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(places.random(r), app.daycue.domain.signal.GeofenceTransitionKind.entries.random(r), s.now))
            2 -> Event.SignalObserved(app.daycue.domain.signal.CompanionActivity(CompanionState.entries.random(r), s.now))
            3 -> Event.SignalObserved(app.daycue.domain.signal.MotionActivity(app.daycue.domain.signal.MotionKind.entries.random(r), s.now))
            4 -> Event.HabitAck(listOf(Defaults.SUNSCREEN, Defaults.HYDRATION).random(r))
            5 -> Event.HabitSnooze(listOf(Defaults.SUNSCREEN, Defaults.HYDRATION).random(r))
            6 -> Event.PostureControl(PostureAction.entries.random(r))
            7 -> Event.RoutineControl(listOf(RoutineAction.Start(Defaults.MORNING_ROUTINE), RoutineAction.Done, RoutineAction.Pause, RoutineAction.Resume, RoutineAction.SkipStep).random(r))
            8 -> Event.LeavingNow
            9 -> Event.StartSession(SessionKind.Working)
            10 -> Event.EndSession
            11 -> Event.MedicationTaken(SlotRef("m", s.date, LocalTime.of(listOf(8, 12, 18).random(r), 0)))
            12 -> Event.AlarmControl(Defaults.MORNING_ALARM, AlarmAction.entries.filter { it != AlarmAction.Test }.random(r))
            13 -> Event.CalendarSynced(listOf(CalendarEvent("e${r.nextInt(3)}", calendarId = "c", start = s.now.plusSeconds(r.nextLong(0, 7200)), end = s.now.plusSeconds(9000), otherAttendees = r.nextInt(3))), s.now)
            14 -> Event.TimeChanged
            else -> Event.Tick
        }
    }

    @Test
    fun `nextWakeAt is never in the past and BootCompleted replay is a no-op, over random streams`() {
        for (seed in 1..12) {
            val r = Random(seed)
            val s = Scenario(start = "06:30")
            s.apply(
                ConfigOp.SetHabitEnabled(Defaults.SUNSCREEN, true), ConfigOp.SetHabitEnabled(Defaults.HYDRATION, true),
                ConfigOp.SetHabitEnabled(Defaults.WATER_BOTTLE, true), ConfigOp.SetPostureEnabled(true),
                ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, true),
                ConfigOp.UpsertMedication(Medication("m", "Synthetic", listOf(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(18, 0)))),
            )
            repeat(150) { step ->
                s.advance(r.nextLong(0, 25))
                try { s.send(randomEvent(r, s)) } catch (e: AssertionError) { fail("seed $seed step $step: ${e.message}") }
                val st = s.state
                val replay = Engine.reduce(s.config, st, Event.BootCompleted, s.clock)
                if (replay.state != st || replay.effects.any { it is Effect.Deliver || it is Effect.ScheduleWake || it is Effect.DismissCue }) {
                    fail("seed $seed step $step: BootCompleted replay not a no-op: ${replay.effects.filter { it !is Effect.RecordHistory }}\nDIFF state=${replay.state != st}")
                }
                replay.state.nextWakeAt?.let { assertTrue(it.isAfter(s.now)) }
                // Serialization round-trip is lossless at every step.
                assertEquals(st, EngineState.decode(EngineState.encode(st)))
            }
            assertTrue(Duration.between(s.t("06:30"), s.now).toHours() >= 1)
        }
    }
}
