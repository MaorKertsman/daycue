package app.daycue.ui

import app.daycue.ui.theme.reduceMotionFrom
import app.daycue.ui.util.clockDuration
import app.daycue.ui.util.clockText
import app.daycue.ui.util.hour12
import app.daycue.ui.util.isolatedRange
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REVIEW-1 #29, #30, #31, #42: bidi isolation, clock text and the reduced-motion rule (pure JVM). */
class UiTextTest {
    private val lri = "⁦"
    private val pdi = "⁩"

    @Test
    fun overnightRangeIsOneLtrIsolate() {
        val range = isolatedRange("22:30", "07:00")
        assertEquals("${lri}22:30–07:00$pdi", range)
        // The Hebrew template: only the range is isolated, the Hebrew words stay in the RTL run.
        val line = "%1\$s, מסתיים למחרת".format(range)
        assertEquals("${lri}22:30–07:00$pdi, מסתיים למחרת", line)
        assertEquals(1, line.count { it == '⁦' })
        assertEquals(1, line.count { it == '⁩' })
    }

    @Test
    fun hebrewDurationWithUnitsIsNotWrappedInAnIsolate() {
        val text = "%1\$d שע׳ %2\$d דק׳".format(2, 15)
        assertEquals("2 שע׳ 15 דק׳", text)
        assertFalse(text.contains('⁦'))
    }

    @Test
    fun clockFormatDurationIsAnLtrRun() {
        assertEquals("${lri}1:20$pdi", clockDuration(1, 20))
    }

    @Test
    fun clockTextFollowsTheDeviceHourSetting() {
        assertEquals("19:30", clockText(19, 30, true, Locale.US))
        assertEquals("07:05", clockText(7, 5, true, Locale.US))
        val pm = clockText(19, 30, false, Locale.US)
        assertTrue(pm, pm.startsWith("7:30") && pm.endsWith("PM"))
        val am = clockText(7, 30, false, Locale.US)
        assertTrue(am, am.startsWith("7:30") && am.endsWith("AM"))
    }

    @Test
    fun hour12ShowsMidnightAndNoonAsTwelve() {
        assertEquals(12, hour12(0))
        assertEquals(12, hour12(12))
        assertEquals(7, hour12(19))
        assertEquals(1, hour12(13))
    }

    @Test
    fun reducedMotionComesFromSettingAnimatorScaleOrBatterySaver() {
        assertFalse(reduceMotionFrom(setting = false, animatorScale = 1f, powerSave = false))
        assertTrue(reduceMotionFrom(setting = true, animatorScale = 1f, powerSave = false))
        assertTrue(reduceMotionFrom(setting = false, animatorScale = 0f, powerSave = false))
        assertTrue(reduceMotionFrom(setting = false, animatorScale = 1f, powerSave = true))
        assertFalse(reduceMotionFrom(setting = false, animatorScale = 0.5f, powerSave = false))
    }
}
