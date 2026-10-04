package app.daycue.domain.config

import kotlinx.serialization.Serializable

@Serializable enum class TypicalEnvironment { Indoor, Outdoor, Mixed }
@Serializable enum class SessionStart { AutoStart, Suggest, Off }

@Serializable
data class GeoPoint(val lat: Double, val lng: Double)

/** PRODUCT §1.2. Templates ship without a center (inactive until set). */
@Serializable
data class Place(
    val id: String,
    val name: String,
    val center: GeoPoint? = null,
    val radiusM: Int = 150,
    val typicalEnvironment: TypicalEnvironment = TypicalEnvironment.Indoor,
    val allowedRoutines: Set<String> = emptySet(),
    val allowedActivities: Set<SessionKind> = emptySet(),
    val sessionStart: SessionStart = SessionStart.Off,
    val defaultSessionKind: SessionKind = SessionKind.Working,
    val bottleReminderOnLeave: Boolean = false,
) {
    val active: Boolean get() = center != null
}

/** Per-type cue identity (PRODUCT §8.1, §8.5). Phrases are user text in both languages. */
@Serializable
data class CueProfile(
    val id: String,
    val type: CueType,
    val soundId: String,
    val vibrationId: String,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val speechEnabled: Boolean = true,
    val phrase: LocalizedText = LocalizedText(),
    /** Per-profile speech language override; null = settings.language. */
    val language: Language? = null,
)
