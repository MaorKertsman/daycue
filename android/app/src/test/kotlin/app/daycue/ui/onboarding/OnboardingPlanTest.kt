package app.daycue.ui.onboarding

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.edit.ConfigOp
import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessItem
import app.daycue.system.ReadinessReport
import app.daycue.system.ReadinessStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPlanTest {
    private val config = Defaults.config()

    private fun report(vararg rows: Pair<ReadinessId, ReadinessStatus>) =
        ReadinessReport(rows.map { ReadinessItem(it.first, it.second) }, Instant.EPOCH, null, null)

    @Test
    fun firstRunIsEmptyAndSeedsNoMedication() {
        assertTrue(config.habits.none { it.enabled })
        assertTrue(!config.postureCycle.enabled)
        assertTrue(config.routines.none { it.enabled })
        assertTrue(config.alarms.none { it.enabled })
        assertTrue(config.medications.isEmpty())
        assertTrue(OnboardingPlan.ops(config, emptySet()).isEmpty())
    }

    @Test
    fun choosingTemplatesEnablesExactlyThose() {
        val ops = OnboardingPlan.ops(config, setOf(Template.Hydration, Template.Posture, Template.Alarm, Template.Routine, Template.Calendar))
        assertTrue(ConfigOp.SetHabitEnabled("hydration", true) in ops)
        assertTrue(ConfigOp.SetPostureEnabled(true) in ops)
        assertTrue(ConfigOp.SetAlarmEnabled("morning-alarm", true) in ops)
        assertTrue(ops.any { it is ConfigOp.UpsertRoutine && it.routine.id == "morning-routine" && it.routine.enabled })
        // Sunscreen and bottle stay as they are (already off): no op is sent for them.
        assertTrue(ops.none { it is ConfigOp.SetHabitEnabled && it.id != "hydration" })
        // Calendar has no switch: its starter rules already exist.
        assertEquals(4, ops.size)
    }

    @Test
    fun goingBackAndUnticksDisablesAgain() {
        val on = config.copy(habits = config.habits.map { if (it.id == "hydration" && it is IntervalHabit) it.copy(enabled = true) else it })
        val ops = OnboardingPlan.ops(on, emptySet())
        assertEquals(listOf<ConfigOp>(ConfigOp.SetHabitEnabled("hydration", false)), ops)
    }

    @Test
    fun cardsFollowTheUxOrderAndOnlyShowWhatIsMissing() {
        val r = report(
            ReadinessId.Notifications to ReadinessStatus.Off,
            ReadinessId.ExactAlarms to ReadinessStatus.Limited,
            ReadinessId.FullScreenIntent to ReadinessStatus.Limited,
            ReadinessId.BatteryOptimization to ReadinessStatus.Limited,
            ReadinessId.Hibernation to ReadinessStatus.Limited,
        )
        val all = OnboardingPlan.cards(setOf(Template.Alarm, Template.Calendar), r, calendarGranted = false, skipped = emptySet())
        assertEquals(
            listOf(PermissionKind.Notifications, PermissionKind.ExactAlarms, PermissionKind.FullScreen, PermissionKind.Calendar, PermissionKind.Battery, PermissionKind.Hibernation),
            all,
        )
        // No alarm chosen: no full-screen card. Calendar already granted: no calendar card.
        val some = OnboardingPlan.cards(setOf(Template.Hydration), r, calendarGranted = true, skipped = emptySet())
        assertEquals(listOf(PermissionKind.Notifications, PermissionKind.ExactAlarms, PermissionKind.Battery, PermissionKind.Hibernation), some)
    }

    @Test
    fun grantedOrSkippedCardsAreNotShown() {
        val r = report(
            ReadinessId.Notifications to ReadinessStatus.Ready,
            ReadinessId.ExactAlarms to ReadinessStatus.Ready,
            ReadinessId.BatteryOptimization to ReadinessStatus.Limited,
            ReadinessId.Hibernation to ReadinessStatus.Limited,
        )
        val cards = OnboardingPlan.cards(setOf(Template.Hydration), r, true, skipped = setOf("battery"))
        assertEquals(listOf(PermissionKind.Hibernation), cards)
        // Nothing chosen: only notifications may be asked (the test reminder needs them), nothing else.
        val none = OnboardingPlan.cards(
            emptySet(),
            report(ReadinessId.Notifications to ReadinessStatus.Off, ReadinessId.BatteryOptimization to ReadinessStatus.Limited),
            false, emptySet(),
        )
        assertEquals(listOf(PermissionKind.Notifications), none)
        assertTrue(OnboardingPlan.cards(setOf(Template.Alarm), null, false, emptySet()).isEmpty())
    }
}
