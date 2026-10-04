package app.daycue.actions

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
import java.time.LocalDate
import java.time.LocalTime

/**
 * Notification taps -> engine [Event]s (pure, JVM-tested). Inputs are what the notification carried:
 * the cue's item key, the tapped [ActionKind] (or [Tap.Dismissed] for the delete intent), the cue id
 * and the action's minutes. Item key formats are the domain's (`habit:<id>`, `med:<id>|<date>|<time>`,
 * `posture`, `routine:<id>`, `routine-prompt:<id>`, `cal:<key>`, `session:<placeId>`, `alarm:<id>`).
 *
 * Dismissal is never an acknowledgement (GEN-1, MED-2, acceptance scenario 4): it maps only to
 * `CueDismissed`, which the engine logs and uses to forget the visible notification.
 */
object ActionMapper {

    /** Minutes for the notification "Pause" button (the full option list is in-app, SUN-10/HYD-4). */
    const val NOTIFICATION_PAUSE_MIN = 60

    sealed interface Tap {
        data class Action(val kind: ActionKind, val minutes: Int?) : Tap
        data object Dismissed : Tap
    }

    fun map(itemKey: String, tap: Tap, cueId: String?): Event? {
        if (tap is Tap.Dismissed) return cueId?.let { Event.CueDismissed(it) }
        val a = tap as Tap.Action
        val prefix = itemKey.substringBefore(':', itemKey)
        val id = itemKey.substringAfter(':', "")
        return when (prefix) {
            "habit" -> when (a.kind) {
                ActionKind.Applied, ActionKind.Drank, ActionKind.Done -> Event.HabitAck(id, cueId)
                ActionKind.GotIt -> Event.BottleAck(id, notNeeded = false, cueId = cueId)
                ActionKind.NotNeeded -> Event.BottleAck(id, notNeeded = true, cueId = cueId)
                ActionKind.Snooze -> Event.HabitSnooze(id, cueId)
                ActionKind.Pause -> Event.Pause(PauseTarget.Habit(id), PauseChoice.For(NOTIFICATION_PAUSE_MIN), cueId)
                else -> null
            }
            "med" -> slot(id)?.let { slot ->
                when (a.kind) {
                    ActionKind.Taken -> Event.MedicationTaken(slot, cueId)
                    ActionKind.Snooze -> Event.MedicationSnooze(slot, cueId)
                    else -> null
                }
            }
            "posture" -> when (a.kind) {
                ActionKind.Switched -> Event.PostureControl(PostureAction.Switched, cueId)
                ActionKind.Snooze -> Event.PostureControl(PostureAction.Snooze, cueId)
                ActionKind.Skip -> Event.PostureControl(PostureAction.Skip, cueId)
                ActionKind.Extend -> Event.PostureControl(
                    when (a.minutes) { 10 -> PostureAction.Extend10; 15 -> PostureAction.Extend15; else -> PostureAction.Extend5 }, cueId)
                ActionKind.Pause -> Event.PostureControl(PostureAction.Pause, cueId)
                else -> null
            }
            "routine" -> when (a.kind) {
                ActionKind.Done -> Event.RoutineControl(RoutineAction.Done, cueId)
                ActionKind.Skip -> Event.RoutineControl(RoutineAction.SkipStep, cueId)
                ActionKind.Extend -> Event.RoutineControl(RoutineAction.Extend(a.minutes ?: 1), cueId)
                ActionKind.Pause -> Event.RoutineControl(RoutineAction.Pause, cueId)
                ActionKind.Resume -> Event.RoutineControl(RoutineAction.Recover(RecoveryChoice.Resume), cueId)
                ActionKind.Restart -> Event.RoutineControl(RoutineAction.Recover(RecoveryChoice.Restart), cueId)
                ActionKind.Cancel -> Event.RoutineControl(RoutineAction.Recover(RecoveryChoice.Cancel), cueId)
                else -> null
            }
            "routine-prompt" -> when (a.kind) {
                ActionKind.Start -> Event.RoutineControl(RoutineAction.PromptStart(id), cueId)
                ActionKind.SkipToday -> Event.RoutineControl(RoutineAction.PromptSkipToday(id), cueId)
                else -> null
            }
            "cal" -> cueId?.let {
                when (a.kind) {
                    ActionKind.GotIt -> Event.CalendarAck(it)
                    ActionKind.Snooze -> Event.CalendarSnooze(it)
                    else -> null
                }
            }
            "session" -> when (a.kind) {
                ActionKind.Start -> Event.SessionPromptAnswer(id, SessionAnswer.Start, cueId)
                ActionKind.NotNow -> Event.SessionPromptAnswer(id, SessionAnswer.NotNow, cueId)
                ActionKind.NotWorking -> Event.SessionPromptAnswer(id, SessionAnswer.NotWorking, cueId)
                else -> null
            }
            "alarm" -> when (a.kind) {
                ActionKind.Stop -> Event.AlarmControl(id, AlarmAction.Stop)
                ActionKind.Snooze -> Event.AlarmControl(id, AlarmAction.Snooze)
                else -> null
            }
            else -> null
        }
    }

    /** True when the action must start the routine foreground service (a user action, RTN-9 / Android 17 audio). */
    fun startsRoutinePlayback(itemKey: String, kind: ActionKind): Boolean =
        itemKey.startsWith("routine-prompt:") && kind == ActionKind.Start

    /** `med:<medicationId>|<yyyy-mm-dd>|<HH:mm[:ss]>` -> [SlotRef]. */
    fun slot(key: String): SlotRef? {
        val parts = key.split('|')
        if (parts.size != 3) return null
        return runCatching { SlotRef(parts[0], LocalDate.parse(parts[1]), LocalTime.parse(parts[2])) }.getOrNull()
    }

    /**
     * Android shows at most 3 actions. Picks them in UX §4 order per type; the rest stay in-app
     * (e.g. routine "Done · Pause · +1 min"; posture "Switched · Snooze · +5 min", Skip in-app).
     */
    fun <T> pickThree(itemKey: String, actions: List<T>, kindOf: (T) -> ActionKind, minutesOf: (T) -> Int?): List<T> {
        if (actions.size <= 3) return actions
        return if (itemKey.startsWith("routine:")) {
            val done = actions.firstOrNull { kindOf(it) == ActionKind.Done } ?: actions.firstOrNull { kindOf(it) == ActionKind.Skip }
            val pause = actions.firstOrNull { kindOf(it) == ActionKind.Pause }
            val ext = actions.firstOrNull { kindOf(it) == ActionKind.Extend && minutesOf(it) == 1 } ?: actions.firstOrNull { kindOf(it) == ActionKind.Extend }
            listOfNotNull(done, pause, ext).ifEmpty { actions.take(3) }
        } else actions.take(3)
    }
}
