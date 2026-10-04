@file:UseSerializers(LocalTimeSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.LocalDateSerializer
import app.daycue.domain.time.LocalTimeSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Serializable
sealed interface AlarmSource {
    @Serializable @SerialName("localTone") data class LocalTone(val toneId: String = "morning") : AlarmSource
    /** ALM-2: Spotify item with a mandatory local fallback. Playback itself is an app concern. */
    @Serializable @SerialName("spotify") data class SpotifyItem(val uri: String, val fallbackToneId: String = "morning") : AlarmSource
}

/** PRODUCT §11. Alarms always follow local time. */
@Serializable
data class MorningAlarm(
    val id: String,
    val name: String = "Morning alarm",
    val enabled: Boolean = false,
    val time: LocalTime = LocalTime.of(7, 0),
    /** Empty days + [oneOffDate] = one-off alarm. Null days = settings.workDays. */
    val days: Set<DayOfWeek>? = null,
    val oneOffDate: LocalDate? = null,
    val source: AlarmSource = AlarmSource.LocalTone(),
    val spotifyStartTimeoutSec: Int = 10,
    val volumeRampSec: Int = 30,
    val snoozeMin: Int = 9,
    val maxSnoozes: Int = 3,
    val ringTimeoutMin: Int = 10,
    val vibrate: Boolean = true,
    val followOnRoutineId: String? = null,
    /** ALM-4: "Skip next" disables only this occurrence date. */
    val skipDate: LocalDate? = null,
)
