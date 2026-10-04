@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, LocalTimeSerializer::class)

package app.daycue.domain.engine

import app.daycue.domain.config.Environment
import app.daycue.domain.config.EventAvailability
import app.daycue.domain.config.SessionKind
import app.daycue.domain.signal.Signal
import app.daycue.domain.time.InstantSerializer
import app.daycue.domain.time.LocalDateSerializer
import app.daycue.domain.time.LocalTimeSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * Engine inputs. The tap / processing instant is always `clock.now()`.
 *
 * Action events carry an optional `cueId`: when set (the action came from a notification button) and it
 * no longer matches the item's current cue, the action is ignored as stale. This makes re-delivery of the
 * same broadcast idempotent and prevents old notifications from acting on newer state. In-app controls pass
 * `cueId = null`.
 */
@Serializable
sealed interface Event {

    // ---- Time / lifecycle (GEN-7) ----
    /** The single next-wake alarm fired (or any periodic re-evaluation). */
    @Serializable @SerialName("tick") data object Tick : Event
    /** Device boot **or** process start; a real reboot is detected from `Clock.elapsedRealtime()` going backwards. */
    @Serializable @SerialName("boot") data object BootCompleted : Event
    @Serializable @SerialName("timeChanged") data object TimeChanged : Event
    @Serializable @SerialName("timezoneChanged") data object TimezoneChanged : Event
    /** Config document changed (any applied ConfigOp set). */
    @Serializable @SerialName("configChanged") data object ConfigChanged : Event

    // ---- Context (PRODUCT §1) ----
    @Serializable @SerialName("signal") data class SignalObserved(val signal: Signal) : Event
    @Serializable @SerialName("overrideEnvironment") data class OverrideEnvironment(val value: Environment, val duration: OverrideDuration) : Event
    @Serializable @SerialName("clearEnvironmentOverride") data object ClearEnvironmentOverride : Event
    @Serializable @SerialName("startSession") data class StartSession(val kind: SessionKind, val duration: OverrideDuration = OverrideDuration.UntilChanged) : Event
    @Serializable @SerialName("endSession") data object EndSession : Event
    /** Answer to the WRK-2 suggestion / undo notification. */
    @Serializable @SerialName("sessionPrompt") data class SessionPromptAnswer(val placeId: String, val answer: SessionAnswer, val cueId: String? = null) : Event
    @Serializable @SerialName("pauseDetection") data class PauseAutoDetection(val duration: OverrideDuration) : Event
    @Serializable @SerialName("resumeDetection") data object ResumeAutoDetection : Event
    /** BTL-1. */
    @Serializable @SerialName("leavingNow") data object LeavingNow : Event

    // ---- Interval habits (§2–§4) ----
    @Serializable @SerialName("habitAck") data class HabitAck(val habitId: String, val cueId: String? = null) : Event
    @Serializable @SerialName("habitSnooze") data class HabitSnooze(val habitId: String, val cueId: String? = null) : Event

    /** Pause request from a notification / UI. The engine answers with `Effect.ApplyConfigOps` (config is the source of truth). */
    @Serializable @SerialName("pause") data class Pause(val target: PauseTarget, val choice: PauseChoice, val cueId: String? = null) : Event
    @Serializable @SerialName("resume") data class Resume(val target: PauseTarget) : Event

    // ---- Water bottle (§5) ----
    @Serializable @SerialName("bottleAck") data class BottleAck(val habitId: String, val notNeeded: Boolean = false, val cueId: String? = null) : Event

    // ---- Medication (§7) ----
    @Serializable @SerialName("medTaken") data class MedicationTaken(val slot: SlotRef, val cueId: String? = null) : Event
    @Serializable @SerialName("medSnooze") data class MedicationSnooze(val slot: SlotRef, val cueId: String? = null) : Event
    /** Only from the dose detail screen (PRODUCT §7 actions). */
    @Serializable @SerialName("medSkip") data class MedicationSkip(val slot: SlotRef) : Event

    // ---- Posture (§6) ----
    @Serializable @SerialName("posture") data class PostureControl(val action: PostureAction, val cueId: String? = null) : Event

    // ---- Routines (§10) ----
    @Serializable @SerialName("routine") data class RoutineControl(val action: RoutineAction, val cueId: String? = null) : Event

    // ---- Alarms (§11) ----
    @Serializable @SerialName("alarm") data class AlarmControl(val alarmId: String, val action: AlarmAction) : Event

    // ---- Calendar (§9) ----
    @Serializable @SerialName("calendarSynced") data class CalendarSynced(val events: List<CalendarEvent>, val syncedAt: Instant) : Event
    @Serializable @SerialName("calendarAck") data class CalendarAck(val cueId: String) : Event
    @Serializable @SerialName("calendarSnooze") data class CalendarSnooze(val cueId: String) : Event

    // ---- Delivery feedback ----
    /** GEN-1: logged only. */
    @Serializable @SerialName("dismissed") data class CueDismissed(val cueId: String) : Event
    @Serializable @SerialName("speechFinished") data class SpeechFinished(val cueId: String) : Event
    /** SPK-3: a failed speech is never a failed cue; it only flags the readiness screen. */
    @Serializable @SerialName("speechFailed") data class SpeechFailed(val cueId: String, val reason: String) : Event
    /** GEN-10 preview of a cue profile; never changes state. */
    @Serializable @SerialName("previewCue") data class PreviewCue(val habitOrType: String) : Event
}

@Serializable enum class SessionAnswer { Start, NotNow, NotWorking }

@Serializable
sealed interface OverrideDuration {
    @Serializable @SerialName("untilChanged") data object UntilChanged : OverrideDuration
    @Serializable @SerialName("for") data class For(val minutes: Int) : OverrideDuration
    @Serializable @SerialName("untilTransition") data object UntilTransition : OverrideDuration
    @Serializable @SerialName("restOfToday") data object RestOfToday : OverrideDuration
}

@Serializable
sealed interface PauseTarget {
    @Serializable @SerialName("habit") data class Habit(val habitId: String) : PauseTarget
    @Serializable @SerialName("posture") data object Posture : PauseTarget
    @Serializable @SerialName("all") data object All : PauseTarget
}

/** SUN-10 / HYD-4 pause options. */
@Serializable
sealed interface PauseChoice {
    @Serializable @SerialName("for") data class For(val minutes: Int) : PauseChoice
    @Serializable @SerialName("untilConditionEnds") data object UntilConditionEnds : PauseChoice
    @Serializable @SerialName("restOfToday") data object RestOfToday : PauseChoice
    @Serializable @SerialName("until") data class Until(val at: Instant) : PauseChoice
    @Serializable @SerialName("indefinite") data object Indefinite : PauseChoice
}

/** Dose slot identity (MED-1): `(itemId, localDate, localTime)` in the policy zone. */
@Serializable
data class SlotRef(val medicationId: String, val date: java.time.LocalDate, val time: java.time.LocalTime) {
    val key: String get() = "$medicationId|$date|$time"
}

@Serializable
enum class PostureAction {
    /** Start the cycle (needed for `ManualOnly`; otherwise starts immediately if allowed). */
    Start, Stop,
    /** POS-3 cue actions. */
    Switched, Snooze, Skip, Extend5, Extend10, Extend15,
    /** POS-4 controls. */
    SwitchNow, Pause, Resume, Reset,
}

@Serializable
sealed interface RoutineAction {
    @Serializable @SerialName("start") data class Start(val routineId: String, val test: RoutineTestMode? = null, val replaceCurrent: Boolean = false) : RoutineAction
    @Serializable @SerialName("pause") data object Pause : RoutineAction
    @Serializable @SerialName("resume") data object Resume : RoutineAction
    @Serializable @SerialName("cancel") data object Cancel : RoutineAction
    @Serializable @SerialName("skipStep") data object SkipStep : RoutineAction
    @Serializable @SerialName("backStep") data object BackStep : RoutineAction
    @Serializable @SerialName("done") data object Done : RoutineAction
    @Serializable @SerialName("extend") data class Extend(val minutes: Int) : RoutineAction
    /** Answer to the RTN-6 AskToResume prompt. */
    @Serializable @SerialName("recover") data class Recover(val choice: RecoveryChoice) : RoutineAction
    /** Answer to the RTN-9 scheduled start prompt. */
    @Serializable @SerialName("promptStart") data class PromptStart(val routineId: String) : RoutineAction
    @Serializable @SerialName("promptSkipToday") data class PromptSkipToday(val routineId: String) : RoutineAction
}

@Serializable enum class RecoveryChoice { Resume, Restart, Cancel }
@Serializable enum class RoutineTestMode { X1, Fast }

@Serializable enum class AlarmAction { Stop, Snooze, Test, SpotifyFellBack }

@Serializable enum class EventStatus { Confirmed, Tentative, Canceled }
@Serializable enum class SelfResponse { Accepted, Declined, Tentative, NeedsAction, None }

/**
 * A calendar event instance as cached by the app. [title] is untrusted user/third-party text: it is only
 * matched against keywords and displayed (CAL-5), never interpreted.
 */
@Serializable
data class CalendarEvent(
    /** Stable instance key (event id + original start). */
    val key: String,
    val seriesId: String? = null,
    val calendarId: String,
    val title: String = "",
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    val status: EventStatus = EventStatus.Confirmed,
    val self: SelfResponse = SelfResponse.None,
    val otherAttendees: Int = 0,
    val hasConferencingLink: Boolean = false,
    val color: String? = null,
    val availability: EventAvailability = EventAvailability.Busy,
    val hasOwnReminders: Boolean = false,
)
