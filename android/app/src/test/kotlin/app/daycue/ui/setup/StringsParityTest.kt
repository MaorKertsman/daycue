package app.daycue.ui.setup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A string that exists in English but not in Hebrew silently falls back to English inside a Hebrew screen
 * (REVIEW-2 C4, S7). This fails the build instead. Covers the Setup strings and the engine text keys
 * (`dc_*`, "why matched" and friends).
 */
class StringsParityTest {

    private fun names(path: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val out = mutableSetOf<String>()
        for (tag in listOf("string", "plurals", "string-array")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                out += n.attributes.getNamedItem("name").nodeValue
            }
        }
        return out
    }

    private fun check(file: String) {
        val en = names("src/main/res/values/$file")
        val he = names("src/main/res/values-iw/$file")
        val missing = (en - he).sorted()
        assertTrue("$file: missing in values-iw (would show English inside Hebrew): $missing", missing.isEmpty())
    }

    @Test fun `setup strings have a Hebrew twin`() = check("strings_setup.xml")

    @Test fun `engine text keys have a Hebrew twin`() = check("strings_engine.xml")
}
