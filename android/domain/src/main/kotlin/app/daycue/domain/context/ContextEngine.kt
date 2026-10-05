package app.daycue.domain.context

import app.daycue.domain.config.Activity
import app.daycue.domain.config.AwayEnvironmentPolicy
import app.daycue.domain.config.Confidence
import app.daycue.domain.config.ContextCondition
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Environment
import app.daycue.domain.config.SessionKind
import app.daycue.domain.config.SessionStart
import app.daycue.domain.config.TypicalEnvironment
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.signal.CompanionActivity
import app.daycue.domain.signal.CompanionGone
import app.daycue.domain.signal.MotionTransition
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.signal.GeofenceSnapshot
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.signal.LocationAvailability
import app.daycue.domain.signal.MotionActivity
import app.daycue.domain.signal.MotionAvailability
import app.daycue.domain.signal.MotionKind
import app.daycue.domain.signal.Signal
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Instant
import java.time.ZoneId

/** Session lifecycle notifications produced while advancing context (WRK-2 / WRK-4). */
sealed interface SessionNote {
    data class AutoStarted(val placeId: String, val at: Instant) : SessionNote
    data class Suggest(val placeId: String, val kind: SessionKind, val at: Instant) : SessionNote
    data class Paused(val at: Instant, val reason: String) : SessionNote
    data class Resumed(val at: Instant) : SessionNote
    data class Ended(val at: Instant, val reason: String) : SessionNote
    data class PlaceChanged(val from: PlaceValue, val to: PlaceValue, val at: Instant) : SessionNote
}

data class Advance(val state: ContextState, val notes: List<SessionNote>)

/**
 * Context inference (PRODUCT §1). Pure functions over [ContextState]. Raw observations are folded in by
 * [applySignal]; [advance] performs the time-based confirmations (dwell, hysteresis, expiry, sessions) up to
 * `now`; [infer] produces the layer-3 snapshot.
 */
object ContextEngine {

    fun applySignal(cs: ContextState, sig: Signal, config: DayCueConfig, now: Instant): ContextState {
        val r = config.contextRules
        if (sig.expiresAt?.let { !now.isBefore(it) } == true) return cs // stale on arrival: ignored
        return when (sig) {
            is LocationAvailability ->
                if (sig.available) cs.copy(locationAvailable = true)
                else cs.copy(locationAvailable = false, rawInside = emptyMap(), geofenceKnown = false)
            is GeofenceSnapshot -> cs.copy(
                geofenceKnown = true, snapshotAt = sig.observedAt,
                rawInside = sig.insidePlaceIds.associateWith { cs.rawInside[it] ?: sig.observedAt },
                rawExitAt = cs.rawExitAt + (cs.rawInside.keys - sig.insidePlaceIds).associateWith { sig.observedAt },
            )
            is GeofenceTransition -> when (sig.kind) {
                GeofenceTransitionKind.Enter -> cs.copy(geofenceKnown = true, rawInside = cs.rawInside + (sig.placeId to (cs.rawInside[sig.placeId] ?: sig.observedAt)), rawExitAt = cs.rawExitAt - sig.placeId)
                // OS dwell = already loitered: confirmable immediately.
                GeofenceTransitionKind.Dwell -> cs.copy(geofenceKnown = true,
                    rawInside = cs.rawInside + (sig.placeId to minOf(cs.rawInside[sig.placeId] ?: sig.observedAt, sig.observedAt.minusSeconds(r.placeEnterDwellMin * 60L))),
                    rawExitAt = cs.rawExitAt - sig.placeId)
                GeofenceTransitionKind.Exit -> cs.copy(geofenceKnown = true, rawInside = cs.rawInside - sig.placeId, rawExitAt = cs.rawExitAt + (sig.placeId to sig.observedAt))
            }
            is MotionAvailability -> if (sig.available) cs.copy(motionAvailable = true) else cs.copy(motionAvailable = false, onFootSince = null, lastOnFootAt = null, onFootOngoing = false)
            is MotionActivity -> when (sig.kind) {
                MotionKind.OnFoot -> {
                    if (cs.lastOnFootAt != null && sig.observedAt.isBefore(cs.lastOnFootAt)) cs // out of order
                    else {
                        val alive = onFootHoldEnd(cs, r)?.let { sig.observedAt.isBefore(it) } == true
                        val ongoing = when (sig.transition) {
                            MotionTransition.Enter -> true
                            MotionTransition.Exit -> false
                            MotionTransition.Sample -> alive && cs.onFootOngoing // a sample refreshes an ongoing walk
                        }
                        cs.copy(onFootSince = if (alive) cs.onFootSince else sig.observedAt, lastOnFootAt = sig.observedAt, onFootOngoing = ongoing)
                    }
                }
                // CTX-6: in-vehicle is never Outdoor; ends the on-foot run.
                MotionKind.InVehicle -> cs.copy(onFootSince = null, lastOnFootAt = null, onFootOngoing = false)
                // Still / Other: contradict an ongoing walk (which lasted until now, capped at its stale bound);
                // the on-foot hold then continues from there.
                else -> {
                    val last = cs.lastOnFootAt
                    if (!cs.onFootOngoing || last == null || sig.observedAt.isBefore(last)) cs
                    else cs.copy(onFootOngoing = false, lastOnFootAt = minOf(sig.observedAt, ongoingEnd(last, r)))
                }
            }
            is CompanionActivity -> {
                val c = cs.companion
                if (c.lastObservedAt != null && sig.observedAt.isBefore(c.lastObservedAt)) return cs // out of order
                val exp = sig.expiresAt ?: sig.observedAt.plusMin(r.companionExpiryMin)
                val continues = !c.gone && c.state == sig.state && c.expiresAt != null && !sig.observedAt.isAfter(c.expiresAt)
                cs.copy(companion = CompanionTrack(sig.state, if (continues) c.stateSince else sig.observedAt, sig.observedAt, exp))
            }
            // Explicit retraction: no state, not fresh, remembered as "gone" for immediate suspension (WRK-5).
            is CompanionGone -> {
                val c = cs.companion
                if (c.lastObservedAt != null && sig.observedAt.isBefore(c.lastObservedAt)) cs
                else cs.copy(companion = CompanionTrack(state = null, stateSince = null, lastObservedAt = sig.observedAt, expiresAt = sig.observedAt, gone = true))
            }
        }
    }

    private fun ongoingEnd(last: Instant, r: app.daycue.domain.config.ContextRules): Instant = last.plusMin(maxOf(r.onFootOngoingMaxMin, r.onFootHoldMin))

    /**
     * CTX-6: end of on-foot evidence. While a walk is ongoing (ENTER, no contrary transition) it lasts until
     * `onFootOngoingMaxMin` after the last supporting on-foot signal; otherwise `onFootHoldMin` after the last
     * on-foot instant. Null when there is no on-foot evidence.
     */
    internal fun onFootHoldEnd(cs: ContextState, r: app.daycue.domain.config.ContextRules): Instant? {
        val last = cs.lastOnFootAt ?: return null
        return if (cs.onFootOngoing) ongoingEnd(last, r) else last.plusMin(r.onFootHoldMin)
    }

    fun overrideExpiry(d: OverrideDuration, now: Instant, capMin: Int, zone: ZoneId, config: DayCueConfig): Instant = when (d) {
        OverrideDuration.UntilChanged, OverrideDuration.UntilTransition -> now.plusMin(capMin)
        is OverrideDuration.For -> now.plusMin(minOf(d.minutes, capMin))
        OverrideDuration.RestOfToday -> TimeMath.nextDayBoundary(now, zone, config.settings.dayStartsAt)
    }

    // ---------------------------------------------------------------------------------------------

    fun advance(cs0: ContextState, config: DayCueConfig, now: Instant, zone: ZoneId, meetingInProgress: Boolean): Advance {
        val notes = mutableListOf<SessionNote>()
        var cs = cs0
        cs.detectionPause?.until?.let { if (!now.isBefore(it)) cs = cs.copy(detectionPause = null) }
        cs.envOverride?.let { o ->
            if (!now.isBefore(o.expiresAt)) cs = cs.copy(envOverride = null)
        }
        cs.placeOverride?.let { o ->
            val gone = o.value.kind == PlaceKind.Saved && config.place(o.value.placeId!!) == null
            if (!now.isBefore(o.expiresAt) || gone) cs = cs.copy(placeOverride = null)
        }
        cs = advancePlace(cs, config, now, notes)
        cs.envOverride?.let { o -> // UntilTransition ends at the next meaningful transition (CTX-3)
            if (o.untilTransition && cs.lastTransitionAt != null && cs.lastTransitionAt!!.isAfter(o.setAt)) cs = cs.copy(envOverride = null)
        }
        cs.placeOverride?.let { o -> // same for the manual place: the next automatic meaningful transition ends it
            if (o.untilTransition && cs.lastTransitionAt != null && cs.lastTransitionAt!!.isAfter(o.setAt)) cs = cs.copy(placeOverride = null)
        }
        cs = advanceEnv(cs, config, now)
        cs = advanceSession(cs, config, now, zone, meetingInProgress, notes)
        return Advance(cs, notes)
    }

    private fun advancePlace(cs: ContextState, config: DayCueConfig, now: Instant, notes: MutableList<SessionNote>): ContextState {
        val r = config.contextRules
        if (!cs.locationAvailable || cs.detectionPause != null) {
            return if (cs.place.value.kind == PlaceKind.Unknown) cs else cs.copy(place = PlaceTrack(PlaceValue.UNKNOWN, now))
        }
        val enterDwell = r.placeEnterDwellMin.toLong() * 60
        // Places whose raw enter has satisfied the dwell (CTX-2); the most recent enter wins on overlap.
        fun confirmable(at: Instant) = cs.rawInside.filter { (id, t) -> config.place(id) != null && !at.isBefore(t.plusSeconds(enterDwell)) }
            .maxByOrNull { it.value }
        val cur = cs.place.value
        var next: PlaceTrack = cs.place
        if (cur.kind == PlaceKind.Saved && cur.placeId !in cs.rawInside) {
            val exitAt = cs.rawExitAt[cur.placeId] ?: cs.place.since ?: now
            val confirmAt = exitAt.plusMin(r.placeExitDwellMin)
            if (!now.isBefore(confirmAt)) {
                val other = confirmable(now)
                next = if (other != null) PlaceTrack(PlaceValue.saved(other.key), maxOf(confirmAt, other.value.plusSeconds(enterDwell)))
                else PlaceTrack(if (cs.geofenceKnown) PlaceValue.ELSEWHERE else PlaceValue.UNKNOWN, confirmAt)
            }
        } else if (cur.kind != PlaceKind.Saved) {
            val other = confirmable(now)
            if (other != null) next = PlaceTrack(PlaceValue.saved(other.key), other.value.plusSeconds(enterDwell).let { if (cs.place.since != null && it.isBefore(cs.place.since)) cs.place.since else it })
            else if (cur.kind == PlaceKind.Unknown && cs.geofenceKnown && cs.rawInside.isEmpty()) {
                next = PlaceTrack(PlaceValue.ELSEWHERE, cs.snapshotAt?.let { maxOf(it, cs.rawExitAt.values.maxOrNull() ?: it) } ?: now)
            }
        }
        if (next.value == cur) return cs
        notes += SessionNote.PlaceChanged(cur, next.value, next.since ?: now)
        val meaningful = cur.kind != PlaceKind.Unknown && next.value.kind != PlaceKind.Unknown
        return cs.copy(place = next, lastTransitionAt = if (meaningful) next.since else cs.lastTransitionAt)
    }

    private data class Raw(val value: Environment, val confidence: Confidence, val since: Instant, val source: ContextSource, val enterDwellMin: Int)

    private fun rawEnv(cs: ContextState, config: DayCueConfig, now: Instant): Raw {
        val r = config.contextRules
        if (cs.detectionPause != null) return Raw(Environment.Unknown, Confidence.Low, cs.detectionPause.setAt, ContextSource.DetectionPaused, 0)
        val p = cs.effectivePlace
        if (p.value.kind == PlaceKind.Saved) {
            val place = config.place(p.value.placeId!!)
            when (place?.typicalEnvironment) {
                TypicalEnvironment.Indoor -> return Raw(Environment.Indoor, Confidence.Medium, p.since ?: now, ContextSource.PlaceTypical, 0)
                TypicalEnvironment.Outdoor -> return Raw(Environment.Outdoor, Confidence.Medium, p.since ?: now, ContextSource.PlaceTypical, r.outdoorEnterDwellMin)
                else -> {} // Mixed contributes nothing; fall through to motion
            }
        }
        val unknownSince = p.since ?: now
        return when (r.awayEnvironment) {
            AwayEnvironmentPolicy.Unknown -> Raw(Environment.Unknown, Confidence.Low, unknownSince, ContextSource.None, 0)
            AwayEnvironmentPolicy.AssumeOutdoor ->
                if (p.value.kind == PlaceKind.Elsewhere) Raw(Environment.Outdoor, Confidence.Low, p.since ?: now, ContextSource.AssumedAway, r.outdoorEnterDwellMin)
                else Raw(Environment.Unknown, Confidence.Low, unknownSince, ContextSource.None, 0)
            AwayEnvironmentPolicy.OutdoorWhenOnFoot -> {
                val since = cs.onFootSince; val last = cs.lastOnFootAt
                if (!cs.motionAvailable || since == null || last == null) Raw(Environment.Unknown, Confidence.Low, onFootHoldEnd(cs, r) ?: unknownSince, ContextSource.None, 0)
                else {
                    val holdEnd = onFootHoldEnd(cs, r)!!
                    // An ongoing walk is sustained by definition (the enter dwell below still applies).
                    val sustained = cs.onFootOngoing || !last.plusMin(r.activityRecognitionExpiryMin).isBefore(since.plusMin(r.onFootSustainMin))
                    // The away policy only applies since Place left a saved place.
                    val effSince = p.since?.let { maxOf(it, since) } ?: since
                    if (now.isBefore(holdEnd) && sustained) Raw(Environment.Outdoor, Confidence.Medium, effSince, ContextSource.Motion,
                        if (effSince == since) maxOf(r.outdoorEnterDwellMin, r.onFootSustainMin) else r.outdoorEnterDwellMin)
                    else Raw(Environment.Unknown, Confidence.Low, if (now.isBefore(holdEnd)) since else holdEnd, ContextSource.None, 0)
                }
            }
        }
    }

    private fun advanceEnv(cs: ContextState, config: DayCueConfig, now: Instant): ContextState {
        val r = config.contextRules
        val raw = rawEnv(cs, config, now)
        val e = cs.env
        val next = if (e.value == Environment.Outdoor) {
            if (raw.value == Environment.Outdoor) e.copy(leaveCandidateSince = null, confidence = raw.confidence, source = raw.source)
            else {
                val lc = e.leaveCandidateSince ?: (e.since?.let { maxOf(it, raw.since) } ?: raw.since)
                val confirmAt = lc.plusMin(r.outdoorExitDwellMin)
                if (!now.isBefore(confirmAt)) EnvTrack(raw.value, raw.confidence, confirmAt, raw.source)
                else e.copy(leaveCandidateSince = lc)
            }
        } else {
            if (raw.value == Environment.Outdoor) {
                val confirmAt = raw.since.plusMin(raw.enterDwellMin)
                if (!now.isBefore(confirmAt)) EnvTrack(Environment.Outdoor, raw.confidence, maxOf(confirmAt, e.since ?: confirmAt), raw.source)
                else e.copy(outdoorConfirmAt = confirmAt)
            } else {
                if (e.value == raw.value && e.outdoorConfirmAt == null) e.copy(confidence = raw.confidence, source = raw.source)
                else EnvTrack(raw.value, raw.confidence, if (e.value == raw.value) e.since else raw.since, raw.source)
            }
        }
        return cs.copy(env = next)
    }

    // ---- Sessions (§1.5) ----

    fun permitted(config: DayCueConfig, now: Instant, zone: ZoneId): Boolean {
        val s = config.contextRules.sessions
        return TimeMath.inWindow(now, zone, s.permittedHours, s.permittedDays ?: config.settings.workDays)
    }

    private fun advanceSession(cs0: ContextState, config: DayCueConfig, now: Instant, zone: ZoneId, meeting: Boolean, notes: MutableList<SessionNote>): ContextState {
        val r = config.contextRules.sessions
        var cs = cs0
        val comp = cs.companion
        val fresh = comp.fresh(now)
        var s = cs.session
        if (s != null) {
            // status transitions
            when (s.status) {
                SessionStatus.Active -> {
                    val keep = meeting && r.meetingKeepsSessionActive
                    if (fresh && !keep && comp.stateSince != null) {
                        val th = when (comp.state) { CompanionState.Idle -> r.idleToPauseMin; CompanionState.Locked, CompanionState.Asleep -> r.lockedToPauseMin; else -> null }
                        if (th != null && !now.isBefore(comp.stateSince.plusMin(th))) {
                            s = s.copy(status = SessionStatus.Paused, statusSince = comp.stateSince.plusMin(th)); notes += SessionNote.Paused(s.statusSince, "WRK-3 ${comp.state}")
                        }
                    }
                    if (s.status == SessionStatus.Active && !s.manual && !fresh) {
                        val last = comp.lastObservedAt ?: s.startedAt
                        // An explicit "gone" suspends at once; silence only after companionStaleToSuspend.
                        val staleAt = if (comp.gone) maxOf(last, s.startedAt) else last.plusMin(r.companionStaleToSuspendMin)
                        if (!now.isBefore(staleAt)) {
                            s = s.copy(status = SessionStatus.Suspended, statusSince = staleAt)
                            notes += SessionNote.Paused(staleAt, if (comp.gone) "WRK-5 companion gone" else "WRK-5 companion stale")
                        }
                    }
                }
                SessionStatus.Paused, SessionStatus.Suspended -> {
                    if (fresh && comp.state == CompanionState.Active) { s = s.copy(status = SessionStatus.Active, statusSince = comp.stateSince ?: now); notes += SessionNote.Resumed(now) }
                    else if (s.status == SessionStatus.Suspended && fresh) s = s.copy(status = SessionStatus.Paused, statusSince = s.statusSince)
                }
            }
            // end conditions (WRK-4 / WRK-7)
            val endReason = when {
                s.status != SessionStatus.Active && !now.isBefore(s.statusSince.plusMin(r.pausedToEndMin)) -> "WRK-4 paused >= pausedToEnd"
                s.placeId != null && cs.effectivePlace.value.kind != PlaceKind.Unknown && cs.effectivePlace.value != PlaceValue.saved(s.placeId) -> "WRK-4 left place"
                !s.manual && !permitted(config, now, zone) -> "WRK-4 end of permitted hours"
                s.capAt != null && !now.isBefore(s.capAt) -> "WRK-4 cap"
                else -> null
            }
            if (endReason != null) { notes += SessionNote.Ended(now, endReason); s = null }
            cs = cs.copy(session = s)
        }
        if (cs.session == null && cs.detectionPause == null) {
            val pv = cs.effectivePlace.value
            val place = pv.placeId?.let { config.place(it) }
            // WRK-1 / WRK-6
            if (pv.kind == PlaceKind.Saved && place != null && place.sessionStart != SessionStart.Off &&
                place.defaultSessionKind in place.allowedActivities && permitted(config, now, zone) &&
                fresh && comp.state == CompanionState.Active && comp.stateSince != null &&
                !now.isBefore(comp.stateSince.plusMin(r.sustainedActiveToStartMin)) &&
                cs.sessionSuppressedUntil[place.id]?.let { now.isBefore(it) } != true
            ) {
                when (place.sessionStart) {
                    SessionStart.AutoStart -> {
                        cs = cs.copy(session = Session(place.defaultSessionKind, place.id, manual = false, startedAt = now, statusSince = now))
                        notes += SessionNote.AutoStarted(place.id, now)
                    }
                    SessionStart.Suggest -> {
                        val last = cs.sessionSuggestedAt[place.id]
                        if (last == null || !now.isBefore(last.plusMin(r.suggestCooldownMin))) {
                            cs = cs.copy(sessionSuggestedAt = cs.sessionSuggestedAt + (place.id to now))
                            notes += SessionNote.Suggest(place.id, place.defaultSessionKind, now)
                        }
                    }
                    SessionStart.Off -> {}
                }
            }
        }
        return cs
    }

    // ---- Inference ----

    fun infer(cs: ContextState, config: DayCueConfig, now: Instant, routineRunning: Boolean, meetingKey: String?, meetingEnd: Instant?): InferredContext {
        val paused = cs.detectionPause != null
        val po = cs.placeOverride
        val place = when {
            po != null -> Dim(po.value, Confidence.High, ContextSource.Manual, po.setAt) // CTX-1: manual beats everything
            paused -> Dim(PlaceValue.UNKNOWN, Confidence.Low, ContextSource.DetectionPaused, cs.detectionPause!!.setAt)
            cs.place.value.kind == PlaceKind.Unknown -> Dim(PlaceValue.UNKNOWN, Confidence.Low, ContextSource.None, cs.place.since)
            else -> Dim(cs.place.value, Confidence.High, ContextSource.Geofence, cs.place.since)
        }
        val env = cs.envOverride?.let { Dim(it.value, Confidence.High, ContextSource.Manual, it.setAt) }
            ?: Dim(cs.env.value, cs.env.confidence, cs.env.source, cs.env.since)
        val s = cs.session
        val meeting = if (paused) null else meetingKey
        val act = when {
            routineRunning -> Dim(Activity.RoutineRunning, Confidence.High, ContextSource.Routine)
            meeting != null -> Dim(Activity.Meeting, Confidence.Medium, ContextSource.Calendar)
            s != null && s.status == SessionStatus.Active -> Dim(s.kind.activity(), if (s.manual) Confidence.High else Confidence.Medium, ContextSource.Session, s.statusSince)
            !paused && cs.companion.fresh(now) && cs.companion.state != CompanionState.Active -> Dim(Activity.Inactive, Confidence.Medium, ContextSource.Companion, cs.companion.stateSince)
            else -> Dim(Activity.Unknown, Confidence.Low, ContextSource.None)
        }
        return InferredContext(place, env, act, s, meeting, if (meeting != null) meetingEnd else null, paused)
    }

    /** Does [c] hold in [ctx]? Returns the instant since which it holds (best effort) or null. */
    fun matches(c: ContextCondition, ctx: InferredContext, now: Instant): Instant? {
        var since: Instant? = null
        fun <T> dim(allowed: Set<T>?, d: Dim<*>, isUnknown: Boolean, contains: (Set<T>) -> Boolean): Boolean {
            if (allowed == null) return true
            if (isUnknown || !d.confidence.atLeast(c.minConfidence)) return c.unknownMatches
            val ok = contains(allowed)
            if (ok && d.since != null) since = if (since == null || d.since.isAfter(since)) d.since else since
            return ok
        }
        val okEnv = dim(c.environments, ctx.environment, ctx.environment.value == Environment.Unknown) { it.contains(ctx.environment.value) }
        val okPlace = dim(c.places, ctx.place, ctx.place.value.kind == PlaceKind.Unknown) { ctx.place.value.placeId in it }
        val okAct = dim(c.activities, ctx.activity, ctx.activity.value == Activity.Unknown) { it.contains(ctx.activity.value) }
        return if (okEnv && okPlace && okAct) (since ?: now) else null
    }

    /** Future instants at which context may change by itself. (instant, user-facing?) */
    fun wakeTimes(cs: ContextState, config: DayCueConfig, now: Instant, zone: ZoneId): List<Pair<Instant, Boolean>> {
        val r = config.contextRules
        val s = r.sessions
        val out = mutableListOf<Pair<Instant, Boolean>>()
        cs.rawInside.values.forEach { out += it.plusMin(r.placeEnterDwellMin) to true }
        if (cs.place.value.kind == PlaceKind.Saved && cs.place.value.placeId !in cs.rawInside) {
            (cs.rawExitAt[cs.place.value.placeId] ?: cs.place.since)?.let { out += it.plusMin(r.placeExitDwellMin) to true }
        }
        cs.env.outdoorConfirmAt?.let { out += it to true }
        cs.env.leaveCandidateSince?.let { out += it.plusMin(r.outdoorExitDwellMin) to true }
        onFootHoldEnd(cs, r)?.let { out += it to false }
        cs.onFootSince?.let { out += it.plusMin(maxOf(r.outdoorEnterDwellMin, r.onFootSustainMin)) to true }
        cs.envOverride?.let { out += it.expiresAt to false }
        cs.placeOverride?.let { out += it.expiresAt to false }
        cs.detectionPause?.until?.let { out += it to false }
        val c = cs.companion
        c.expiresAt?.let { out += it to false }
        c.lastObservedAt?.let { out += it.plusMin(s.companionStaleToSuspendMin) to false }
        c.stateSince?.let { since ->
            out += since.plusMin(s.sustainedActiveToStartMin) to false
            out += since.plusMin(s.idleToPauseMin) to false
            out += since.plusMin(s.lockedToPauseMin) to false
        }
        cs.session?.let { ss ->
            if (ss.status != SessionStatus.Active) out += ss.statusSince.plusMin(s.pausedToEndMin) to false
            ss.capAt?.let { out += it to false }
            if (!ss.manual) TimeMath.currentWindowEnd(now, zone, s.permittedHours, s.permittedDays ?: config.settings.workDays)?.let { out += it to false }
        }
        if (cs.session == null) TimeMath.nextOpen(now, zone, s.permittedHours, s.permittedDays ?: config.settings.workDays)?.let { out += it to false }
        cs.sessionSuppressedUntil.values.forEach { out += it to false }
        cs.sessionSuggestedAt.values.forEach { out += it.plusMin(s.suggestCooldownMin) to false }
        return out.filter { it.first.isAfter(now) }
    }
}
