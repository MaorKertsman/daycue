package app.daycue.integrations.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import app.daycue.system.runAsync
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.GeofencingEvent

/**
 * Geofence transitions from Play services (PendingIntent target, not exported). Converts them into
 * domain signals with `observedAt` = the triggering fix time and an expiry (see [GeofenceSignals]).
 * A geofence event is an FGS-start exemption, but nothing here needs one: the engine reduce and the
 * resulting notification fit in the receiver budget (`goAsync`).
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        val error = if (ev.hasError()) ev.errorCode else null
        val transition = ev.geofenceTransition
        val ids = ev.triggeringGeofences?.map { it.requestId }.orEmpty()
        val fixTime = ev.triggeringLocation?.time
        runAsync(context, "geofence") { app -> app.location.onGeofenceEvent(error, transition, ids, fixTime) }
    }
}

/** Activity Recognition transitions (optional, `ACTIVITY_RECOGNITION`). */
class MotionTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val events = result.transitionEvents.map { RawTransition(it.activityType, it.transitionType, it.elapsedRealTimeNanos) }
        runAsync(context, "activity transitions") { app -> app.location.onMotionEvents(events) }
    }
}

/**
 * Re-registration triggers that arrive while DayCue isn't running: boot (the OS drops geofences and
 * activity-transition requests), app update, and the location switch (`PROVIDERS_CHANGED` is on the
 * implicit-broadcast exception list). The calendar periodic work is (re)enqueued here too.
 */
class LocationSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {}
            else -> return
        }
        Log.i(LocationIntegration.TAG, "system broadcast $action")
        runAsync(context, "location $action", timeoutMs = 9_000) { app ->
            val boot = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
            if (boot) app.location.invalidateRegistrations()
            app.startIntegrations()
            app.location.sync(force = boot || app.location.access.value.mode != PlaceDetectionMode.Automatic, reason = action.substringAfterLast('.'))
        }
    }
}
