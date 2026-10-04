package app.daycue.domain.engine

import app.daycue.domain.config.Medication
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.QuietHours
import app.daycue.domain.config.QuietWindow
import app.daycue.domain.config.TravelPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.Queries
import app.daycue.domain.testing.Scenario
import app.daycue.domain.time.TimeWindow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MedicationTest {

    private fun med(vararg times: String, policy: TravelPolicy = TravelPolicy.FollowLocalTime) =
        Medication("med-a", "Synthetic A", times.map { LocalTime.parse(it) }, travelPolicy = policy)

    private fun scenario(m: Medication = med("08:00"), zone: ZoneId = ZoneId.of("UTC"), date: LocalDate = LocalDate.of(2026, 10, 5), start: String = "06:00") =
        Scenario(date = date, zone = zone, start = start).apply { apply(ConfigOp.UpsertMedication(m)) }

    private fun Scenario.medCues() = delivered.filter { it.type == app.daycue.domain.config.CueType.Medication }
    private fun Scenario.slot(date: LocalDate = this.date, time: String = "08:00") = state.medication.slots[SlotRef("med-a", date, LocalTime.parse(time)).key]!!

    @Test
    fun `acceptance 4 - MED-2 dismissal does not mark taken, MED-4 repeats then the slot stays Due`() {
        val s = scenario()
        s.advanceTo("08:01")
        val cue = s.medCues().single()
        assertEquals(s.t("08:00"), cue.deliveredAt)
        s.send(Event.CueDismissed(cue.id))
        assertEquals(SlotStatus.Due, s.slot().status)
        assertNull(s.slot().takenAt)
        s.advanceTo("09:00")
        assertEquals(listOf("08:00", "08:10", "08:20", "08:30").map { s.t(it) }, s.medCues().map { it.deliveredAt })
        assertEquals(SlotStatus.Due, s.slot().status)
        assertTrue(s.slot().cue!!.exhausted)
        assertFalse("med:${s.slot().key}" in s.dismissedKeys(), "MED-4: notification is not auto-cancelled")
        assertEquals(DoseStatus.Due, Queries.todayView(s.config, s.state, s.clock).doses.first { it.slot.date == s.date }.status)
        s.advanceTo(s.t("04:01", s.date.plusDays(1)))
        assertEquals(DoseStatus.NotConfirmed, Queries.todayView(s.config, s.state, s.clock).doses.first { it.slot.date == s.date }.status, "MED-1 Not confirmed after the day boundary")
        assertTrue(s.history(HistoryKind.NotConfirmed).isNotEmpty())
    }

    @Test
    fun `MED-2 Taken marks taken, stops repeats, and is idempotent`() {
        val s = scenario()
        s.advanceTo("08:03")
        val id = s.medCues().single().id
        s.send(Event.MedicationTaken(s.slot().ref, id))
        s.send(Event.MedicationTaken(s.slot().ref, id))
        assertEquals(SlotStatus.Taken, s.slot().status)
        assertEquals(s.t("08:03"), s.slot().takenAt)
        assertEquals(1, s.history(HistoryKind.Taken).size)
        s.advanceTo("09:00")
        assertEquals(1, s.medCues().size)
    }

    @Test
    fun `MED-5 Taken accepted early for an Upcoming slot of today`() {
        val s = scenario()
        s.at("07:30", Event.MedicationTaken(SlotRef("med-a", s.date, LocalTime.of(8, 0))))
        s.advanceTo("09:00")
        assertTrue(s.medCues().isEmpty())
    }

    @Test
    fun `MED-3 never suppressed by quiet hours, global pause, a running routine or meetings (meeting only mutes speech)`() {
        val s = scenario()
        s.config = s.config.copy(settings = s.config.settings.copy(quietHours = QuietHours(true, listOf(QuietWindow(TimeWindow(LocalTime.of(7, 0), LocalTime.of(9, 0))))),
            pauseAll = PauseSpec.Until(s.now, s.t("12:00"))))
        s.send(Event.ConfigChanged)
        s.send(Event.CalendarSynced(listOf(CalendarEvent("m", calendarId = "c", start = s.t("07:55"), end = s.t("08:30"), otherAttendees = 3)), s.now))
        s.send(Event.RoutineControl(RoutineAction.Start(app.daycue.domain.config.Defaults.MORNING_ROUTINE)))
        s.advanceTo("08:01")
        val c = s.medCues().single()
        assertEquals(s.t("08:00"), c.deliveredAt)
        assertNull(c.speech, "§8.5 inMeeting NotificationOnly")
        assertTrue(c.vibrationId != null)
    }

    @Test
    fun `lock screen generic by default and speech never includes the label (SPK-4)`() {
        val s = scenario()
        s.advanceTo("08:01")
        val c = s.medCues().single()
        assertEquals(LockScreenVisibility.Private, c.lockScreen)
        assertEquals("cue.medication.generic.title", c.publicTitle!!.key)
        assertEquals("speech.medication.generic", c.speech!!.lead.key)
        assertTrue(c.speech!!.lead.args.isEmpty())
    }

    @Test
    fun `snooze re-delivers after snooze minutes and restarts repeats`() {
        val s = scenario()
        s.advanceTo("08:02")
        s.send(Event.MedicationSnooze(s.slot().ref, s.medCues().single().id))
        s.advanceTo("08:13")
        assertEquals(listOf(s.t("08:00"), s.t("08:12")), s.medCues().map { it.deliveredAt })
    }

    @Test
    fun `a medication added after its time today is not cued for that slot`() {
        val s = scenario(start = "09:00")
        s.advanceTo("10:00")
        assertTrue(s.medCues().isEmpty())
        assertEquals("created_after_time", s.slot().noCueReason)
        s.advanceTo(s.t("08:01", s.date.plusDays(1)))
        assertEquals(1, s.medCues().size)
    }

    @Test
    fun `MED-6 DST gap - nonexistent local time fires at the transition instant`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val day = LocalDate.of(2027, 3, 28) // 02:00 -> 03:00
        val s = scenario(med("02:30"), berlin, day, start = "00:00")
        s.advanceTo("04:00")
        assertEquals(java.time.Instant.parse("2027-03-28T01:00:00Z"), s.medCues().first().deliveredAt)
    }

    @Test
    fun `MED-6 DST overlap - repeated local time fires once at the first occurrence`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val day = LocalDate.of(2026, 10, 25) // 03:00 -> 02:00
        val s = scenario(med("02:30").copy(repeat = app.daycue.domain.config.RepeatPolicy(10, 0)), berlin, day, start = "00:00")
        s.advanceTo("05:00")
        assertEquals(listOf(java.time.Instant.parse("2026-10-25T00:30:00Z")), s.medCues().map { it.deliveredAt })
    }

    @Test
    fun `MED-7 timezone change - taken slots never re-cued, newly past slots get ONE merged cue plus a policy notice`() {
        val s = scenario(med("08:00", "12:00", "16:00"))
        s.advanceTo("08:01")
        s.send(Event.MedicationTaken(s.slot().ref, s.medCues().single().id))
        s.advanceTo("10:00")
        s.clock.zoneId = ZoneId.of("Asia/Tokyo") // local 19:00: 12:00 and 16:00 are now in the past
        s.send(Event.TimezoneChanged)
        val cues = s.lastDelivered()
        val merged = cues.filter { it.itemKey == "med:merged" }
        assertEquals(1, merged.size)
        assertEquals("2", merged.single().title.args["count"])
        assertEquals(0, cues.count { it.type == app.daycue.domain.config.CueType.Medication && it.itemKey != "med:merged" }, "never one per slot")
        assertTrue(cues.any { it.itemKey == "med:policy" }, "one-time informational notice")
        assertEquals(SlotStatus.Taken, s.slot().status)
        s.advance(30)
        assertEquals(1, s.delivered.count { it.itemKey == "med:merged" })
        assertEquals(1, s.delivered.count { it.itemKey == "med:policy" })
    }

    @Test
    fun `MED-7 KeepHomeTimezone keeps instants - no merged cue`() {
        val s = scenario(med("08:00", "12:00", policy = TravelPolicy.KeepHomeTimezone(ZoneId.of("UTC"))))
        s.advanceTo("10:00")
        val noon = s.t("12:00") // computed in UTC before the zone changes
        s.clock.zoneId = ZoneId.of("Asia/Tokyo")
        s.send(Event.TimezoneChanged)
        assertTrue(s.lastDelivered().none { it.itemKey == "med:merged" })
        s.advanceTo(noon.plusSeconds(60))
        assertTrue(s.medCues().any { it.deliveredAt == noon })
    }

    @Test
    fun `MED-8 boot recovery - one merged cue for today's due slots, no rapid-fire`() {
        val s = scenario(med("08:00", "12:00"))
        s.advanceTo("07:50")
        s.reboot(offMinutes = 280) // back at 12:30
        val med = s.lastDelivered().filter { it.type == app.daycue.domain.config.CueType.Medication }
        assertEquals(listOf("med:merged"), med.map { it.itemKey })
        s.advanceTo("14:00")
        assertEquals(1, s.medCues().size)
    }

    @Test
    fun `MED-8 boot with one due slot re-delivers it once`() {
        val s = scenario(med("08:00"))
        s.advanceTo("07:50")
        s.reboot(offMinutes = 30)
        assertEquals(listOf(s.t("08:20")), s.medCues().map { it.deliveredAt })
        assertTrue(s.history(HistoryKind.Delivered).any { it.rule == "MED-8" })
    }

    @Test
    fun `MED-9 deleting a medication retracts its notification and drops slots`() {
        val s = scenario()
        s.advanceTo("08:01")
        s.apply(ConfigOp.DeleteMedication("med-a"))
        assertTrue(s.state.medication.slots.isEmpty())
        assertTrue(s.dismissedKeys().any { it.startsWith("med:med-a") })
    }
}
