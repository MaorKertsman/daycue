@file:UseSerializers(InstantSerializer::class)

package app.daycue.domain.config

import app.daycue.domain.time.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

// ---- Context vocabulary shared by config and engine (PRODUCT §1) ----

@Serializable
enum class Confidence { Low, Medium, High;
    fun atLeast(other: Confidence) = ordinal >= other.ordinal
}

@Serializable
enum class Environment { Indoor, Outdoor, Unknown }

@Serializable
enum class Activity { Working, Studying, Meeting, RoutineRunning, Inactive, Unknown }

@Serializable
enum class SessionKind { Working, Studying;
    fun activity(): Activity = if (this == Working) Activity.Working else Activity.Studying
}

/**
 * Context condition for an item (PRODUCT §1, "Item conditions require at least Medium unless stated").
 * Each non-null dimension constrains the context; null = any value. A constrained dimension whose current
 * value is Unknown (or below [minConfidence]) matches only if [unknownMatches].
 */
@Serializable
data class ContextCondition(
    val environments: Set<Environment>? = null,
    val places: Set<String>? = null,
    val activities: Set<Activity>? = null,
    val minConfidence: Confidence = Confidence.Medium,
    val unknownMatches: Boolean = false,
) {
    /** True when no dimension is constrained: the item has no "condition stretch" semantics. */
    val isAny: Boolean get() = environments == null && places == null && activities == null

    companion object {
        val ANY = ContextCondition(unknownMatches = true)
        val OUTDOOR = ContextCondition(environments = setOf(Environment.Outdoor))
    }
}

/** GEN-4. */
@Serializable
data class RepeatPolicy(val everyMin: Int = 20, val maxRepeats: Int = 0) {
    companion object { val NONE = RepeatPolicy(20, 0) }
}

/**
 * A pause stored in config (single source of truth, editable from UI / MCP via `ConfigOp.SetPause`).
 * Medication can't be paused this way (MED-9: pausing a medication is an end-date edit).
 */
@Serializable
sealed interface PauseSpec {
    val setAt: Instant

    /** Paused until a fixed instant ("1 h", "2 h", "rest of today", custom). */
    @Serializable @SerialName("until")
    data class Until(override val setAt: Instant, val until: Instant) : PauseSpec

    /** Paused until the item's condition stops holding (SUN-10 "until Outdoor ends"), capped. */
    @Serializable @SerialName("untilConditionEnds")
    data class UntilConditionEnds(override val setAt: Instant, val cap: Instant) : PauseSpec

    /** Paused until explicitly resumed. */
    @Serializable @SerialName("indefinite")
    data class Indefinite(override val setAt: Instant) : PauseSpec
}

/** Bilingual user text. Missing language falls back to the other one. */
@Serializable
data class LocalizedText(val en: String = "", val he: String = "") {
    fun get(lang: Language): String = when (lang) {
        Language.en -> en.ifBlank { he }
        Language.he -> he.ifBlank { en }
    }
    companion object { fun of(en: String, he: String) = LocalizedText(en, he) }
}

@Suppress("EnumEntryName")
@Serializable
enum class Language { en, he }

/** Cue types with fixed priorities (PRODUCT §8.1, 1 = highest). */
@Serializable
enum class CueType(val priority: Int, val channelId: String) {
    Alarm(1, "alarm"),
    Medication(2, "medication"),
    RoutineStep(3, "routine"),
    Calendar(4, "calendar"),
    WaterBottle(5, "bottle"),
    Sunscreen(6, "sunscreen"),
    Posture(7, "posture"),
    Hydration(8, "hydration"),
    Habit(8, "habit"),
    /** Session suggestion / "Not working" undo (WRK-2), routine start prompts, informational notices. */
    Notice(9, "notice"),
}
