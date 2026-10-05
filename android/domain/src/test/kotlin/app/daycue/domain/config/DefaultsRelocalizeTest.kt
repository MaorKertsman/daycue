package app.daycue.domain.config

import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VALIDATION D10(b)/(c): onboarding language choice re-localizes untouched first-run names; work days by region. */
class DefaultsRelocalizeTest {
    /** Synthetic "Hebrew" texts for a few keys (the app supplies the real resources). */
    private val he = mapOf(
        "template.habit.hydration" to "שתיית מים", "template.place.home" to "בית", "template.alarm.morning" to "שעון מעורר",
        "template.routine.morning" to "שגרת בוקר", "template.step.shower" to "מקלחת", "template.calendar.meetings" to "פגישות",
    )
    private val known: (String) -> Set<String> = { key -> setOfNotNull(Defaults.TEMPLATE_TEXT[key], he[key]) }

    private fun apply(c: DayCueConfig, ops: List<ConfigOp>): DayCueConfig =
        (ConfigEditor.applyOps(c, ops, c.version) as ApplyResult.Applied).config

    @Test
    fun `English first-run names become Hebrew, user-edited names are never touched`() {
        val base = Defaults.config(Language.en)
        val edited = apply(base, listOf(ConfigOp.UpsertPlace(base.place(Defaults.HOME)!!.copy(name = "My flat"))))
        val ops = Defaults.relocalizeOps(edited, Defaults.Names { he[it] }, known, Language.en, Language.he, region = "US")
        val c = apply(edited, ops)
        assertEquals("שתיית מים", c.habit(Defaults.HYDRATION)!!.name)
        assertEquals("My flat", c.place(Defaults.HOME)!!.name, "user-edited name kept")
        assertEquals("שעון מעורר", c.alarm(Defaults.MORNING_ALARM)!!.name)
        val r = c.routines.single { it.id == Defaults.MORNING_ROUTINE }
        assertEquals("שגרת בוקר", r.name)
        assertEquals("מקלחת", r.steps.first().name)
        assertEquals("מקלחת", r.steps.first().phrase)
        assertEquals("פגישות", c.calendarRules.rules.first { it.id == "meetings" }.name)
        // Keys without a Hebrew text keep their (English) default name.
        assertEquals("Sunscreen", c.habit(Defaults.SUNSCREEN)!!.name)
        assertEquals(base.settings.workDays, c.settings.workDays, "region known (US): work days unchanged")
        // Running it again changes nothing; and back to English restores English defaults.
        assertTrue(Defaults.relocalizeOps(c, Defaults.Names { he[it] }, known, Language.en, Language.he, "US").isEmpty())
        val back = apply(c, Defaults.relocalizeOps(c, Defaults.Names(), known))
        assertEquals("Hydration", back.habit(Defaults.HYDRATION)!!.name)
        assertEquals("My flat", back.place(Defaults.HOME)!!.name)
    }

    @Test
    fun `work days follow the language only when the region is unknown and still the old default`() {
        val base = Defaults.config(Language.en)
        val ops = Defaults.relocalizeOps(base, Defaults.Names(), known, Language.en, Language.he, region = null)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), apply(base, ops).settings.workDays)
        val custom = apply(base, listOf(ConfigOp.SetGlobalSettings(base.settings.copy(workDays = setOf(DayOfWeek.MONDAY)))))
        assertTrue(Defaults.relocalizeOps(custom, Defaults.Names(), known, Language.en, Language.he, null).none { it is ConfigOp.SetGlobalSettings })
    }

    /** DOMAIN.md work-day rule table: region decides; no region -> language. */
    @Test
    fun `work day table - region decides, language only without a region`() {
        val sunThu = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        val monFri = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val table = listOf(
            Triple(Language.he, "IL", sunThu), Triple(Language.en, "IL", sunThu),
            Triple(Language.he, "US", monFri), Triple(Language.en, "US", monFri),
            Triple(Language.he, null, sunThu), Triple(Language.en, null, monFri),
        )
        for ((lang, region, want) in table) assertEquals(want, Defaults.workDaysFor(region, lang), "$lang-$region")
    }
}
