@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.ALL_DAYS
import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.LocalTimeSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalTime

@Serializable enum class RoutineTimingPolicy { FollowActualCompletion, FollowSchedule }
@Serializable enum class RoutineRecoveryPolicy { AskToResume, ResumeCurrentStep, Cancel }
@Serializable enum class RoutineStartMode { AskToStart, AutoStart }
@Serializable enum class StepCompletion { Timed, Explicit }

@Serializable
sealed interface RoutineTrigger {
    @Serializable @SerialName("manual") data object Manual : RoutineTrigger
    @Serializable @SerialName("schedule") data class Schedule(val time: LocalTime, val days: Set<DayOfWeek> = ALL_DAYS) : RoutineTrigger
    @Serializable @SerialName("afterAlarm") data class AfterAlarm(val alarmId: String) : RoutineTrigger
}

@Serializable
data class RoutineRecovery(val policy: RoutineRecoveryPolicy = RoutineRecoveryPolicy.AskToResume, val thresholdMin: Int = 10)

@Serializable
data class RoutineStep(
    val id: String,
    val name: String,
    /** Empty = the name is spoken. */
    val phrase: String = "",
    /** 0 = no timer. */
    val durationSec: Int = 120,
    val completion: StepCompletion = StepCompletion.Timed,
    val repeat: Int = 1,
    val optional: Boolean = false,
    val cueProfileId: String? = null,
) {
    val spokenText: String get() = phrase.ifBlank { name }
}

/** PRODUCT §10. */
@Serializable
data class Routine(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val trigger: RoutineTrigger = RoutineTrigger.Manual,
    /** Null = trigger default: Schedule -> AskToStart, AfterAlarm -> AutoStart. */
    val startMode: RoutineStartMode? = null,
    val timing: RoutineTimingPolicy = RoutineTimingPolicy.FollowActualCompletion,
    val recovery: RoutineRecovery = RoutineRecovery(),
    val steps: List<RoutineStep> = emptyList(),
    val travelPolicy: TravelPolicy = TravelPolicy.FollowLocalTime,
) {
    val effectiveStartMode: RoutineStartMode get() = startMode ?: when (trigger) {
        is RoutineTrigger.AfterAlarm -> RoutineStartMode.AutoStart
        else -> RoutineStartMode.AskToStart
    }
}
