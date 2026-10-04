package app.daycue.engine

import app.daycue.actions.ActionMapper
import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.ActionKind
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.HistoryKind
import app.daycue.domain.engine.WakePrecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * EngineHost: persistence + single re-arm + effect round trips, on the JVM with in-memory fakes.
 * Times are 10:00 local on a Monday (quiet hours 22:30-07:00 don't interfere).
 */
class EngineHostTest {
    private val clock = TestClock(Instant.parse("2026-10-05T07:00:00Z")) // 10:00 Asia/Jerusalem
    private val store = MemoryStore()
    private val scheduler = FakeScheduler()
    private val sink = RecordingSink(store)

    private fun host(s: MemoryStore = store) = EngineHost(s, clock, scheduler, sink, NoopBootSnapshot, SilentLog)

    /** Fires the armed wake like AlarmManager would: advance to it, then Tick. */
    private suspend fun EngineHost.fireArmed(): Instant {
        val (at, _) = scheduler.armed ?: error("nothing armed")
        if (at.isAfter(clock.instant)) clock.advance(Duration.between(clock.instant, at))
        dispatch(Event.Tick)
        return at
    }

    private suspend fun EngineHost.enableDemo(intervalMin: Int = 5) {
        val cfg = ensureLoaded().config
        val r = applyOps(listOf(ConfigOp.UpsertHabit(demoHabit(intervalMin))), cfg.version, "ui")
        assertTrue("applied: $r", r is ApplyOutcome.Applied)
    }

    @Test
    fun firstRunSeedsDefaultsAndArmsNothing() = runBlocking {
        val h = host()
        h.dispatch(Event.BootCompleted)
        assertNotNull(store.config)
        assertEquals(0L, store.config!!.version)
        assertNotNull("state persisted", store.state)
        assertNull("defaults are all disabled -> no wake", store.state!!.nextWakeAt)
        assertTrue(scheduler.calls.last() == "cancel")
    }

    @Test
    fun configChangePersistsHistoryAndArmsExactWake() = runBlocking {
        val h = host()
        h.enableDemo()
        assertEquals(1L, store.config!!.version)
        assertEquals("previous version kept for undo", setOf(0L), store.configHistory.keys)
        assertEquals("config.apply", store.audit.single().action)
        val st = store.state!!
        assertNotNull(st.nextWakeAt)
        assertEquals("armed = persisted next wake", st.nextWakeAt to st.nextWakePrecision, scheduler.armed)
        assertEquals(WakePrecision.Exact, st.nextWakePrecision)
        assertEquals(1L, st.configVersion)
    }

    /** Acceptance scenario 1 basis: the cue fires at its time, ack schedules the next one from the ack. */
    @Test
    fun cueFiresAndAckSchedulesNext() = runBlocking {
        val h = host()
        h.enableDemo(5)
        val firstAt = h.fireArmed()
        val cue = sink.delivered().single { it.itemKey == "habit:demo" }
        assertEquals(firstAt, cue.deliveredAt)
        assertTrue("delivery recorded", store.history.any { it.kind == HistoryKind.Delivered && it.cueId == cue.id })
        // Persist before execute: when the Deliver ran, the stored state already knew the cue.
        val idx = sink.effects.indexOfFirst { it is Effect.Deliver }
        assertEquals(cue.id, sink.persistedAtExecute[idx]!!.intervals["demo"]!!.cue!!.cueId)

        clock.advance(Duration.ofSeconds(40))
        val ackAt = clock.instant
        val ack = ActionMapper.map(cue.itemKey, ActionMapper.Tap.Action(ActionKind.Done, null), cue.id)!!
        h.dispatch(ack)
        val s = store.state!!.intervals["demo"]!!
        assertEquals(ackAt, s.lastAckAt)
        assertEquals(ackAt.plus(Duration.ofMinutes(5)), store.state!!.nextWakeAt)
        assertEquals(store.state!!.nextWakeAt, scheduler.armed!!.first)
        assertTrue(sink.effects.any { it is Effect.DismissCue && it.notificationKey == "habit:demo" })

        sink.clear()
        h.fireArmed()
        assertEquals("next one fires one interval after the ack", 1, sink.delivered().count { it.itemKey == "habit:demo" })
    }

    /** Acceptance scenario 4: dismissal is never an ack. */
    @Test
    fun dismissalDoesNotAck() = runBlocking {
        val h = host()
        h.enableDemo(5)
        h.fireArmed()
        val cue = sink.delivered().single()
        val before = store.state!!.intervals["demo"]!!
        val ev = ActionMapper.map(cue.itemKey, ActionMapper.Tap.Dismissed, cue.id)
        assertTrue(ev is Event.CueDismissed)
        h.dispatch(ev!!)
        val after = store.state!!.intervals["demo"]!!
        assertEquals("no ack recorded", before.lastAckAt, after.lastAckAt)
        assertNull(after.lastAckAt)
        assertEquals("cue still pending (repeats continue)", cue.id, after.cue?.cueId)
        assertTrue(store.history.any { it.kind == HistoryKind.Dismissed })
        assertFalse(store.history.any { it.kind == HistoryKind.Acked })
        // The repeat still comes.
        sink.clear()
        h.fireArmed()
        assertTrue("repeat delivered after dismissal", sink.delivered().any { it.itemKey == "habit:demo" && it.repeatIndex == 1 })
    }

    /** Scenario 5/13 basis: process death between reduces changes nothing; a fresh host re-arms the same wake. */
    @Test
    fun processDeathThenNextCueStillFires() = runBlocking {
        host().enableDemo(5)
        val armedBefore = scheduler.armed
        scheduler.armed = null // pretend we don't know; a new process re-arms from persisted state
        sink.clear()
        val fresh = host() // new process: empty caches, same storage
        fresh.dispatch(Event.BootCompleted) // DayCueApplication sends this on every process start
        assertTrue("process start delivers nothing", sink.delivered().isEmpty())
        assertEquals(armedBefore, scheduler.armed)
        fresh.fireArmed()
        assertEquals(1, sink.delivered().count { it.itemKey == "habit:demo" })
    }

    /** Scenario 17 basis: after a reboot (elapsedRealtime went backwards) the wake is re-armed. */
    @Test
    fun rebootRearms() = runBlocking {
        host().enableDemo(5)
        val wake = store.state!!.nextWakeAt!!
        scheduler.armed = null // alarms are cleared by a reboot
        clock.advance(Duration.ofMinutes(1)); clock.elapsed = Duration.ofSeconds(30)
        host().dispatch(Event.BootCompleted)
        assertEquals(wake, scheduler.armed!!.first)
        assertTrue(store.history.any { it.detail["reboot"] == "true" })
    }

    /** Pause from a notification: engine asks for SetPause -> applyOps -> ConfigChanged; config is the source of truth. */
    @Test
    fun pauseRoundTripsThroughConfig() = runBlocking {
        val h = host()
        h.enableDemo(5)
        h.fireArmed()
        val cue = sink.delivered().single()
        val v = store.config!!.version
        h.dispatch(ActionMapper.map(cue.itemKey, ActionMapper.Tap.Action(ActionKind.Pause, null), cue.id)!!)
        val habit = store.config!!.habit("demo") as IntervalHabit
        assertTrue(habit.pause is PauseSpec.Until)
        assertEquals(clock.instant.plus(Duration.ofMinutes(60)), (habit.pause as PauseSpec.Until).until)
        assertEquals(v + 1, store.config!!.version)
        assertEquals("state reduced against the new version", v + 1, store.state!!.configVersion)
        assertEquals("engine-requested edits are audited as system", "system", store.audit.last().actor)
        assertTrue(store.history.any { it.kind == HistoryKind.Paused })
    }

    @Test
    fun undoRestoresPreviousContentAsNewVersion() = runBlocking {
        val h = host()
        h.enableDemo(5)
        val v1 = store.config!!
        val r = h.applyOps(listOf(ConfigOp.SetHabitInterval("demo", 30)), v1.version, "ui")
        assertTrue(r is ApplyOutcome.Applied)
        assertEquals(30, (store.config!!.habit("demo") as IntervalHabit).intervalMin)
        val u = h.undo()
        assertTrue("undo: $u", u is ApplyOutcome.Applied)
        val now = store.config!!
        assertEquals(v1.version + 2, now.version)
        assertEquals(ConfigCodec.encode(v1.copy(version = 0)), ConfigCodec.encode(now.copy(version = 0)))
        assertEquals("undo consumed the history row it restored", setOf(0L), store.configHistory.keys)
        assertEquals(5, (store.config!!.habit("demo") as IntervalHabit).intervalMin)
        assertEquals("config.undo", store.audit.last().action)
    }

    @Test
    fun invalidAndConflictingEditsChangeNothing() = runBlocking {
        val h = host()
        h.enableDemo(5)
        val before = store.config
        val bad = h.applyOps(listOf(ConfigOp.SetHabitInterval("demo", 1)), before!!.version, "ui")
        assertTrue(bad is ApplyOutcome.Invalid)
        val stale = h.applyOps(listOf(ConfigOp.SetHabitInterval("demo", 30)), before.version - 1, "mcp")
        assertTrue(stale is ApplyOutcome.Conflict)
        assertEquals(before, store.config)
    }

    @Test
    fun missingExactAccessStillArms() = runBlocking {
        scheduler.exactAllowed = false
        val h = host()
        h.enableDemo(5)
        assertNotNull(scheduler.armed)
        assertTrue(h.lastArm!!.degraded)
    }

    @Test
    fun timezoneChangeKeepsIntervalInstants() = runBlocking {
        val h = host()
        h.enableDemo(5)
        val wake = store.state!!.nextWakeAt
        clock.zoneId = java.time.ZoneId.of("America/New_York")
        h.dispatch(Event.TimezoneChanged)
        assertEquals("interval habits are UTC instants", wake, store.state!!.nextWakeAt)
    }

    @Test
    fun stateJsonSurvivesStoreRoundTrip() = runBlocking {
        val h = host()
        h.enableDemo(5)
        h.fireArmed()
        val json = EngineState.encode(store.state!!)
        assertEquals(store.state, EngineState.decode(json))
        assertEquals(Defaults.config().cueProfiles, store.config!!.cueProfiles)
    }
}
