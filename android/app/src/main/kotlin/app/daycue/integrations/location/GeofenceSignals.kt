package app.daycue.integrations.location

import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.signal.LocationAvailability
import app.daycue.domain.signal.Signal
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import java.time.Duration
import java.time.Instant

/** What a geofence broadcast means for the engine (pure, unit-tested). */
data class GeofenceDecode(
    val signals: List<Signal>,
    /** The OS dropped our geofences (e.g. network location turned off): re-register when possible. */
    val reregister: Boolean = false,
    /** A transition arrived too late to be trusted as "now": ask for a fresh snapshot instead. */
    val refreshSnapshot: Boolean = false,
    val note: String,
)

object GeofenceSignals {
    /**
     * A transition older than this on arrival is not passed on (the documented OS latency is < 2 min
     * typical, up to ~6 min stationary). Older ones come out of a queue (process start, Doze) and would
     * fire the bottle cue (BTL-2) long after the departure; the domain also drops it on arrival.
     */
    val MAX_TRANSITION_AGE: Duration = Duration.ofMinutes(30)

    /** The triggering location's fix time is used as `observedAt` when plausible; otherwise the receive time. */
    fun observedAt(fixTimeMs: Long?, now: Instant): Instant {
        if (fixTimeMs == null || fixTimeMs <= 0) return now
        val t = Instant.ofEpochMilli(fixTimeMs)
        return if (t.isAfter(now.plusSeconds(60)) || t.isBefore(now.minus(Duration.ofHours(6)))) now else minOf(t, now)
    }

    fun kind(transition: Int): GeofenceTransitionKind? = when (transition) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransitionKind.Enter
        Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransitionKind.Exit
        Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceTransitionKind.Dwell
        else -> null
    }

    /**
     * @param errorCode `GeofencingEvent.errorCode` when `hasError()`, else null.
     * @param knownPlaceIds ids of active places in the current config (stale ids from a just-deleted place are ignored).
     */
    fun decode(
        errorCode: Int?, transition: Int, requestIds: List<String>, fixTimeMs: Long?, now: Instant, knownPlaceIds: Set<String>,
    ): GeofenceDecode {
        if (errorCode != null) {
            return when (errorCode) {
                // Documented: sent when the network location provider is disabled; all geofences are removed.
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> GeofenceDecode(listOf(LocationAvailability(false, now)), reregister = true, note = "GEOFENCE_NOT_AVAILABLE")
                GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION -> GeofenceDecode(listOf(LocationAvailability(false, now)), note = "INSUFFICIENT_LOCATION_PERMISSION")
                else -> GeofenceDecode(emptyList(), note = "error $errorCode")
            }
        }
        val kind = kind(transition) ?: return GeofenceDecode(emptyList(), note = "unknown transition $transition")
        val at = observedAt(fixTimeMs, now)
        val ids = requestIds.filter { it in knownPlaceIds }.distinct()
        if (ids.isEmpty()) return GeofenceDecode(emptyList(), note = "no known place in ${requestIds.size} ids")
        val expires = at.plus(MAX_TRANSITION_AGE)
        if (!now.isBefore(expires)) return GeofenceDecode(emptyList(), refreshSnapshot = true, note = "stale $kind observed $at")
        return GeofenceDecode(ids.map { GeofenceTransition(it, kind, at, expires) }, note = "$kind ${ids.size}")
    }
}
