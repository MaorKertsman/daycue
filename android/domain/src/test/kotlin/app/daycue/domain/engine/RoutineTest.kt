package app.daycue.domain.engine

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Routine
import app.daycue.domain.config.RoutineRecovery
import app.daycue.domain.config.RoutineRecoveryPolicy
import app.daycue.domain.config.RoutineStep
import app.daycue.domain.config.RoutineTimingPolicy
import app.daycue.domain.config.RoutineTrigger
import app.daycue.domain.config.StepCompletion
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.hydrationKey
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineTest {
    private val R = Defaults.MORNING_ROUTINE
    private fun Scenario.steps() = deliveredFor("routine:$R").filter { it.title.key == "cue.routine.step.title" }.map { it.title.args["step"] to it.deliveredAt }
    private val Scenario.run get() = state.routine.run

    @Test
    fun `acceptance 5 - FollowActualCompletion timed steps chain, explicit step waits for Done, survives process recreation`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.advanceTo("07:06")
        s.processRestart() // process death mid-flow
        s.advanceTo("07:15")
        assertEquals(listOf("Shower" to s.t("07:00"), "Face cleanser" to s.t("07:05"), "Brush teeth" to s.t("07:07"), "Get dressed" to s.t("07:09")), s.steps())
        assertEquals("get-dressed", s.run!!.routine.steps[s.run!!.stepIndex].id)
        assertTrue(s.deliveredFor("routine:$R").any { it.title.key == "cue.routine.nudge.title" && it.deliveredAt == s.t("07:11") }, "explicit duration is a nudge")
        s.send(Event.RoutineControl(RoutineAction.Done))
        assertNull(s.run)
        assertTrue(s.history(HistoryKind.RoutineCompleted).isNotEmpty())
    }

    @Test
    fun `RTN-2 a timed step ending is the next step's start cue - one cue, not two`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.advanceTo("07:05")
        assertEquals(2, s.deliveredFor("routine:$R").size)
    }

    private fun scheduled() = Routine("sched", "Sched", enabled = true, timing = RoutineTimingPolicy.FollowSchedule, steps = listOf(
        RoutineStep("a", "Alpha", durationSec = 120, completion = StepCompletion.Explicit),
        RoutineStep("b", "Bravo", durationSec = 120),
        RoutineStep("c", "Charlie", durationSec = 120),
    ))

    @Test
    fun `FollowSchedule - an overrun shortens the next step and the routine ends on time`() {
        val s = Scenario()
        s.apply(ConfigOp.UpsertRoutine(scheduled()))
        s.at("07:00", Event.RoutineControl(RoutineAction.Start("sched")))
        s.at("07:03", Event.RoutineControl(RoutineAction.Done))
        assertEquals(s.t("07:04"), s.run!!.stepEndsAt, "Bravo shortened to its planned end")
        s.advanceTo("07:06")
        assertNull(s.run, "ends on time at 07:06")
    }

    @Test
    fun `FollowSchedule - a step whose whole window passed is skipped_by_schedule without a cue`() {
        val s = Scenario()
        s.apply(ConfigOp.UpsertRoutine(scheduled()))
        s.at("07:00", Event.RoutineControl(RoutineAction.Start("sched")))
        s.at("07:05", Event.RoutineControl(RoutineAction.Done))
        val names = s.deliveredFor("routine:sched").map { it.title.args["step"] }
        assertEquals(listOf("Alpha", "Charlie"), names.distinct())
        assertTrue(s.history(HistoryKind.RoutineSkippedBySchedule).any { it.detail["step"] == "b" })
    }

    @Test
    fun `RTN-5 short interruption jumps to the correct step with one cue, no intermediate cues`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.advanceTo("07:03")
        s.clock.advance(Duration.ofMinutes(5)) // process dead 07:03-07:08, no ticks
        s.processRestart()
        assertEquals(listOf("Shower" to s.t("07:00"), "Brush teeth" to s.t("07:08")), s.steps())
        assertEquals(s.t("07:09"), s.run!!.stepEndsAt, "continues with real elapsed time (brush started 07:07)")
    }

    @Test
    fun `RTN-6 long interruption (reboot) - AskToResume posts one prompt, Resume restarts the step with full duration`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.advanceTo("07:02")
        s.reboot(offMinutes = 20)
        assertEquals(RunStatus.AwaitingRecovery, s.run!!.status)
        val prompts = s.deliveredFor("routine:$R").filter { it.title.key == "cue.routine.recover.title" }
        assertEquals(1, prompts.size)
        assertEquals(listOf("Shower" to s.t("07:00")), s.steps(), "never rapid-fire catch-up")
        s.send(Event.RoutineControl(RoutineAction.Recover(RecoveryChoice.Resume), prompts.single().id))
        assertEquals(s.now.plusSeconds(300), s.run!!.stepEndsAt)
    }

    @Test
    fun `RTN-6 Cancel recovery policy ends the run`() {
        val s = Scenario()
        s.apply(ConfigOp.UpsertRoutine(s.config.routine(R)!!.copy(recovery = RoutineRecovery(RoutineRecoveryPolicy.Cancel, 10))))
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.reboot(offMinutes = 15)
        assertNull(s.run)
    }

    @Test
    fun `pause and resume keep remaining time, RTN-3 a second start is refused`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.at("07:02", Event.RoutineControl(RoutineAction.Pause))
        s.at("07:06", Event.RoutineControl(RoutineAction.Resume))
        assertEquals(s.t("07:09"), s.run!!.stepEndsAt)
        s.send(Event.RoutineControl(RoutineAction.Start(R)))
        assertEquals(s.t("07:09"), s.run!!.stepEndsAt)
        assertTrue(s.history(HistoryKind.Skipped).any { it.rule == "RTN-3" })
    }

    @Test
    fun `RTN-7 editing the routine during a run affects the next run only`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R)))
        s.apply(ConfigOp.DeleteRoutineStep(R, "face-cleanser"))
        s.advanceTo("07:06")
        assertEquals("face-cleanser", s.run!!.routine.steps[s.run!!.stepIndex].id)
    }

    @Test
    fun `RTN-8 fast test mode - 10 s steps, labelled test, no history counts`() {
        val s = Scenario()
        s.at("07:00", Event.RoutineControl(RoutineAction.Start(R, test = RoutineTestMode.Fast)))
        s.advanceTo(s.t("07:00").plusSeconds(35))
        val cues = s.deliveredFor("routine:$R")
        assertEquals(4, cues.size)
        assertTrue(cues.all { it.isTest && it.title.key == "cue.test.title" })
        assertTrue(s.history().filter { it.itemKey == "routine:$R" }.all { it.test })
    }

    @Test
    fun `RTN-9 scheduled prompt - no answer within 30 min means skipped today`() {
        val s = Scenario()
        s.apply(ConfigOp.UpsertRoutine(s.config.routine(R)!!.copy(enabled = true, trigger = RoutineTrigger.Schedule(LocalTime.of(7, 0)))))
        s.advanceTo("07:01")
        assertEquals(1, s.deliveredFor("routine-prompt:$R").size)
        s.advanceTo("08:00")
        assertTrue(s.history(HistoryKind.Skipped).any { it.rule == "RTN-9" })
        assertEquals(1, s.deliveredFor("routine-prompt:$R").size)
        s.advanceTo(s.t("07:01", s.date.plusDays(1)))
        assertEquals(2, s.deliveredFor("routine-prompt:$R").size)
    }

    @Test
    fun `RTN-9 prompt Start begins the run`() {
        val s = Scenario()
        s.apply(ConfigOp.UpsertRoutine(s.config.routine(R)!!.copy(enabled = true, trigger = RoutineTrigger.Schedule(LocalTime.of(7, 0)))))
        s.advanceTo("07:01")
        s.send(Event.RoutineControl(RoutineAction.PromptStart(R)))
        assertEquals(RunStatus.Running, s.run!!.status)
    }

    @Test
    fun `RTN-10 hydration is deferred while a routine runs and delivered after it ends`() {
        val s = Scenario()
        s.enable(Defaults.HYDRATION)
        s.at("09:58", Event.RoutineControl(RoutineAction.Start(R)))
        s.advanceTo("10:08")
        assertTrue(s.deliveredFor(hydrationKey()).isEmpty())
        s.send(Event.RoutineControl(RoutineAction.Cancel))
        assertEquals(1, s.deliveredFor(hydrationKey()).size)
    }
}
