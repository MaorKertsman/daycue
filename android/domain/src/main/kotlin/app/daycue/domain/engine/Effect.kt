@file:UseSerializers(InstantSerializer::class)

package app.daycue.domain.engine

import app.daycue.domain.config.AlarmSource
import app.daycue.domain.config.CueType
import app.daycue.domain.config.Language
import app.daycue.domain.config.SpeechOutput
import app.daycue.domain.config.SpeechOverMediaPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.time.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/** Engine outputs, executed by the app (ARCHITECTURE §3.1). Order within a [Reduction] is meaningful. */
@Serializable
sealed interface Effect {
    /**
     * Re-arm the single next-wake alarm. The domain decides [precision]: morning alarms and medication ->
     * [WakePrecision.AlarmClock]; other user-facing cues -> [WakePrecision.Exact]; housekeeping / expiry
     * recomputation -> [WakePrecision.Inexact].
     */
    @Serializable @SerialName("scheduleWake")
    data class ScheduleWake(val at: Instant, val precision: WakePrecision, val reason: String) : Effect

    @Serializable @SerialName("cancelWake") data object CancelWake : Effect

    /** Post or update (same [Cue.notificationKey]) a notification, with optional sound/vibration/speech. */
    @Serializable @SerialName("deliver") data class Deliver(val cue: Cue) : Effect

    /** Remove a notification. Never an acknowledgement by itself. */
    @Serializable @SerialName("dismiss") data class DismissCue(val notificationKey: String, val cueId: String?, val reason: String) : Effect

    /** Stand-alone utterance (deferred speech after an alarm stops, COL-4). */
    @Serializable @SerialName("speak") data class Speak(val speech: SpeechRequest) : Effect

    /** Ring an alarm (full-screen UI, ALM-1). Spotify playback + local fallback is the app's job (ALM-2). */
    @Serializable @SerialName("startAlarm")
    data class StartAlarm(
        val alarmId: String,
        val occurrence: String,
        val source: AlarmSource,
        val spotifyStartTimeoutSec: Int,
        val volumeRampSec: Int,
        val vibrate: Boolean,
        val snoozeCount: Int,
        val maxSnoozes: Int,
        val isTest: Boolean = false,
    ) : Effect

    @Serializable @SerialName("stopAlarm") data class StopAlarm(val alarmId: String, val reason: String) : Effect

    /** GEN-8 history row. */
    @Serializable @SerialName("history") data class RecordHistory(val entry: HistoryEntry) : Effect

    /** Ask the app to run these ops through `applyOps` and then send `ConfigChanged` (single edit path). */
    @Serializable @SerialName("applyOps") data class ApplyConfigOps(val ops: List<ConfigOp>, val reason: String) : Effect
}

/** Ordered from strongest to weakest; when wakes coincide the strongest wins. */
@Serializable enum class WakePrecision { AlarmClock, Exact, Inexact }

@Serializable
data class HistoryEntry(
    val at: Instant,
    val itemKey: String,
    val kind: HistoryKind,
    /** Rule ID that caused it (GEN-8), e.g. "SUN-2". */
    val rule: String,
    val cueId: String? = null,
    val detail: Map<String, String> = emptyMap(),
    /** GEN-10: test actions never count. */
    val test: Boolean = false,
)

@Serializable
enum class HistoryKind {
    Delivered, Repeated, Acked, Snoozed, Paused, Resumed, Dismissed, Unanswered, Retracted, Held, Skipped,
    SkippedLate, NotConfirmed, Taken, MissedPowerOff, SessionStarted, SessionPaused, SessionEnded,
    RoutineStarted, RoutineStep, RoutineCompleted, RoutineCanceled, RoutinePaused, RoutineResumed, RoutineSkippedBySchedule,
    AlarmRang, AlarmSnoozed, AlarmStopped, SpeechFailed, SpeechFinished, ContextChanged, Test,
}

/** A localizable text: resource [key] + arguments. User-authored text travels as an argument. */
@Serializable
data class Text(val key: String, val args: Map<String, String> = emptyMap())

@Serializable
enum class ActionKind { Applied, Drank, Done, GotIt, NotNeeded, Taken, Snooze, Pause, Switched, Extend, Skip, Start, NotNow, NotWorking, Resume, Restart, Cancel, Stop, SkipToday, Open }

/** A notification button. The app maps a tap back to an [Event] carrying [Cue.id]. */
@Serializable
data class CueAction(val kind: ActionKind, val label: Text, val minutes: Int? = null)

/** Lock-screen visibility (Android Notification.VISIBILITY_*). PRIVATE shows [Cue.publicTitle]. */
@Serializable enum class LockScreenVisibility { Public, Private, Secret }

@Serializable
data class SpeechRequest(
    val language: Language,
    val lead: Text,
    /** COL-1: "Also: posture, hydration." (already capped to maxSpokenItems - 1). */
    val also: List<Text> = emptyList(),
    val moreCount: Int = 0,
    val overMedia: SpeechOverMediaPolicy,
    val output: SpeechOutput,
    val createdAt: Instant,
    /** SPK-2: drop if not spoken by then. */
    val dropAfter: Instant,
)

/** GEN-9 "Why now?". [facts] are context values and their sources, as key/value data (never instructions). */
@Serializable
data class WhyNow(val rule: String, val reason: String, val facts: Map<String, String> = emptyMap())

@Serializable
data class Cue(
    /** Unique per delivery cycle; actions echo it back. */
    val id: String,
    /** Stable per item: re-alerts and re-deliveries update the same notification (GEN-4, no stacking). */
    val notificationKey: String,
    val itemKey: String,
    val type: CueType,
    val priority: Int,
    val channelId: String,
    val title: Text,
    val body: Text,
    val actions: List<CueAction>,
    val lockScreen: LockScreenVisibility,
    /** Shown on the lock screen when [lockScreen] is Private. */
    val publicTitle: Text? = null,
    val soundId: String? = null,
    val vibrationId: String? = null,
    /** No sound and no vibration at all. */
    val silent: Boolean = false,
    val speech: SpeechRequest? = null,
    /** COL-1 delivery group; members share it, only the lead carries sound/vibration/speech. */
    val groupKey: String? = null,
    val groupLead: Boolean = true,
    val repeatIndex: Int = 0,
    val isTest: Boolean = false,
    /** Keep the notification after app actions such as timeouts (medication MED-4). */
    val ongoing: Boolean = false,
    val fullScreen: Boolean = false,
    val why: WhyNow,
    val dueAt: Instant,
    val deliveredAt: Instant,
)
