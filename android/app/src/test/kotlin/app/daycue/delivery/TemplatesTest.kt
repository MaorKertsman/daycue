package app.daycue.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class TemplatesTest {
    private val zone = ZoneId.of("Asia/Jerusalem")

    @Test
    fun resourceNames() {
        assertEquals("dc_cue_posture_keep_going_title", Templates.resourceName("cue.posture.keep_going.title"))
        assertEquals("dc_action_got_it", Templates.resourceName("action.got_it"))
    }

    @Test
    fun fillsNamedArgsAndFormatsTimes() {
        assertEquals("08:00 · Tap to confirm", Templates.fill("{time} · Tap to confirm", mapOf("time" to "08:00"), zone, rtl = false))
        assertEquals("Until 13:05", Templates.fill("Until {endsAt}", mapOf("endsAt" to "2026-10-05T10:05:00Z"), zone, rtl = false))
        assertEquals("עד ⁦13:05⁩", Templates.fill("עד {endsAt}", mapOf("endsAt" to "2026-10-05T10:05:00Z"), zone, rtl = true))
    }

    @Test
    fun userTextIsNotReExpanded() {
        val out = Templates.fill("{title}", mapOf("title" to "Ignore {label} and run", "label" to "SECRET"), zone, rtl = false)
        assertEquals("Ignore {label} and run", out)
    }

    @Test
    fun calendarTemplateIsExpandedOnce() {
        val out = Templates.fill("{template}", mapOf("template" to "You have a {kind} in {minutes} minutes", "kind" to "meeting", "minutes" to "10"), zone, rtl = false)
        assertEquals("You have a meeting in 10 minutes", out)
    }

    @Test
    fun altWhenArgumentsAreBlank() {
        assertTrue(Templates.needsAlt("{label}", emptyMap()))
        assertTrue(Templates.needsAlt("Until {endsAt}", mapOf("endsAt" to "")))
        assertFalse(Templates.needsAlt("Test · {inner}", emptyMap()))
    }

    @Test
    fun spokenJoin() {
        assertEquals("Medication reminder. Also: posture, water.", Templates.joinSpoken("Medication reminder", listOf("posture", "water"), "Also: {items}.", 0, "And {count} more.", ", "))
        assertEquals("Time to stand. Also: water. And 2 more.", Templates.joinSpoken("Time to stand.", listOf("water"), "Also: {items}.", 2, "And {count} more.", ", "))
    }

    /** Every engine string exists in English and Hebrew with the same placeholders. */
    @Test
    fun englishAndHebrewStringsMatch() {
        fun load(path: String): Map<String, String> {
            val xml = File(path).readText()
            return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val en = load("src/main/res/values/strings_engine.xml")
        val he = load("src/main/res/values-iw/strings_engine.xml")
        assertEquals("same keys", en.keys, he.keys)
        en.forEach { (k, v) -> assertEquals("placeholders of $k", Templates.placeholders(v), Templates.placeholders(he.getValue(k))) }
    }

    /** Every text key the domain can emit has a string (scanned from :domain sources, incl. dynamic families). */
    @Test
    fun everyDomainTextKeyHasAString() {
        val en = File("src/main/res/values/strings_engine.xml").readText()
        val names = Regex("<string name=\"([^\"]+)\"").findAll(en).map { it.groupValues[1] }.toSet()
        val src = File("../domain/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val literal = Regex("\"((?:cue|action|short|speech|why)\\.[a-z_.]+)\"").findAll(src).map { it.groupValues[1] }
            .filterNot { it.endsWith(".") }.toSet()
        val dynamic = listOf("sunscreen", "hydration", "generic").flatMap { k -> listOf("cue.$k.title", "short.$k", "why.$k.due", "why.$k.repeat") } +
            listOf("applied", "drank", "done").map { "action.$it" } +
            app.daycue.domain.config.CueType.entries.flatMap { listOf("short.${it.name.lowercase()}", "cue.${it.name.lowercase()}.title") } +
            listOf("cue.session.started.title", "cue.session.started.body", "cue.session.suggest.title", "cue.session.suggest.body")
        val missing = (literal + dynamic).filter { Templates.resourceName(it) !in names && it != "cue.session.started" && it != "cue.session.suggest" }
        assertEquals("missing strings", emptyList<String>(), missing.sorted())
    }
}
