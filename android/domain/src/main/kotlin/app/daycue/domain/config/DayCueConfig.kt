package app.daycue.domain.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The single versioned definitions document (ARCHITECTURE §3.2, ADR-0001).
 * [version] increments on every applied change set (see `applyOps`).
 */
@Serializable
data class DayCueConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val version: Long = 0,
    val habits: List<Habit> = emptyList(),
    val postureCycle: PostureCycleConfig = PostureCycleConfig(),
    val medications: List<Medication> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val alarms: List<MorningAlarm> = emptyList(),
    val places: List<Place> = emptyList(),
    val cueProfiles: List<CueProfile> = emptyList(),
    val calendarRules: CalendarConfig = CalendarConfig(),
    val contextRules: ContextRules = ContextRules(),
    val settings: GlobalSettings = GlobalSettings(),
) {
    val intervalHabits: List<IntervalHabit> get() = habits.filterIsInstance<IntervalHabit>()
    val transitionHabits: List<TransitionHabit> get() = habits.filterIsInstance<TransitionHabit>()

    fun habit(id: String): Habit? = habits.firstOrNull { it.id == id }
    fun place(id: String): Place? = places.firstOrNull { it.id == id }
    fun medication(id: String): Medication? = medications.firstOrNull { it.id == id }
    fun routine(id: String): Routine? = routines.firstOrNull { it.id == id }
    fun alarm(id: String): MorningAlarm? = alarms.firstOrNull { it.id == id }

    /** Profile by explicit id, else the first profile for [type]. */
    fun profileFor(type: CueType, explicitId: String? = null): CueProfile? =
        explicitId?.let { id -> cueProfiles.firstOrNull { it.id == id } } ?: cueProfiles.firstOrNull { it.type == type }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Canonical JSON for config, engine state and ops: forward tolerant (unknown keys ignored, unknown enum
 * values coerced to the property default) and explicit (defaults are always encoded so documents are
 * stable and self-describing).
 */
val DayCueJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    classDiscriminator = "type"
    prettyPrint = false
}

object ConfigCodec {
    fun encode(config: DayCueConfig): String = DayCueJson.encodeToString(DayCueConfig.serializer(), config)
    fun decode(json: String): DayCueConfig = migrate(DayCueJson.decodeFromString(DayCueConfig.serializer(), json))

    /** Schema migrations hook. v1 is the first schema, so nothing to migrate yet. */
    fun migrate(config: DayCueConfig): DayCueConfig =
        if (config.schemaVersion < DayCueConfig.CURRENT_SCHEMA_VERSION) config.copy(schemaVersion = DayCueConfig.CURRENT_SCHEMA_VERSION) else config
}
