@file:UseSerializers(DayOfWeekSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.ALL_DAYS
import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.TimeWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek

@Serializable enum class PostureTimerStartPolicy { AtConfirmation, AtCue }
@Serializable enum class PostureMeetingPolicy { DeferCue, Freeze, Ignore }
@Serializable enum class PostureInterruptionPolicy { ResetToFirst, RestartCurrent, ContinueRemaining }
@Serializable enum class PostureModeKind { Sitting, Standing, Walking, Custom }

@Serializable
data class PostureMode(
    val id: String,
    val kind: PostureModeKind,
    val name: LocalizedText,
    val durationMin: Int = 30,
    val enabled: Boolean = true,
    /** Spoken when switching *to* this mode, e.g. "Time to stand". */
    val phrase: LocalizedText? = null,
)

@Serializable
sealed interface PostureActiveWhen {
    @Serializable @SerialName("duringSessions") data object DuringSessions : PostureActiveWhen
    @Serializable @SerialName("activeHours") data class ActiveHours(val window: TimeWindow, val days: Set<DayOfWeek> = ALL_DAYS) : PostureActiveWhen
    @Serializable @SerialName("manualOnly") data object ManualOnly : PostureActiveWhen
}

/** PRODUCT §6. */
@Serializable
data class PostureCycleConfig(
    val enabled: Boolean = false,
    val modes: List<PostureMode> = emptyList(),
    val activeWhen: PostureActiveWhen = PostureActiveWhen.DuringSessions,
    val timerStart: PostureTimerStartPolicy = PostureTimerStartPolicy.AtConfirmation,
    val confirmRepeat: RepeatPolicy = RepeatPolicy(5, 2),
    val duringMeeting: PostureMeetingPolicy = PostureMeetingPolicy.DeferCue,
    val shortInterruptionMin: Int = 15,
    val longInterruption: PostureInterruptionPolicy = PostureInterruptionPolicy.ResetToFirst,
    val extendOptionsMin: List<Int> = listOf(5, 10, 15),
    val snoozeMin: Int = 5,
    val cueProfileId: String? = null,
    val pause: PauseSpec? = null,
) {
    val enabledModes: List<PostureMode> get() = modes.filter { it.enabled }
}
