package app.daycue.facade

import app.daycue.data.db.HistoryEventEntity
import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.Medication
import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.Sensitivity
import app.daycue.engine.ConfigDiff
import app.daycue.engine.demoHabit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class ConfigTransferTest {
    private val now = Instant.parse("2026-10-05T07:00:00Z")
    private val base = Defaults.config().copy(version = 7)
    private val row = HistoryEventEntity(1, now.toEpochMilli(), "UTC", "Acked", "habit", "demo", "c1", null, null, "SUN-2", false)

    @Test
    fun exportExcludesHistoryUnlessAsked() {
        val plain = ConfigTransfer.export(base, null, now)
        assertFalse(plain.contains("\"history\""))
        val full = ConfigTransfer.export(base, listOf(row), now)
        assertTrue(full.contains("\"history\""))
        assertTrue(full.contains("SUN-2"))
    }

    @Test
    fun roundTripOfOwnExportIsNoChange() {
        val parsed = ConfigTransfer.parse(ConfigTransfer.export(base, listOf(row), now)) as ImportParse.Ok
        assertTrue(parsed.hadHistory)
        val plan = ConfigTransfer.plan(base, parsed.config)
        assertTrue(plan.noChanges)
        assertTrue(plan.valid)
    }

    @Test
    fun rejectsNonDayCueFilesAndFutureSchemas() {
        assertTrue(ConfigTransfer.parse("not json") is ImportParse.NotDayCue)
        assertTrue(ConfigTransfer.parse("{}") is ImportParse.NotDayCue)
        assertTrue(ConfigTransfer.parse("""{"hello":"world"}""") is ImportParse.NotDayCue)
        assertTrue(ConfigTransfer.parse("""{"format":"daycue-backup","exportedAt":"x","config":{"schemaVersion":99,"settings":{}}}""") is ImportParse.UnsupportedSchema)
        // A bare config document (no wrapper) is accepted.
        assertTrue(ConfigTransfer.parse(ConfigCodec.encode(base)) is ImportParse.Ok)
    }

    /** Import goes through ConfigOp validation: an out-of-range value is reported and nothing applies. */
    @Test
    fun invalidImportIsReportedNotApplied() {
        val bad = base.copy(habits = base.habits.map { if (it is IntervalHabit && it.id == "hydration") it.copy(intervalMin = 1) else it })
        val text = ConfigTransfer.export(bad, null, now)
        val plan = ConfigTransfer.plan(base, (ConfigTransfer.parse(text) as ImportParse.Ok).config)
        assertFalse(plan.valid)
        assertTrue(plan.preview.errors.any { it.path.contains("intervalMin") })
        assertTrue(ConfigEditor.applyOps(base, plan.ops, plan.baseVersion) is ApplyResult.Invalid)
    }

    @Test
    fun importWithChangesReproducesTheDocumentAndFlagsSensitivity() {
        val target = base.copy(
            habits = base.habits.filterNot { it.id == "sunscreen" } + demoHabit(10),
            medications = listOf(Medication("m1", "Demo dose", times = listOf(LocalTime.of(8, 0)))),
            settings = base.settings.copy(quietHours = base.settings.quietHours.copy(enabled = false)),
        )
        val plan = ConfigTransfer.plan(base, (ConfigTransfer.parse(ConfigTransfer.export(target, null, now)) as ImportParse.Ok).config)
        assertTrue(plan.valid)
        assertTrue(plan.containsMedication)
        assertEquals("deleting a habit is destructive", Sensitivity.destructive, plan.preview.sensitivity)
        val applied = ConfigEditor.applyOps(base, plan.ops, base.version) as ApplyResult.Applied
        assertTrue(ConfigDiff.sameContent(applied.config, target))
        assertEquals(base.version + 1, applied.config.version)
    }

    @Test
    fun diffHandlesDeleteSideEffects() {
        val noPlaces: DayCueConfig = base.copy(places = emptyList(), habits = base.habits.map {
            if (it is app.daycue.domain.config.TransitionHabit) it.copy(placeIds = emptySet()) else it
        })
        val ops = ConfigDiff.ops(base, noPlaces)
        val applied = ConfigEditor.applyOps(base, ops, base.version)
        assertTrue("applied: $applied", applied is ApplyResult.Applied)
        assertTrue(ConfigDiff.sameContent((applied as ApplyResult.Applied).config, noPlaces))
    }
}
