package app.daycue.domain.engine

import app.daycue.domain.config.PostureInterruptionPolicy
import app.daycue.domain.config.PostureMeetingPolicy
import app.daycue.domain.config.PostureTimerStartPolicy
import app.daycue.domain.config.SessionKind
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.testing.Scenario
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostureTest {

    private fun scenario(f: (app.daycue.domain.config.PostureCycleConfig) -> app.daycue.domain.config.PostureCycleConfig = { it }) = Scenario().apply {
        apply(ConfigOp.SetPostureCycle(f(config.postureCycle.copy(enabled = true))))
        at("09:00", Event.StartSession(SessionKind.Working))
    }
    private fun Scenario.cues() = deliveredFor("posture")
    private val Scenario.p get() = state.posture

    @Test
    fun `POS-1 cycle starts with the first mode when a session is active`() {
        val s = scenario()
        assertEquals(PosturePhase.Running, s.p.phase)
        assertEquals("sitting", s.p.modeId)
        assertEquals(s.t("09:30"), s.p.modeEndsAt)
    }

    @Test
    fun `POS-3 AtConfirmation - cue names the next mode, repeats twice, then waits silently (POS-8), Switched starts the timer`() {
        val s = scenario()
        s.advanceTo("09:50")
        assertEquals(listOf(s.t("09:30"), s.t("09:35"), s.t("09:40")), s.cues().map { it.deliveredAt })
        assertEquals("cue.posture.switch.title", s.cues().first().title.key)
        assertEquals(PosturePhase.SwitchPending, s.p.phase)
        assertEquals("standing", s.p.pendingModeId)
        assertEquals(listOf(ActionKind.Switched, ActionKind.Snooze, ActionKind.Extend), s.cues().first().actions.map { it.kind }, "product decision: Switched / Snooze / +5 min")
        s.send(Event.PostureControl(PostureAction.Switched, s.cues().last().id))
        assertEquals("standing", s.p.modeId)
        assertEquals(s.t("10:20"), s.p.modeEndsAt)
    }

    @Test
    fun `acceptance 3 - pause and resume at the correct position (short interruption continues remaining)`() {
        val s = scenario()
        s.at("09:10", Event.PostureControl(PostureAction.Pause))
        assertEquals(PosturePhase.Paused, s.p.phase)
        assertEquals(Duration.ofMinutes(20).toMillis(), s.p.remainingMs)
        s.processRestart() // survives process death (POS-1)
        s.at("09:22", Event.PostureControl(PostureAction.Resume))
        assertEquals(PosturePhase.Running, s.p.phase)
        assertEquals("sitting", s.p.modeId)
        assertEquals(s.t("09:42"), s.p.modeEndsAt)
        s.advanceTo("09:43")
        assertEquals(s.t("09:42"), s.cues().single().deliveredAt)
    }

    @Test
    fun `POS-6 long interruption follows longInterruption policy`() {
        val reset = scenario()
        reset.advanceTo("09:40")
        reset.send(Event.PostureControl(PostureAction.Switched, reset.cues().last().id)) // standing from 09:40
        reset.at("09:50", Event.PostureControl(PostureAction.Pause))
        reset.at("10:30", Event.PostureControl(PostureAction.Resume))
        assertEquals("sitting", reset.p.modeId, "ResetToFirst")
        assertEquals(reset.t("11:00"), reset.p.modeEndsAt)

        val cont = scenario { it.copy(longInterruption = PostureInterruptionPolicy.ContinueRemaining) }
        cont.at("09:10", Event.PostureControl(PostureAction.Pause))
        cont.at("10:10", Event.PostureControl(PostureAction.Resume))
        assertEquals(cont.t("10:30"), cont.p.modeEndsAt)
    }

    @Test
    fun `POS-2 freezes when the session pauses on idle (WRK-3) and resumes position`() {
        val s = Scenario()
        s.apply(ConfigOp.SetPostureCycle(s.config.postureCycle.copy(enabled = true)))
        s.at("09:00", Event.StartSession(SessionKind.Working))
        s.at("09:05", Event.SignalObserved(app.daycue.domain.signal.CompanionActivity(app.daycue.domain.signal.CompanionState.Locked, s.t("09:05"))))
        s.advanceTo("09:08")
        assertEquals(PosturePhase.Frozen, s.p.phase, "locked >= 2 min pauses the manual session -> posture frozen")
        assertEquals(Duration.ofMinutes(23).toMillis(), s.p.remainingMs)
        s.at("09:15", Event.SignalObserved(app.daycue.domain.signal.CompanionActivity(app.daycue.domain.signal.CompanionState.Active, s.t("09:15"))))
        assertEquals(PosturePhase.Running, s.p.phase)
        assertEquals(s.t("09:38"), s.p.modeEndsAt)
    }

    @Test
    fun `extend +5 from the notification, snooze, and stale actions are ignored`() {
        val s = scenario()
        s.advanceTo("09:31")
        val id = s.cues().single().id
        s.send(Event.PostureControl(PostureAction.Extend5, id))
        assertEquals(PosturePhase.Running, s.p.phase)
        assertEquals(s.t("09:36"), s.p.modeEndsAt)
        s.send(Event.PostureControl(PostureAction.Extend5, id)) // duplicate broadcast
        assertEquals(s.t("09:36"), s.p.modeEndsAt, "idempotent re-delivery")
        s.advanceTo("09:37")
        val id2 = s.cues().last().id
        s.send(Event.PostureControl(PostureAction.Snooze, id2))
        assertEquals(s.t("09:42"), s.p.modeEndsAt)
        assertNull(s.p.cue)
    }

    @Test
    fun `Skip (in-app) goes to the mode after next, POS-5 disabled modes are skipped`() {
        val s = scenario()
        s.advanceTo("09:30")
        s.send(Event.PostureControl(PostureAction.Skip))
        assertEquals("walking", s.p.modeId)
        s.apply(ConfigOp.SetPostureModes(s.config.postureCycle.modes.map { if (it.id == "sitting") it.copy(enabled = false) else it }))
        s.advanceTo("10:01")
        assertEquals("standing", s.p.pendingModeId, "walking -> (sitting disabled) -> standing")
    }

    @Test
    fun `POS-5 with one enabled mode the cue is keep going`() {
        val s = scenario { pc -> pc.copy(modes = pc.modes.mapIndexed { i, m -> m.copy(enabled = i == 0) }) }
        s.advanceTo("09:31")
        assertEquals("cue.posture.keep_going.title", s.cues().single().title.key)
    }

    @Test
    fun `AtCue - the next timer starts at the cue instant`() {
        val s = scenario { it.copy(timerStart = PostureTimerStartPolicy.AtCue) }
        s.advanceTo("09:31")
        assertEquals("standing", s.p.modeId)
        assertEquals(s.t("10:00"), s.p.modeEndsAt)
        s.advanceTo("10:01")
        assertEquals(2, s.cues().size)
    }

    @Test
    fun `PostureMeetingPolicy DeferCue - timer runs, cue delivered when the meeting ends`() {
        val s = scenario { it.copy(duringMeeting = PostureMeetingPolicy.DeferCue) }
        s.send(Event.CalendarSynced(listOf(CalendarEvent("m", calendarId = "c", start = s.t("09:20"), end = s.t("09:45"), otherAttendees = 1)), s.now))
        s.advanceTo("09:44")
        assertTrue(s.cues().isEmpty())
        s.advanceTo("09:46")
        assertEquals(listOf(s.t("09:45")), s.cues().map { it.deliveredAt })
    }

    @Test
    fun `session end freezes the cycle with remaining kept`() {
        val s = scenario()
        s.at("09:10", Event.EndSession)
        assertEquals(PosturePhase.Frozen, s.p.phase)
        s.advanceTo("12:00")
        assertTrue(s.cues().isEmpty())
    }
}
