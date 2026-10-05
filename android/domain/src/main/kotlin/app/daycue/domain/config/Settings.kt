@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.ALL_DAYS
import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.LocalTimeSerializer
import app.daycue.domain.time.TimeWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalTime

@Serializable enum class AwayEnvironmentPolicy { OutdoorWhenOnFoot, Unknown, AssumeOutdoor }

/** WRK settings (PRODUCT §1.5). Per-place `sessionStart` lives on [Place]. */
@Serializable
data class SessionRules(
    val sustainedActiveToStartMin: Int = 5,
    val permittedHours: TimeWindow = TimeWindow(LocalTime.of(8, 0), LocalTime.of(19, 0)),
    /** Null = settings.workDays. */
    val permittedDays: Set<DayOfWeek>? = null,
    val idleToPauseMin: Int = 10,
    val lockedToPauseMin: Int = 2,
    val pausedToEndMin: Int = 60,
    val companionStaleToSuspendMin: Int = 10,
    val suggestCooldownMin: Int = 120,
    val meetingKeepsSessionActive: Boolean = true,
    val manualSessionCapMin: Int = 600,
)

/** Context inference parameters (PRODUCT §1.3, §1.4). */
@Serializable
data class ContextRules(
    val placeEnterDwellMin: Int = 3,
    val placeExitDwellMin: Int = 5,
    val outdoorEnterDwellMin: Int = 5,
    val outdoorExitDwellMin: Int = 10,
    val awayEnvironment: AwayEnvironmentPolicy = AwayEnvironmentPolicy.OutdoorWhenOnFoot,
    val onFootSustainMin: Int = 5,
    val onFootHoldMin: Int = 45,
    /**
     * CTX-6 continuous walk: after an on-foot ENTER transition the walk counts as ongoing without new
     * readings for at most this long after the last supporting on-foot signal; then it is stale and
     * Environment falls back to Unknown (after the exit dwell). Effective value is never below [onFootHoldMin].
     */
    val onFootOngoingMaxMin: Int = 180,
    val activityRecognitionExpiryMin: Int = 10,
    val companionExpiryMin: Int = 3,
    val environmentOverrideCapMin: Int = 480,
    val sessions: SessionRules = SessionRules(),
)

@Serializable
data class QuietWindow(val window: TimeWindow, val days: Set<DayOfWeek> = ALL_DAYS)

/** PRODUCT §8.2. */
@Serializable
data class QuietHours(
    val enabled: Boolean = true,
    val windows: List<QuietWindow> = listOf(QuietWindow(TimeWindow(LocalTime.of(22, 30), LocalTime.of(7, 0)))),
    val respectSystemDnd: Boolean = true,
)

/** PRODUCT §8.3. */
@Serializable
data class CollisionSettings(
    val mergeWindowMin: Int = 2,
    val minAudibleGapSec: Int = 30,
    val maxSpokenItems: Int = 3,
    val speechMaxAgeSec: Int = 120,
)

@Serializable enum class SpeechOverMediaPolicy { DuckAndSpeak, PauseAndSpeak, NotificationOnly }
@Serializable enum class SpeechInMeetingPolicy { NotificationOnly, VibrateOnly, SpeakAnyway }
@Serializable enum class SpeechOutput { AnyRoute, HeadphonesOnly }

/** PRODUCT §8.5. */
@Serializable
data class SpeechSettings(
    val enabled: Boolean = true,
    val overMedia: SpeechOverMediaPolicy = SpeechOverMediaPolicy.DuckAndSpeak,
    val inMeeting: SpeechInMeetingPolicy = SpeechInMeetingPolicy.NotificationOnly,
    val output: SpeechOutput = SpeechOutput.AnyRoute,
)

@Serializable
data class GlobalSettings(
    val language: Language = Language.en,
    val dayStartsAt: LocalTime = LocalTime.of(4, 0),
    val workDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val quietHours: QuietHours = QuietHours(),
    val collision: CollisionSettings = CollisionSettings(),
    val speech: SpeechSettings = SpeechSettings(),
    /** Global pause of non-exempt items (medication, alarms and a running routine are exempt). */
    val pauseAll: PauseSpec? = null,
    /** Clock style for times in notifications and speech text (seeded from the device on first run). */
    val use24Hour: Boolean = true,
)
