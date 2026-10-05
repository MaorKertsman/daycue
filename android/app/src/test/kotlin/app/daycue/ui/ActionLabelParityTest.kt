package app.daycue.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The same action has the same label on a notification button and on the in-app button (Hebrew QA: "החלפתי" vs "עברתי",
 * "בוצע" vs "סיימתי", the bottle / calendar "Got it" shown as "לקחתי"). Each notification action string is tied to its
 * in-app twin(s), in both languages; arguments are filled with the same sample so templates compare too.
 */
class ActionLabelParityTest {
    private fun strings(dir: String): Map<String, String> = File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!
        .flatMap { f ->
            val n = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
            (0 until n.length).map { (n.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } }
        }.toMap()

    /** notification string -> in-app twins. */
    private val twins = mapOf(
        "dc_action_applied" to listOf("app_act_applied"),
        "dc_action_drank" to listOf("app_act_drank"),
        "dc_action_done" to listOf("app_act_done", "cues_play_done"),
        "dc_action_got_it" to listOf("app_act_got_it"),
        "dc_action_not_needed" to listOf("app_act_not_needed"),
        "dc_action_taken" to listOf("app_act_taken", "cues_med_taken"),
        "dc_action_switched" to listOf("app_act_switched", "cues_posture_switched"),
        "dc_action_skip" to listOf("app_act_skip", "cues_play_skip", "cues_posture_skip"),
        "dc_action_start" to listOf("app_act_start"),
        "dc_action_skip_today" to listOf("app_act_skip_today", "cues_play_skip_today"),
        "dc_action_not_now" to listOf("app_act_not_now"),
        "dc_action_resume" to listOf("cues_play_resume"),
        "dc_action_stop" to listOf("cues_posture_stop"),
        "dc_action_snooze" to listOf("app_act_snooze_min"),
        "dc_action_extend" to listOf("app_act_extend5"),
    )

    private fun norm(s: String, he: Boolean): String {
        val five = if (he) "5 דק׳" else "5 min"
        return s.replace("{minutes}", "5").replace("%1\$s", five).replace("‎", "").replace("\\'", "'").trim()
    }

    @Test
    fun `every notification action label equals its in-app twin in English and Hebrew`() {
        for ((dir, he) in listOf("values" to false, "values-iw" to true)) {
            val s = strings(dir)
            for ((dc, apps) in twins) for (app in apps) {
                assertEquals("$dir: $dc vs $app", norm(s.getValue(dc), he), norm(s.getValue(app), he))
            }
        }
    }
}
