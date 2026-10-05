package app.daycue.ui.util

import app.daycue.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Friendly diff text, driven by the real `strings_setup.xml` files so a missing Hebrew string fails here. */
class DiffTextTest {

    private val idToName: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun load(dir: String): Map<String, String> {
        val f = File("src/main/res/$dir/strings_setup.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to unescape(n.textContent)
        }
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"")
        .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

    private val en = load("values")
    private val he = load("values-iw")

    private fun lex(strings: Map<String, String>) = DiffLex { id, args ->
        val name = idToName.getValue(id)
        String.format(Locale.ROOT, strings[name] ?: error("missing $name"), *args)
    }

    private fun english(vararg lines: String) = DiffText.lines(lines.toList(), lex(en), DiffEnv(Locale.US, true))
    private fun hebrew(vararg lines: String) = DiffText.lines(lines.toList(), lex(he), DiffEnv(Locale("iw", "IL"), true))

    @Test fun `every diff string has a Hebrew twin`() {
        val missing = en.keys.filter { it.startsWith("diff_") && it !in he }
        assertTrue("missing in values-iw: $missing", missing.isEmpty())
    }

    @Test fun `work days become a range`() {
        val l = "settings.workDays: [\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\"] -> [\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]"
        assertEquals("Work days: Mon–Sat → Mon–Fri", english(l).single())
        val h = hebrew(l).single()
        assertTrue(h, h.startsWith("ימי עבודה: ב׳–ש׳ ← ב׳–ו׳"))
    }

    @Test fun `Sunday to Thursday wraps correctly`() {
        val l = "settings.workDays: [\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"] -> [\"SUNDAY\",\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\"]"
        assertEquals("Work days: Mon–Fri → Sun–Thu", english(l).single())
    }

    @Test fun `interval of a named habit`() {
        assertEquals("Hydration: every 1 h → every 1 h 30 min", english("habits[hydration].intervalMin: 60 -> 90").single())
        val h = hebrew("habits[hydration].intervalMin: 60 -> 90").single()
        assertTrue(h, h.startsWith("שתייה: כל שעה ← כל שעה ו־30 דק׳"))
    }

    @Test fun `quiet hours windows are readable and never raw json`() {
        val l = "settings.quietHours.windows: [{\"window\":{\"start\":\"22:30\",\"end\":\"07:00\"},\"days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}] -> [{\"window\":{\"start\":\"23:00\",\"end\":\"06:30\"},\"days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}]"
        val e = english(l).single()
        assertEquals("Quiet hours: 22:30–07:00, Every day → 23:00–06:30, Every day", e.replace("⁦", "").replace("⁩", ""))
        assertFalse(e.contains("{") || e.contains("\""))
    }

    @Test fun `booleans enums and unknown paths have no technical text`() {
        assertEquals("Hydration: Off → On", english("habits[hydration].enabled: false -> true").single())
        assertEquals("Usually: Indoors → Outdoors", english("places[abc-1].typicalEnvironment: Indoor -> Outdoor").single())
        val unknown = english("habits[hydration].onLeaveCondition: RetractAndHold -> KeepVisible").single()
        assertEquals("Setting changed (On leave condition (Hydration)): Retract and hold → Keep visible", unknown)
        val heUnknown = hebrew("contextRules.placeEnterDwellMin: 3 -> 5").single()
        assertTrue(heUnknown, heUnknown.startsWith("הגדרה שונתה (כללי הקשר)"))
        assertFalse(heUnknown.any { it in 'a'..'z' })
    }

    @Test fun `redaction markers read as sentences`() {
        assertEquals("Removed place: Home (location removed)", english("places[home]: removed Home (location removed)").single())
        assertEquals("Place moved: Home", english("places[home].location: set -> moved").single().replace("Hydration", "Home"))
        assertEquals("Medication details changed (shown only on the phone)", english("medications: details withheld -> changed (details withheld)").single())
        assertFalse(hebrew("medications: details withheld -> changed (details withheld)").single().any { it in 'a'..'z' })
    }

    @Test fun `added item uses its name from json`() {
        val l = "habits[x1]: added {\"type\":\"interval\",\"id\":\"x1\",\"name\":\"Stretch\",\"intervalMin\":45}"
        assertEquals("Added reminder: Stretch", english(l).single())
    }

    @Test fun `added and removed items of every kind read as sentences, never JSON`() {
        val cases = mapOf(
            "habits[x1]: added {\"type\":\"interval\",\"id\":\"x1\",\"name\":\"Stretch\",\"intervalMin\":45}" to "Added reminder: Stretch",
            "places[p-77]: added {\"id\":\"p-77\",\"name\":\"Studio\",\"radiusM\":120}" to "Added place: Studio",
            "routines[r1]: added {\"id\":\"r1\",\"name\":\"Evening wind-down\",\"steps\":[{\"id\":\"s1\"}]}" to "Added routine: Evening wind-down",
            "alarms[a1]: added {\"id\":\"a1\",\"name\":\"Gym\",\"time\":\"06:00\"}" to "Added alarm: Gym",
            "medications[med-a]: added {\"id\":\"med-a\",\"label\":\"Vitamin X\",\"travelPolicy\":{\"type\":\"followLocalTime\"}}" to "Added medication: Vitamin X",
            "habits[x1]: removed {\"type\":\"interval\",\"id\":\"x1\",\"name\":\"Stretch\"}" to "Removed reminder: Stretch",
            "places[p-77]: removed {\"id\":\"p-77\",\"name\":\"Studio\"}" to "Removed place: Studio",
            "routines[r1]: removed {\"id\":\"r1\",\"name\":\"Evening wind-down\"}" to "Removed routine: Evening wind-down",
            "alarms[a1]: removed {\"id\":\"a1\",\"name\":\"Gym\"}" to "Removed alarm: Gym",
            "medications[med-a]: removed {\"id\":\"med-a\",\"label\":\"Vitamin X\"}" to "Removed medication: Vitamin X",
            // a value cut off by the summary length limit is not valid JSON, but the name is still shown
            "medications[med-b]: added {\"id\":\"med-b\",\"label\":\"Vitamin Y\",\"times\":[\"08:00\",\"20" to "Added medication: Vitamin Y",
        )
        for ((line, expected) in cases) {
            val e = english(line).single()
            assertEquals(expected, e)
            assertFalse(e, e.contains("{") || e.contains("\"") || e.contains("[") )
        }
        val he = hebrew("medications[med-a]: added {\"id\":\"med-a\",\"label\":\"Vitamin X\",\"travelPolicy\":{\"type\":\"followLocalTime\"}}").single()
        assertEquals("הוספת תרופה: Vitamin X", he)
        assertFalse(hebrew("alarms[a1]: removed {\"id\":\"a1\",\"name\":\"Gym\"}").single().contains("{"))
    }

    @Test fun `long lists are truncated`() {
        val l = "medications[m1].times: [\"08:00\",\"09:00\",\"10:00\",\"11:00\",\"12:00\",\"13:00\"] -> [\"08:00\"]"
        val e = english(l).single()
        assertTrue(e, e.contains("+2 more"))
    }

    @Test fun `title for a single change`() {
        val t = DiffText.title(listOf("settings.workDays: [\"MONDAY\"] -> [\"MONDAY\",\"TUESDAY\"]"), lex(en), DiffEnv(Locale.US, true))
        assertEquals("Change work days to Mon, Tue", t)
    }
}
