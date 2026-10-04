package app.daycue.facade

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.daycue.AppContainer
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Place
import app.daycue.domain.edit.ConfigOp
import app.daycue.engine.ApplyOutcome
import app.daycue.integrations.location.CurrentLocationResult
import app.daycue.integrations.location.GeofenceStatus
import app.daycue.integrations.location.LocationAccess
import app.daycue.integrations.location.LocationAccessState
import app.daycue.integrations.location.LocationPermissionStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Where a location "fix" button should send the user. */
enum class LocationFix { AppSettings, LocationSettings, PlayServices }

/**
 * Places + location permissions for the UI (docs/architecture/APP_API.md §10). Every edit goes through
 * `ConfigOp` (`UpsertPlace`, `SetPlaceLocation`, `DeletePlace`); geofences follow the config automatically.
 */
class PlacesFacade internal constructor(private val c: AppContainer, private val f: DayCueFacade) {

    val places: Flow<List<Place>> get() = c.host.snapshot.filterNotNull().map { it.config.places }.distinctUntilChanged()

    suspend fun upsertPlace(place: Place, baseVersion: Long? = null): ApplyOutcome = f.apply(listOf(ConfigOp.UpsertPlace(place)), baseVersion)

    /** Set / move / clear (`center = null` makes the place inactive: no geofence). */
    suspend fun setPlaceLocation(id: String, center: GeoPoint?, radiusM: Int? = null, baseVersion: Long? = null): ApplyOutcome =
        f.apply(listOf(ConfigOp.SetPlaceLocation(id, center, radiusM)), baseVersion)

    suspend fun deletePlace(id: String, baseVersion: Long? = null): ApplyOutcome = f.apply(listOf(ConfigOp.DeletePlace(id)), baseVersion)

    /**
     * "Use current location": one high-accuracy fix (cached up to 60 s accepted), with accuracy. Show the
     * accuracy; when `precise == false` (approximate grant) say the fix is too coarse for a place circle.
     */
    suspend fun currentLocation(): CurrentLocationResult = c.location.currentLocation().get(highAccuracy = true)

    // ---- Permissions / readiness ----

    /** Live grant state (refresh with [refreshAccess] in `onResume` and after every permission result). */
    val access: StateFlow<LocationAccessState> get() = c.location.access

    /** Last geofence registration outcome (`mode`, `registered`, `detail`). */
    val geofenceStatus: StateFlow<GeofenceStatus?> get() = c.location.status

    /** Re-reads permissions and re-registers geofences / activity transitions if anything changed. */
    suspend fun refreshAccess(): LocationAccessState {
        c.location.sync(force = false, reason = "ui refresh")
        return c.location.access.value
    }

    /** Call with the result of any location / activity-recognition permission request. */
    suspend fun onPermissionsChanged(): LocationAccessState {
        c.location.sync(force = true, reason = "permission result")
        return c.location.access.value
    }

    /**
     * The next permission step and the exact permissions to pass to `RequestMultiplePermissions`. Android
     * rules: foreground first (fine + coarse together), background only afterwards and alone; on API 30+
     * the background request opens the settings page whose option is labelled [backgroundOptionLabel].
     */
    fun nextPermissionStep(): LocationPermissionStep = LocationAccess.read(c.app).nextStep

    /** Optional on-foot detection (CTX-6). Empty below API 29 (install-time permission). */
    fun activityRecognitionPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 29) listOf(android.Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()

    /** "Allow all the time" in the user's language (API 30+), for the pre-permission explanation. */
    fun backgroundOptionLabel(): CharSequence? =
        if (Build.VERSION.SDK_INT >= 30) c.app.packageManager.backgroundPermissionOptionLabel else null

    fun fixIntent(fix: LocationFix): Intent = when (fix) {
        LocationFix.AppSettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.app.packageName, null))
        LocationFix.LocationSettings -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        LocationFix.PlayServices -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.gms"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
