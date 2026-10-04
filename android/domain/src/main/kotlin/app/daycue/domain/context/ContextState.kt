@file:UseSerializers(InstantSerializer::class)

package app.daycue.domain.context

import app.daycue.domain.config.Activity
import app.daycue.domain.config.Confidence
import app.daycue.domain.config.Environment
import app.daycue.domain.config.SessionKind
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.time.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

@Serializable enum class PlaceKind { Saved, Elsewhere, Unknown }

@Serializable
data class PlaceValue(val kind: PlaceKind, val placeId: String? = null) {
    companion object {
        val UNKNOWN = PlaceValue(PlaceKind.Unknown)
        val ELSEWHERE = PlaceValue(PlaceKind.Elsewhere)
        fun saved(id: String) = PlaceValue(PlaceKind.Saved, id)
    }
    override fun toString(): String = if (kind == PlaceKind.Saved) "Saved($placeId)" else kind.name
}

@Serializable enum class ContextSource { Manual, Geofence, PlaceTypical, Motion, AssumedAway, Companion, Calendar, Routine, Session, DetectionPaused, None }

@Serializable
data class PlaceTrack(
    val value: PlaceValue = PlaceValue.UNKNOWN,
    val since: Instant? = null,
)

/** CTX-4/CTX-5 hysteresis on the automatic environment. */
@Serializable
data class EnvTrack(
    val value: Environment = Environment.Unknown,
    val confidence: Confidence = Confidence.Low,
    val since: Instant? = null,
    val source: ContextSource = ContextSource.None,
    /** Raw Outdoor is pending; it will be confirmed at this instant (CTX-4). */
    val outdoorConfirmAt: Instant? = null,
    /** Raw stopped being Outdoor at this instant, waiting for `outdoorExitDwell`. */
    val leaveCandidateSince: Instant? = null,
)

@Serializable
data class CompanionTrack(
    val state: CompanionState? = null,
    /** Start of the current uninterrupted run of [state]. */
    val stateSince: Instant? = null,
    val lastObservedAt: Instant? = null,
    val expiresAt: Instant? = null,
) {
    fun fresh(now: Instant): Boolean = expiresAt != null && now.isBefore(expiresAt)
}

@Serializable enum class SessionStatus { Active, Paused, Suspended }

@Serializable
data class Session(
    val kind: SessionKind,
    val placeId: String?,
    val manual: Boolean,
    val startedAt: Instant,
    val status: SessionStatus = SessionStatus.Active,
    val statusSince: Instant,
    /** Manual cap / For(d) end. */
    val capAt: Instant? = null,
)

@Serializable
data class EnvOverride(val value: Environment, val setAt: Instant, val expiresAt: Instant, val untilTransition: Boolean)

@Serializable
data class DetectionPause(val setAt: Instant, val until: Instant?)

/** Persisted context-inference state (layer 2 raw observations + layer 3 tracking). */
@Serializable
data class ContextState(
    val locationAvailable: Boolean = true,
    /** Since the last boot the OS has told us something about geofences (CTX: Unknown after boot until then). */
    val geofenceKnown: Boolean = false,
    /** Raw (unconfirmed) inside set: placeId -> raw enter instant. */
    val rawInside: Map<String, Instant> = emptyMap(),
    /** Raw exit instants (for CTX-3 confirmation). */
    val rawExitAt: Map<String, Instant> = emptyMap(),
    val snapshotAt: Instant? = null,
    val place: PlaceTrack = PlaceTrack(),
    val motionAvailable: Boolean = true,
    val onFootSince: Instant? = null,
    val lastOnFootAt: Instant? = null,
    val env: EnvTrack = EnvTrack(),
    val companion: CompanionTrack = CompanionTrack(),
    val session: Session? = null,
    val sessionSuppressedUntil: Map<String, Instant> = emptyMap(),
    val sessionSuggestedAt: Map<String, Instant> = emptyMap(),
    val envOverride: EnvOverride? = null,
    val detectionPause: DetectionPause? = null,
    /** Last meaningful transition (confirmed place change, CTX-3). */
    val lastTransitionAt: Instant? = null,
)

/** One inferred dimension with confidence, source and since (GEN-9 facts). */
@Serializable
data class Dim<T>(val value: T, val confidence: Confidence, val source: ContextSource, val since: Instant? = null)

/** Layer-3 snapshot used by every item. */
@Serializable
data class InferredContext(
    val place: Dim<PlaceValue>,
    val environment: Dim<Environment>,
    val activity: Dim<Activity>,
    val session: Session? = null,
    val meetingEventKey: String? = null,
    val meetingEndsAt: Instant? = null,
    val detectionPaused: Boolean = false,
) {
    val inMeeting: Boolean get() = activity.value == Activity.Meeting || meetingEventKey != null

    fun facts(): Map<String, String> = mapOf(
        "place" to "${place.value} (${place.confidence}, ${place.source})",
        "environment" to "${environment.value} (${environment.confidence}, ${environment.source})",
        "activity" to "${activity.value} (${activity.confidence}, ${activity.source})",
    )
}
