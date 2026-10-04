@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.ALL_DAYS
import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.LocalTimeSerializer
import app.daycue.domain.time.TimeWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalTime

// ---- Policy enums (PRODUCT §2, §3.2) ----

@Serializable enum class IntervalAnchor { FromAck, FromDue }
@Serializable enum class UnansweredPolicy { RollForward, StayDue }
@Serializable enum class DuringMeeting { Defer, DeliverSilently, Deliver }
@Serializable enum class DayStartPolicy { IntervalAfterStart, AtStart }

@Serializable
sealed interface FirstReminderPolicy {
    @Serializable @SerialName("onConditionStart") data object OnConditionStart : FirstReminderPolicy
    @Serializable @SerialName("afterDelay") data class AfterDelay(val minutes: Int) : FirstReminderPolicy
    @Serializable @SerialName("afterFullInterval") data object AfterFullInterval : FirstReminderPolicy
    @Serializable @SerialName("onlyAfterApplied") data object OnlyAfterApplied : FirstReminderPolicy
}

@Serializable
sealed interface ReentryPolicy {
    @Serializable @SerialName("remindOnReentry") data class RemindOnReentry(val graceMin: Int = 0) : ReentryPolicy
    @Serializable @SerialName("treatAsFirst") data object TreatAsFirst : ReentryPolicy
    @Serializable @SerialName("waitNextInterval") data object WaitNextInterval : ReentryPolicy
}

@Serializable enum class LeaveConditionPolicy { RetractAndHold, KeepVisible, RemindAnyway }

@Serializable enum class IntervalKind { Sunscreen, Hydration, Generic }

/** HYD-5: optional per-context intervals, first match wins. */
@Serializable
data class ContextInterval(val condition: ContextCondition, val intervalMin: Int)

@Serializable
sealed interface Habit {
    val id: String
    val name: String
    val enabled: Boolean
    val pause: PauseSpec?
}

/**
 * Interval habit (PRODUCT §2–§4). The outdoor-specific names in PRODUCT map onto the generic
 * "condition" here: `firstReminder` = FirstReminderPolicy, `reentry` = OutdoorReentryPolicy,
 * `onLeaveCondition` = LeaveOutdoorPolicy. Habits whose condition is [ContextCondition.isAny] have no
 * condition stretch and instead follow [dayStart] at the start of each active window (HYD-3).
 */
@Serializable
@SerialName("interval")
data class IntervalHabit(
    override val id: String,
    val kind: IntervalKind = IntervalKind.Generic,
    override val name: String,
    override val enabled: Boolean = false,
    val intervalMin: Int = 60,
    val activeHours: TimeWindow? = null,
    val days: Set<DayOfWeek> = ALL_DAYS,
    val condition: ContextCondition = ContextCondition.ANY,
    val anchor: IntervalAnchor = IntervalAnchor.FromAck,
    val dayStart: DayStartPolicy = DayStartPolicy.IntervalAfterStart,
    val firstReminder: FirstReminderPolicy = FirstReminderPolicy.OnConditionStart,
    val reentry: ReentryPolicy = ReentryPolicy.RemindOnReentry(0),
    val onLeaveCondition: LeaveConditionPolicy = LeaveConditionPolicy.RetractAndHold,
    val repeat: RepeatPolicy = RepeatPolicy.NONE,
    val unanswered: UnansweredPolicy = UnansweredPolicy.RollForward,
    val snoozeMin: Int = 15,
    val duringMeeting: DuringMeeting = DuringMeeting.Defer,
    val contextIntervals: List<ContextInterval> = emptyList(),
    val cueProfileId: String? = null,
    val phrase: LocalizedText? = null,
    override val pause: PauseSpec? = null,
) : Habit {
    val cueType: CueType get() = when (kind) {
        IntervalKind.Sunscreen -> CueType.Sunscreen
        IntervalKind.Hydration -> CueType.Hydration
        IntervalKind.Generic -> CueType.Habit
    }
}

@Serializable enum class BottleTrigger { LeavingNow, GeofenceExit }

@Serializable
data class ScheduledDeparture(
    val placeId: String,
    val time: LocalTime,
    val days: Set<DayOfWeek> = ALL_DAYS,
)

/** Water bottle departure cue (PRODUCT §5). */
@Serializable
@SerialName("transition")
data class TransitionHabit(
    override val id: String,
    override val name: String,
    override val enabled: Boolean = false,
    /** Empty = places with `bottleReminderOnLeave`. */
    val placeIds: Set<String> = emptySet(),
    val triggers: Set<BottleTrigger> = setOf(BottleTrigger.LeavingNow, BottleTrigger.GeofenceExit),
    val scheduledDepartures: List<ScheduledDeparture> = emptyList(),
    val cooldownPerPlaceMin: Int = 60,
    val dedupWindowMin: Int = 30,
    val repeat: RepeatPolicy = RepeatPolicy(10, 0),
    val cueProfileId: String? = null,
    val phrase: LocalizedText? = null,
    override val pause: PauseSpec? = null,
) : Habit
