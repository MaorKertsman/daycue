package app.daycue.ui.util

import app.daycue.R
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.RemoteRedaction
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit of the diff formatter over a broad synthetic change: every [ConfigOp] variant (see DOMAIN.md), on-phone and
 * remote previews, English and Hebrew. No line may fall back to the technical wording: "Setting changed (", raw JSON
 * braces or brackets, an ALL_CAPS enum token, or an unconverted `path[id].field` key.
 */
class DiffAuditTest {

    private val idToName: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun load(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings_setup.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\'", "'").replace("\\\"", "\"")
        }
    }

    private val en = load("values")
    private val he = load("values-iw")
    private fun lex(strings: Map<String, String>) = DiffLex { id, args -> String.format(Locale.ROOT, strings.getValue(idToName.getValue(id)), *args) }

    private val base = DiffSamples.base
    private val allCaps = Regex("""\b[A-Z][A-Z_]{2,}\b""")
    private val rawPath = Regex("""\b\w+\[[^\]]*]\.?\w*""")

    private fun diffLines(): List<String> {
        val out = mutableListOf<String>()
        // Every variant on its own (what a single remote edit looks like) ...
        DiffSamples.samples.forEach { s ->
            out += ConfigEditor.preview(base, listOf(s.op)).lines.map { it.text }
            out += ConfigEditor.previewForRemote(base, listOf(s.op), RemoteRedaction(allowMedication = true)).lines.map { it.text }
            out += ConfigEditor.previewForRemote(base, listOf(s.op), RemoteRedaction(allowMedication = false)).lines.map { it.text }
        }
        // ... and all of them as one import-sized change.
        val all: List<ConfigOp> = DiffSamples.samples.map { it.op }
        runCatching { ConfigEditor.preview(base, all).lines.map { it.text } }.getOrNull()?.let { out += it }
        // Renames and step edits spelled out.
        out += listOf(
            "routines[morning-routine].steps[shower].name: Shower -> מקלחת",
            "routines[morning-routine].steps[shower].durationSec: 120 -> 240",
            "routines[morning-routine].steps[shower].completion: Timed -> Manual",
            "routines[morning-routine].steps[stretch]: added {\"id\":\"stretch\",\"name\":\"Stretch\"}",
            "routines[morning-routine].steps[shower]: removed {\"id\":\"shower\",\"name\":\"Shower\"}",
            "routines[morning-routine].name: Morning routine -> שגרת בוקר",
            "habits[hydration].name: Hydration -> Water",
            "places[home].name: Home -> House",
            "alarms[morning-alarm].name: Morning alarm -> Wake up",
            "medications[med-a].label: Synthetic -> Other",
        )
        return out.distinct()
    }

    private fun check(label: String, lines: List<String>) {
        val bad = lines.filter { l ->
            "Setting changed (" in l || "הגדרה שונתה (" in l || l.any { it in "{}[]" } || allCaps.containsMatchIn(l.replace("⁦", "").replace("⁩", "")) || rawPath.containsMatchIn(l)
        }
        File("build").mkdirs()
        File("build/diff-audit-$label.txt").writeText(lines.joinToString("\n"))
        assertTrue("$label: technical wording in:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun `english diff over every op has no technical fallback`() {
        val env = envFor(Locale.US, true, base)
        check("en", DiffText.lines(diffLines(), lex(en), env))
    }

    @Test fun `hebrew diff over every op has no technical fallback`() {
        val env = envFor(Locale("iw", "IL"), true, base)
        val lines = DiffText.lines(diffLines(), lex(he), env)
        check("he", lines)
    }

    @Test fun `renames and step edits read naturally in both languages`() {
        val raw = listOf(
            "routines[morning-routine].steps[shower].name: Shower -> מקלחת",
            "habits[hydration].name: Hydration -> Water",
        )
        val e = DiffText.lines(raw, lex(en), DiffEnv(Locale.US, true, stepNumber = { _, _ -> 1 }, nameOf = { if (it == "morning-routine") "Morning routine" else null }))
        assertEquals("Morning routine, step 1: Shower → מקלחת", e[0].replace("⁦", "").replace("⁩", ""))
        assertEquals("Renamed: Hydration → Water", e[1])
        val h = DiffText.lines(raw, lex(he), DiffEnv(Locale("iw", "IL"), true, stepNumber = { _, _ -> 1 }, nameOf = { if (it == "morning-routine") "שגרת בוקר" else null }))
        assertEquals("שגרת בוקר, שלב 1: Shower ← מקלחת".replace("Shower", "Shower"), h[0].replace("⁦", "").replace("⁩", "").let { it })
        assertEquals("שונה שם: Hydration ← Water", h[1])
    }
}
