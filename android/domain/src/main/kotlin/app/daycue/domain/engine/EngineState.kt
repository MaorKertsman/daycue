@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, LocalTimeSerializer::class, ZoneIdSerializer::class)

package app.daycue.domain.engine

import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.SessionKind
import app.daycue.domain.context.ContextState
import app.daycue.domain.time.InstantSerializer
import app.daycue.domain.time.LocalDateSerializer
import app.daycue.domain.time.LocalTimeSerializer
import app.daycue.domain.time.ZoneIdSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything the engine remembers between reduces. Persist it after **every** reduce (single transaction)
 * and re-arm one alarm from [nextWakeAt]. Nothing depends on in-memory timers (ARCHITECTURE §3.1).
 */
@Serializable
data class EngineState(
    val schemaVersion: Int = 1,
    val lastEvaluatedAt: Instant? = null,
    /** `Clock.elapsedRealtime()` at the last reduce, in ms. Going backwards = real reboot. */
    val lastElapsedRealtimeMs: Long? = null,
    val lastZone: ZoneId? = null,
    val configVersion: Long = -1,
    val nextWakeAt: Instant? = null,
    val nextWakePrecision: WakePrecision? = null,
    val nextWakeReason: String? = null,
    val context: ContextState = ContextState(),
    val intervals: Map<String, IntervalState> = emptyMap(),
    val bottles: Map<String, BottleState> = emptyMap(),
    val posture: PostureState = PostureState(),
    val medication: MedicationState = MedicationState(),
    val routine: RoutineState = RoutineState(),
    val alarms: Map<String, AlarmState> = emptyMap(),
    val calendar: CalendarState = CalendarState(),
    val delivery: DeliveryState = DeliveryState(),
    val readiness: Readiness = Readiness(),
) {
    companion object {
        fun encode(s: EngineState): String = DayCueJson.encodeToString(serializer(), s)
        fun decode(json: String): EngineState = DayCueJson.decodeFromString(serializer(), json)
    }
}

/** A delivered cue that's still the item's current one (GEN-2: at most one per item). */
@Serializable
data class ActiveCue(
    val cueId: String,
    val firstDeliveredAt: Instant,
    val lastDeliveredAt: Instant,
    val repeatsDone: Int = 0,
    /** Repeats are over; nothing more will be sent for this cue (it may stay visible). */
    val exhausted: Boolean = false,
    /** LeaveOutdoorPolicy.RemindAnyway: condition ignored for this cue. */
    val ignoreCondition: Boolean = false,
)

// ---- Interval habits (§2–§4) ----
@Serializable
data class IntervalState(
    /** SUN-1 `lastAppliedAt` / HYD `lastAckAt`. UTC instant, survives everything (SUN-6). */
    val lastAckAt: Instant? = null,
    val dueAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val cue: ActiveCue? = null,
    val lastDeliveryAt: Instant? = null,
    /** Start of the current condition stretch (e.g. Outdoor confirmed), null when not in one. */
    val stretchStart: Instant? = null,
    /** Due state carried over from an earlier stretch (became due / retracted / snoozed while not in condition). */
    val heldDue: Boolean = false,
    /** UnansweredPolicy.StayDue: wait for ack or a new stretch. */
    val awaitingNewStretch: Boolean = false,
    /** HYD-3: the day whose active-window start was applied. */
    val dayStartAppliedFor: LocalDate? = null,
    val appliedIntervalMin: Int? = null,
    /** SUN-10 "until condition ends" pause has ended (setAt of that pause). */
    val pauseEndedFor: Instant? = null,
    /** Idempotency: the cue id of the last ack (duplicate broadcasts are ignored). */
    val lastAckCueId: String? = null,
)

// ---- Water bottle (§5) ----
@Serializable
data class BottleState(
    val lastCueAt: Instant? = null,
    val lastCueByPlace: Map<String, Instant> = emptyMap(),
    val cue: ActiveCue? = null,
    val firedDepartures: Map<String, LocalDate> = emptyMap(),
    /** Departure waiting to be delivered in this reduce (set by event handlers). */
    val pending: PendingDeparture? = null,
)

@Serializable
data class PendingDeparture(val placeId: String?, val trigger: String, val at: Instant, val loud: Boolean)

// ---- Posture (§6) ----
@Serializable enum class PosturePhase { Off, Running, SwitchPending, Paused, Frozen }

@Serializable
data class PostureState(
    val phase: PosturePhase = PosturePhase.Off,
    val modeId: String? = null,
    val modeStartedAt: Instant? = null,
    val modeEndsAt: Instant? = null,
    /** Remaining ms while Paused/Frozen. */
    val remainingMs: Long? = null,
    val interruptedAt: Instant? = null,
    /** Phase to return to after Paused/Frozen. */
    val resumePhase: PosturePhase? = null,
    val pendingModeId: String? = null,
    /** AtCue: the mode we switched away from (for Snooze). */
    val previousModeId: String? = null,
    val cue: ActiveCue? = null,
    val snoozedUntil: Instant? = null,
    val manualStarted: Boolean = false,
    val seq: Int = 0,
)

// ---- Medication (§7) ----
@Serializable enum class SlotStatus { Upcoming, Due, Taken, Skipped }

@Serializable
data class MedSlot(
    val medicationId: String,
    val date: LocalDate,
    val time: LocalTime,
    val dueAt: Instant,
    val status: SlotStatus = SlotStatus.Upcoming,
    val takenAt: Instant? = null,
    val materializedAt: Instant,
    val cue: ActiveCue? = null,
    val snoozedUntil: Instant? = null,
    /** Why no cue was/will be sent (e.g. created after its time), null = cueing normally. */
    val noCueReason: String? = null,
    /** Covered by a merged recovery cue (MED-7/8). */
    val mergedCueId: String? = null,
    val notConfirmedRecorded: Boolean = false,
) {
    val ref: SlotRef get() = SlotRef(medicationId, date, time)
    val key: String get() = ref.key
}

@Serializable
data class MedicationState(
    val slots: Map<String, MedSlot> = emptyMap(),
    /** Ids already known to the engine (new ids get `trackingSince = now`). */
    val trackingSince: Map<String, Instant> = emptyMap(),
    val mergedCue: ActiveCue? = null,
    val policyNoticeZone: ZoneId? = null,
)

// ---- Routines (§10) ----
@Serializable enum class RunStatus { Running, Paused, AwaitingRecovery }

@Serializable
data class RoutineRun(
    val routineId: String,
    /** RTN-7: the run keeps the routine as it was when started. */
    val routine: app.daycue.domain.config.Routine,
    val configVersion: Long,
    val startedAt: Instant,
    val stepIndex: Int = 0,
    val repeatIndex: Int = 0,
    val stepStartedAt: Instant,
    /** Planned end of the current (sub)step; null = no timer. */
    val stepEndsAt: Instant? = null,
    val status: RunStatus = RunStatus.Running,
    val pausedAt: Instant? = null,
    val pausedRemainingMs: Long? = null,
    /** FollowSchedule: shift added by user pauses. */
    val planShiftMs: Long = 0,
    val nudged: Boolean = false,
    val test: RoutineTestMode? = null,
    val cueId: String? = null,
    val seq: Int = 0,
)

@Serializable
data class RoutineState(
    val run: RoutineRun? = null,
    /** RTN-9 scheduled prompts: routineId -> date answered/skipped/started. */
    val promptHandled: Map<String, LocalDate> = emptyMap(),
    val openPrompt: RoutinePrompt? = null,
)

@Serializable
data class RoutinePrompt(val routineId: String, val date: LocalDate, val postedAt: Instant, val cueId: String)

// ---- Alarms (§11) ----
@Serializable
data class AlarmRing(
    val occurrence: LocalDate,
    val scheduledAt: Instant,
    val startedAt: Instant,
    val snoozeCount: Int = 0,
    val snoozedUntil: Instant? = null,
    val test: Boolean = false,
)

@Serializable
data class AlarmState(
    /** Last occurrence date that rang / was stopped / skipped / logged as missed. */
    val handledThrough: LocalDate? = null,
    val ring: AlarmRing? = null,
)

// ---- Calendar (§9) ----
@Serializable
data class CalendarCueRecord(
    val eventKey: String,
    val start: Instant,
    /** Leads (minutes) delivered or consumed for this event start. */
    val consumedLeads: Set<Int> = emptySet(),
    val cue: ActiveCue? = null,
    val leadMin: Int? = null,
    val snoozedUntil: Instant? = null,
)

@Serializable
data class CalendarState(
    val events: List<CalendarEvent> = emptyList(),
    val syncedAt: Instant? = null,
    val records: Map<String, CalendarCueRecord> = emptyMap(),
)

// ---- Delivery (§8) ----
@Serializable
data class VisibleCue(val cueId: String, val notificationKey: String, val itemKey: String, val type: app.daycue.domain.config.CueType, val deliveredAt: Instant)

@Serializable
data class DeliveryState(
    val lastAudibleAt: Instant? = null,
    val ringingAlarmId: String? = null,
    /** COL-4: utterances absorbed by a ringing alarm. */
    val deferredSpeech: List<SpeechRequest> = emptyList(),
    val visible: Map<String, VisibleCue> = emptyMap(),
    val seq: Long = 0,
)

@Serializable
data class Readiness(
    val speechFailedAt: Instant? = null,
    val speechFailureReason: String? = null,
    val lastAlarmSourceFallbackAt: Instant? = null,
)

@Serializable
data class SessionView(val kind: SessionKind, val placeId: String?, val manual: Boolean)
