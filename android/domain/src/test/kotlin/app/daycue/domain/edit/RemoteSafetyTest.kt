package app.daycue.domain.edit

import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.edit.OpSamples.COORD_FRAGMENTS
import app.daycue.domain.edit.OpSamples.MED_FRAGMENTS
import app.daycue.domain.edit.OpSamples.base
import app.daycue.domain.edit.OpSamples.samples
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Security review H-1 / M-8: value-level redaction of everything that leaves the phone. */
class RemoteRedactionTest {
    private val strict = RemoteRedaction.Strict
    private val withMed = RemoteRedaction(allowMedication = true)

    /** Every string a remote caller can receive from a [RemotePreview]. */
    private fun outputs(p: RemotePreview): List<String> =
        p.lines.flatMap { listOfNotNull(it.path, it.before, it.after, it.text) } +
            p.errors.flatMap { listOf(it.path, it.code, it.message) } +
            listOf(p.text, p.summary(), p.summary(maxChars = 40), p.summary(prefix = "Undo of version 3: "),
                DayCueJson.encodeToString(RemotePreview.serializer(), p))

    private fun assertClean(p: RemotePreview, what: String, medicationAllowed: Boolean) {
        for (s in outputs(p)) {
            COORD_FRAGMENTS.forEach { f -> assertFalse(s.contains(f), "$what: coordinate fragment '$f' in: $s") }
            if (!medicationAllowed) MED_FRAGMENTS.forEach { f -> assertFalse(s.contains(f), "$what: medication fragment '$f' in: $s") }
        }
    }

    @Test
    fun `every op variant - no coordinate digits and no medication content in any redacted output`() {
        assertEquals(ConfigOpCodec.knownTypes, samples.map { OpSamples.serialName(it.op) }.toSet(), "fixture covers every ConfigOp variant")
        for (s in samples) {
            val name = OpSamples.serialName(s.op)
            assertClean(ConfigEditor.previewForRemote(base, listOf(s.op), strict), "strict $name", medicationAllowed = false)
            assertClean(ConfigEditor.previewForRemote(base, listOf(s.op), withMed), "withMed $name", medicationAllowed = true)
            // Undo direction: the restore of places/medications after the op (re-adds a deleted place, moves it back).
            val next = (ConfigEditor.applyOps(base, listOf(s.op), base.version) as? ApplyResult.Applied)?.config ?: continue
            assertClean(ConfigEditor.previewForRemote(next, OpSamples.restoreOps, strict), "undo strict $name", medicationAllowed = false)
            assertClean(ConfigEditor.previewForRemote(next, OpSamples.restoreOps, withMed), "undo withMed $name", medicationAllowed = true)
        }
    }

    @Test
    fun `property - random op sequences (valid or not) never leak coordinates or medication content`() {
        val r = Random(20261004)
        repeat(400) { i ->
            val ops = List(r.nextInt(1, 5)) { samples[r.nextInt(samples.size)].op }
            assertClean(ConfigEditor.previewForRemote(base, ops, strict), "seq#$i strict $ops", medicationAllowed = false)
            assertClean(ConfigEditor.previewForRemote(base, ops, withMed), "seq#$i withMed $ops", medicationAllowed = true)
        }
    }

    @Test
    fun `deleted, moved and cleared places are described by name and a location marker`() {
        // Negative control (H-1): the on-phone preview does contain the coordinates, so the detector works.
        assertTrue(ConfigEditor.preview(base, listOf(ConfigOp.DeletePlace(Defaults.HOME)), redactMedicationLabels = true).text.contains(OpSamples.HOME_C.lat.toString()))
        val del = ConfigEditor.previewForRemote(base, listOf(ConfigOp.DeletePlace(Defaults.HOME)), strict)
        assertTrue(del.lines.any { it.path == "places[home]" && it.before == "Home (location removed)" && it.after == null }, del.text)
        assertEquals(Sensitivity.destructive, del.sensitivity)
        val moved = ConfigEditor.previewForRemote(base, listOf(ConfigOp.SetPlaceLocation(Defaults.HOME, OpSamples.NEW_C)), strict)
        assertEquals(listOf("places[home].location: set -> moved"), moved.lines.map { it.text })
        assertEquals(Sensitivity.sensitive, moved.sensitivity)
        val cleared = ConfigEditor.previewForRemote(base, listOf(ConfigOp.SetPlaceLocation(Defaults.HOME, null)), strict)
        assertEquals(listOf("places[home].location: set -> none"), cleared.lines.map { it.text })
        val added = ConfigEditor.previewForRemote(base, listOf(ConfigOp.UpsertPlace(app.daycue.domain.config.Place("park", "Park", center = OpSamples.NEW_C))), strict)
        assertTrue(added.lines.any { it.text == "places[park]: added Park (location set)" }, added.text)
        // Undo of the deletion re-adds the place: name + marker only.
        val next = (ConfigEditor.applyOps(base, listOf(ConfigOp.DeletePlace(Defaults.HOME)), 0) as ApplyResult.Applied).config
        val undo = ConfigEditor.previewForRemote(next, listOf(ConfigOp.UpsertPlace(base.place(Defaults.HOME)!!)), strict)
        assertTrue(undo.lines.any { it.text == "places[home]: added Home (location set)" }, undo.text)
    }

    @Test
    fun `medication changes are one withheld line without the medication permission, full with it`() {
        val op = ConfigOp.SetMedicationTimes("med-a", listOf(java.time.LocalTime.of(6, 43)))
        val strictP = ConfigEditor.previewForRemote(base, listOf(op), strict)
        assertEquals(listOf("medications: details withheld -> changed (details withheld)"), strictP.lines.map { it.text })
        assertEquals(Sensitivity.sensitive, strictP.sensitivity)
        val full = ConfigEditor.previewForRemote(base, listOf(op), withMed)
        assertTrue(full.text.contains("06:43"), full.text)
        // Validation errors about medication are withheld too.
        val bad = ConfigEditor.previewForRemote(base, listOf(ConfigOp.UpsertMedication(base.medications.first().copy(label = ""))), strict)
        assertTrue(bad.errors.isNotEmpty() && bad.errors.all { it.path == "medications" }, bad.errors.toString())
    }

    @Test
    fun `remote sensitivity equals on-phone sensitivity and ordinary edits keep their readable diff`() {
        for (s in samples) {
            assertEquals(ConfigEditor.preview(base, listOf(s.op)).sensitivity, ConfigEditor.previewForRemote(base, listOf(s.op), strict).sensitivity, s.op.toString())
        }
        val p = ConfigEditor.previewForRemote(base, listOf(ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 90)), strict)
        assertEquals("habits[sunscreen].intervalMin: 120 -> 90", p.text)
        assertEquals("habits[sunscreen].intervalMin: 120 -> 90", p.summary())
        assertEquals("Undo: habits[sunscreen].i…", p.summary(maxChars = 26, prefix = "Undo: "))
    }
}

/** Security review M-5 + DOMAIN.md §5: every op variant is classified explicitly. */
class SensitivityTableTest {
    @Test
    fun `table - every ConfigOp variant has an explicit expected class and the classifier matches it`() {
        val byName = samples.groupBy { OpSamples.serialName(it.op) }
        assertEquals(ConfigOpCodec.knownTypes, byName.keys, "every variant has at least one expectation")
        for (s in samples) assertEquals(s.expected, ConfigSensitivity.of(base, s.op), "${OpSamples.serialName(s.op)}: ${s.op}")
    }

    @Test
    fun `preview never reports a lower class than the table, and lists take the maximum`() {
        for (s in samples) {
            val p = ConfigEditor.preview(base, listOf(s.op)).sensitivity
            assertTrue(p.ordinal >= s.expected.ordinal, "${s.op}: preview $p < table ${s.expected}")
        }
        val mixed = listOf(ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 90), ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, false))
        assertEquals(Sensitivity.sensitive, ConfigEditor.sensitivity(base, mixed))
        assertEquals(Sensitivity.destructive, ConfigEditor.sensitivity(base, mixed + ConfigOp.DeletePlace(Defaults.GYM)))
        // An invalid list still reports the class of every op (no downgrade by failing early).
        val invalid = ConfigEditor.preview(base, listOf(ConfigOp.SetHabitInterval("nope", 90), ConfigOp.SetQuietHours(app.daycue.domain.config.QuietHours(false))))
        assertFalse(invalid.valid)
        assertEquals(Sensitivity.sensitive, invalid.sensitivity)
    }

    @Test
    fun `M-5 alarm-defeating and silencing changes are not ordinary`() {
        val notOrdinary = listOf(
            ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, false),
            ConfigOp.SkipNextAlarm(Defaults.MORNING_ALARM, java.time.LocalDate.of(2026, 10, 6)),
            ConfigOp.UpsertAlarm(base.alarm(Defaults.MORNING_ALARM)!!.copy(time = java.time.LocalTime.of(11, 0))),
            ConfigOp.SetPause(app.daycue.domain.engine.PauseTarget.All, app.daycue.domain.config.PauseSpec.Indefinite(java.time.Instant.EPOCH)),
            ConfigOp.SetQuietHours(app.daycue.domain.config.QuietHours(enabled = true)),
            ConfigOp.SetPlaceLocation(Defaults.HOME, OpSamples.NEW_C),
            ConfigOp.SetContextRules(app.daycue.domain.config.ContextRules()),
            ConfigOp.SetSessionRules(app.daycue.domain.config.SessionRules()),
        )
        notOrdinary.forEach { assertTrue(ConfigEditor.preview(base, listOf(it)).sensitivity != Sensitivity.ordinary, it.toString()) }
    }
}

/** Forward compatibility (DOMAIN.md §4): unknown op / nested subtypes become typed errors, never a crash. */
class ConfigOpCodecTest {
    @Test
    fun `known ops round-trip through decodeList`() {
        val ops = samples.map { it.op }
        val json = DayCueJson.encodeToJsonElement(ListSerializer(ConfigOp.serializer()), ops.take(50))
        val d = ConfigOpCodec.decodeList(json)
        assertTrue(d.ok, d.errors.toString())
        assertEquals(ops.take(50), d.ops)
    }

    @Test
    fun `unknown op type, unknown nested subtype and malformed ops are typed errors`() {
        val d = ConfigOpCodec.decodeList("""[
            {"type":"setHabitInterval","id":"sunscreen","minutes":90},
            {"type":"teleportPlace","id":"home"},
            {"type":"upsertHabit","habit":{"type":"futureHabit","id":"x","name":"X"}},
            {"type":"setPause","target":{"type":"galaxy"},"pause":null},
            {"type":"setHabitInterval","id":"sunscreen"},
            {"id":"no-type"},
            42
        ]""")
        assertFalse(d.ok)
        assertEquals(listOf(ConfigOp.SetHabitInterval("sunscreen", 90)), d.ops)
        assertEquals(listOf("ops[1]" to "unsupported_op", "ops[2]" to "unsupported_type", "ops[3]" to "unsupported_type",
            "ops[4]" to "bad_op", "ops[5]" to "bad_op", "ops[6]" to "bad_op"), d.errors.map { it.path to it.code })
    }

    @Test
    fun `list-level errors - not an array, empty, too many, not JSON`() {
        assertEquals("bad_ops", ConfigOpCodec.decodeList("""{"type":"setLanguage"}""").errors.single().code)
        assertEquals("bad_ops", ConfigOpCodec.decodeList("[]").errors.single().code)
        assertEquals("bad_ops", ConfigOpCodec.decodeList("not json").errors.single().code)
        val many = JsonArray(List(51) { DayCueJson.encodeToJsonElement(ConfigOp.serializer(), ConfigOp.SetLanguage(app.daycue.domain.config.Language.he)) })
        assertEquals("too_many", ConfigOpCodec.decodeList(many).errors.single().code)
        assertEquals("bad_ops", ConfigOpCodec.decodeList(null as kotlinx.serialization.json.JsonElement?).errors.single().code)
    }

    @Test
    fun `caller text in messages is sanitized`() {
        val e = ConfigOpCodec.decodeList("""[{"type":"<script>alert(1)</script>"}]""").errors.single()
        assertEquals("unsupported_op", e.code)
        assertFalse(e.message.contains("<"), e.message)
    }

    @Test
    fun `config documents still round-trip with the new context field`() {
        val c = base.copy(contextRules = base.contextRules.copy(onFootOngoingMaxMin = 240))
        assertEquals(c, ConfigCodec.decode(ConfigCodec.encode(c)))
        assertEquals(180, ConfigCodec.decode("""{"contextRules":{"onFootHoldMin":45}}""").contextRules.onFootOngoingMaxMin)
    }
}
