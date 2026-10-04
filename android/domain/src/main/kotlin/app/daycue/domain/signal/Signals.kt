@file:UseSerializers(InstantSerializer::class)

package app.daycue.domain.signal

import app.daycue.domain.time.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * Observed signals (ARCHITECTURE §2 layer 2). Every signal has [observedAt]; [expiresAt] (when set)
 * is the instant after which it is stale and ignored (PRODUCT §1.1). Signals that are already stale
 * when they arrive are dropped.
 */
@Serializable
sealed interface Signal {
    val observedAt: Instant
    val expiresAt: Instant?
}

@Serializable enum class GeofenceTransitionKind { Enter, Exit, Dwell }

@Serializable @SerialName("geofence")
data class GeofenceTransition(
    val placeId: String,
    val kind: GeofenceTransitionKind,
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
) : Signal

/**
 * Result of (re-)registering geofences: the set of places the OS reports we're inside. An empty set means
 * "confidently outside every saved place" (Place = Elsewhere). After boot Place stays Unknown until this
 * or a transition arrives (PRODUCT §1.1).
 */
@Serializable @SerialName("geofenceSnapshot")
data class GeofenceSnapshot(
    val insidePlaceIds: Set<String>,
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
) : Signal

/** Location permission/service state. Off -> Place Unknown immediately. */
@Serializable @SerialName("locationAvailability")
data class LocationAvailability(
    val available: Boolean,
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
) : Signal

@Serializable enum class MotionKind { OnFoot, InVehicle, Still, Other }

/**
 * How a [MotionActivity] was produced (CTX-6, DOMAIN.md §3 "CTX-6 continuous walk").
 * - [Sample]: a point reading ("on foot at this instant"); it refreshes an ongoing walk.
 * - [Enter]: an activity-transition ENTER. For `OnFoot` the walk is *ongoing* until a contrary signal
 *   (Still / Other / InVehicle / an `OnFoot` [Exit]) or until `onFootOngoingMaxMin` after the last supporting
 *   on-foot signal, whichever is first — no periodic re-reports are needed.
 * - [Exit]: an activity-transition EXIT ("was on foot until this instant"); ends an ongoing walk, and the
 *   `onFootHoldMin` hold counts from here.
 */
@Serializable enum class MotionTransition { Sample, Enter, Exit }

/** Activity recognition reading (optional). Default expiry = observedAt + 10 min (`activityRecognitionExpiryMin`). */
@Serializable @SerialName("motion")
data class MotionActivity(
    val kind: MotionKind,
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
    val transition: MotionTransition = MotionTransition.Sample,
) : Signal

/** Activity-recognition permission state. Without it `OutdoorWhenOnFoot` behaves as `Unknown` (CTX-6). */
@Serializable @SerialName("motionAvailability")
data class MotionAvailability(
    val available: Boolean,
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
) : Signal

@Serializable enum class CompanionState { Active, Idle, Locked, Asleep }

/** Windows companion report (active/idle/locked/asleep only). Default expiry = observedAt + 3 min. */
@Serializable @SerialName("companion")
data class CompanionActivity(
    val state: CompanionState,
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
) : Signal

/**
 * The companion explicitly stopped reporting (paused by the owner, shutting down, unpaired). It retracts any
 * still-fresh earlier [CompanionActivity] observed at or before [observedAt]: Activity becomes Unknown at once
 * and an automatic session is suspended immediately (WRK-5) instead of after `companionStaleToSuspendMin`.
 * A later [CompanionActivity] starts a new track. Out-of-order (older than the last companion signal) is ignored.
 */
@Serializable @SerialName("companionGone")
data class CompanionGone(
    override val observedAt: Instant,
    override val expiresAt: Instant? = null,
) : Signal
