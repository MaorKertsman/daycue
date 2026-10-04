package app.daycue.integrations.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.daycue.domain.config.AwayEnvironmentPolicy
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.engine.Event
import app.daycue.domain.signal.MotionAvailability
import app.daycue.domain.signal.LocationAvailability
import app.daycue.domain.signal.Signal
import app.daycue.engine.EngineHost
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** Last registration outcome, for the facade / readiness (machine-readable). */
data class GeofenceStatus(
    val mode: PlaceDetectionMode,
    val registered: Int,
    val at: Instant,
    /** e.g. `ok`, `no_places`, `no_permission`, `gms_error:1004`. */
    val detail: String,
    val skippedPlaceIds: List<String> = emptyList(),
)

/**
 * Saved places -> OS geofences, and optional Activity Recognition transitions (PRODUCT §1.1, CTX-6).
 *
 * No continuous location sampling and no foreground service: geofences and activity transitions are
 * OS-side registrations that wake the app through PendingIntent broadcasts. The only location
 * requests are one-shot fixes (snapshot after (re)registration, "Use current location").
 *
 * Re-registration triggers: process start ([start]), config change (place set / dwell / away policy),
 * `BOOT_COMPLETED` (OS clears geofences), `MY_PACKAGE_REPLACED`, `PROVIDERS_CHANGED` (location switch back
 * on), `GEOFENCE_NOT_AVAILABLE`, permission changes reported by the UI, app open.
 */
class LocationIntegration(
    private val context: Context,
    private val host: EngineHost,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("daycue_location", Context.MODE_PRIVATE)
    private val current = CurrentLocation(context)
    private val _status = MutableStateFlow<GeofenceStatus?>(null)
    val status: StateFlow<GeofenceStatus?> = _status.asStateFlow()
    private val _access = MutableStateFlow(LocationAccess.read(context))
    val access: StateFlow<LocationAccessState> = _access.asStateFlow()
    @Volatile private var started = false

    fun currentLocation(): CurrentLocation = current

    /** Idempotent. Watches the config for place / rule changes. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            host.snapshot.filterNotNull().map { regKey(it.config) }.distinctUntilChanged().drop(1).collect {
                sync(force = false, reason = "config")
            }
        }
        scope.launch { sync(force = false, reason = "process start") }
    }

    private fun regKey(c: DayCueConfig) = GeofencePlanner.plan(c).fingerprint + "#" + c.contextRules.awayEnvironment

    fun syncAsync(force: Boolean, reason: String) { scope.launch { sync(force, reason) } }

    /** Brings OS registrations in line with config + permissions. [force] re-registers even if unchanged. */
    suspend fun sync(force: Boolean, reason: String): GeofenceStatus = mutex.withLock {
        val snap = host.ensureLoaded()
        val access = LocationAccess.read(context).also { _access.value = it }
        val plan = GeofencePlanner.plan(snap.config)
        val st = syncGeofences(plan, access, force, reason, snap.state.context.locationAvailable)
        syncMotion(snap.config, access, force, snap.state.context.motionAvailable)
        _status.value = st
        Log.i(TAG, "location sync ($reason, force=$force): ${st.mode} ${st.detail} registered=${st.registered}")
        st
    }

    @SuppressLint("MissingPermission")
    private suspend fun syncGeofences(plan: GeofencePlan, access: LocationAccessState, force: Boolean, reason: String, engineAvailable: Boolean): GeofenceStatus {
        val now = Instant.now()
        val mode = access.mode
        if (mode != PlaceDetectionMode.Automatic || plan.specs.isEmpty()) {
            if (access.playServices && prefs.getString(KEY_FP, null) != null) {
                runCatching { LocationServices.getGeofencingClient(context).removeGeofences(geofencePendingIntent(context)).awaitResult() }
                prefs.edit().remove(KEY_FP).apply()
            }
            val detail = when {
                plan.specs.isEmpty() -> "no_places"
                !access.playServices -> "no_play_services"
                access.foreground == LocationGrant.None -> "no_permission"
                access.foreground == LocationGrant.Approximate -> "approximate_only"
                !access.background -> "no_background"
                else -> "location_off"
            }
            // Place = Unknown immediately when location is unusable (PRODUCT §1.1). With no places nothing depends on it.
            if (plan.specs.isNotEmpty() && engineAvailable) dispatch(LocationAvailability(false, now))
            return GeofenceStatus(if (mode == PlaceDetectionMode.Automatic) PlaceDetectionMode.Off else mode, 0, now, detail, plan.skipped)
        }
        if (!force && prefs.getString(KEY_FP, null) == plan.fingerprint) {
            if (!engineAvailable) dispatch(LocationAvailability(true, now))
            return GeofenceStatus(mode, plan.specs.size, now, "unchanged", plan.skipped)
        }
        val client = LocationServices.getGeofencingClient(context)
        val pi = geofencePendingIntent(context)
        return try {
            runCatching { client.removeGeofences(pi).awaitResult() }
            val request = GeofencingRequest.Builder()
                // Already inside -> ENTER (and DWELL after the loitering delay) right after registration.
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(plan.specs.map { s ->
                    Geofence.Builder()
                        .setRequestId(s.placeId)
                        .setCircularRegion(s.lat, s.lng, s.radiusM)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
                        .setLoiteringDelay(s.loiteringDelayMs)
                        .build()
                })
                .build()
            client.addGeofences(request, pi).awaitResult()
            prefs.edit().putString(KEY_FP, plan.fingerprint).putLong(KEY_AT, now.toEpochMilli()).apply()
            dispatch(LocationAvailability(true, now))
            // Best effort, outside the lock and the receiver budget; the OS initial trigger covers "inside" anyway.
            scope.launch { refreshSnapshot(plan, "after register ($reason)") }
            GeofenceStatus(mode, plan.specs.size, now, "ok", plan.skipped)
        } catch (t: Throwable) {
            prefs.edit().remove(KEY_FP).apply()
            val code = (t as? com.google.android.gms.common.api.ApiException)?.statusCode
            Log.w(TAG, "addGeofences failed (code=$code)", t)
            if (engineAvailable) dispatch(LocationAvailability(false, now))
            GeofenceStatus(mode, 0, now, "gms_error:${code ?: t.javaClass.simpleName}", plan.skipped)
        }
    }

    /**
     * One-shot fix -> [app.daycue.domain.signal.GeofenceSnapshot] when unambiguous (see [Geo.snapshot]).
     * Balanced accuracy (Wi-Fi/cell), cached fixes up to 2 min accepted. Never repeated.
     */
    suspend fun refreshSnapshot(plan: GeofencePlan? = null, reason: String) {
        val p = plan ?: GeofencePlanner.plan(host.ensureLoaded().config)
        if (p.specs.isEmpty() || LocationAccess.read(context).mode != PlaceDetectionMode.Automatic) return
        // Balanced (Wi-Fi/cell) first; GPS once only if that yields nothing (no network location, e.g. the emulator).
        val first = current.get(highAccuracy = false, maxAgeMs = 120_000, timeoutMs = 15_000)
        val res = if (first is CurrentLocationResult.Unavailable) current.get(highAccuracy = true, maxAgeMs = 120_000, timeoutMs = 20_000) else first
        when (val r = res) {
            is CurrentLocationResult.Ok -> {
                val snap = Geo.snapshot(p, r.fix, r.fix.at.plus(SNAPSHOT_MAX_AGE))
                Log.i(TAG, "snapshot ($reason): acc=${r.fix.accuracyM}m -> ${snap?.insidePlaceIds?.size?.let { "$it inside" } ?: "ambiguous, not sent"}")
                if (snap != null) dispatch(snap)
            }
            else -> Log.i(TAG, "snapshot ($reason): no fix ($r)")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun syncMotion(config: DayCueConfig, access: LocationAccessState, force: Boolean, engineAvailable: Boolean) {
        val wanted = access.playServices && access.activityRecognition && config.contextRules.awayEnvironment == AwayEnvironmentPolicy.OutdoorWhenOnFoot
        val now = Instant.now()
        val client = if (access.playServices) ActivityRecognition.getClient(context) else null
        if (!wanted) {
            if (client != null && prefs.getBoolean(KEY_AR, false)) runCatching { client.removeActivityTransitionUpdates(motionPendingIntent(context)).awaitResult() }
            prefs.edit().putBoolean(KEY_AR, false).apply()
            if (engineAvailable) dispatch(MotionAvailability(false, now))
            return
        }
        if (!force && prefs.getBoolean(KEY_AR, false)) { if (!engineAvailable) dispatch(MotionAvailability(true, now)); return }
        try {
            val req = ActivityTransitionRequest(MotionSignals.requested.map { (type, tr) ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(tr).build()
            })
            client!!.requestActivityTransitionUpdates(req, motionPendingIntent(context)).awaitResult()
            prefs.edit().putBoolean(KEY_AR, true).apply()
            dispatch(MotionAvailability(true, now))
        } catch (t: Throwable) {
            Log.w(TAG, "activity transitions registration failed", t)
            prefs.edit().putBoolean(KEY_AR, false).apply()
            if (engineAvailable) dispatch(MotionAvailability(false, now))
        }
    }

    /** Called by [GeofenceBroadcastReceiver]. */
    suspend fun onGeofenceEvent(errorCode: Int?, transition: Int, ids: List<String>, fixTimeMs: Long?) {
        val cfg = host.ensureLoaded().config
        val d = GeofenceSignals.decode(errorCode, transition, ids, fixTimeMs, Instant.now(), cfg.places.filter { it.active }.map { it.id }.toSet())
        Log.i(TAG, "geofence event: ${d.note}")
        d.signals.forEach { dispatch(it) }
        if (d.reregister) { prefs.edit().remove(KEY_FP).apply() }
        if (d.refreshSnapshot) refreshSnapshot(reason = "stale transition")
    }

    /** Called by [MotionTransitionReceiver]. */
    suspend fun onMotionEvents(events: List<RawTransition>) {
        val cfg = host.ensureLoaded().config
        val signals = MotionSignals.map(events, Instant.now(), SystemClock.elapsedRealtimeNanos(), cfg.contextRules.activityRecognitionExpiryMin)
        Log.i(TAG, "activity transitions: ${events.size} -> ${signals.map { it.kind }}")
        signals.forEach { dispatch(it) }
    }

    /** After boot / OS-side loss: registrations are gone. */
    fun invalidateRegistrations() { prefs.edit().remove(KEY_FP).putBoolean(KEY_AR, false).apply() }

    private suspend fun dispatch(s: Signal) { host.dispatch(Event.SignalObserved(s)) }

    companion object {
        const val TAG = "DayCueLocation"
        private const val KEY_FP = "geofence_fingerprint"
        private const val KEY_AT = "geofence_registered_at_ms"
        private const val KEY_AR = "activity_transitions_registered"
        val SNAPSHOT_MAX_AGE: Duration = Duration.ofMinutes(10)

        fun geofencePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 41, Intent(context, GeofenceBroadcastReceiver::class.java),
            // Play services adds the event extras: must be mutable (API 31+).
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        fun motionPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 42, Intent(context, MotionTransitionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
