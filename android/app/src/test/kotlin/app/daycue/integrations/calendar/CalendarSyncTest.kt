package app.daycue.integrations.calendar

import app.daycue.domain.config.CalendarPreference
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.CalendarEvent
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.WakePrecision
import app.daycue.engine.EngineHost
import app.daycue.engine.FakeScheduler
import app.daycue.engine.MemoryStore
import app.daycue.engine.NoopBootSnapshot
import app.daycue.engine.RecordingSink
import app.daycue.engine.SilentLog
import app.daycue.engine.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Provider fake -> CalendarSync -> real EngineHost/Engine. Acceptance scenarios 10 and 11 at unit level. */
class CalendarSyncTest {
    private val clock = TestClock(Instant.parse("2026-10-05T07:00:00Z"))
    private val store = MemoryStore()
    private val scheduler = FakeScheduler()
    private val sink = RecordingSink(store)
    private val host = EngineHost(store, clock, scheduler, sink, NoopBootSnapshot, SilentLog)

    private class FakeSource : CalendarSource {
        var rows = mutableListOf<RawInstance>()
        val attendees = mutableListOf<RawAttendee>()
        var reads = 0
        override fun calendars() = listOf(DeviceCalendar(1, "Test", "owner@example.com", "LOCAL", "owner@example.com", null, true, true, 700, true))
        override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>): List<RawInstance> {
            reads++
            return rows.filter { it.calendarId in calendarIds && it.endMs > fromMs && it.beginMs < toMs }
        }
        override fun attendees(eventIds: Set<Long>) = attendees.filter { it.eventId in eventIds }
        override fun reminders(eventIds: Set<Long>) = emptyList<RawReminder>()
    }

    private class FakeCache : CalendarCache {
        var rows: List<CalendarEvent> = emptyList()
        override suspend fun replace(events: List<CalendarEvent>, fetchedAt: Instant) { rows = events }
    }

    private val source = FakeSource()
    private val cache = FakeCache()
    private var permission = true
    private val sync = CalendarSync(source, cache, clock, { host.ensureLoaded().config }, { permission }, { host.dispatch(it) },
        previous = { host.snapshot.value?.state?.calendar?.events.orEmpty() })

    private fun at(min: Long) = clock.now().plus(Duration.ofMinutes(min)).toEpochMilli()

    private fun row(eventId: Long, title: String, startMin: Long, status: Int = 1, self: Int = 1) =
        RawInstance(eventId, eventId, 1, title, at(startMin), at(startMin + 30), false, "UTC", status, self, 0, false, "owner@example.com",
            null, null, null, null, null, null, null, null)

    private suspend fun setup() {
        host.ensureLoaded()
        host.applyOps(listOf(ConfigOp.SetCalendarPreference(CalendarPreference("1"))), host.ensureLoaded().config.version, "ui")
    }

    /** Fires the armed wake (like AlarmManager) until [untilMin] from now. */
    private suspend fun runUntil(untilMin: Long) {
        val end = clock.now().plus(Duration.ofMinutes(untilMin))
        while (true) {
            val (t, _) = scheduler.armed ?: break
            if (t.isAfter(end)) break
            clock.advance(Duration.between(clock.now(), t)); host.dispatch(Event.Tick)
        }
        if (clock.now().isBefore(end)) { clock.advance(Duration.between(clock.now(), end)); host.dispatch(Event.Tick) }
    }

    private fun deliveredKeys() = sink.delivered().filter { !it.isTest }.map { it.notificationKey }

    @Test fun matchingEventGetsACueAndExcludedOnesDoNot() = runBlocking {
        setup()
        source.rows += row(1, "Planning", 60) // meeting: has attendees below
        source.attendees += RawAttendee(1, "a@example.com", 1, 1, 1)
        source.rows += row(2, "Planning (declined)", 60, self = 2).copy(eventId = 2)
        source.attendees += RawAttendee(2, "a@example.com", 1, 1, 1)
        source.rows += row(3, "Lunch", 90) // no rule matches -> default no cue
        val r = sync.sync("test")
        assertEquals(CalendarSyncStatus.Ok, r.status); assertEquals(3, r.events); assertEquals(3, r.added)
        assertEquals(3, cache.rows.size)
        assertEquals(Instant.ofEpochMilli(at(50)), scheduler.armed!!.first) // 10 min lead
        assertEquals(WakePrecision.Exact, scheduler.armed!!.second)
        runUntil(120)
        assertEquals(listOf("cal:1"), deliveredKeys())
    }

    @Test fun movedEventReschedulesAndCanceledRetracts() = runBlocking {
        setup()
        source.rows += row(1, "Review", 60); source.attendees += RawAttendee(1, "a@example.com", 1, 1, 1)
        sync.sync("t1")
        assertEquals(Instant.ofEpochMilli(at(50)), scheduler.armed!!.first)
        // Moved 1 h later before the cue.
        source.rows[0] = row(1, "Review", 120)
        val r = sync.sync("t2")
        assertEquals(1, r.changed)
        assertEquals(Instant.ofEpochMilli(at(110)), scheduler.armed!!.first)
        runUntil(112)
        assertEquals(listOf("cal:1"), deliveredKeys())
        val cueId = sink.delivered().last().id
        // Canceled after the cue was delivered: pending/visible cue is retracted.
        source.rows[0] = source.rows[0].copy(status = CalendarMapper.STATUS_CANCELED)
        sink.clear()
        sync.sync("t3")
        val dismiss = sink.effects.filterIsInstance<Effect.DismissCue>().single()
        assertEquals("cal:1", dismiss.notificationKey); assertEquals(cueId, dismiss.cueId)
        // Removed entirely: nothing left pending.
        source.rows.clear(); sync.sync("t4")
        assertTrue(host.snapshot.value!!.state.calendar.events.isEmpty())
    }

    @Test fun eventAddedShortlyBeforeStartGetsOneCueOnNextSync() = runBlocking {
        setup()
        sync.sync("empty")
        source.rows += row(7, "Quick sync", 4); source.attendees += RawAttendee(7, "a@example.com", 1, 1, 1)
        sync.sync("added") // 10 min lead already past, event not started: latest lead delivered once (CAL-3)
        assertEquals(listOf("cal:7"), deliveredKeys())
    }

    @Test fun permissionRevokedOrNothingSelectedClearsCalendar() = runBlocking {
        setup()
        source.rows += row(1, "Review", 60); source.attendees += RawAttendee(1, "a@example.com", 1, 1, 1)
        sync.sync("t1")
        permission = false
        assertEquals(CalendarSyncStatus.NoPermission, sync.sync("revoked").status)
        assertTrue(host.snapshot.value!!.state.calendar.events.isEmpty())
        assertTrue(cache.rows.isEmpty())
        permission = true
        host.applyOps(listOf(ConfigOp.RemoveCalendarPreference("1")), host.ensureLoaded().config.version, "ui")
        assertEquals(CalendarSyncStatus.NothingSelected, sync.sync("none").status)
        assertEquals(1, source.reads)
    }

    @Test fun providerFailureKeepsPreviousState() = runBlocking {
        setup()
        source.rows += row(1, "Review", 60); source.attendees += RawAttendee(1, "a@example.com", 1, 1, 1)
        sync.sync("t1")
        val failing = CalendarSync(object : CalendarSource by source {
            override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>): List<RawInstance> = throw SecurityException("revoked mid-read")
        }, cache, clock, { host.ensureLoaded().config }, { true }, { host.dispatch(it) }, previous = { emptyList() })
        val r = failing.sync("t2")
        assertEquals(CalendarSyncStatus.Failed, r.status)
        assertEquals(1, host.snapshot.value!!.state.calendar.events.size)
    }
}
