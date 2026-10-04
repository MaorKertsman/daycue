package app.daycue.domain.config

import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigHistory
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.ConfigValidator
import app.daycue.domain.edit.Sensitivity
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.query.Queries
import app.daycue.domain.testing.Scenario
import app.daycue.domain.query.WaitingReason
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.OverrideDuration
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `§12 defaults are valid, synthetic, medication list empty, everything disabled`() {
        val c = Defaults.config()
        assertEquals(emptyList(), ConfigValidator.validate(c))
        assertTrue(c.medications.isEmpty())
        assertTrue(c.habits.none { when (it) { is IntervalHabit -> it.enabled; is TransitionHabit -> it.enabled } })
        assertTrue(c.places.all { it.center == null }, "no locations shipped")
        assertTrue(c.settings.quietHours.enabled)
        assertEquals(5, c.calendarRules.rules.size)
        assertEquals(Defaults.workDaysFor(Language.he), Defaults.config(Language.he).settings.workDays)
    }

    @Test
    fun `JSON round trip is lossless and stable`() {
        val c = Defaults.config().copy(medications = listOf(Medication("m", "Synthetic", listOf(LocalTime.of(8, 0)), travelPolicy = TravelPolicy.KeepHomeTimezone(java.time.ZoneId.of("Asia/Jerusalem")))))
        val json = ConfigCodec.encode(c)
        assertEquals(c, ConfigCodec.decode(json))
        assertEquals(json, ConfigCodec.encode(ConfigCodec.decode(json)))
        assertTrue(json.contains("\"schemaVersion\":1"))
    }

    @Test
    fun `JSON is forward tolerant - unknown keys ignored, unknown enum values coerced, missing fields defaulted`() {
        val c = ConfigCodec.decode("""{"version":7,"futureField":{"x":1},"settings":{"language":"fr","newThing":true}}""")
        assertEquals(7, c.version)
        assertEquals(Language.en, c.settings.language)
        assertEquals(DayCueConfig.CURRENT_SCHEMA_VERSION, c.schemaVersion)
    }
}

class ConfigEditTest {
    private val base = Defaults.config()

    @Test
    fun `applyOps increments version and returns the previous document`() {
        val r = ConfigEditor.applyOps(base, listOf(ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 90)), base.version)
        assertIs<ApplyResult.Applied>(r)
        assertEquals(base.version + 1, r.config.version)
        assertEquals(90, (r.config.habit(Defaults.SUNSCREEN) as IntervalHabit).intervalMin)
        assertEquals(base, r.previous)
    }

    @Test
    fun `stale baseVersion is a conflict`() {
        assertEquals(ApplyResult.Conflict(0, 3), ConfigEditor.applyOps(base, listOf(ConfigOp.SetLanguage(Language.he)), 3))
    }

    @Test
    fun `validation errors carry field paths and PRODUCT ranges`() {
        val r = ConfigEditor.applyOps(base, listOf(
            ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 20), // 30-360
            ConfigOp.SetHabitInterval(Defaults.HYDRATION, 300), // 15-240
            ConfigOp.UpsertMedication(Medication("m", "", emptyList())),
            ConfigOp.SetContextRules(ContextRules(placeEnterDwellMin = 0)),
        ), base.version)
        assertIs<ApplyResult.Invalid>(r)
        val paths = r.errors.map { it.path }.toSet()
        assertTrue("habits[sunscreen].intervalMin" in paths)
        assertTrue("habits[hydration].intervalMin" in paths)
        assertTrue("medications[m].label" in paths)
        assertTrue("medications[m].times" in paths)
        assertTrue("contextRules.placeEnterDwellMin" in paths)
    }

    @Test
    fun `unknown ids are rejected, reorder must be a permutation`() {
        assertIs<ApplyResult.Invalid>(ConfigEditor.applyOps(base, listOf(ConfigOp.DeleteAlarm("nope")), 0))
        assertIs<ApplyResult.Invalid>(ConfigEditor.applyOps(base, listOf(ConfigOp.ReorderRoutineSteps(Defaults.MORNING_ROUTINE, listOf("shower"))), 0))
        val ok = ConfigEditor.applyOps(base, listOf(ConfigOp.ReorderRoutineSteps(Defaults.MORNING_ROUTINE, listOf("brush-teeth", "shower", "face-cleanser", "get-dressed"))), 0)
        assertIs<ApplyResult.Applied>(ok)
        assertEquals("brush-teeth", ok.config.routine(Defaults.MORNING_ROUTINE)!!.steps.first().id)
    }

    @Test
    fun `deletions cascade references`() {
        val withRef = (ConfigEditor.applyOps(base, listOf(ConfigOp.UpsertAlarm(base.alarm(Defaults.MORNING_ALARM)!!.copy(followOnRoutineId = Defaults.MORNING_ROUTINE))), 0) as ApplyResult.Applied).config
        val r = ConfigEditor.applyOps(withRef, listOf(ConfigOp.DeleteRoutine(Defaults.MORNING_ROUTINE)), withRef.version) as ApplyResult.Applied
        assertNull(r.config.alarm(Defaults.MORNING_ALARM)!!.followOnRoutineId)
    }

    @Test
    fun `preview - human readable diff and sensitivity classes (MED-9)`() {
        val p1 = ConfigEditor.preview(base, listOf(ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 90)))
        assertEquals(Sensitivity.ordinary, p1.sensitivity)
        assertEquals("habits[sunscreen].intervalMin: 120 -> 90", p1.text)
        val med = Medication("m", "Synthetic", listOf(LocalTime.of(8, 0)))
        val p2 = ConfigEditor.preview(base, listOf(ConfigOp.UpsertMedication(med)), redactMedicationLabels = true)
        assertEquals(Sensitivity.sensitive, p2.sensitivity)
        assertTrue(!p2.text.contains("Synthetic"), "label redacted: ${p2.text}")
        val withMed = base.copy(medications = listOf(med))
        assertEquals(Sensitivity.sensitive, ConfigEditor.preview(withMed, listOf(ConfigOp.SetMedicationTimes("m", listOf(LocalTime.of(9, 0))))).sensitivity)
        assertEquals(Sensitivity.destructive, ConfigEditor.preview(withMed, listOf(ConfigOp.DeleteMedication("m"))).sensitivity)
        assertEquals(Sensitivity.destructive, ConfigEditor.preview(base, listOf(ConfigOp.DeletePlace(Defaults.GYM))).sensitivity)
    }

    @Test
    fun `every editable area has an op - places, profiles, quiet hours, calendar overrides, alarms, pause-until`() {
        val ops = listOf(
            ConfigOp.SetPlaceLocation(Defaults.HOME, GeoPoint(0.0, 0.0), 200),
            ConfigOp.UpsertCueProfile(base.cueProfiles.first().copy(speechEnabled = false)),
            ConfigOp.SetQuietHours(QuietHours(enabled = false)),
            ConfigOp.SetEventOverride("evt-1", OverrideScope.Instance, EventDecisionOverride.Never),
            ConfigOp.SetEventOverride("series-1", OverrideScope.Series, EventDecisionOverride.Always(listOf(5))),
            ConfigOp.SkipNextAlarm(Defaults.MORNING_ALARM, java.time.LocalDate.of(2026, 10, 6)),
            ConfigOp.SetPause(PauseTarget.Habit(Defaults.HYDRATION), PauseSpec.Until(Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-05T12:00:00Z"))),
            ConfigOp.SetPause(PauseTarget.All, PauseSpec.Indefinite(Instant.parse("2026-10-05T10:00:00Z"))),
            ConfigOp.SetSpeechSettings(SpeechSettings(inMeeting = SpeechInMeetingPolicy.SpeakAnyway)),
        )
        val r = ConfigEditor.applyOps(base, ops, 0)
        assertIs<ApplyResult.Applied>(r)
        assertEquals(2, r.config.calendarRules.overrides.size)
        // ConfigOp lists are serializable (MCP command payloads)
        val json = DayCueJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ConfigOp.serializer()), ops)
        assertEquals(ops, DayCueJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ConfigOp.serializer()), json))
    }

    @Test
    fun `ConfigHistory is bounded and undo creates a new monotonic version`() {
        var h = ConfigHistory(maxSize = 3)
        var c = base
        repeat(5) { i ->
            val r = ConfigEditor.applyOps(c, listOf(ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 60 + i)), c.version) as ApplyResult.Applied
            h = h.push(r.previous); c = r.config
        }
        assertEquals(3, h.entries.size)
        val (undone, h2) = h.undo(c)!!
        assertEquals(c.version + 1, undone.version)
        assertEquals(63, (undone.habit(Defaults.SUNSCREEN) as IntervalHabit).intervalMin)
        assertEquals(2, h2.entries.size)
    }
}

class TodayViewTest {
    @Test
    fun `todayView lists context, active items and next cues with waiting reasons (GEN-9)`() {
        val s = Scenario()
        s.enable(Defaults.SUNSCREEN); s.enable(Defaults.HYDRATION)
        s.apply(ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, true), ConfigOp.UpsertMedication(Medication("m", "Synthetic", listOf(LocalTime.of(13, 0)))))
        s.advanceTo("09:30")
        var v = Queries.todayView(s.config, s.state, s.clock)
        val sun = v.upcoming.first { it.itemKey == "habit:sunscreen" }
        assertEquals(WaitingReason.WhenConditionHolds, sun.waiting, "'when outdoors'")
        val hyd = v.upcoming.first { it.itemKey == "habit:hydration" }
        assertEquals(s.t("10:00"), hyd.at)
        assertNotNull(v.upcoming.firstOrNull { it.itemKey.startsWith("med:") && it.at == s.t("13:00") })
        assertEquals(s.t("07:00", s.date.plusDays(1)), v.upcoming.first { it.itemKey == "alarm:${Defaults.MORNING_ALARM}" }.at)
        s.send(Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.send(Event.HabitAck(Defaults.SUNSCREEN))
        v = Queries.todayView(s.config, s.state, s.clock)
        val covered = v.upcoming.first { it.itemKey == "habit:sunscreen" }
        assertEquals(WaitingReason.CoveredUntil, covered.waiting)
        assertEquals(s.t("11:30"), covered.at)
        assertEquals(Environment.Outdoor, v.context.environment.value)
    }
}
