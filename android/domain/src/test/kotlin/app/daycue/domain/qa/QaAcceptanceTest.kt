package app.daycue.domain.qa

import app.daycue.domain.config.CueType
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MedicationQuietHours
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.config.TravelPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.PostureAction
import app.daycue.domain.engine.PosturePhase
import app.daycue.domain.config.SessionKind
import app.daycue.domain.engine.SlotRef
import app.daycue.domain.engine.SlotStatus
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.sunscreenKey
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent QA checks written from docs/PRODUCT.md and docs/ACCEPTANCE.md, not from the implementation.
 * Each test cites the rule IDs it verifies. All data is synthetic.
 */
class QaAcceptanceTest {
    private fun med(id: String, vararg times: String, tz: TravelPolicy = TravelPolicy.FollowLocalTime, quiet: MedicationQuietHours = MedicationQuietHours.DeliverNormally) =
        Medication(id, "Synthetic $id", times.map { LocalTime.parse(it) }, travelPolicy = tz, repeat = RepeatPolicy(10, 3), quietHours = quiet)

    private fun Scenario.medCues() = delivered.filter { it.type == CueType.Medication }

    @Test
    fun `scenario 2 - outdoor, long indoor visit, outdoor again keeps the last ack and creates no second timer (SUN-6, SUN-7, GEN-2)`() {
        val s = Scenario(start = "09:00")
        s.enable(Defaults.SUNSCREEN)
        s.advanceTo("10:00"); s.outdoors()
        s.at("10:05", Event.HabitAck(Defaults.SUNSCREEN))
        val ack = s.state.intervals.getValue(Defaults.SUNSCREEN).lastAckAt
        assertEquals(s.t("10:05"), ack)
        s.advanceTo("10:20"); s.indoors()
        s.advanceTo("10:50"); s.outdoors()
        s.advanceTo("11:30")
        val st = s.state.intervals.getValue(Defaults.SUNSCREEN)
        assertEquals(ack, st.lastAckAt, "SUN-6: lastAppliedAt preserved across indoor visits and overrides")
        assertEquals(0, s.deliveredFor(sunscreenKey()).count { it.deliveredAt.isAfter(s.t("10:05")) }, "SUN-3: covered until 12:05, no cue")
        assertEquals(s.t("12:05"), st.dueAt, "SUN-2: due = ack + interval")
        s.advanceTo("12:10")
        val cues = s.deliveredFor(sunscreenKey()).filter { it.deliveredAt.isAfter(s.t("12:00")) }
        assertEquals(1, cues.map { it.id }.distinct().size, "GEN-2: exactly one cue for the item at 12:05")
    }

    @Test
    fun `scenario 3 - posture pause and resume keeps the mode and the remaining time after a long pause (POS-4, POS-6)`() {
        val s = Scenario(start = "09:00")
        s.apply(ConfigOp.SetPostureEnabled(true))
        s.send(Event.StartSession(SessionKind.Working))
        val mode = s.state.posture.modeId
        assertNotNull(mode)
        s.advanceTo("09:10")
        s.send(Event.PostureControl(PostureAction.Pause))
        assertEquals(PosturePhase.Paused, s.state.posture.phase)
        val remaining = s.state.posture.remainingMs
        assertNotNull(remaining)
        assertEquals(20 * 60_000L, remaining, "30 min mode, 10 min elapsed")
        s.advanceTo("09:20")                                  // 10 min pause is within shortInterruption (15 min)
        assertEquals(remaining, s.state.posture.remainingMs)
        s.send(Event.PostureControl(PostureAction.Resume))
        assertEquals(mode, s.state.posture.modeId, "same mode after a manual pause/resume")
        assertEquals(PosturePhase.Running, s.state.posture.phase)
        assertEquals(s.t("09:40"), s.state.posture.modeEndsAt, "POS-6: <= shortInterruption continues the 20 min that were left")
        // A pause LONGER than shortInterruption applies longInterruption = ResetToFirst (POS-6): first mode, full time.
        s.advanceTo("09:30"); s.send(Event.PostureControl(PostureAction.Pause))
        s.advanceTo("10:30"); s.send(Event.PostureControl(PostureAction.Resume))
        assertEquals(s.t("11:00"), s.state.posture.modeEndsAt, "POS-6: 60 min pause resets to the first mode with its full 30 min")
    }

    @Test
    fun `scenario 4 - dismissing a medication cue never marks it taken, it repeats and stays due (GEN-1, MED-2, MED-4)`() {
        val s = Scenario(start = "07:00")
        s.apply(ConfigOp.UpsertMedication(med("m1", "08:00")))
        s.advanceTo("08:00:30")
        val cue = s.medCues().last()
        s.send(Event.CueDismissed(cue.id))
        val slot = s.state.medication.slots.values.single { it.date == s.date }
        assertEquals(SlotStatus.Due, slot.status)
        val before = s.medCues().size
        s.advanceTo("08:11")
        assertTrue(s.medCues().size > before, "MED-4: repeat after dismissal")
        assertEquals(SlotStatus.Due, s.state.medication.slots.values.single { it.date == s.date }.status)
        s.send(Event.MedicationTaken(SlotRef("m1", s.date, LocalTime.of(8, 0))))
        assertEquals(SlotStatus.Taken, s.state.medication.slots.values.single { it.date == s.date }.status, "only an explicit Taken acks")
    }

    @Test
    fun `scenario 12 - simultaneous cues are merged into one audible group and no medication is dropped, also in quiet hours (COL-1 to COL-3, MED-3, QH-3)`() {
        val s = Scenario(start = "09:00")
        s.apply(ConfigOp.SetPostureEnabled(true))
        s.enable(Defaults.HYDRATION)
        s.send(Event.StartSession(SessionKind.Working))
        s.apply(
            ConfigOp.UpsertMedication(med("ma", "10:00")), ConfigOp.UpsertMedication(med("mb", "10:00")),
            ConfigOp.UpsertMedication(med("mc", "10:01", quiet = MedicationQuietHours.DeliverSilently)),
        )
        s.advanceTo("10:05")
        val meds = s.medCues().filter { it.repeatIndex == 0 }.map { it.itemKey }.toSet()
        assertEquals(3, meds.size, "every medication slot produced its own cue (COL-2)")
        val audibleLeads = s.delivered.filter { it.deliveredAt >= s.t("10:00") && it.deliveredAt < s.t("10:02") && !it.silent && it.groupLead }
        assertTrue(audibleLeads.size <= 2, "COL-1/COL-3: sounds in the 10:00-10:02 cluster are merged or spaced, got ${audibleLeads.size}")
        s.apply(ConfigOp.SetQuietHours(Defaults.config().settings.quietHours.copy(enabled = true)))
        s.apply(ConfigOp.UpsertMedication(med("mq", "23:00")), ConfigOp.UpsertMedication(med("mqs", "23:00", quiet = MedicationQuietHours.DeliverSilently)))
        s.clear()
        s.advanceTo("23:05")
        val q = s.medCues().filter { it.itemKey.contains("mq") }
        assertEquals(2, q.map { it.itemKey }.distinct().size, "MED-3: both quiet-hours doses delivered")
        assertTrue(!q.first { it.itemKey.contains("mq|") }.silent, "QH-3: DeliverNormally is audible in quiet hours")
        assertTrue(q.first { it.itemKey.contains("mqs") }.silent, "DeliverSilently honoured")
        assertTrue(s.delivered.none { (it.type == CueType.Hydration || it.type == CueType.Posture) && it.deliveredAt >= s.t("22:30") }, "QH-1: P7/P8 held in quiet hours")
    }

    @Test
    fun `scenario 17 - timezone change follows each medication travel policy, one merged cue, taken slots never re-cued (MED-7)`() {
        val s = Scenario(start = "06:00")
        s.apply(
            ConfigOp.UpsertMedication(med("loc1", "09:00")), ConfigOp.UpsertMedication(med("loc2", "09:00")),
            ConfigOp.UpsertMedication(med("home", "09:00", tz = TravelPolicy.KeepHomeTimezone(ZoneId.of("UTC")))),
            ConfigOp.UpsertMedication(med("done", "08:00")),
        )
        s.advanceTo("08:30")
        s.send(Event.MedicationTaken(SlotRef("done", s.date, LocalTime.of(8, 0))))
        s.advanceTo("08:55")
        s.clear()
        s.clock.zoneId = ZoneId.of("Asia/Tokyo")
        s.send(Event.TimezoneChanged)
        val merged = s.medCues().filter { it.itemKey.endsWith("merged") }
        assertEquals(1, merged.size, "MED-7: one merged cue, not one per slot")
        assertTrue(s.medCues().none { it.itemKey.contains("done") }, "MED-7: a Taken slot is never re-cued")
        assertTrue(s.medCues().none { it.itemKey.contains("home") }, "KeepHomeTimezone: the home slot is still in the future")
    }

    @Test
    fun `scenario 17 - manual clock change backwards does not replay delivered cues, forwards delivers at most once per item (GEN-7)`() {
        val s = Scenario(start = "09:00")
        s.enable(Defaults.HYDRATION)
        s.advanceTo("12:00")
        s.clear()
        s.clock.instant = s.clock.instant.minus(Duration.ofHours(2)); s.send(Event.TimeChanged)
        assertTrue(s.delivered.isEmpty(), "going back 2 h replays nothing")
        s.clock.instant = s.clock.instant.plus(Duration.ofHours(30)); s.send(Event.TimeChanged)
        assertTrue(s.deliveredFor("habit:${Defaults.HYDRATION}").map { it.id }.distinct().size <= 1, "no catch-up of missed occurrences one by one")
    }

    @Test
    fun `scenario 7 - jittering geofence signals around a boundary produce no extra place transitions (CTX-2, CTX-3)`() {
        val s = Scenario(start = "08:00")
        s.enter(Defaults.OFFICE); s.advanceTo("08:10")
        val since = s.state.context.place.since
        for (i in 0 until 12) { s.advance(1); s.exit(Defaults.OFFICE); s.advance(2); s.enter(Defaults.OFFICE) }
        assertEquals(since, s.state.context.place.since, "place never re-confirmed during flapping")
    }
}
