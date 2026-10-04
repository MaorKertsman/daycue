package app.daycue.domain.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class CalendarReminderSupplementPolicy { SupplementOnMatch, OnlyWhenNoReminder, Ignore }
@Serializable enum class CalendarPreferenceMode { Always, Never, Rules }
@Serializable enum class TentativeHandling { Accepted, Exclude }
@Serializable enum class EventAvailability { Busy, Free }

/** One content condition of a step-3 rule. A rule matches if ANY of its conditions match. */
@Serializable
sealed interface CalendarCondition {
    /** At least [min] attendees besides self. */
    @Serializable @SerialName("attendees") data class Attendees(val min: Int = 1) : CalendarCondition
    @Serializable @SerialName("conferencing") data object ConferencingLink : CalendarCondition
    /** Case-insensitive substring match on the title (titles are data, CAL-5). */
    @Serializable @SerialName("keywords") data class Keywords(val words: List<String>) : CalendarCondition
    @Serializable @SerialName("color") data class Color(val colors: List<String>) : CalendarCondition
    @Serializable @SerialName("availability") data class Availability(val availability: EventAvailability) : CalendarCondition
}

/** Step-3 content rule (PRODUCT §9.2). Empty [leadsMin] with [noCue] = decisive "no cue". */
@Serializable
data class CalendarRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val anyOf: List<CalendarCondition>,
    val leadsMin: List<Int> = emptyList(),
    val kind: LocalizedText = LocalizedText(),
    val noCue: Boolean = false,
    /** CAL-4: a matching in-progress busy event sets Activity = Meeting. */
    val isMeeting: Boolean = false,
)

@Serializable
data class CalendarPreference(val calendarId: String, val mode: CalendarPreferenceMode = CalendarPreferenceMode.Rules, val leadsMin: List<Int> = listOf(10))

@Serializable enum class OverrideScope { Instance, Series }

@Serializable
sealed interface EventDecisionOverride {
    @Serializable @SerialName("always") data class Always(val leadsMin: List<Int>, val asMeeting: Boolean = false) : EventDecisionOverride
    @Serializable @SerialName("never") data object Never : EventDecisionOverride
}

/** Step-1 override (CAL-1). [key] is the instance key or the series id depending on [scope]. */
@Serializable
data class EventOverride(val key: String, val scope: OverrideScope, val decision: EventDecisionOverride)

@Serializable
sealed interface CalendarDefaultPolicy {
    @Serializable @SerialName("noCue") data object NoCue : CalendarDefaultPolicy
    @Serializable @SerialName("cue") data class Cue(val leadsMin: List<Int>) : CalendarDefaultPolicy
}

/** PRODUCT §9. */
@Serializable
data class CalendarConfig(
    val calendars: List<CalendarPreference> = emptyList(),
    val syncHorizonDays: Int = 7,
    val maxCacheAgeHours: Int = 24,
    val supplement: CalendarReminderSupplementPolicy = CalendarReminderSupplementPolicy.SupplementOnMatch,
    val speakTitles: Boolean = false,
    val showTitlesOnLockScreen: Boolean = false,
    val tentative: TentativeHandling = TentativeHandling.Accepted,
    val rules: List<CalendarRule> = emptyList(),
    val defaultPolicy: CalendarDefaultPolicy = CalendarDefaultPolicy.NoCue,
    val overrides: List<EventOverride> = emptyList(),
    val snoozeMin: Int = 5,
    val phraseTemplate: LocalizedText = LocalizedText(
        en = "You have a {kind} in {minutes} minutes",
        he = "יש לך {kind} בעוד {minutes} דקות",
    ),
    val cueProfileId: String? = null,
)
