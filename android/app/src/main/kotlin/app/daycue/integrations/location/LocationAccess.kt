package app.daycue.integrations.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

enum class LocationGrant { None, Approximate, Precise }

/** What the user has granted / the device provides. Pure data; [LocationAccess.read] fills it. */
data class LocationAccessState(
    val foreground: LocationGrant,
    /** `ACCESS_BACKGROUND_LOCATION` (always true below API 29, where foreground covers background). */
    val background: Boolean,
    val locationEnabled: Boolean,
    val playServices: Boolean,
    /** `ACTIVITY_RECOGNITION` (API 29+; below, the install-time GMS permission). */
    val activityRecognition: Boolean,
    val sdkInt: Int,
) {
    /** How place detection runs. See [degradations] for the user-visible consequence. */
    val mode: PlaceDetectionMode get() = when {
        !playServices -> PlaceDetectionMode.Off
        foreground == LocationGrant.None -> PlaceDetectionMode.Off
        // Geofencing needs ACCESS_FINE_LOCATION (official geofencing guide); approximate-only fails to register.
        foreground == LocationGrant.Approximate -> PlaceDetectionMode.Off
        !background -> PlaceDetectionMode.Off
        !locationEnabled -> PlaceDetectionMode.Paused
        else -> PlaceDetectionMode.Automatic
    }

    /**
     * The next runtime-permission step, in the order Android requires: foreground (fine + coarse in one
     * request; the user may still pick "approximate") -> precise upgrade -> background as a **separate**
     * request (API 30+ sends the user to the settings page; the option label is
     * `PackageManager.getBackgroundPermissionOptionLabel()`).
     */
    val nextStep: LocationPermissionStep get() = when {
        foreground == LocationGrant.None -> LocationPermissionStep.Foreground
        foreground == LocationGrant.Approximate -> LocationPermissionStep.Precise
        !background -> LocationPermissionStep.Background
        else -> LocationPermissionStep.Done
    }

    val degradations: List<LocationDegradation> get() = buildList {
        if (!playServices) add(LocationDegradation.NoPlayServices)
        when (foreground) {
            LocationGrant.None -> add(LocationDegradation.NoLocationPermission)
            LocationGrant.Approximate -> add(LocationDegradation.ApproximateOnly)
            LocationGrant.Precise -> if (!background) add(LocationDegradation.ForegroundOnly)
        }
        if (!locationEnabled) add(LocationDegradation.LocationServicesOff)
        if (!activityRecognition) add(LocationDegradation.NoActivityRecognition)
    }
}

enum class PlaceDetectionMode {
    /** Geofences registered; Place follows the OS transitions + the domain's dwell/hysteresis. */
    Automatic,
    /** Permissions fine but the location switch is off: Place = Unknown until it is turned back on. */
    Paused,
    /** No geofences. Place = Unknown; manual context, Leaving now, scheduled departures keep working (scenario 6). */
    Off,
}

enum class LocationPermissionStep(val permissions: List<String>) {
    Foreground(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Precise(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Background(if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()),
    Done(emptyList()),
}

/**
 * Exactly what degrades (machine-readable; the UI words it):
 * - [NoLocationPermission] / [NoPlayServices]: no automatic Place at all; "Use current location" unavailable.
 * - [ApproximateOnly]: no geofences (registration requires precise); "Use current location" returns a
 *   ~1-3 km fix (accuracy is reported) which is not good enough to place a 150 m circle.
 * - [ForegroundOnly]: precise location while the app is open only. DayCue does **not** register geofences
 *   (Android 10+ needs background location to deliver transitions to a closed app) and does **not**
 *   poll location, so Place stays Unknown: items conditioned on a place follow their `unknownMatches`,
 *   geofence-exit bottle cues never fire, sessions never auto-start. "Use current location" works.
 * - [LocationServicesOff]: like ForegroundOnly until the switch is turned on (re-registered automatically).
 * - [NoActivityRecognition]: `OutdoorWhenOnFoot` behaves as `Unknown` away from saved places (CTX-6).
 */
enum class LocationDegradation { NoPlayServices, NoLocationPermission, ApproximateOnly, ForegroundOnly, LocationServicesOff, NoActivityRecognition }

object LocationAccess {
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun playServicesAvailable(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun read(context: Context): LocationAccessState {
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fg = when { fine -> LocationGrant.Precise; coarse -> LocationGrant.Approximate; else -> LocationGrant.None }
        val bg = if (Build.VERSION.SDK_INT >= 29) granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) else fg != LocationGrant.None
        val lm = context.getSystemService(LocationManager::class.java)
        val enabled = lm != null && LocationManagerCompat.isLocationEnabled(lm)
        val ar = if (Build.VERSION.SDK_INT >= 29) granted(context, Manifest.permission.ACTIVITY_RECOGNITION) else true
        return LocationAccessState(fg, bg, enabled, playServicesAvailable(context), ar, Build.VERSION.SDK_INT)
    }
}
