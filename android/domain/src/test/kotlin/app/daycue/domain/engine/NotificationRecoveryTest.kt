package app.daycue.domain.engine

import app.daycue.domain.config.CueType
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Medication
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.Queries
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.hydrationKey
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** VALIDATION D1 (MED-8, MED-4, GEN-7) and the owner's MED-11 catch-up decision (2026-10-05). */
class NotificationRecoveryTest {

    private fun med(id: String, vararg times: String) = Medication(id, "Synthetic $id", times.map { LocalTime.parse(it) }, repeat = RepeatPolicy(10, 1))

    /** Two due, unconfirmed doses and a hydration cue on screen at 10:30. */
    private fun scenario(): Scenario = Scenario().apply {
        apply(ConfigOp.UpsertMedication(med("loc1", "10:00")), ConfigOp.UpsertMedication(med("loc2", "10:10")))
        enable(Defaults.HYDRATION)
        advanceTo("10:30")
    }

    private fun Scenario.medDeliveries(effects: List<Effect>) = effects.filterIsInstance<Effect.Deliver>().map { it.cue }.filter { it.type == CueType.Medication }

    @Test
    fun `D1 reboot - two pending doses give ONE merged cue, the visible hydration cue is re-posted quietly, state matches reality`() {
        val s = scenario()
        val hyd = s.state.delivery.visible[hydrationKey()]!!
        assertTrue(s.state.delivery.visible.keys.containsAll(listOf("med:loc1|2026-10-05|10:00", "med:loc2|2026-10-05|10:10", hydrationKey())))
        val fx = s.reboot(offMinutes = 2)
        val med = s.medDeliveries(fx)
        assertEquals(listOf("med:merged"), med.map { it.itemKey }, "one merged cue, never one per dose")
        assertEquals("2", med.single().title.args["count"])
        assertFalse(med.single().silent, "MED-8 merged cue alerts like MED-7")
        assertEquals(LockScreenVisibility.Private, med.single().lockScreen)
        val hydRepost = fx.filterIsInstance<Effect.Deliver>().single { it.cue.itemKey == hydrationKey() }.cue
        assertEquals(hyd.cueId, hydRepost.id, "same cue id: its buttons keep working")
        assertTrue(hydRepost.silent && hydRepost.speech == null && hydRepost.soundId == null)
        assertEquals(setOf("med:merged", hydrationKey()), s.state.delivery.visible.keys, "visible = what is really posted after boot")
        assertTrue(s.history(HistoryKind.Reposted).any { it.itemKey == hydrationKey() })
        // No rapid-fire afterwards; the doses stay Due until confirmed.
        s.advanceTo("12:00")
        assertEquals(1, s.delivered.count { it.itemKey == "med:merged" })
        assertTrue(Queries.todayView(s.config, s.state, s.clock).doses.filter { it.slot.date == s.date }.all { it.status == DoseStatus.Due })
    }

    @Test
    fun `D1 reboot when a merged cue was already showing - it is delivered again (the old one is gone)`() {
        val s = scenario()
        s.reboot(offMinutes = 1)
        val first = s.state.medication.mergedCue!!.cueId
        s.advance(30)
        val fx = s.reboot(offMinutes = 1)
        val merged = s.medDeliveries(fx).single()
        assertEquals("med:merged", merged.itemKey)
        assertTrue(merged.id != first)
        assertEquals(merged.id, s.state.medication.mergedCue!!.cueId)
    }

    @Test
    fun `D1 reboot with one pending dose re-delivers it normally, a running snooze survives the reboot`() {
        val s = Scenario().apply { apply(ConfigOp.UpsertMedication(med("a", "10:00")), ConfigOp.UpsertMedication(med("b", "10:05"))); advanceTo("10:06") }
        val b = s.state.medication.slots.values.single { it.medicationId == "b" && it.date == s.date }
        s.send(Event.MedicationSnooze(b.ref, b.cue!!.cueId)) // snoozed until 10:16
        val fx = s.reboot(offMinutes = 2) // 10:08
        assertEquals(listOf("med:a|2026-10-05|10:00"), s.medDeliveries(fx).map { it.itemKey })
        s.advanceTo("10:17")
        assertEquals(s.t("10:16"), s.deliveredFor("med:b|2026-10-05|10:05").last().deliveredAt, "snooze kept its time")
    }

    @Test
    fun `reboot is detected on whichever event comes first after boot`() {
        val s = scenario()
        s.clock.elapsed = Duration.ofSeconds(30) // booted; a geofence signal or config edit arrives before BootCompleted
        val fx = s.send(Event.ConfigChanged)
        assertEquals(listOf("med:merged"), s.medDeliveries(fx).map { it.itemKey })
        assertTrue(s.send(Event.BootCompleted).filterIsInstance<Effect.Deliver>().isEmpty(), "no second recovery")
    }

    @Test
    fun `NotificationsObserved - missing cues are re-posted quietly with the same id, shown ones are left alone`() {
        val s = scenario()
        val visible = s.state.delivery.visible
        val fx = s.send(Event.NotificationsObserved(setOf("med:loc1|2026-10-05|10:00")))
        val re = fx.filterIsInstance<Effect.Deliver>().map { it.cue }
        assertEquals(setOf("med:loc2|2026-10-05|10:10", hydrationKey()), re.map { it.notificationKey }.toSet())
        re.forEach { c ->
            assertEquals(visible[c.notificationKey]!!.cueId, c.id)
            assertTrue(c.silent && c.speech == null && c.vibrationId == null)
        }
        assertEquals(visible.keys, s.state.delivery.visible.keys)
        assertTrue(s.send(Event.NotificationsObserved(visible.keys)).none { it is Effect.Deliver }, "all shown: nothing to do")
        // A re-posted dose still accepts its Taken button.
        val loc2 = s.state.medication.slots.values.single { it.medicationId == "loc2" && it.date == s.date }
        s.send(Event.MedicationTaken(loc2.ref, re.single { it.itemKey.startsWith("med:loc2") }.id))
        assertEquals(SlotStatus.Taken, s.state.medication.slots[loc2.key]!!.status)
    }

    @Test
    fun `MED-11 clock jump forward over a day - one merged catch-up cue counts never-cued doses of the last 48 h, none per dose`() {
        val s = Scenario(start = "07:00").apply { apply(ConfigOp.UpsertMedication(med("a", "08:00", "20:00"))) }
        // 07:30 Monday -> clock set to Tuesday 09:00 (Monday 08:00 and 20:00 and Tuesday 08:00 never cued).
        s.advanceTo("07:30")
        s.clock.instant = s.t("09:00", s.date.plusDays(1))
        val fx = s.send(Event.TimeChanged)
        val med = s.medDeliveries(fx)
        assertEquals(listOf("med:merged"), med.map { it.itemKey })
        assertEquals("3", med.single().title.args["count"])
        assertEquals("cue.medication.merged.title", med.single().title.key)
        assertEquals(LockScreenVisibility.Private, med.single().lockScreen)
        val doses = Queries.todayView(s.config, s.state, s.clock).doses
        assertTrue(doses.filter { it.slot.date == s.date }.all { it.status == DoseStatus.NotConfirmed }, "previous-day doses stay Not confirmed")
        s.advanceTo(s.t("12:00", s.date.plusDays(1)))
        assertEquals(1, s.delivered.count { it.itemKey == "med:merged" }, "one notice total")
        assertTrue(s.delivered.none { it.itemKey.startsWith("med:a|") }, "never one per dose")
    }

    @Test
    fun `MED-11 doses older than 48 hours are not counted`() {
        val s = Scenario(start = "07:00").apply { apply(ConfigOp.UpsertMedication(med("a", "08:00"))) }
        s.advanceTo("07:30")
        // Jump Monday 07:30 -> Wednesday 09:00: Monday 08:00 is outside the tracked window (49 h old) and only Tuesday and
        // Wednesday 08:00 are counted.
        s.clock.instant = s.t("09:00", s.date.plusDays(2))
        val fx = s.send(Event.TimeChanged)
        val merged = s.medDeliveries(fx).singleOrNull { it.itemKey == "med:merged" }
        assertNotNull(merged)
        assertEquals("2", merged.title.args["count"], "Tuesday 08:00 and Wednesday 08:00 only")
    }
}
