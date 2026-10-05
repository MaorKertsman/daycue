package app.daycue.ui.util

import app.daycue.delivery.Templates
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ClockFormatTest {
    private val en = Locale.forLanguageTag("en")
    private val he = Locale.forLanguageTag("he")

    @Test
    fun `24-hour is the same in both languages`() {
        assertEquals("07:00", clockText(7, 0, true, en))
        assertEquals("19:05", clockText(19, 5, true, he))
    }

    @Test
    fun `12-hour English uses AM and PM`() {
        assertEquals("7:00 AM", clockText(7, 0, false, en))
        assertEquals("7:05 PM", clockText(19, 5, false, en))
    }

    @Test
    fun `12-hour Hebrew localizes the AM PM markers`() {
        val am = clockText(7, 0, false, he)
        val pm = clockText(19, 5, false, he)
        assertFalse("no Latin markers in Hebrew: $am / $pm", "AM" in am || "PM" in pm)
        assertTrue("$am / $pm", am.startsWith("7:00") && pm.startsWith("7:05"))
        assertTrue(am, am.contains("לפנה"))
        assertTrue(pm, pm.contains("אחה"))
    }

    /** VALIDATION D10(a): rendered sample strings (screen, notification, speech paths) never carry Latin AM / PM in Hebrew. */
    @Test
    fun `Hebrew 12-hour samples contain no Latin AM or PM anywhere`() {
        val latin = Regex("\\b(AM|PM|am|pm|a\\.m\\.|p\\.m\\.)\\b")
        val utc = ZoneOffset.UTC
        val heLocales = listOf(he, Locale.forLanguageTag("he-IL"), Locale("iw"), Locale("iw", "IL"), localeOf(app.daycue.domain.config.Language.he))
        for (loc in heLocales) for (h in 0..23) for (m in listOf(0, 1, 30, 59)) {
            val samples = listOf(
                clockText(h, m, false, loc), clockTextIsolated(h, m, false, loc),
                Templates.fill("תזכורת ב־{time}", mapOf("time" to "%02d:%02d".format(h, m)), utc, rtl = true, hour24 = false, locale = loc),
                Templates.fill("בעוד {at}", mapOf("at" to "2026-10-05T%02d:%02d:00Z".format(h, m)), utc, rtl = true, hour24 = false, locale = loc),
                isolatedRange(clockText(h, m, false, loc), clockText((h + 1) % 24, m, false, loc)),
            )
            samples.forEach { s -> assertFalse("Latin marker in Hebrew ($loc): $s", latin.containsMatchIn(s)) }
        }
        assertTrue(amPmMarkers(Locale("iw")).all { it.any { c -> c in '֐'..'׿' } })
        assertEquals("12:01 אחה״צ", clockText(12, 1, false, he))
        assertEquals("12:01 לפנה״צ", clockText(0, 1, false, he))
        // Hebrew marker -> RTL isolate (time first in Hebrew reading); digits only -> LTR isolate.
        assertTrue(clockTextIsolated(12, 1, false, he).startsWith(RLI))
        assertTrue(clockTextIsolated(12, 1, true, he).startsWith(LRI))
    }

    /** Every time on screen, in notifications and speech goes through [clockText]: no other time formatting in main code. */
    @Test
    fun `no other clock formatter exists in app code`() {
        val root = java.io.File("src/main/kotlin")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val forbidden = listOf(
            Regex("SimpleDateFormat"), Regex("DateFormat\\.getTimeFormat"), Regex("ofLocalizedTime"), Regex("FormatStyle\\.SHORT\\)\\.withLocale"),
            Regex("ofPattern\\(\"[^\"]*(h|H):mm"), Regex("ofPattern\\(\"[^\"]*\\ba\\b"),
        )
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Text.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (forbidden.any { it.containsMatchIn(line) }) "${f.name}:${i + 1}: ${line.trim()}" else null }
        }.toList()
        assertTrue("time formatting outside ui/util/Text.kt clockText:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `isolated clock is one LTR run`() {
        assertEquals("⁦7:00 AM⁩", clockTextIsolated(7, 0, false, en))
        assertTrue(clockTextIsolated(7, 0, false, he).let { it.startsWith(RLI) && it.endsWith(PDI) }) // Hebrew marker: RTL isolate (D10)
    }

    @Test
    fun `notification and speech templates use the same formatter and locale`() {
        val utc = ZoneOffset.UTC
        assertEquals("7:00 AM", Templates.formatArg("time", "07:00", utc, rtl = false, hour24 = false, locale = en))
        assertEquals("07:00", Templates.formatArg("time", "07:00", utc, rtl = false, hour24 = true, locale = he))
        val heText = Templates.formatArg("time", "2026-10-05T19:05:00Z", utc, rtl = true, hour24 = false, locale = he)
        assertEquals(clockTextIsolated(19, 5, false, he), heText)
        assertFalse("PM" in heText)
    }
}
