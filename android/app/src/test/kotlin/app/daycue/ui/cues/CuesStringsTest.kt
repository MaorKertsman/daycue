package app.daycue.ui.cues

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** English and Hebrew Cues strings stay in step: same keys, same format placeholders, Hebrew really Hebrew. */
class CuesStringsTest {
    private fun load(dir: String): Map<String, String> {
        val file = listOf(File("src/main/res/$dir/strings_cues.xml"), File("app/src/main/res/$dir/strings_cues.xml")).first { it.isFile }
        return Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en = load("values")
    private val he = load("values-iw")
    private val placeholder = Regex("""%\d\$[sd]""")

    @Test fun sameKeys() {
        assertEquals(en.keys, he.keys)
        assertTrue("string count", en.size > 400)
    }

    @Test fun sameFormatPlaceholders() {
        for ((k, v) in en) assertEquals("placeholders of $k", placeholder.findAll(v).map { it.value }.sorted().toList(), placeholder.findAll(he.getValue(k)).map { it.value }.sorted().toList())
    }

    @Test fun hebrewStringsContainHebrew() {
        val hebrew = Regex("[֐-׿]")
        for ((k, v) in he) {
            // Product name only, nothing else may stay English.
            val stripped = v.replace("DayCue", "").filter { it.isLetter() }
            if (stripped.isEmpty()) continue
            assertTrue("no Hebrew in $k: $v", hebrew.containsMatchIn(v))
        }
    }

    @Test fun medicationWordingNeverJudges() {
        val banned = listOf("missed", "adherence", "streak", "score", "forgot")
        for ((k, v) in en) for (b in banned) assertTrue("$k contains $b", !Regex("\\b" + b).containsMatchIn(v.lowercase()))
    }

    @Test fun plusMinutesStaysLeftToRightInHebrew() {
        // REVIEW-2 B7: "+5" must not render as "5+" in Hebrew, so the sign and digit are isolated.
        val v = he.getValue("cues_plus_min")
        assertTrue(v, v.contains("&#x2066;") && v.contains("&#x2069;"))
    }

    @Test fun zoneIsShownByNameNotId() {
        val zone = java.time.ZoneId.of("Asia/Jerusalem")
        val en = zoneDisplayName(zone, java.util.Locale.ENGLISH)
        val iw = zoneDisplayName(zone, java.util.Locale("he"))
        assertTrue(en, !en.contains("/") && en.isNotBlank())
        assertTrue(iw, !iw.contains("/") && iw.isNotBlank())
    }
}
