package app.daycue.integrations.calendar

/*
 * Raw rows read from the Android Calendar Provider (docs/adr/0004-calendar-provider.md). Read-only:
 * DayCue never inserts, updates or deletes anything in the provider. Titles, descriptions and locations
 * are untrusted third-party text (CAL-5): description/location are only scanned for a conferencing
 * host name and never stored; the title is stored on-device for display/keyword matching only.
 */

/** One row of `CalendarContract.Calendars` (for the selection list). */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
    /** Email of the calendar owner; identifies "self" among attendees. */
    val ownerAccount: String?,
    val color: Int?,
    val visible: Boolean,
    val syncEvents: Boolean,
    val accessLevel: Int?,
    val isPrimary: Boolean,
)

/** One row of `CalendarContract.Instances` (recurrences already expanded by the provider). */
data class RawInstance(
    val instanceId: Long,
    val eventId: Long,
    val calendarId: Long,
    val title: String?,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val eventTimezone: String?,
    /** `Events.STATUS`: 0 tentative, 1 confirmed, 2 canceled; null = unknown. */
    val status: Int?,
    /** `Events.SELF_ATTENDEE_STATUS`: 0 none, 1 accepted, 2 declined, 3 invited, 4 tentative. */
    val selfStatus: Int?,
    /** `Events.AVAILABILITY`: 0 busy, 1 free, 2 tentative. */
    val availability: Int?,
    val hasAlarm: Boolean,
    val organizer: String?,
    /** Set for an exception (modified instance) of a recurring series: the series' event id. */
    val originalId: Long?,
    /** Original start (UTC ms) of the instance this exception replaces. */
    val originalInstanceTimeMs: Long?,
    val rrule: String?,
    val rdate: String?,
    /** iCalendar UID (`Events.UID_2445`): identical for the same meeting synced into two calendars. */
    val uid: String?,
    val displayColor: Int?,
    val description: String?,
    val location: String?,
    val deleted: Boolean = false,
)

data class RawAttendee(val eventId: Long, val email: String?, val relationship: Int?, val type: Int?, val status: Int?)

data class RawReminder(val eventId: Long, val method: Int?, val minutes: Int?)

/** Abstraction over the provider so sync logic is JVM-testable with fakes. */
interface CalendarSource {
    fun calendars(): List<DeviceCalendar>
    /** Instances overlapping `[fromMs, toMs)` in [calendarIds]. */
    fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>): List<RawInstance>
    fun attendees(eventIds: Set<Long>): List<RawAttendee>
    fun reminders(eventIds: Set<Long>): List<RawReminder>
}
