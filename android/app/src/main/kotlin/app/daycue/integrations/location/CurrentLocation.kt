package app.daycue.integrations.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Result of the one-shot "Use current location" request (adding a place, or a snapshot after registration). */
sealed interface CurrentLocationResult {
    /** [precise] = false when only approximate permission was granted: the fix is coarse (km scale). */
    data class Ok(val fix: Fix, val precise: Boolean) : CurrentLocationResult
    data object NoPermission : CurrentLocationResult
    data object PlayServicesMissing : CurrentLocationResult
    data object LocationOff : CurrentLocationResult
    /** No fix within the time budget (indoors, airplane mode). */
    data object Unavailable : CurrentLocationResult
    data class Failed(val reason: String) : CurrentLocationResult
}

/**
 * One-shot fused location (`getCurrentLocation`). Never a continuous request: one fix, then the
 * request is gone. Accepts a cached fix up to [maxAgeMs] old (cheap, no GPS spin-up when fresh).
 */
class CurrentLocation(private val context: Context) {

    @SuppressLint("MissingPermission") // checked via LocationAccess.read
    suspend fun get(highAccuracy: Boolean = true, maxAgeMs: Long = 60_000, timeoutMs: Long = 20_000): CurrentLocationResult {
        val access = LocationAccess.read(context)
        if (!access.playServices) return CurrentLocationResult.PlayServicesMissing
        if (access.foreground == LocationGrant.None) return CurrentLocationResult.NoPermission
        if (!access.locationEnabled) return CurrentLocationResult.LocationOff
        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = CurrentLocationRequest.Builder()
            .setPriority(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(timeoutMs)
            .build()
        val cts = CancellationTokenSource()
        return try {
            val loc = withTimeoutOrNull(timeoutMs + 2_000) { client.getCurrentLocation(req, cts.token).awaitResult() }
            if (loc == null) { cts.cancel(); CurrentLocationResult.Unavailable }
            else CurrentLocationResult.Ok(
                Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE,
                    GeofenceSignals.observedAt(loc.time, Instant.now())),
                precise = access.foreground == LocationGrant.Precise,
            )
        } catch (t: Throwable) {
            cts.cancel()
            CurrentLocationResult.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }
}
