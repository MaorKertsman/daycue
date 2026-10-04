package app.daycue.integrations.location

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Place
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.signal.LocationAvailability
import app.daycue.domain.signal.MotionKind
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Synthetic coordinates only (an arbitrary point in the sea), never a real place. */
class LocationSignalsTest {
    private val c0 = GeoPoint(10.0, 20.0)
    private val now = Instant.parse("2026-10-04T08:00:00Z")

    private fun cfg(vararg places: Place) = Defaults.config().copy(places = places.toList())

    // ---- plan ----

    @Test fun planUsesActivePlacesAndMatchesLoiteringDelayToEnterDwell() {
        val c = cfg(Place("a", "A", center = c0, radiusM = 150), Place("b", "B")) // b has no center: inactive
        val p = GeofencePlanner.plan(c.copy(contextRules = c.contextRules.copy(placeEnterDwellMin = 4)))
        assertEquals(listOf("a"), p.specs.map { it.placeId })
        assertEquals(4 * 60_000, p.specs.single().loiteringDelayMs)
        assertEquals(150f, p.specs.single().radiusM)
    }

    @Test fun planCapsAt100AndReportsSkipped() {
        val many = (1..103).map { Place("p$it", "P$it", center = GeoPoint(10.0 + it * 0.01, 20.0)) }
        val p = GeofencePlanner.plan(cfg(*many.toTypedArray()))
        assertEquals(100, p.specs.size)
        assertEquals(listOf("p101", "p102", "p103"), p.skipped)
    }

    @Test fun fingerprintChangesWithCenterRadiusOrDwellOnly() {
        val base = cfg(Place("a", "A", center = c0))
        val f0 = GeofencePlanner.plan(base).fingerprint
        assertEquals(f0, GeofencePlanner.plan(base.copy(places = listOf(base.places[0].copy(name = "Renamed")))).fingerprint)
        assertTrue(f0 != GeofencePlanner.plan(base.copy(places = listOf(base.places[0].copy(radiusM = 200)))).fingerprint)
        assertTrue(f0 != GeofencePlanner.plan(base.copy(contextRules = base.contextRules.copy(placeEnterDwellMin = 5))).fingerprint)
    }

    // ---- snapshot ----

    private val plan = GeofencePlanner.plan(cfg(Place("a", "A", center = c0, radiusM = 150), Place("b", "B", center = GeoPoint(10.05, 20.0), radiusM = 150)))
    private fun northOf(m: Double) = 10.0 + m / 111_195.0

    @Test fun snapshotInsideWhenAccuracyCircleFullyInside() {
        val s = Geo.snapshot(plan, Fix(northOf(50.0), 20.0, 30f, now))
        assertEquals(setOf("a"), s!!.insidePlaceIds)
        assertEquals(now, s.observedAt)
    }

    @Test fun snapshotElsewhereWhenFarFromAll() {
        assertEquals(emptySet<String>(), Geo.snapshot(plan, Fix(northOf(2_000.0), 20.0, 40f, now))!!.insidePlaceIds)
    }

    @Test fun snapshotAmbiguousOnBoundaryOrCoarseFix() {
        assertNull("boundary", Geo.snapshot(plan, Fix(northOf(140.0), 20.0, 30f, now)))
        assertNull("coarse", Geo.snapshot(plan, Fix(northOf(2_000.0), 20.0, 1_500f, now)))
    }

    @Test fun distanceIsHaversine() {
        val d = Geo.distanceM(c0, northOf(1_000.0), 20.0)
        assertTrue("$d", kotlin.math.abs(d - 1_000.0) < 1.0)
    }

    // ---- geofence decode ----

    private val known = setOf("a", "b")

    @Test fun transitionsMapToSignalsWithFixTimeAndExpiry() {
        val fixAt = now.minusSeconds(90)
        for ((t, k) in listOf(Geofence.GEOFENCE_TRANSITION_ENTER to GeofenceTransitionKind.Enter, Geofence.GEOFENCE_TRANSITION_EXIT to GeofenceTransitionKind.Exit, Geofence.GEOFENCE_TRANSITION_DWELL to GeofenceTransitionKind.Dwell)) {
            val d = GeofenceSignals.decode(null, t, listOf("a"), fixAt.toEpochMilli(), now, known)
            val s = d.signals.single() as GeofenceTransition
            assertEquals(k, s.kind); assertEquals("a", s.placeId); assertEquals(fixAt, s.observedAt)
            assertEquals(fixAt.plus(GeofenceSignals.MAX_TRANSITION_AGE), s.expiresAt)
        }
    }

    @Test fun implausibleFixTimeFallsBackToNow() {
        assertEquals(now, GeofenceSignals.observedAt(now.plusSeconds(3_600).toEpochMilli(), now))
        assertEquals(now, GeofenceSignals.observedAt(now.minus(Duration.ofDays(2)).toEpochMilli(), now))
        assertEquals(now, GeofenceSignals.observedAt(null, now))
        assertEquals(now, GeofenceSignals.observedAt(now.plusSeconds(20).toEpochMilli(), now)) // small clock skew clamps to now
    }

    @Test fun staleTransitionIsNotPassedOnButAsksForSnapshot() {
        val d = GeofenceSignals.decode(null, Geofence.GEOFENCE_TRANSITION_EXIT, listOf("a"), now.minus(Duration.ofMinutes(45)).toEpochMilli(), now, known)
        assertTrue(d.signals.isEmpty()); assertTrue(d.refreshSnapshot)
    }

    @Test fun unknownPlaceIdsAreDroppedAndMultipleIdsSplit() {
        val d = GeofenceSignals.decode(null, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("a", "deleted", "b", "a"), null, now, known)
        assertEquals(listOf("a", "b"), d.signals.map { (it as GeofenceTransition).placeId })
        assertTrue(GeofenceSignals.decode(null, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("deleted"), null, now, known).signals.isEmpty())
    }

    @Test fun notAvailableMakesPlaceUnknownAndRequestsReregistration() {
        val d = GeofenceSignals.decode(GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE, -1, emptyList(), null, now, known)
        assertEquals(listOf(LocationAvailability(false, now)), d.signals)
        assertTrue(d.reregister)
        val p = GeofenceSignals.decode(GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION, -1, emptyList(), null, now, known)
        assertEquals(listOf(LocationAvailability(false, now)), p.signals)
    }

    // ---- permissions model ----

    private fun access(fg: LocationGrant, bg: Boolean, on: Boolean = true, gms: Boolean = true, ar: Boolean = true) = LocationAccessState(fg, bg, on, gms, ar, 37)

    @Test fun progressivePermissionSteps() {
        assertEquals(LocationPermissionStep.Foreground, access(LocationGrant.None, false).nextStep)
        assertEquals(LocationPermissionStep.Precise, access(LocationGrant.Approximate, false).nextStep)
        assertEquals(LocationPermissionStep.Background, access(LocationGrant.Precise, false).nextStep)
        assertEquals(LocationPermissionStep.Done, access(LocationGrant.Precise, true).nextStep)
    }

    @Test fun modeAndDegradations() {
        assertEquals(PlaceDetectionMode.Automatic, access(LocationGrant.Precise, true).mode)
        assertEquals(PlaceDetectionMode.Paused, access(LocationGrant.Precise, true, on = false).mode)
        assertEquals(PlaceDetectionMode.Off, access(LocationGrant.Precise, false).mode)
        assertEquals(PlaceDetectionMode.Off, access(LocationGrant.Approximate, true).mode)
        assertEquals(PlaceDetectionMode.Off, access(LocationGrant.Precise, true, gms = false).mode)
        assertEquals(listOf(LocationDegradation.ForegroundOnly), access(LocationGrant.Precise, false).degradations)
        assertEquals(listOf(LocationDegradation.ApproximateOnly, LocationDegradation.NoActivityRecognition), access(LocationGrant.Approximate, false, ar = false).degradations)
    }

    // ---- activity transitions ----

    @Test fun motionTransitionsMapToDomainKinds() {
        val nowNanos = 10_000_000_000_000L
        val ago = { s: Long -> nowNanos - s * 1_000_000_000L }
        val out = MotionSignals.map(listOf(
            RawTransition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER, ago(600)),
            RawTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, ago(300)),
            RawTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_EXIT, ago(60)),
            RawTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, ago(30)),
            RawTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT, ago(10)),
        ).shuffled(java.util.Random(1)), now, nowNanos, expiryMin = 10)
        assertEquals(listOf(MotionKind.Still, MotionKind.OnFoot, MotionKind.OnFoot, MotionKind.InVehicle), out.map { it.kind })
        assertEquals(now.minusSeconds(300), out[1].observedAt)
        assertEquals(now.minusSeconds(60), out[2].observedAt) // walk end: hold counts from here
        assertEquals(out[1].observedAt.plus(Duration.ofMinutes(10)), out[1].expiresAt)
        assertNotNull(MotionSignals.requested.find { it.first == DetectedActivity.ON_BICYCLE })
    }
}
