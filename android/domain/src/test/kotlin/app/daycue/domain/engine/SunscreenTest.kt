package app.daycue.domain.engine

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.config.LeaveConditionPolicy
import app.daycue.domain.config.ReentryPolicy
import app.daycue.domain.config.FirstReminderPolicy
import app.daycue.domain.testing.Scenario
import app.daycue.domain.testing.sunscreenKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunscreenTest {

    private val S = Defaults.SUNSCREEN
    private fun scenario(start: String = "06:00") = Scenario(start = start).apply { enable(S) }
    private fun Scenario.cues() = deliveredFor(sunscreenKey())
    private fun Scenario.walkUntil(from: String, to: String) {
        // on-foot readings every 5 minutes
        advanceTo(from)
        var t = t(from)
        while (!t.isAfter(t(to))) { advanceTo(t); walking(); t = t.plusSeconds(300) }
    }

    @Test
    fun `acceptance 1 - SUN-2 acknowledged application schedules the next reminder from the tap`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        assertEquals(1, s.cues().size, "SUN-8 first reminder on outdoor start (not covered)")
        assertEquals(s.t("10:00"), s.cues().single().deliveredAt)
        s.at("10:07", Event.HabitAck(S, s.cues().single().id))
        assertEquals(s.t("10:07"), s.state.intervals[S]!!.lastAckAt)
        assertEquals(s.t("12:07"), s.state.intervals[S]!!.dueAt)
        assertTrue(sunscreenKey() in s.dismissedKeys(), "ack removes the notification")
        s.advanceTo("12:06")
        assertEquals(1, s.cues().size)
        s.advanceTo("12:08")
        assertEquals(2, s.cues().size)
        assertEquals(s.t("12:07"), s.cues().last().deliveredAt)
    }

    @Test
    fun `SUN-3 applied at home then outside - no first cue while covered, cue at dueAt`() {
        val s = scenario()
        s.at("09:00", Event.HabitAck(S)) // Applied accepted anywhere (SUN-2)
        s.at("09:30", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        assertEquals(0, s.cues().size)
        s.advanceTo("10:59")
        assertEquals(0, s.cues().size)
        s.advanceTo("11:01")
        assertEquals(listOf(s.t("11:00")), s.cues().map { it.deliveredAt })
    }

    @Test
    fun `acceptance 2 - leaving and re-entering outdoors keeps lastAppliedAt and never duplicates timers (GEN-2, SUN-6, SUN-7)`() {
        val s = scenario()
        s.at("10:00", Event.HabitAck(S))
        s.outdoors()
        repeat(3) { i ->
            s.advanceTo("10:${10 + i * 10}")
            s.indoors()
            s.advance(5)
            s.outdoors()
        }
        assertEquals(s.t("10:00"), s.state.intervals[S]!!.lastAckAt, "SUN-6")
        assertEquals(s.t("12:00"), s.state.intervals[S]!!.dueAt)
        s.advanceTo("12:01")
        assertEquals(1, s.cues().size, "exactly one cue, no duplicates from re-entries")
        s.advanceTo("12:19")
        assertEquals(1, s.cues().size)
        s.advanceTo("12:21")
        assertEquals(2, s.cues().size, "GEN-4: one repeat at +20 min re-uses the same cue id")
        assertEquals(s.cues()[0].id, s.cues()[1].id)
        assertEquals(s.cues()[0].notificationKey, s.cues()[1].notificationKey)
    }

    @Test
    fun `edge - outside at 10 not covered, on-foot detection confirms after dwell (~10-05)`() {
        val s = scenario()
        s.walkUntil("10:00", "10:30")
        val first = s.cues().first()
        assertEquals(s.t("10:05"), first.deliveredAt, "CTX-4/CTX-6: Outdoor confirmed after 5 min sustained on foot")
        assertEquals("Outdoor", s.state.context.env.value.name)
    }

    @Test
    fun `edge - ignored cue repeats once then unanswered, next cue at last delivery plus interval (GEN-4, GEN-5)`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.advanceTo("10:44")
        assertEquals(listOf(s.t("10:00"), s.t("10:20")), s.cues().map { it.deliveredAt })
        s.advanceTo("10:41")
        assertTrue(s.history(HistoryKind.Unanswered).isNotEmpty())
        assertNull(s.state.intervals[S]!!.lastAckAt, "SUN-5 unanswered never changes lastAppliedAt")
        assertEquals(s.t("12:20"), s.state.intervals[S]!!.dueAt, "RollForward: last delivery (10:20) + 2 h")
        s.advanceTo("12:21")
        assertEquals(3, s.cues().size)
    }

    @Test
    fun `GEN-1 dismissal changes nothing - repeats continue`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        val id = s.cues().single().id
        s.at("10:02", Event.CueDismissed(id))
        assertEquals(id, s.state.intervals[S]!!.cue!!.cueId)
        assertNull(s.state.intervals[S]!!.lastAckAt)
        s.advanceTo("10:21")
        assertEquals(2, s.cues().size)
    }

    @Test
    fun `LeaveOutdoorPolicy RetractAndHold then RemindOnReentry with grace 0`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.at("10:05", Event.OverrideEnvironment(Environment.Indoor, OverrideDuration.UntilChanged))
        assertTrue(sunscreenKey() in s.dismissedKeys(), "retracted")
        assertTrue(s.state.intervals[S]!!.heldDue)
        s.advanceTo("11:00")
        assertEquals(1, s.cues().size, "no cue while indoors")
        s.at("11:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        assertEquals(2, s.cues().size)
        assertEquals(s.t("11:00"), s.cues().last().deliveredAt)
    }

    @Test
    fun `OutdoorReentryPolicy WaitNextInterval and KeepVisible`() {
        val s = scenario()
        s.updateHabit(S) { it.copy(reentry = ReentryPolicy.WaitNextInterval, onLeaveCondition = LeaveConditionPolicy.KeepVisible) }
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.at("10:05", Event.OverrideEnvironment(Environment.Indoor, OverrideDuration.UntilChanged))
        assertTrue(sunscreenKey() !in s.dismissedKeys(), "KeepVisible leaves the notification")
        s.advanceTo("10:30")
        assertEquals(1, s.cues().size, "KeepVisible stops repeats")
        s.at("11:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.advanceTo("12:59")
        assertEquals(1, s.cues().size)
        s.advanceTo("13:01")
        assertEquals(s.t("13:00"), s.cues().last().deliveredAt, "WaitNextInterval: now + interval")
    }

    @Test
    fun `FirstReminderPolicy AfterDelay and OnlyAfterApplied`() {
        val a = scenario()
        a.updateHabit(S) { it.copy(firstReminder = FirstReminderPolicy.AfterDelay(15)) }
        a.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        a.advanceTo("10:16")
        assertEquals(listOf(a.t("10:15")), a.cues().map { it.deliveredAt })

        val b = scenario()
        b.updateHabit(S) { it.copy(firstReminder = FirstReminderPolicy.OnlyAfterApplied) }
        b.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        b.advanceTo("13:00")
        assertEquals(0, b.cues().size, "never a first cue")
        b.send(Event.HabitAck(S))
        b.advanceTo("15:01")
        assertEquals(listOf(b.t("15:00")), b.cues().map { it.deliveredAt })
    }

    @Test
    fun `SUN-9 snooze ending while not outdoors becomes a held due, re-entry cues`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.at("10:01", Event.HabitSnooze(S, s.cues().single().id))
        s.at("10:05", Event.OverrideEnvironment(Environment.Indoor, OverrideDuration.UntilChanged))
        s.advanceTo("10:30")
        assertEquals(1, s.cues().size)
        s.at("10:30", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        assertEquals(2, s.cues().size)
    }

    @Test
    fun `GEN-3 snooze re-delivers at tap plus snooze minutes`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        s.at("10:02", Event.HabitSnooze(S, s.cues().single().id))
        s.advanceTo("10:18")
        assertEquals(listOf(s.t("10:00"), s.t("10:17")), s.cues().map { it.deliveredAt })
    }

    @Test
    fun `GEN-6 outdoors before active hours - delivered once at 07-00`() {
        val s = scenario(start = "05:00")
        s.at("06:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        assertEquals(0, s.cues().size)
        s.advanceTo("07:01")
        assertEquals(listOf(s.t("07:00")), s.cues().map { it.deliveredAt })
    }

    @Test
    fun `edge - Environment Unknown while due is treated as not Outdoor (hold)`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.For(30)))
        s.send(Event.HabitAck(S))
        s.advanceTo("13:00")
        assertEquals(1, s.cues().size, "after the override expires Environment is Unknown -> no cue at 12:00")
        assertTrue(s.state.intervals[S]!!.heldDue)
    }

    @Test
    fun `edge - applied twice within minutes, latest wins`() {
        val s = scenario()
        s.at("10:00", Event.HabitAck(S))
        s.at("10:03", Event.HabitAck(S))
        assertEquals(s.t("12:03"), s.state.intervals[S]!!.dueAt)
        assertEquals(2, s.history(HistoryKind.Acked).size, "history keeps both")
    }

    @Test
    fun `SUN-10 pause until condition ends - resumes after the outdoor stretch ends, never replays`() {
        val s = scenario()
        s.at("10:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        val pauseOps = s.send(Event.Pause(PauseTarget.Habit(S), PauseChoice.UntilConditionEnds)).filterIsInstance<Effect.ApplyConfigOps>().single()
        s.apply(*pauseOps.ops.toTypedArray())
        s.advanceTo("12:30")
        assertEquals(1, s.cues().size, "paused: no cues")
        s.at("12:30", Event.OverrideEnvironment(Environment.Indoor, OverrideDuration.UntilChanged))
        s.at("13:00", Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.UntilChanged))
        assertEquals(2, s.cues().size, "pause ended with the stretch; one cue on re-entry (GEN-6), not one per missed occurrence")
    }

    @Test
    fun `edge - 3-minute doorway pass during an outdoor stretch changes nothing`() {
        val s = scenario()
        s.walkUntil("10:00", "10:06")
        s.send(Event.HabitAck(S))
        s.walkUntil("10:10", "10:55")
        s.at("11:00", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.GYM, app.daycue.domain.signal.GeofenceTransitionKind.Enter, s.t("11:00"))))
        s.at("11:02", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.GYM, app.daycue.domain.signal.GeofenceTransitionKind.Exit, s.t("11:02"))))
        s.walkUntil("11:05", "12:10")
        assertEquals("Outdoor", s.state.context.env.value.name)
        assertEquals(2, s.cues().size, "10:05 cue and 12:05 due cue only")
        assertEquals(s.t("12:05"), s.cues().last().deliveredAt)
    }

    @Test
    fun `edge - due while in a shop (saved indoor place) is retracted and held, cue again after leaving`() {
        val s = scenario()
        s.walkUntil("10:00", "10:06")
        s.at("10:07", Event.HabitAck(S))
        s.walkUntil("10:10", "11:55")
        s.at("12:00", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.GYM, app.daycue.domain.signal.GeofenceTransitionKind.Enter, s.t("12:00"))))
        s.advanceTo("12:20")
        // Place confirmed 12:03 -> raw Indoor; Outdoor holds until 12:13 (CTX-5), so the 12:07 cue is delivered then retracted.
        assertEquals("Indoor", s.state.context.env.value.name)
        assertTrue(sunscreenKey() in s.dismissedKeys())
        val before = s.cues().size
        s.at("12:30", Event.SignalObserved(app.daycue.domain.signal.GeofenceTransition(Defaults.GYM, app.daycue.domain.signal.GeofenceTransitionKind.Exit, s.t("12:30"))))
        s.walkUntil("12:30", "12:45")
        assertEquals(before + 1, s.cues().size)
        assertEquals(s.t("12:40"), s.cues().last().deliveredAt, "PRODUCT §3.4: exit dwell (5) + outdoor enter dwell (5), grace 0")
    }
}
