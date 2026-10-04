package app.daycue.integrations.location

import app.daycue.domain.signal.MotionActivity
import app.daycue.domain.signal.MotionKind
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import java.time.Duration
import java.time.Instant

/** One Activity Recognition transition, independent of the GMS class (for tests). */
data class RawTransition(val activityType: Int, val transitionType: Int, val elapsedRealtimeNanos: Long)

/**
 * Activity Recognition Transition API -> domain [MotionActivity] (CTX-6 `OutdoorWhenOnFoot`).
 *
 * - WALKING / RUNNING / ON_BICYCLE ENTER -> `OnFoot` at the transition instant.
 * - Their EXIT -> `OnFoot` at the exit instant: the user *was* on foot until then, so the domain's
 *   45-minute hold (`onFootHoldMin`) counts from the end of the walk, not from its start.
 * - IN_VEHICLE ENTER -> `InVehicle` (ends any on-foot run; in-vehicle is never Outdoor).
 * - STILL ENTER -> `Still` (the domain lets the hold run out; it never flips Environment by itself).
 * - Everything else is ignored.
 *
 * The transition API reports changes only, never "still walking": a single walk longer than the hold
 * without any transition loses Outdoor (documented in FEASIBILITY.md §5).
 */
object MotionSignals {
    private val onFoot = setOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE)

    /** Transitions we register for. */
    val requested: List<Pair<Int, Int>> =
        onFoot.flatMap { listOf(it to ActivityTransition.ACTIVITY_TRANSITION_ENTER, it to ActivityTransition.ACTIVITY_TRANSITION_EXIT) } +
            listOf(DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER, DetectedActivity.STILL to ActivityTransition.ACTIVITY_TRANSITION_ENTER)

    fun instantOf(eventElapsedNanos: Long, now: Instant, nowElapsedNanos: Long): Instant {
        val ago = (nowElapsedNanos - eventElapsedNanos).coerceAtLeast(0)
        return now.minusNanos(ago)
    }

    fun map(events: List<RawTransition>, now: Instant, nowElapsedNanos: Long, expiryMin: Int): List<MotionActivity> =
        events.sortedBy { it.elapsedRealtimeNanos }.mapNotNull { e ->
            val kind = when {
                e.activityType in onFoot -> MotionKind.OnFoot
                e.activityType == DetectedActivity.IN_VEHICLE && e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> MotionKind.InVehicle
                e.activityType == DetectedActivity.STILL && e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> MotionKind.Still
                else -> null
            } ?: return@mapNotNull null
            val at = instantOf(e.elapsedRealtimeNanos, now, nowElapsedNanos)
            MotionActivity(kind, at, at.plus(Duration.ofMinutes(expiryMin.toLong())))
        }
}
