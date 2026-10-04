@file:UseSerializers(LocalTimeSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class, ZoneIdSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.ALL_DAYS
import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.LocalDateSerializer
import app.daycue.domain.time.LocalTimeSerializer
import app.daycue.domain.time.ZoneIdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Travel policy for fixed-clock schedules (ARCHITECTURE §3.1, PRODUCT §7). */
@Serializable
sealed interface TravelPolicy {
    /** Local time in whatever zone the phone is in now (`Clock.zone()`). */
    @Serializable @SerialName("followLocalTime") data object FollowLocalTime : TravelPolicy
    /** The instant of that local time in a fixed zone. */
    @Serializable @SerialName("keepHomeTimezone") data class KeepHomeTimezone(val zone: ZoneId) : TravelPolicy

    fun zone(current: ZoneId): ZoneId = when (this) {
        FollowLocalTime -> current
        is KeepHomeTimezone -> zone
    }
}

@Serializable enum class LockScreenPresentation { Generic, Full, Hidden }
@Serializable enum class MedicationQuietHours { DeliverNormally, DeliverSilently }

/** PRODUCT §7. The label is user text and never leaves the phone unless the `medication` scope is granted. */
@Serializable
data class Medication(
    val id: String,
    val label: String,
    val times: List<LocalTime>,
    val days: Set<DayOfWeek> = ALL_DAYS,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val travelPolicy: TravelPolicy = TravelPolicy.FollowLocalTime,
    val repeat: RepeatPolicy = RepeatPolicy(10, 3),
    val snoozeMin: Int = 10,
    val lockScreen: LockScreenPresentation = LockScreenPresentation.Generic,
    val speakLabel: Boolean = false,
    val quietHours: MedicationQuietHours = MedicationQuietHours.DeliverNormally,
    val historyRetentionDays: Int = 90,
    val cueProfileId: String? = null,
) {
    fun activeOn(date: LocalDate): Boolean =
        date.dayOfWeek in days && (startDate == null || !date.isBefore(startDate)) && (endDate == null || !date.isAfter(endDate))
}
