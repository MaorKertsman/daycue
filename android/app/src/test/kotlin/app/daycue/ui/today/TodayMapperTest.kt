package app.daycue.ui.today

import app.daycue.R
import app.daycue.domain.config.Activity
import app.daycue.domain.config.Confidence
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.context.ContextSource
import app.daycue.domain.context.DetectionPause
import app.daycue.domain.context.Dim
import app.daycue.domain.context.InferredContext
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.context.PlaceValue
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.PosturePhase
import app.daycue.domain.engine.PostureState
import app.daycue.domain.engine.SlotRef
import app.daycue.domain.query.ActiveItem
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.DoseView
import app.daycue.domain.query.TodayView
import app.daycue.domain.query.UpcomingItem
import app.daycue.domain.query.WaitingReason
import app.daycue.ui.components.ContextLineKind
import app.daycue.ui.field.FieldActive
import app.daycue.ui.marks.CueType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.daycue.domain.config.CueType as DomainCue

/** The Today mapper is pure: structured models from todayView + config + state (no Android, no rules). */
class TodayMapperTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-05T10:00:00Z")

    private fun enabled(vararg ids: String): DayCueConfig {
        val base = Defaults.config()
        return base.copy(habits = base.habits.map { h -> if (h.id in ids && h is IntervalHabit) h.copy(enabled = true) else h })
    }

    private fun ctx(
        place: PlaceValue = PlaceValue.saved(Defaults.HOME),
        env: Environment = Environment.Indoor,
        envSource: ContextSource = ContextSource.PlaceTypical,
        activity: Activity = Activity.Working,
        paused: Boolean = false,
    ) = InferredContext(
        place = Dim(place, Confidence.High, ContextSource.Geofence, now),
        environment = Dim(env, Confidence.High, envSource, now),
        activity = Dim(activity, Confidence.High, ContextSource.Companion, now),
        detectionPaused = paused,
    )

    private fun view(
        context: InferredContext = ctx(),
        active: List<ActiveItem> = emptyList(),
        upcoming: List<UpcomingItem> = emptyList(),
        doses: List<DoseView> = emptyList(),
    ) = TodayView(now, context, active, upcoming, doses, null)

    @Test
    fun firstRunShowsTheChooseState() {
        val config = Defaults.config()
        val m = TodayMapper.map(view(), config, EngineState(), zone)
        assertTrue(m.nothingEnabled)
        assertTrue(m.due.isEmpty() && m.running.isEmpty() && m.next.isEmpty())
        assertNull(m.field)
        assertTrue(config.medications.isEmpty())
    }

    @Test
    fun dueHabitCarriesItsActionsDataAndLastConfirmation() {
        val config = enabled(Defaults.HYDRATION)
        val ack = "2026-10-05T09:20:00Z"
        val m = TodayMapper.map(
            view(
                active = listOf(ActiveItem("habit:hydration", "cue", mapOf("cueId" to "c1", "type" to "Hydration"))),
                upcoming = listOf(UpcomingItem("habit:hydration", DomainCue.Hydration, "Hydration", waiting = WaitingReason.Pending, rule = "GEN-4", facts = mapOf("lastAck" to ack))),
            ),
            config, EngineState(), zone,
        )
        val due = m.due.single() as HabitDue
        assertEquals("hydration", due.habitId)
        assertEquals(CueType.Hydration, due.mark)
        assertEquals(Instant.parse(ack), due.lastAck)
        assertEquals(R.string.cue_hydration, due.name.res)
        assertEquals(15, due.snoozeMin)
        // A Pending row is the Now card, never a Next row.
        assertTrue(m.next.isEmpty())
        assertTrue(m.field?.overdue == true)
    }

    @Test
    fun nextRowsShowTimeOrWaitingReasonAndNeverTheDueItem() {
        val config = enabled(Defaults.SUNSCREEN, Defaults.HYDRATION)
        val m = TodayMapper.map(
            view(
                upcoming = listOf(
                    UpcomingItem("habit:sunscreen", DomainCue.Sunscreen, "Sunscreen", waiting = WaitingReason.WhenConditionHolds, rule = "SUN-4"),
                    UpcomingItem("habit:hydration", DomainCue.Hydration, "Hydration", at = now.plusSeconds(40 * 60), rule = "HYD-3"),
                ),
            ),
            config, EngineState(), zone,
        )
        val sun = m.next.first { it.key == "habit:sunscreen" }
        assertEquals(WaitingReason.WhenConditionHolds, sun.waiting)
        assertEquals(ConditionText.Outdoors, sun.condition)
        val water = m.next.first { it.key == "habit:hydration" }
        assertEquals(now.plusSeconds(2400), water.at)
        assertNull(water.waiting)
        // The Field points at the one with a time.
        assertEquals(CueType.Hydration, m.field?.nextMark)
        assertEquals(40, m.field?.remainingMinutes)
    }

    @Test
    fun contextLineKinds() {
        val config = Defaults.config()
        assertEquals(ContextLineKind.Normal, TodayMapper.map(view(), config, EngineState(), zone).context.lineKind)
        val unknown = view(context = ctx(place = PlaceValue.UNKNOWN, env = Environment.Unknown, activity = Activity.Unknown))
        val u = TodayMapper.map(unknown, config, EngineState(), zone)
        assertEquals(ContextLineKind.Uncertain, u.context.lineKind)
        assertEquals(PlaceKind.Unknown, u.context.place.kind)
        val manual = view(context = ctx(envSource = ContextSource.Manual))
        assertEquals(ContextLineKind.Override, TodayMapper.map(manual, config, EngineState(), zone).context.lineKind)
        val paused = view(context = ctx(paused = true))
        val pausedState = EngineState(context = EngineState().context.copy(detectionPause = DetectionPause(now, now.plusSeconds(7200))))
        val p = TodayMapper.map(paused, config, pausedState, zone)
        assertEquals(ContextLineKind.Paused, p.context.lineKind)
        assertEquals(now.plusSeconds(7200), p.context.detectionPausedUntil)
    }

    @Test
    fun bundledNamesFollowTheLanguageButRenamedOnesKeepTheirText() {
        assertEquals(R.string.app_place_home, defaultName("home", "Home").res)
        assertNull(defaultName("home", "My flat").res)
        assertEquals("My flat", defaultName("home", "My flat").raw)
        assertNull(defaultName("custom-id", "Home").res)
    }

    @Test
    fun runningPostureIsARunningRowNotANextRow() {
        val base = Defaults.config()
        val config = base.copy(postureCycle = base.postureCycle.copy(enabled = true))
        val modes = config.postureCycle.modes
        val state = EngineState(
            posture = PostureState(phase = PosturePhase.Running, modeId = modes[1].id, modeStartedAt = now.minusSeconds(18 * 60), modeEndsAt = now.plusSeconds(12 * 60)),
        )
        val m = TodayMapper.map(
            view(upcoming = listOf(UpcomingItem("posture", DomainCue.Posture, "Posture", at = now.plusSeconds(12 * 60), rule = "POS-3"))),
            config, state, zone,
        )
        val posture = m.running.filterIsInstance<PostureRunning>().single()
        assertEquals("Standing", posture.modeName)
        assertEquals("Walking (treadmill)", posture.nextName)
        assertTrue(m.next.none { it.key == "posture" })
        // 18 of 30 minutes done: the Field fill follows the engine timestamps.
        val fill = (m.field?.active as FieldActive.Posture).fill
        assertEquals(0.6f, fill, 0.01f)
    }

    @Test
    fun dueDoseAppearsWithTakeActionData() {
        val slot = SlotRef("med-1", LocalDate.of(2026, 10, 5), LocalTime.of(9, 30))
        val m = TodayMapper.map(
            view(doses = listOf(DoseView(slot, "Vitamin sample", now.minusSeconds(300), DoseStatus.Due, cueId = "c9"))),
            Defaults.config(), EngineState(), zone,
        )
        val dose = m.due.single() as DoseDue
        assertEquals(slot, dose.slot)
        assertEquals("Vitamin sample", dose.label)
        assertEquals(false, dose.notConfirmed)
        assertEquals(CueType.Medication, dose.mark)
    }

    @Test
    fun dueItemsAreOrderedByCuePriority() {
        val config = enabled(Defaults.SUNSCREEN, Defaults.HYDRATION)
        val slot = SlotRef("med-1", LocalDate.of(2026, 10, 5), LocalTime.of(9, 30))
        val m = TodayMapper.map(
            view(
                active = listOf(
                    ActiveItem("habit:hydration", "cue", mapOf("cueId" to "a")),
                    ActiveItem("habit:sunscreen", "cue", mapOf("cueId" to "b")),
                ),
                doses = listOf(DoseView(slot, "Vitamin sample", now, DoseStatus.Due)),
            ),
            config, EngineState(), zone,
        )
        assertEquals(listOf("med:med-1|2026-10-05|09:30", "habit:sunscreen", "habit:hydration"), m.due.map { it.key })
    }

    @Test
    fun whyNowCarriesSpecificFactsFromTheDomainReasonData() {
        val base = Defaults.config()
        val hydration = base.habits.filterIsInstance<IntervalHabit>().first { it.id == Defaults.HYDRATION }
        val config = base.copy(habits = base.habits.map { if (it.id == Defaults.HYDRATION) hydration.copy(enabled = true, intervalMin = 120) else it })
        val last = "2026-10-05T08:10:00Z"
        val v = view(upcoming = listOf(
            UpcomingItem("habit:${Defaults.HYDRATION}", DomainCue.Hydration, "Hydration", at = Instant.parse("2026-10-05T10:10:00Z"), rule = "HYD-3", facts = mapOf("lastAck" to last)),
            UpcomingItem("med:m|2026-10-05|20:00", DomainCue.Medication, "Synthetic", at = Instant.parse("2026-10-05T20:00:00Z"), rule = "MED-1"),
            UpcomingItem("alarm:${Defaults.MORNING_ALARM}", DomainCue.Alarm, "Morning alarm", at = Instant.parse("2026-10-06T07:00:00Z"), rule = "ALM-1"),
            UpcomingItem("cal:e1", DomainCue.Calendar, "Event", at = Instant.parse("2026-10-05T12:00:00Z"), rule = "CAL-1"),
        ))
        val why = TodayMapper.map(v, config, EngineState(), zone).why
        val h = why.getValue("habit:${Defaults.HYDRATION}")
        assertEquals(WhyKind.Interval, h.kind)
        assertEquals(120, h.intervalMin)
        assertEquals(Instant.parse(last), h.lastAck)
        assertEquals(WhyKind.Dose, why.getValue("med:m|2026-10-05|20:00").kind)
        assertEquals(WhyKind.Alarm, why.getValue("alarm:${Defaults.MORNING_ALARM}").kind)
        assertTrue(why.getValue("alarm:${Defaults.MORNING_ALARM}").days!!.isNotEmpty())
        assertEquals(WhyKind.Other, why.getValue("cal:e1").kind)
    }
}
