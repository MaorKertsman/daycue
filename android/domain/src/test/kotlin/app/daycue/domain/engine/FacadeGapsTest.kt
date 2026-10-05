package app.daycue.domain.engine

import app.daycue.domain.config.Confidence
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.FirstRunSeed
import app.daycue.domain.config.Language
import app.daycue.domain.config.Medication
import app.daycue.domain.context.ContextSource
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.context.PlaceValue
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.query.Queries
import app.daycue.domain.testing.Scenario
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI-requested engine features (2026-10-05): manual place, dose time / corrections (MED-5), first-run seed. */
class FacadeGapsTest {

    private fun Scenario.place() = Queries.todayView(config, state, clock).context.place

    private fun withPlaces() = Scenario()

    @Test
    fun `CTX-1 I'm at a place corrects a wrong automatic place, UntilTransition ends at the next automatic change`() {
        val s = withPlaces()
        s.at("09:00", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.HOME, app.daycue.domain.signal.GeofenceTransitionKind.Enter, s.t("09:00"))))
        s.advanceTo("09:05")
        assertEquals(PlaceValue.saved(Defaults.HOME), s.place().value)
        s.send(Event.OverridePlace(Defaults.OFFICE))
        assertEquals(PlaceValue.saved(Defaults.OFFICE), s.place().value)
        assertEquals(ContextSource.Manual, s.place().source)
        assertEquals(Confidence.High, s.place().confidence)
        // Typical environment follows the manual place; a session started now is at the office.
        s.send(Event.StartSession(app.daycue.domain.config.SessionKind.Working))
        assertEquals(Defaults.OFFICE, s.state.context.session!!.placeId)
        // The geofence later confirms leaving home (meaningful automatic transition): the override ends.
        s.at("10:00", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.HOME, app.daycue.domain.signal.GeofenceTransitionKind.Exit, s.t("10:00"))))
        s.advanceTo("10:10")
        assertNull(s.state.context.placeOverride)
        assertEquals(PlaceKind.Elsewhere, s.place().value.kind)
    }

    @Test
    fun `CTX-1 not at a saved place, For and clear, unknown place ignored`() {
        val s = withPlaces()
        s.at("09:00", Event.OverridePlace(null, OverrideDuration.For(30)))
        assertEquals(PlaceValue.ELSEWHERE, s.place().value)
        s.advanceTo("09:31")
        assertNull(s.state.context.placeOverride, "For(30) expired")
        s.send(Event.OverridePlace(Defaults.GYM, OverrideDuration.UntilChanged))
        s.send(Event.ClearPlaceOverride)
        assertNull(s.state.context.placeOverride)
        s.send(Event.OverridePlace("no-such-place"))
        assertNull(s.state.context.placeOverride)
        // Survives process death.
        s.send(Event.OverridePlace(Defaults.GYM, OverrideDuration.RestOfToday))
        s.processRestart()
        assertEquals(PlaceValue.saved(Defaults.GYM), s.place().value)
    }

    private fun medScenario() = Scenario().apply { apply(ConfigOp.UpsertMedication(Medication("m", "Synthetic", listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))))) }
    private fun Scenario.slot(t: String) = state.medication.slots.values.single { it.time == LocalTime.parse(t) && it.date == date }

    @Test
    fun `MED-5 Taken with an earlier time, clamped to now and to 24 h before`() {
        val s = medScenario()
        s.advanceTo("09:00")
        s.send(Event.MedicationTaken(s.slot("08:00").ref, takenAt = s.t("08:15")))
        assertEquals(s.t("08:15"), s.slot("08:00").takenAt)
        assertTrue(s.history(HistoryKind.Taken).single().detail["takenAt"] == s.t("08:15").toString())
        s.send(Event.MedicationTaken(s.slot("20:00").ref, takenAt = s.t("23:00")))
        assertEquals(s.t("09:00"), s.slot("20:00").takenAt, "future time clamped to now")
    }

    @Test
    fun `MED-5 corrections - change time, undo (no new cue), skipped, all recorded with before and after`() {
        val s = medScenario()
        s.advanceTo("08:01")
        s.send(Event.MedicationTaken(s.slot("08:00").ref))
        s.send(Event.MedicationCorrect(s.slot("08:00").ref, DoseCorrection.Taken(s.t("07:55"))))
        assertEquals(s.t("07:55"), s.slot("08:00").takenAt)
        s.send(Event.MedicationCorrect(s.slot("08:00").ref, DoseCorrection.Undo))
        assertEquals(SlotStatus.Due, s.slot("08:00").status)
        assertNull(s.slot("08:00").takenAt)
        val cuesBefore = s.delivered.size
        s.advanceTo("09:00")
        assertEquals(cuesBefore, s.delivered.size, "an undone dose is not cued again (no implied advice)")
        s.send(Event.MedicationCorrect(s.slot("08:00").ref, DoseCorrection.Skipped))
        assertEquals(SlotStatus.Skipped, s.slot("08:00").status)
        val rows = s.history(HistoryKind.Corrected)
        assertEquals(listOf("Taken" to "Taken", "Taken" to "Due", "Due" to "Skipped"), rows.map { it.detail["from"] to it.detail["to"] })
        assertTrue(rows.all { it.rule == "MED-5" })
        // Undo of a dose whose time is still ahead returns it to Upcoming and it cues normally.
        s.send(Event.MedicationTaken(s.slot("20:00").ref))
        s.send(Event.MedicationCorrect(s.slot("20:00").ref, DoseCorrection.Undo))
        assertEquals(SlotStatus.Upcoming, s.slot("20:00").status)
        s.advanceTo("20:01")
        assertEquals(s.t("20:00"), s.deliveredFor("med:${s.slot("20:00").key}").first().deliveredAt)
    }

    @Test
    fun `first run seed - Hebrew template names, Israeli work week, 12-hour clock`() {
        val he = mapOf("template.place.home" to "בית", "template.habit.sunscreen" to "קרם הגנה", "template.routine.morning" to "שגרת בוקר")
        val c = Defaults.config(FirstRunSeed(Language.he, region = "IL", use24Hour = false, text = { he[it] }))
        assertEquals(Language.he, c.settings.language)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), c.settings.workDays)
        assertEquals(false, c.settings.use24Hour)
        assertEquals("בית", c.place(Defaults.HOME)!!.name)
        assertEquals("קרם הגנה", c.habit(Defaults.SUNSCREEN)!!.name)
        assertEquals("שגרת בוקר", c.routines.single().name)
        assertEquals("Office", c.place(Defaults.OFFICE)!!.name, "missing text falls back to English")
        assertTrue(app.daycue.domain.edit.ConfigValidator.validate(c).isEmpty(), "seeded document is valid")
        assertEquals(Defaults.workDaysFor(Language.en), Defaults.workDaysFor("US", Language.he))
        assertEquals(Defaults.workDaysFor(Language.he), Defaults.workDaysFor(null, Language.he))
        assertEquals(Defaults.config(), Defaults.config(FirstRunSeed()), "default seed = the old English document")
    }
}
