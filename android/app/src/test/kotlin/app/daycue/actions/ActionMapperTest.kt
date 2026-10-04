package app.daycue.actions

import app.daycue.actions.ActionMapper.Tap
import app.daycue.domain.engine.ActionKind
import app.daycue.domain.engine.AlarmAction
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.PauseChoice
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.engine.PostureAction
import app.daycue.domain.engine.RecoveryChoice
import app.daycue.domain.engine.RoutineAction
import app.daycue.domain.engine.SessionAnswer
import app.daycue.domain.engine.SlotRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ActionMapperTest {
    private fun a(kind: ActionKind, minutes: Int? = null) = Tap.Action(kind, minutes)

    @Test
    fun habitActions() {
        assertEquals(Event.HabitAck("sunscreen", "c1"), ActionMapper.map("habit:sunscreen", a(ActionKind.Applied), "c1"))
        assertEquals(Event.HabitAck("hydration", "c1"), ActionMapper.map("habit:hydration", a(ActionKind.Drank), "c1"))
        assertEquals(Event.HabitAck("x", "c1"), ActionMapper.map("habit:x", a(ActionKind.Done), "c1"))
        assertEquals(Event.HabitSnooze("x", "c1"), ActionMapper.map("habit:x", a(ActionKind.Snooze, 15), "c1"))
        assertEquals(Event.Pause(PauseTarget.Habit("x"), PauseChoice.For(60), "c1"), ActionMapper.map("habit:x", a(ActionKind.Pause), "c1"))
        assertEquals(Event.BottleAck("water-bottle", false, "c1"), ActionMapper.map("habit:water-bottle", a(ActionKind.GotIt), "c1"))
        assertEquals(Event.BottleAck("water-bottle", true, "c1"), ActionMapper.map("habit:water-bottle", a(ActionKind.NotNeeded), "c1"))
    }

    /** GEN-1 / MED-2 / acceptance scenario 4: a swipe maps to CueDismissed only, for every item type. */
    @Test
    fun dismissalIsNeverAnAck() {
        listOf("habit:sunscreen", "med:m1|2026-10-05|08:00", "posture", "routine:r", "cal:k", "alarm:a", "session:home").forEach { key ->
            val e = ActionMapper.map(key, Tap.Dismissed, "cue-1")
            assertEquals(Event.CueDismissed("cue-1"), e)
        }
        assertNull("no cue id -> nothing", ActionMapper.map("habit:x", Tap.Dismissed, null))
    }

    @Test
    fun medicationSlotParsing() {
        val slot = SlotRef("m1", LocalDate.of(2026, 10, 5), LocalTime.of(8, 0))
        assertEquals(Event.MedicationTaken(slot, "c"), ActionMapper.map("med:m1|2026-10-05|08:00", a(ActionKind.Taken), "c"))
        assertEquals(Event.MedicationSnooze(slot, "c"), ActionMapper.map("med:m1|2026-10-05|08:00", a(ActionKind.Snooze, 10), "c"))
        assertEquals("slot key round trip", "med:${slot.key}", "med:m1|2026-10-05|08:00")
        assertNull("merged cue Open is an activity, not an event", ActionMapper.map("med:merged", a(ActionKind.Open), "c"))
        assertNull(ActionMapper.slot("garbage"))
    }

    @Test
    fun postureRoutineCalendarSessionAlarm() {
        assertEquals(Event.PostureControl(PostureAction.Switched, "c"), ActionMapper.map("posture", a(ActionKind.Switched), "c"))
        assertEquals(Event.PostureControl(PostureAction.Extend5, "c"), ActionMapper.map("posture", a(ActionKind.Extend, 5), "c"))
        assertEquals(Event.PostureControl(PostureAction.Snooze, "c"), ActionMapper.map("posture", a(ActionKind.Snooze, 5), "c"))
        assertEquals(Event.RoutineControl(RoutineAction.Done, "c"), ActionMapper.map("routine:r", a(ActionKind.Done), "c"))
        assertEquals(Event.RoutineControl(RoutineAction.Extend(1), "c"), ActionMapper.map("routine:r", a(ActionKind.Extend, 1), "c"))
        assertEquals(Event.RoutineControl(RoutineAction.Recover(RecoveryChoice.Restart), "c"), ActionMapper.map("routine:r", a(ActionKind.Restart), "c"))
        assertEquals(Event.RoutineControl(RoutineAction.PromptStart("r"), "c"), ActionMapper.map("routine-prompt:r", a(ActionKind.Start), "c"))
        assertEquals(Event.RoutineControl(RoutineAction.PromptSkipToday("r"), "c"), ActionMapper.map("routine-prompt:r", a(ActionKind.SkipToday), "c"))
        assertEquals(Event.CalendarAck("c"), ActionMapper.map("cal:k1", a(ActionKind.GotIt), "c"))
        assertEquals(Event.CalendarSnooze("c"), ActionMapper.map("cal:k1", a(ActionKind.Snooze, 5), "c"))
        assertEquals(Event.SessionPromptAnswer("office", SessionAnswer.NotWorking, "c"), ActionMapper.map("session:office", a(ActionKind.NotWorking), "c"))
        assertEquals(Event.AlarmControl("morning-alarm", AlarmAction.Stop), ActionMapper.map("alarm:morning-alarm", a(ActionKind.Stop), null))
        assertEquals(Event.AlarmControl("morning-alarm", AlarmAction.Snooze), ActionMapper.map("alarm:morning-alarm", a(ActionKind.Snooze, 9), null))
        assertTrue(ActionMapper.startsRoutinePlayback("routine-prompt:r", ActionKind.Start))
    }

    @Test
    fun atMostThreeActions() {
        val routine = listOf(ActionKind.Done to null, ActionKind.Skip to null, ActionKind.Extend to 1, ActionKind.Extend to 5, ActionKind.Pause to null)
        val picked = ActionMapper.pickThree("routine:r", routine, { it.first }, { it.second })
        assertEquals(listOf(ActionKind.Done to null, ActionKind.Pause to null, ActionKind.Extend to 1), picked)
        val timed = routine.drop(1)
        assertEquals(listOf(ActionKind.Skip to null, ActionKind.Pause to null, ActionKind.Extend to 1), ActionMapper.pickThree("routine:r", timed, { it.first }, { it.second }))
        val posture = listOf(ActionKind.Switched to null, ActionKind.Snooze to 5, ActionKind.Extend to 5)
        assertEquals(posture, ActionMapper.pickThree("posture", posture, { it.first }, { it.second }))
    }
}
