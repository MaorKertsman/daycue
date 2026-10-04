package app.daycue.integrations.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders

/**
 * Read-only access to `CalendarContract` (needs `READ_CALENDAR` only). Every call is a query; nothing
 * here ever inserts, updates or deletes. Callers check the permission first; a `SecurityException`
 * propagates.
 */
class ProviderCalendarSource(private val cr: ContentResolver) : CalendarSource {

    override fun calendars(): List<DeviceCalendar> {
        val proj = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_COLOR, Calendars.VISIBLE, Calendars.SYNC_EVENTS, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY,
        )
        return cr.query(Calendars.CONTENT_URI, proj, null, null, "${Calendars._ID} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) add(DeviceCalendar(
                    id = c.getLong(0), displayName = c.str(1) ?: "", accountName = c.str(2), accountType = c.str(3), ownerAccount = c.str(4),
                    color = c.int(5), visible = c.int(6) == 1, syncEvents = c.int(7) == 1, accessLevel = c.int(8), isPrimary = c.int(9) == 1,
                ))
            }
        }.orEmpty()
    }

    /** null = not probed yet; false = this provider rejects the optional columns. */
    @Volatile private var optionalOk: Boolean? = null

    override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>): List<RawInstance> {
        if (calendarIds.isEmpty()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, fromMs); ContentUris.appendId(it, toMs) }.build()
        val sel = "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
        val args = calendarIds.map { it.toString() }.toTypedArray()
        // Optional columns are probed: a provider that doesn't map one rejects the whole projection.
        if (optionalOk == false) return query(uri, CORE, sel, args)
        return try {
            query(uri, CORE + OPTIONAL, sel, args).also { optionalOk = true }
        } catch (e: IllegalArgumentException) {
            optionalOk = false
            query(uri, CORE, sel, args)
        }
    }

    private fun query(uri: Uri, proj: Array<String>, sel: String, args: Array<String>): List<RawInstance> =
        cr.query(uri, proj, sel, args, "${Instances.BEGIN} ASC")?.use { c ->
            val opt = proj.size > CORE.size
            buildList {
                while (c.moveToNext()) add(RawInstance(
                    instanceId = c.getLong(0), eventId = c.getLong(1), calendarId = c.getLong(2), title = c.str(3),
                    beginMs = c.getLong(4), endMs = c.getLong(5), allDay = c.int(6) == 1, eventTimezone = c.str(7),
                    status = c.int(8), selfStatus = c.int(9), availability = c.int(10), hasAlarm = c.int(11) == 1,
                    organizer = c.str(12), originalId = c.str(13)?.toLongOrNull(), originalInstanceTimeMs = c.long(14),
                    rrule = c.str(15), rdate = c.str(16), displayColor = c.int(17),
                    description = c.str(18), location = c.str(19),
                    uid = if (opt) c.str(20) else null,
                ))
            }
        }.orEmpty()

    override fun attendees(eventIds: Set<Long>): List<RawAttendee> = chunked(eventIds) { sel, args ->
        cr.query(Attendees.CONTENT_URI, arrayOf(Attendees.EVENT_ID, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID} IN ($sel)", args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(RawAttendee(c.getLong(0), c.str(1), c.int(2), c.int(3), c.int(4))) }
        }.orEmpty()
    }

    override fun reminders(eventIds: Set<Long>): List<RawReminder> = chunked(eventIds) { sel, args ->
        cr.query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.METHOD, Reminders.MINUTES), "${Reminders.EVENT_ID} IN ($sel)", args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(RawReminder(c.getLong(0), c.int(1), c.int(2))) }
        }.orEmpty()
    }

    private fun <T> chunked(ids: Set<Long>, q: (String, Array<String>) -> List<T>): List<T> =
        ids.chunked(400).flatMap { part -> q(part.joinToString(",") { "?" }, part.map { it.toString() }.toTypedArray()) }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)

    companion object {
        private val CORE = arrayOf(
            Instances._ID, Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.EVENT_TIMEZONE, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS, Instances.AVAILABILITY, Instances.HAS_ALARM,
            Instances.ORGANIZER, Instances.ORIGINAL_ID, Instances.ORIGINAL_INSTANCE_TIME, Instances.RRULE, Instances.RDATE,
            Instances.DISPLAY_COLOR, Instances.DESCRIPTION, Instances.EVENT_LOCATION,
        )
        private val OPTIONAL = arrayOf(Instances.UID_2445)

        /** Observed / content-trigger root: every provider change notifies under this authority. */
        val ROOT_URI: Uri = CalendarContract.CONTENT_URI
    }
}
