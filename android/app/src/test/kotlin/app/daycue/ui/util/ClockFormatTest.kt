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

    @Test
    fun `isolated clock is one LTR run`() {
        assertEquals("⁦7:00 AM⁩", clockTextIsolated(7, 0, false, en))
        assertTrue(clockTextIsolated(7, 0, false, he).let { it.startsWith(LRI) && it.endsWith(PDI) })
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
