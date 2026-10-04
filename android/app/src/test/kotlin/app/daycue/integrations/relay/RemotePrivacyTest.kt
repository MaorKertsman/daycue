package app.daycue.integrations.relay

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MorningAlarm
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.Place
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.PauseTarget
import app.daycue.engine.ApplyOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

/**
 * Security review H-1, M-5, M-8 (phone side): nothing that leaves the phone is built from an on-phone preview.
 * Every payload the test can observe going out (acks, snapshots, audit rows, notices, the engine's audit rows) is
 * searched for the digits of a synthetic place and for medication content.
 */
class RemotePrivacyTest {
    // Synthetic coordinates, deliberately with distinctive digit runs.
    private val cafe = GeoPoint(31.123456, 35.654321)
    private val moved = GeoPoint(31.999999, 35.888888)
    private val parkAt = GeoPoint(29.555555, 33.444444)
    private val relocated = GeoPoint(30.987654, 34.123456)
    private val fragments = listOf("31.123456", "35.654321", "123456", "654321", "31.999999", "35.888888", "999999", "888888",
        "29.555555", "33.444444", "555555", "444444", "30.987654", "34.123456", "987654")

    private fun ops(vararg o: ConfigOp): JsonArray = DayCueJsonOps.encode(o.toList())

    private fun Harness.version() = runBlocking { host.ensureLoaded().config.version }

    private fun Harness.seedPlace(): Long = runBlocking {
        val cfg = host.ensureLoaded().config
        val r = host.applyOps(listOf(ConfigOp.UpsertPlace(Place("cafe", "Cafe", center = cafe, radiusM = 120))), cfg.version, "ui")
        (r as ApplyOutcome.Applied).config.version
    }

    /** Everything the phone sent out, as text. Excludes opaque hashes/signatures (random hex could contain digit runs). */
    private fun Harness.outbound(): String = buildString {
        relay.acks.forEach { appendLine(it.result.toString()); appendLine(it.outcome) }
        relay.snapshots.forEach { appendLine(it.toString()) }
        audit.rows.forEach { appendLine("${it.actor} ${it.action} ${it.summary}") }
        notifier.applied.forEach { appendLine(it) }
        store.audit.forEach { appendLine("${it.actor} ${it.action} ${it.summary}") }
    }

    private fun assertNoCoordinates(text: String) {
        for (f in fragments) assertFalse("outbound payload leaked '$f':\n$text", text.contains(f))
    }

    private fun previewCmd(h: Harness, vararg o: ConfigOp) = h.relay.queue("config.preview", buildJsonObject { put("ops", ops(*o)) })

    @Test fun onPhonePreviewDoesContainCoordinates_soTheTestCanFail() = runBlocking {
        val h = Harness(); h.seedPlace()
        val raw = ConfigEditor.preview(h.host.ensureLoaded().config, listOf(ConfigOp.DeletePlace("cafe"))).text
        assertTrue("sanity: the raw on-phone preview shows the place's coordinates", raw.contains("31.123456"))
    }

    @Test fun noCoordinateDigitsInAnyOutboundPayloadAcrossPreviewApplyConfirmAndUndo() = runBlocking {
        val h = Harness(); var v = h.seedPlace()

        // ---- preview: delete, replace with a moved center, new place, relocate
        previewCmd(h, ConfigOp.DeletePlace("cafe"))
        previewCmd(h, ConfigOp.UpsertPlace(Place("cafe", "Cafe", center = moved, radiusM = 120)))
        previewCmd(h, ConfigOp.UpsertPlace(Place("park", "Park", center = parkAt)))
        previewCmd(h, ConfigOp.SetPlaceLocation("cafe", relocated, 90))
        h.client.sync("previews")
        assertEquals(4, h.relay.acks.count { it.outcome == "applied" })
        assertTrue(h.relay.acks.all { it.signatureOk })
        assertNoCoordinates(h.outbound())
        // the relay-visible diff still says what happens, without where
        val delPreview = h.relay.acks.first().result["preview"]!!.jsonObject["diff"]!!.toString()
        assertTrue(delPreview, delPreview.contains("Cafe"))

        // ---- apply: relocation (sensitive: awaiting confirmation, then confirm), then delete (destructive), then undo
        h.relay.queueApply(listOf(ConfigOp.SetPlaceLocation("cafe", relocated, 90)), baseVersion = v)
        h.client.sync("apply-relocate")
        assertEquals("awaiting_confirmation", h.relay.acks.last().outcome)
        assertNoCoordinates(h.outbound())
        // the owner's own confirmation screen is on-phone and is allowed to show the raw change
        assertTrue(h.notifier.confirmations.last().lines.joinToString().contains("30.987654"))
        assertEquals(RelayClient.DecisionResult.Done, h.client.decide(h.relay.commands.last().id, accept = true))
        v = h.version()
        assertEquals("applied", h.relay.acks.last().outcome)
        assertNoCoordinates(h.outbound())

        h.relay.queueApply(listOf(ConfigOp.DeletePlace("cafe")), baseVersion = v)
        h.client.sync("apply-delete")
        assertEquals("awaiting_confirmation", h.relay.acks.last().outcome)
        h.client.decide(h.relay.commands.last().id, accept = true)
        v = h.version()
        assertEquals("applied", h.relay.acks.last().outcome)
        assertNoCoordinates(h.outbound())

        // undo of the delete re-adds the place (with its coordinates, on the phone only)
        h.relay.queue("config.undo", buildJsonObject { put("targetVersion", v) }, baseVersion = v)
        h.client.sync("undo")
        if (h.relay.acks.last().outcome == "awaiting_confirmation") h.client.decide(h.relay.commands.last().id, accept = true)
        assertEquals("applied", h.relay.acks.last().outcome)
        assertNotNull("the place is back with its (relocated) center", h.host.ensureLoaded().config.place("cafe")!!.center)
        assertTrue(h.relay.acks.last().result["summary"]!!.jsonPrimitive.content.startsWith("Undo of version $v: "))
        h.client.invalidatePublished(); h.client.sync("snapshot")
        assertNoCoordinates(h.outbound())
        // and the rejected path: an invalid op list must not echo values either
        h.relay.queueApply(listOf(ConfigOp.SetPlaceLocation("cafe", GeoPoint(95.123456, 35.654321))), baseVersion = h.version())
        h.client.sync("invalid")
        assertEquals("rejected", h.relay.acks.last().outcome)
        assertNoCoordinates(h.outbound())
    }

    // ---- medication (M-8, phone side) --------------------------------------------------------------------

    private val med = Medication("m1", "Zork Pill 7", listOf(LocalTime.of(9, 30)))
    private val medScopes = listOf("config:read", "config:write", "medication")

    private fun assertNoMedicationText(text: String) {
        assertFalse(text, text.contains("Zork Pill"))
        assertFalse(text, text.contains("09:30"))
    }

    @Test fun medicationContentNeverReachesAuditRowsNoticesOrOtherGrantsEvenWithTheScope() = runBlocking {
        val h = Harness(settings = RelaySettings(allowMedication = true))
        val v = h.host.ensureLoaded().config.version
        h.relay.queueApply(listOf(ConfigOp.UpsertMedication(med)), baseVersion = v, scopes = medScopes)
        h.client.sync("1")
        assertEquals("awaiting_confirmation", h.relay.acks.last().outcome)
        h.client.decide(h.relay.commands.last().id, accept = true)
        assertEquals("applied", h.relay.acks.last().outcome)
        // The grant that holds the scope and the owner's allowance sees the medication line in its own result ...
        assertTrue(h.relay.acks.last().result.toString().contains("Zork Pill"))
        // ... but audit rows, the engine's audit and notices are Strict.
        assertNoMedicationText(h.audit.rows.joinToString("\n") { it.summary })
        assertNoMedicationText(h.notifier.applied.joinToString("\n"))
        assertNoMedicationText(h.store.audit.joinToString("\n") { it.summary })
        // a later preview by a grant WITHOUT the scope withholds medication
        h.relay.queue("config.preview", buildJsonObject { put("ops", ops(ConfigOp.SetMedicationTimes("m1", listOf(LocalTime.of(10, 45))))) })
        h.client.sync("2")
        val prev = h.relay.acks.last().result.toString()
        assertFalse(prev, prev.contains("Zork")); assertFalse(prev, prev.contains("10:45")); assertFalse(prev, prev.contains("09:30"))
    }

    @Test fun medicationScopeWithoutOwnerAllowanceStaysWithheldInPreviews() = runBlocking {
        val h = Harness(settings = RelaySettings(allowMedication = false))
        h.host.ensureLoaded()
        h.host.applyOps(listOf(ConfigOp.UpsertMedication(med)), h.host.ensureLoaded().config.version, "ui")
        h.relay.queue("config.preview", buildJsonObject { put("ops", ops(ConfigOp.SetMedicationTimes("m1", listOf(LocalTime.of(10, 45))))) }, scopes = medScopes)
        h.client.sync("1")
        val prev = h.relay.acks.last().result.toString()
        assertFalse(prev, prev.contains("10:45")); assertFalse(prev, prev.contains("09:30")); assertFalse(prev, prev.contains("Zork"))
        assertTrue(prev, prev.contains("withheld"))
    }

    @Test fun snapshotTagsMedicationEntriesAndPublishesNoMedicationTimeWithoutAllowance() {
        val cfg = Defaults.config(app.daycue.domain.config.Language.en)
        val at = Instant.parse("2026-10-05T07:30:00Z")
        val st = EngineState(nextWakeAt = at, nextWakeReason = "medication")
        val recent = listOf(
            RecentChange(5, 1_000, "mcp", "medications: details withheld -> changed (details withheld)", medication = true),
            RecentChange(4, 900, "ui", "habits[demo].intervalMin: 30 -> 45"),
        )
        val off = SnapshotBuilder.build(cfg, st, 0, includeMedication = false, recent = recent).toString()
        assertTrue(off, off.contains("\"medication\":true"))
        assertFalse("no dose time or kind without the allowance", off.contains(at.toEpochMilli().toString()))
        assertFalse(off.contains("withheld ->"))
        assertTrue(off.contains("intervalMin"))
        val on = SnapshotBuilder.build(cfg, st, 0, includeMedication = true, recent = recent)
        val cue = on["status"]!!.jsonObject["nextCues"]!!.toString()
        assertTrue(cue, cue.contains("\"medication\":true") && cue.contains(at.toEpochMilli().toString()) && cue.contains("\"kind\":\"medication\""))
        // ordinary cues carry no tag
        val other = SnapshotBuilder.build(cfg, EngineState(nextWakeAt = at, nextWakeReason = "alarm"), 0, false, emptyList())["status"]!!.jsonObject["nextCues"]!!.toString()
        assertFalse(other, other.contains("medication")); assertTrue(other, other.contains("\"kind\":\"alarm\""))
    }

    @Test fun snapshotNeverContainsCoordinatesCalendarEventKeysOrMedicationCueProfilesByDefault() {
        val base = Defaults.config(app.daycue.domain.config.Language.en)
        val cfg = base.copy(
            places = listOf(Place("cafe", "Cafe", center = cafe)),
            calendarRules = base.calendarRules.copy(overrides = listOf(app.daycue.domain.config.EventOverride("evt-uid-abc@example.test", app.daycue.domain.config.OverrideScope.Instance, app.daycue.domain.config.EventDecisionOverride.Never))),
        )
        val s = SnapshotBuilder.build(cfg, EngineState(), 0, false, emptyList()).toString()
        for (f in fragments) assertFalse(f, s.contains(f))
        assertFalse("event ids are hashed", s.contains("evt-uid-abc"))
        assertTrue(s.contains("\"key\":\"h:"))
        assertFalse("medication cue profile withheld", s.contains("\"type\":\"Medication\""))
    }

    // ---- M-5: stricter sensitivity table is enforced through the relay ---------------------------------

    @Test fun alarmPauseAllQuietHoursAndRelocationNowNeedOnPhoneConfirmation() = runBlocking {
        val h = Harness()
        val cfg = h.host.ensureLoaded().config
        h.host.applyOps(listOf(ConfigOp.UpsertAlarm(MorningAlarm("a1", "Alarm", enabled = true, time = LocalTime.of(7, 0), days = null)),
            ConfigOp.UpsertPlace(Place("cafe", "Cafe", center = cafe))), cfg.version, "ui")
        val now = Instant.parse("2026-10-05T07:00:00Z")
        val risky = listOf(
            ConfigOp.SetAlarmEnabled("a1", false),
            ConfigOp.SkipNextAlarm("a1", java.time.LocalDate.parse("2026-10-06")),
            ConfigOp.SetPause(PauseTarget.All, PauseSpec.Until(now, now.plusSeconds(86_400))),
            ConfigOp.SetQuietHours(h.host.ensureLoaded().config.settings.quietHours.copy(enabled = false)),
            ConfigOp.SetPlaceLocation("cafe", relocated),
        )
        for (op in risky) {
            val before = h.host.ensureLoaded().config.version
            h.relay.queueApply(listOf(op), baseVersion = before)
            h.client.sync("t")
            assertEquals("${op::class.simpleName} must wait for the owner", "awaiting_confirmation", h.relay.acks.last().outcome)
            assertEquals("nothing applied before the owner decides", before, h.host.ensureLoaded().config.version)
        }
        assertEquals(risky.size, h.notifier.confirmations.size)
        // an ordinary change still applies silently
        h.relay.queueApply(listOf(ConfigOp.SetHabitInterval("hydration", 90)), baseVersion = h.host.ensureLoaded().config.version)
        h.client.sync("ordinary")
        assertEquals("applied", h.relay.acks.last().outcome)
    }
}

/** Test helper: encodes op lists the way the relay carries them. */
object DayCueJsonOps {
    fun encode(ops: List<ConfigOp>): JsonArray =
        app.daycue.domain.config.DayCueJson.encodeToJsonElement(ListSerializer(ConfigOp.serializer()), ops) as JsonArray
}
