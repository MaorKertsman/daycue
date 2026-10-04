package app.daycue.integrations.location

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.signal.GeofenceSnapshot
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One OS geofence for one saved place (pure; mapped to `com.google.android.gms.location.Geofence` by
 * [GeofenceRegistrar]). Request id = place id.
 *
 * Transitions: ENTER | EXIT | DWELL with `loiteringDelay = placeEnterDwellMin` so the OS DWELL lands
 * exactly when the domain would confirm the place (CTX-2; the domain treats an OS dwell as "already
 * loitered"). Exit dwell / hysteresis (CTX-3) stays in the domain: the raw EXIT is passed through
 * immediately (BTL-2 needs it raw).
 */
data class GeofenceSpec(val placeId: String, val lat: Double, val lng: Double, val radiusM: Float, val loiteringDelayMs: Int)

data class GeofencePlan(val specs: List<GeofenceSpec>, val skipped: List<String>) {
    /** Stable identity of what is registered; re-registration is skipped when unchanged (except after boot etc.). */
    val fingerprint: String = specs.sortedBy { it.placeId }.joinToString("|") { "${it.placeId}:${it.lat}:${it.lng}:${it.radiusM}:${it.loiteringDelayMs}" }
}

object GeofencePlanner {
    /** Google Play services limit: 100 geofences per app per device user. */
    const val MAX_GEOFENCES = 100

    fun plan(config: DayCueConfig): GeofencePlan {
        val dwellMs = config.contextRules.placeEnterDwellMin.coerceIn(1, 15) * 60_000
        val active = config.places.filter { it.center != null }
        val specs = active.take(MAX_GEOFENCES).map { p ->
            GeofenceSpec(p.id, p.center!!.lat, p.center!!.lng, p.radiusM.coerceIn(50, 1000).toFloat(), dwellMs)
        }
        return GeofencePlan(specs, active.drop(MAX_GEOFENCES).map { it.id })
    }
}

/** A one-shot location fix (fused provider). [accuracyM] = 68 % radius as reported by Android. */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float, val at: Instant)

object Geo {
    private const val EARTH_M = 6_371_008.8

    fun distanceM(a: GeoPoint, lat: Double, lng: Double): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(lat)
        val dp = p2 - p1; val dl = Math.toRadians(lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(min(1.0, sqrt(h)))
    }

    /** Fixes worse than this never produce a snapshot ("unknown beats wrong"). */
    const val MAX_SNAPSHOT_ACCURACY_M = 250f

    /**
     * Converts a fix into a [GeofenceSnapshot] only when every saved place is unambiguous: inside when the
     * whole accuracy circle is inside (`d + acc <= r`), outside when it is wholly outside (`d - acc > r`).
     * Anything on a boundary returns null: Place then waits for the OS initial trigger / next transition
     * instead of guessing (PRODUCT §1.1 "after boot Unknown until the OS initial trigger").
     */
    fun snapshot(plan: GeofencePlan, fix: Fix, expiresAt: Instant? = null): GeofenceSnapshot? {
        if (fix.accuracyM > MAX_SNAPSHOT_ACCURACY_M || fix.accuracyM < 0f) return null
        val inside = mutableSetOf<String>()
        for (s in plan.specs) {
            val d = distanceM(GeoPoint(s.lat, s.lng), fix.lat, fix.lng)
            when {
                d + fix.accuracyM <= s.radiusM -> inside += s.placeId
                d - fix.accuracyM > s.radiusM -> {}
                else -> return null
            }
        }
        return GeofenceSnapshot(inside, fix.at, expiresAt)
    }
}
