package app.daycue.integrations.location

import app.daycue.domain.signal.MotionActivity
import app.daycue.domain.signal.MotionKind
import app.daycue.domain.signal.MotionTransition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import java.time.Duration
import java.time.Instant

/** One Activity Recognition transition, independent of the GMS class (for tests). */
data class RawTransition(val activityType: Int, val transitionType: Int, val elapsedRealtimeNanos: Long)

/**
 * Activity Recognition Transition API -> domain [MotionActivity] (CTX-6 `OutdoorWhenOnFoot`, DOMAIN.md §3
 * "CTX-6 continuous walk").
 *
 * - WALKING / RUNNING / ON_BICYCLE ENTER -> `OnFoot` with `transition = Enter`: the domain treats the walk as
 *   *ongoing* until a contrary signal or `onFootOngoingMaxMin` (default 180 min) after the last supporting
 *   on-foot signal, so no periodic re-reports are needed and a long walk keeps Outdoor.
 * - Their EXIT -> `OnFoot` with `transition = Exit` at the exit instant: the walk ended then, and the
 *   45-minute hold (`onFootHoldMin`) counts from the end of the walk, not from its start.
 * - IN_VEHICLE ENTER -> `InVehicle`, `transition = Enter` (ends any on-foot run at once; in-vehicle is never Outdoor).
 * - STILL ENTER -> `Still`, `transition = Enter` (ends an ongoing walk; the domain never flips Environment by itself).
 * - Everything else is ignored.
 *
 * The transition API still reports changes only, never "still walking". A walk longer than
 * `onFootOngoingMaxMin` without any further signal loses Outdoor at that bound (FEASIBILITY.md §5).
 */
object MotionSignals {
    private val onFoot = setOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE)

    /** Transitions we register for. */
    val requested: List<Pair<Int, Int>> =
        onFoot.flatMap { listOf(it to ActivityTransition.ACTIVITY_TRANSITION_ENTER, it to ActivityTransition.ACTIVITY_TRANSITION_EXIT) } +
            listOf(DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER, DetectedActivity.STILL to ActivityTransition.ACTIVITY_TRANSITION_ENTER)

    /** A walk or a drive just started: a good moment for one location fix to confirm whether a saved place was left. */
    fun suggestsDeparture(m: MotionActivity): Boolean =
        m.transition == MotionTransition.Enter && (m.kind == MotionKind.OnFoot || m.kind == MotionKind.InVehicle)

    fun instantOf(eventElapsedNanos: Long, now: Instant, nowElapsedNanos: Long): Instant {
        val ago = (nowElapsedNanos - eventElapsedNanos).coerceAtLeast(0)
        return now.minusNanos(ago)
    }

    fun map(events: List<RawTransition>, now: Instant, nowElapsedNanos: Long, expiryMin: Int): List<MotionActivity> =
        events.sortedBy { it.elapsedRealtimeNanos }.mapNotNull { e ->
            val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            val (kind, transition) = when {
                e.activityType in onFoot -> MotionKind.OnFoot to if (enter) MotionTransition.Enter else MotionTransition.Exit
                e.activityType == DetectedActivity.IN_VEHICLE && enter -> MotionKind.InVehicle to MotionTransition.Enter
                e.activityType == DetectedActivity.STILL && enter -> MotionKind.Still to MotionTransition.Enter
                else -> null
            } ?: return@mapNotNull null
            val at = instantOf(e.elapsedRealtimeNanos, now, nowElapsedNanos)
            MotionActivity(kind, at, at.plus(Duration.ofMinutes(expiryMin.toLong())), transition)
        }
}
