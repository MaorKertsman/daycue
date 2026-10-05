package app.daycue.ui.setup

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.daycue.R
import app.daycue.domain.config.ContextRules
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Place
import app.daycue.domain.config.SessionRules
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.OverrideDuration
import app.daycue.facade.LocationFix
import app.daycue.integrations.location.CurrentLocationResult
import app.daycue.integrations.location.GeofenceStatus
import app.daycue.integrations.location.LocationAccessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Places screens show; `null` config means "still loading" (skeleton, never a spinner). */
data class PlacesUi(
    val config: DayCueConfig? = null,
    val access: LocationAccessState? = null,
    val geofence: GeofenceStatus? = null,
    /** The saved place the context engine says the owner is at right now, if any. */
    val herePlaceId: String? = null,
    val hereElsewhere: Boolean = false,
    val detectionPaused: Boolean = false,
) {
    val places: List<Place> get() = config?.places.orEmpty()
}

/** Places, location permissions and the context / session rules. All edits go through the facade (one op each). */
class PlacesViewModel(app: Application) : SetupViewModel(app) {

    // The saved config is local data: it is on the first frame. The context line (which is computed) may arrive a moment
    // later, so it must never hold the screen back (REVIEW-2 C13).
    private val contextFlow = facade.today(60_000).map { it.context as app.daycue.domain.context.InferredContext? }.onStart { emit(null) }

    val ui: StateFlow<PlacesUi> = combine(
        facade.config.map<DayCueConfig, DayCueConfig?> { it }.onStart { emit(facade.snapshot.value?.config) },
        facade.places.access, facade.places.geofenceStatus, contextFlow,
    ) { cfg, access, geofence, ctx ->
        PlacesUi(
            config = cfg, access = access, geofence = geofence,
            herePlaceId = ctx?.place?.value?.takeIf { it.kind == PlaceKind.Saved }?.placeId,
            hereElsewhere = ctx?.place?.value?.kind == PlaceKind.Elsewhere,
            detectionPaused = ctx?.detectionPaused ?: false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUi(config = facade.snapshot.value?.config, access = facade.places.access.value))

    /** The last "use current location" outcome, for the editor. */
    val lastFix = MutableStateFlow<CurrentLocationResult?>(null)
    val locating = MutableStateFlow(false)

    fun refreshAccess() { viewModelScope.launch { facade.places.refreshAccess() } }
    fun onPermissionsChanged() { viewModelScope.launch { facade.places.onPermissionsChanged() } }

    fun nextPermissionPermissions(): List<String> = facade.places.nextPermissionStep().permissions
    fun activityPermissions(): List<String> = facade.places.activityRecognitionPermissions()
    fun backgroundOptionLabel(): String? = facade.places.backgroundOptionLabel()?.toString()
    fun fixIntent(fix: LocationFix) = facade.places.fixIntent(fix)

    fun useCurrentLocation() {
        if (locating.value) return
        viewModelScope.launch {
            locating.value = true
            lastFix.value = facade.places.currentLocation()
            locating.value = false
        }
    }

    fun clearFix() { lastFix.value = null }

    /** Saves (adds or replaces) a place. The caller shows field errors from the result. */
    suspend fun savePlace(place: Place, announce: Boolean = false): EditResult =
        edit(listOf(ConfigOp.UpsertPlace(place)), if (announce) R.string.su_place_saved else null)

    suspend fun setLocation(id: String, center: GeoPoint?): EditResult =
        edit(listOf(ConfigOp.SetPlaceLocation(id, center)), R.string.su_place_location_saved)

    fun deletePlace(id: String) {
        viewModelScope.launch { edit(listOf(ConfigOp.DeletePlace(id)), R.string.su_place_deleted) }
    }

    fun pauseDetection(duration: OverrideDuration) { viewModelScope.launch { facade.pauseAutoDetection(duration) } }
    fun resumeDetection() { viewModelScope.launch { facade.resumeAutoDetection() } }

    suspend fun saveContextRules(rules: ContextRules): EditResult = edit(listOf(ConfigOp.SetContextRules(rules)))
    suspend fun saveSessionRules(rules: SessionRules): EditResult = edit(listOf(ConfigOp.SetSessionRules(rules)))
}
