package app.daycue.integrations.calendar

import app.daycue.domain.config.EventAvailability
import app.daycue.domain.engine.EventStatus
import app.daycue.domain.engine.SelfResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Synthetic rows only (no real calendar contents). */
class CalendarMapperTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val t0 = Instant.parse("2026-10-05T09:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private fun raw(
        eventId: Long = 11, calendarId: Long = 1, title: String? = "Sync", begin: Long = t0, end: Long = t0 + hour,
        allDay: Boolean = false, status: Int? = 1, self: Int? = 1, availability: Int? = 0, hasAlarm: Boolean = false,
        organizer: String? = "owner@example.com", originalId: Long? = null, originalTime: Long? = null, rrule: String? = null,
        uid: String? = null, color: Int? = null, description: String? = null, location: String? = null,
    ) = RawInstance(1, eventId, calendarId, title, begin, end, allDay, "UTC", status, self, availability, hasAlarm, organizer,
        originalId, originalTime, rrule, null, uid, color, description, location)

    private val cal1 = DeviceCalendar(1, "Work", "owner@example.com", "com.google", "owner@example.com", null, true, true, 700, true)
    private val cal2 = DeviceCalendar(2, "Personal", "other@example.com", "com.google", "other@example.com", null, true, true, 700, true)

    @Test fun instanceKeysTrackSeriesExceptionsAndSingles() {
        assertEquals("11", CalendarMapper.instanceKey(raw()))
        assertNull(CalendarMapper.seriesId(raw()))
        val instance = raw(eventId = 20, rrule = "FREQ=WEEKLY", begin = t0)
        assertEquals("20@$t0", CalendarMapper.instanceKey(instance))
        assertEquals("20", CalendarMapper.seriesId(instance))
        // Exception that moved that instance one hour later keeps the instance key.
        val moved = raw(eventId = 31, originalId = 20, originalTime = t0, begin = t0 + hour, end = t0 + 2 * hour)
        assertEquals("20@$t0", CalendarMapper.instanceKey(moved))
        assertEquals("20", CalendarMapper.seriesId(moved))
    }

    @Test fun statusSelfAvailabilityAndTimes() {
        val e = CalendarMapper.map(raw(status = 2, self = 2, availability = 1), "owner@example.com", emptyList(), emptyList(), zone)
        assertEquals(EventStatus.Canceled, e.status); assertEquals(SelfResponse.Declined, e.self); assertEquals(EventAvailability.Free, e.availability)
        assertEquals(Instant.ofEpochMilli(t0), e.start)
        assertEquals(SelfResponse.NeedsAction, CalendarMapper.self(3))
        assertEquals(SelfResponse.Tentative, CalendarMapper.self(4))
        assertEquals(EventStatus.Tentative, CalendarMapper.status(0))
        assertEquals(EventStatus.Confirmed, CalendarMapper.status(null))
        assertEquals("tentative availability counts as busy", EventAvailability.Busy, CalendarMapper.map(raw(availability = 2), null, emptyList(), null, zone).availability)
    }

    @Test fun allDayIsLocalMidnight() {
        val day = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        val e = CalendarMapper.map(raw(allDay = true, begin = day, end = day + 24 * hour), null, emptyList(), null, zone)
        assertEquals(Instant.parse("2026-10-04T21:00:00Z"), e.start) // 00:00 in Asia/Jerusalem (UTC+3 in October)
        assertEquals(Instant.parse("2026-10-05T21:00:00Z"), e.end)
        assertTrue(e.allDay)
    }

    @Test fun attendeesExcludeSelfAndResources() {
        val atts = listOf(
            RawAttendee(11, "OWNER@example.com", 2, 1, 1),
            RawAttendee(11, "a@example.com", 1, 1, 3),
            RawAttendee(11, "room@resource.example.com", 1, CalendarMapper.TYPE_RESOURCE, 1),
            RawAttendee(11, null, 1, 1, 0),
        )
        assertEquals(2, CalendarMapper.otherAttendees(atts, "owner@example.com"))
    }

    @Test fun ownRemindersCountOnlyOnDeviceMethods() {
        assertTrue(CalendarMapper.hasOwnReminders(raw(hasAlarm = true), listOf(RawReminder(11, 1, 10))))
        assertFalse("email only", CalendarMapper.hasOwnReminders(raw(hasAlarm = true), listOf(RawReminder(11, 2, 30))))
        assertTrue("no rows: trust HAS_ALARM", CalendarMapper.hasOwnReminders(raw(hasAlarm = true), emptyList()))
        assertFalse(CalendarMapper.hasOwnReminders(raw(hasAlarm = false), emptyList()))
        assertTrue("query failed: HAS_ALARM", CalendarMapper.hasOwnReminders(raw(hasAlarm = true), null))
    }

    @Test fun conferencingAndColor() {
        assertTrue(CalendarMapper.hasConferencing(raw(description = "Join: https://meet.google.com/abc-defg-hij")))
        assertTrue(CalendarMapper.hasConferencing(raw(location = "https://example.zoom.us/j/123")))
        assertFalse(CalendarMapper.hasConferencing(raw(description = "Bring the meeting notes")))
        assertEquals("#3366CC", CalendarMapper.colorHex(0xFF3366CC.toInt()))
    }

    @Test fun duplicatesAcrossCalendarsCollapseToOneAcceptedCopy() {
        val cals = mapOf(1L to cal1, 2L to cal2)
        val a = raw(eventId = 11, calendarId = 1, uid = "uid-1", self = 2) // declined on work
        val b = raw(eventId = 55, calendarId = 2, uid = "uid-1", self = 1, organizer = "x@example.com") // accepted on personal
        val c = raw(eventId = 60, calendarId = 1, title = "Other", uid = null, begin = t0 + 3 * hour, end = t0 + 4 * hour)
        val noUidDup = raw(eventId = 61, calendarId = 2, title = "other ", uid = null, begin = t0 + 3 * hour, end = t0 + 4 * hour)
        val out = CalendarMapper.mapAll(listOf(a, b, c, noUidDup), cals, emptyList(), emptyList(), zone, Instant.ofEpochMilli(t0 - hour))
        assertEquals(listOf("55", "60"), out.map { it.key })
        assertEquals(SelfResponse.Accepted, out[0].self)
    }

    @Test fun sameUidDifferentInstancesAreNotDuplicates() {
        val cals = mapOf(1L to cal1)
        val i1 = raw(eventId = 20, rrule = "FREQ=DAILY", uid = "u", begin = t0, end = t0 + hour)
        val i2 = raw(eventId = 20, rrule = "FREQ=DAILY", uid = "u", begin = t0 + 24 * hour, end = t0 + 25 * hour)
        assertEquals(2, CalendarMapper.mapAll(listOf(i1, i2), cals, emptyList(), emptyList(), zone, Instant.ofEpochMilli(t0 - hour)).size)
    }

    @Test fun endedEventsAndDeletedRowsAreDropped() {
        val cals = mapOf(1L to cal1)
        val past = raw(eventId = 1, begin = t0 - 3 * hour, end = t0 - 2 * hour)
        val inProgress = raw(eventId = 2, begin = t0 - hour, end = t0 + hour)
        val deleted = raw(eventId = 3).copy(deleted = true)
        assertEquals(listOf("2"), CalendarMapper.mapAll(listOf(past, inProgress, deleted), cals, emptyList(), emptyList(), zone, Instant.ofEpochMilli(t0)).map { it.key })
    }
}
