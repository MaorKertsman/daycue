package app.daycue.integrations.calendar

import app.daycue.domain.config.EventAvailability
import app.daycue.domain.engine.CalendarEvent
import app.daycue.domain.engine.EventStatus
import app.daycue.domain.engine.SelfResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Provider rows -> domain [CalendarEvent] (pure, unit-tested). Constants mirror `CalendarContract`
 * (stable public API values) so this runs on the JVM.
 */
object CalendarMapper {
    // Events.STATUS
    const val STATUS_TENTATIVE = 0; const val STATUS_CONFIRMED = 1; const val STATUS_CANCELED = 2
    // Attendees.ATTENDEE_STATUS / Events.SELF_ATTENDEE_STATUS
    const val ATT_NONE = 0; const val ATT_ACCEPTED = 1; const val ATT_DECLINED = 2; const val ATT_INVITED = 3; const val ATT_TENTATIVE = 4
    // Events.AVAILABILITY
    const val AVAIL_BUSY = 0; const val AVAIL_FREE = 1; const val AVAIL_TENTATIVE = 2
    // Attendees.ATTENDEE_TYPE
    const val TYPE_RESOURCE = 3
    // Reminders.METHOD: DEFAULT 0, ALERT 1, EMAIL 2, SMS 3, ALARM 4. Only the ones that alert on this phone count.
    private val ON_DEVICE_REMINDER_METHODS = setOf(0, 1, 4)

    /** Host names of conferencing services; matched in description/location only (never stored, never followed). */
    private val CONFERENCING = Regex(
        """(?i)\b(meet\.google\.com|[a-z0-9-]+\.zoom\.us|zoom\.us/j|teams\.microsoft\.com|teams\.live\.com|[a-z0-9-]+\.webex\.com|whereby\.com|gotomeeting\.com|meet\.jit\.si|chime\.aws)\b""",
    )

    /**
     * Stable instance identity, so moves/reschedules/exceptions are tracked (CAL-2):
     * - exception of a series: `<seriesEventId>@<originalInstanceTime>` — the same key the unmodified
     *   instance had, so a rescheduled instance keeps its key and only its start changes;
     * - instance of a recurring series: `<eventId>@<begin>` (begin == original start when unmodified);
     * - single event: `<eventId>` (a moved single event keeps its key).
     */
    fun instanceKey(r: RawInstance): String = when {
        r.originalId != null && r.originalInstanceTimeMs != null -> "${r.originalId}@${r.originalInstanceTimeMs}"
        isRecurring(r) -> "${r.eventId}@${r.beginMs}"
        else -> "${r.eventId}"
    }

    fun seriesId(r: RawInstance): String? = r.originalId?.toString() ?: if (isRecurring(r)) r.eventId.toString() else null

    private fun isRecurring(r: RawInstance) = !r.rrule.isNullOrBlank() || !r.rdate.isNullOrBlank()

    /**
     * All-day instances are stored by the provider as UTC midnights of their dates; they are placed at
     * local midnight of the device zone (what the user sees in their calendar app). Timed events are
     * absolute instants already (the provider applied `EVENT_TIMEZONE`).
     */
    fun start(r: RawInstance, zone: ZoneId): Instant =
        if (r.allDay) utcDate(r.beginMs).atStartOfDay(zone).toInstant() else Instant.ofEpochMilli(r.beginMs)

    fun end(r: RawInstance, zone: ZoneId): Instant =
        if (r.allDay) utcDate(r.endMs).atStartOfDay(zone).toInstant() else Instant.ofEpochMilli(maxOf(r.endMs, r.beginMs))

    private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    fun status(s: Int?): EventStatus = when (s) {
        STATUS_TENTATIVE -> EventStatus.Tentative
        STATUS_CANCELED -> EventStatus.Canceled
        else -> EventStatus.Confirmed
    }

    fun self(s: Int?): SelfResponse = when (s) {
        ATT_ACCEPTED -> SelfResponse.Accepted
        ATT_DECLINED -> SelfResponse.Declined
        ATT_INVITED -> SelfResponse.NeedsAction
        ATT_TENTATIVE -> SelfResponse.Tentative
        else -> SelfResponse.None
    }

    fun colorHex(c: Int?): String? = c?.let { String.format("#%06X", it and 0xFFFFFF) }

    fun hasConferencing(r: RawInstance): Boolean =
        listOfNotNull(r.location, r.description).any { CONFERENCING.containsMatchIn(it) }

    /** Attendees besides self (owner account) and resources (rooms, equipment). */
    fun otherAttendees(attendees: List<RawAttendee>, ownerAccount: String?): Int =
        attendees.count { a ->
            a.type != TYPE_RESOURCE && !(ownerAccount != null && a.email != null && a.email.equals(ownerAccount, ignoreCase = true))
        }

    fun hasOwnReminders(r: RawInstance, reminders: List<RawReminder>?): Boolean =
        if (reminders == null || (reminders.isEmpty() && r.hasAlarm)) r.hasAlarm
        else reminders.any { it.method == null || it.method in ON_DEVICE_REMINDER_METHODS }

    fun map(r: RawInstance, ownerAccount: String?, attendees: List<RawAttendee>, reminders: List<RawReminder>?, zone: ZoneId): CalendarEvent =
        CalendarEvent(
            key = instanceKey(r),
            seriesId = seriesId(r),
            calendarId = r.calendarId.toString(),
            title = r.title.orEmpty(),
            start = start(r, zone),
            end = end(r, zone),
            allDay = r.allDay,
            status = status(r.status),
            self = self(r.selfStatus),
            otherAttendees = otherAttendees(attendees, ownerAccount),
            hasConferencingLink = hasConferencing(r),
            color = colorHex(r.displayColor),
            availability = if (r.availability == AVAIL_FREE) EventAvailability.Free else EventAvailability.Busy,
            hasOwnReminders = hasOwnReminders(r, reminders),
        )

    /**
     * The same meeting synced into several of the owner's calendars (e.g. invited on two accounts) must
     * produce one cue (GEN-2; the engine keys records by `CalendarEvent.key`). Copies are grouped by iCal
     * UID + start (fallback when a copy has no UID: title + start + end + allDay) and one copy is kept:
     * accepted > tentative > needs-action/none > declined > canceled, then the copy where self organizes,
     * then the lowest calendar id (deterministic, so the kept key is stable between syncs).
     * Rationale for "accepted wins": people commonly decline the duplicate invitation on their second
     * account; one cue is better than none.
     */
    fun dedupe(items: List<Pair<CalendarEvent, Dedup>>): List<CalendarEvent> =
        items.groupBy { (e, d) -> d.uid?.takeIf { it.isNotBlank() }?.let { "uid:$it@${e.start}" } ?: "t:${e.title.trim().lowercase()}@${e.start}@${e.end}@${e.allDay}" }
            .values.map { group ->
                group.minWith(compareBy<Pair<CalendarEvent, Dedup>>({ rank(it.first) }, { if (it.second.selfOrganizer) 0 else 1 }, { it.first.calendarId.toLongOrNull() ?: Long.MAX_VALUE }, { it.first.key })).first
            }
            .sortedWith(compareBy({ it.start }, { it.key }))

    data class Dedup(val uid: String?, val selfOrganizer: Boolean)

    private fun rank(e: CalendarEvent): Int = when {
        e.status == EventStatus.Canceled -> 5
        e.self == SelfResponse.Declined -> 4
        e.self == SelfResponse.Accepted -> 0
        e.self == SelfResponse.Tentative -> 1
        else -> 2
    }

    /** Maps a full provider read, dedupes, and drops deleted rows and events that already ended. */
    fun mapAll(
        instances: List<RawInstance>, calendars: Map<Long, DeviceCalendar>, attendees: List<RawAttendee>, reminders: List<RawReminder>?,
        zone: ZoneId, now: Instant,
    ): List<CalendarEvent> {
        val attBy = attendees.groupBy { it.eventId }
        val remBy = reminders?.groupBy { it.eventId }
        val mapped = instances.filter { !it.deleted }.map { r ->
            val owner = calendars[r.calendarId]?.ownerAccount
            val e = map(r, owner, attBy[r.eventId].orEmpty(), remBy?.let { it[r.eventId].orEmpty() }, zone)
            e to Dedup(r.uid, owner != null && r.organizer != null && r.organizer.equals(owner, ignoreCase = true))
        }.filter { it.first.end.isAfter(now) || it.first.start.isAfter(now) }
        return dedupe(mapped)
    }
}
