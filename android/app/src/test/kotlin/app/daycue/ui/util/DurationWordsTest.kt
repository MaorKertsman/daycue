package app.daycue.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * VALIDATION D13: full-word durations and relative times, against the REAL string / plural resources of both languages
 * and Android's plural rules (en: one / other; iw: one / two / (many) / other).
 */
class DurationWordsTest {
    private class Res(dir: String) {
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        init {
            File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.forEach { f ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
                val s = doc.getElementsByTagName("string")
                for (i in 0 until s.length) (s.item(i) as Element).let { strings[it.getAttribute("name")] = it.textContent }
                val p = doc.getElementsByTagName("plurals")
                for (i in 0 until p.length) (p.item(i) as Element).let { el ->
                    val items = el.getElementsByTagName("item")
                    plurals[el.getAttribute("name")] = (0 until items.length).associate { j -> (items.item(j) as Element).let { it.getAttribute("quantity") to it.textContent } }
                }
            }
        }
    }

    private fun fmt(t: String, vararg a: Any) = String.format(java.util.Locale.ROOT, t.replace("\\'", "'"), *a)

    /** [heMany]: the older CLDR Hebrew rule set that still has "many" (n > 10 and n % 10 == 0); newer sets use other. */
    private fun lex(res: Res, hebrew: Boolean, heMany: Boolean = false): DurationLex {
        fun q(n: Int) = when {
            n == 1 -> "one"
            hebrew && n == 2 -> "two"
            hebrew && heMany && n > 10 && n % 10 == 0 -> "many"
            else -> "other"
        }
        fun pl(name: String, n: Int) = res.plurals.getValue(name).let { it[q(n)] ?: it.getValue("other") }.let { fmt(it, n) }
        return DurationLex(
            hours = { pl("desc_hours", it) }, minutes = { pl("desc_minutes", it) },
            joinWord = { a, b -> fmt(res.strings.getValue("desc_join_word"), a, b) },
            joinNumber = { a, b -> fmt(res.strings.getValue("desc_join_number"), a, b) },
            inDuration = { fmt(res.strings.getValue("app_in_duration"), it) },
            now = res.strings.getValue("app_in_now"),
        )
    }

    private val cases = listOf(0, 1, 2, 3, 11, 59, 60, 61, 119, 120, 1139)

    @Test
    fun `English durations and relative times`() {
        val l = lex(Res("values"), hebrew = false)
        val want = mapOf(
            0 to "now", 1 to "in 1 minute", 2 to "in 2 minutes", 3 to "in 3 minutes", 11 to "in 11 minutes", 59 to "in 59 minutes",
            60 to "in 1 hour", 61 to "in 1 hour 1 minute", 119 to "in 1 hour 59 minutes", 120 to "in 2 hours", 1139 to "in 18 hours 59 minutes",
        )
        for (m in cases) assertEquals("$m", want[m], DurationWords.relative(m, l))
        assertEquals("0 minutes", DurationWords.of(0, l))
    }

    @Test
    fun `Hebrew durations keep every number and use the one, two and many forms`() {
        for (many in listOf(false, true)) {
            val l = lex(Res("values-iw"), hebrew = true, heMany = many)
            val want = mapOf(
                0 to "עכשיו", 1 to "בעוד דקה", 2 to "בעוד שתי דקות", 3 to "בעוד 3 דקות", 11 to "בעוד 11 דקות", 59 to "בעוד 59 דקות",
                60 to "בעוד שעה", 61 to "בעוד שעה ודקה", 119 to "בעוד שעה ו־59 דקות", 120 to "בעוד שעתיים", 1139 to "בעוד 18 שעות ו־59 דקות",
            )
            for (m in cases) assertEquals("$m many=$many", want[m], DurationWords.relative(m, l))
            // The QA string: 19 h 1 min must not read "19 שעות דקה" (the joiner was missing).
            assertEquals("19 שעות ודקה", DurationWords.of(19 * 60 + 1, l))
            assertEquals("20 דקות", DurationWords.of(20, l))
        }
    }

    @Test
    fun `no relative string says in 0 minutes in either language`() {
        for ((dir, he) in listOf("values" to false, "values-iw" to true)) {
            val l = lex(Res(dir), he)
            for (m in -5..1) assertFalse(DurationWords.relative(m, l).contains("0"))
        }
    }
}
