package app.daycue.domain.engine

import app.daycue.domain.config.BottleTrigger
import app.daycue.domain.config.CueType
import app.daycue.domain.config.TransitionHabit
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.signal.GeofenceSnapshot
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.signal.Signal
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin

/** Water bottle departure cue (PRODUCT §5). */
internal object BottleModule {

    fun key(h: TransitionHabit) = "habit:${h.id}"
    private fun Run.get(id: String) = st.bottles[id] ?: BottleState()
    private fun Run.put(id: String, s: BottleState) { st = st.copy(bottles = st.bottles + (id to s)) }

    private fun enabledPlaces(run: Run, h: TransitionHabit): Set<String> =
        h.placeIds.ifEmpty { run.config.places.filter { it.bottleReminderOnLeave }.map { it.id }.toSet() }

    /** BTL-1: the dependable path. */
    fun leavingNow(run: Run) {
        val pv = run.st.context.effectivePlace.value
        val placeId = pv.placeId.takeIf { pv.kind == PlaceKind.Saved }
        run.history("context", HistoryKind.ContextChanged, "BTL-1", detail = mapOf("departure" to (placeId ?: "unknown")))
        for (h in run.config.transitionHabits) {
            if (!h.enabled || BottleTrigger.LeavingNow !in h.triggers) continue
            run.put(h.id, run.get(h.id).copy(pending = PendingDeparture(placeId, "LeavingNow", run.now, loud = true)))
        }
    }

    /**
     * BTL-2 / BTL-5: a departure, evaluated before the signal is folded into context (no exit dwell). Either a
     * raw OS exit, or a [GeofenceSnapshot] that no longer lists a place we were raw-inside (the OS exit was
     * missed, e.g. after re-registration or a location-fix check). Both go through the same pending path, so
     * BTL-3 dedup and BTL-4 cooldown apply equally; a place whose departure was already seen (raw exit
     * recorded, not re-entered) never produces a second pending departure.
     */
    fun onSignalBeforeApply(run: Run, sig: Signal) {
        if (sig.expiresAt?.let { !run.now.isBefore(it) } == true) return // stale on arrival: ignored like in context
        val cs = run.st.context
        when (sig) {
            is GeofenceTransition -> if (sig.kind == GeofenceTransitionKind.Exit) {
                val alreadyDeparted = sig.placeId !in cs.rawInside && sig.placeId in cs.rawExitAt
                if (!alreadyDeparted) departure(run, sig.placeId, otherInside = (cs.rawInside.keys - sig.placeId), source = "exit")
            }
            is GeofenceSnapshot -> for (placeId in cs.rawInside.keys - sig.insidePlaceIds) {
                departure(run, placeId, otherInside = sig.insidePlaceIds - placeId, source = "snapshot")
            }
            else -> {}
        }
    }

    private fun departure(run: Run, placeId: String, otherInside: Set<String>, source: String) {
        for (h in run.config.transitionHabits) {
            if (!h.enabled || BottleTrigger.GeofenceExit !in h.triggers || placeId !in enabledPlaces(run, h)) continue
            if (otherInside.any { run.config.place(it) != null }) {
                run.history(key(h), HistoryKind.SkippedLate, "BTL-5", detail = mapOf("place" to placeId, "source" to source))
                continue
            }
            run.put(h.id, run.get(h.id).copy(pending = PendingDeparture(placeId, "GeofenceExit", run.now, loud = false)))
        }
    }

    fun ack(run: Run, ev: Event.BottleAck) {
        val h = run.config.habit(ev.habitId) as? TransitionHabit ?: return
        val s = run.get(h.id)
        val cue = s.cue ?: return
        if (ev.cueId != null && ev.cueId != cue.cueId) return
        run.dismiss(key(h), cue.cueId, if (ev.notNeeded) "not_needed" else "got_it")
        run.put(h.id, s.copy(cue = null))
        run.history(key(h), HistoryKind.Acked, "BTL-7", cue.cueId, mapOf("answer" to if (ev.notNeeded) "not_needed" else "got_it"))
    }

    fun evaluate(run: Run) {
        for (h in run.config.transitionHabits) evaluateOne(run, h)
    }

    private fun evaluateOne(run: Run, h: TransitionHabit) {
        val now = run.now
        var s = run.get(h.id)
        if (!h.enabled) { if (s.pending != null) run.put(h.id, s.copy(pending = null)); return }

        // BTL-6 scheduled departures.
        h.scheduledDepartures.forEachIndexed { i, d ->
            val sk = "$i:${d.placeId}@${d.time}"
            val date = TimeMath.localDate(now, run.zone)
            val occ = TimeMath.resolveLocal(date, d.time, run.zone)
            if (date.dayOfWeek in d.days && !now.isBefore(occ) && s.firedDepartures[sk] != date) {
                val here = run.st.context.effectivePlace.value.let { it.kind == PlaceKind.Saved && it.placeId == d.placeId }
                s = s.copy(firedDepartures = s.firedDepartures + (sk to date))
                if (here && now.isBefore(occ.plusMin(30))) s = s.copy(pending = PendingDeparture(d.placeId, "ScheduledDeparture", now, loud = false))
            }
            // next occurrence
            var nd = date
            repeat(8) {
                val o = TimeMath.resolveLocal(nd, d.time, run.zone)
                if (nd.dayOfWeek in d.days && o.isAfter(now)) { run.wake(o, WakePrecision.Exact, "departure ${h.id}"); return@forEachIndexed }
                nd = nd.plusDays(1)
            }
        }

        val p = s.pending
        if (p != null) {
            val rule: String? = when {
                s.lastCueAt != null && now.isBefore(s.lastCueAt!!.plusMin(h.dedupWindowMin)) -> "BTL-3"
                p.trigger != "LeavingNow" && p.placeId != null && s.lastCueByPlace[p.placeId]?.let { now.isBefore(it.plusMin(h.cooldownPerPlaceMin)) } == true -> "BTL-4"
                run.pauseInfo(h.pause).paused || run.globalPause().paused -> "paused"
                else -> null
            }
            if (rule != null) {
                run.history(key(h), HistoryKind.Skipped, rule, detail = mapOf("trigger" to p.trigger, "place" to (p.placeId ?: "unknown")))
                s = s.copy(pending = null)
            } else {
                val silent = !p.loud && run.quietUntil() != null // §8.1 P5: geofence silent in quiet hours
                propose(run, h, s, null, 0, silent, p)
            }
        }
        val cue = s.cue
        if (cue != null && cue.repeatsDone < h.repeat.maxRepeats) {
            val at = cue.lastDeliveredAt.plusMin(h.repeat.everyMin)
            if (!at.isAfter(now)) propose(run, h, s, cue.cueId, cue.repeatsDone + 1, run.quietUntil() != null, null)
            else run.wake(at, WakePrecision.Exact, "bottle repeat")
        }
        run.put(h.id, s)
    }

    private fun propose(run: Run, h: TransitionHabit, s: BottleState, cueId: String?, repeat: Int, silent: Boolean, p: PendingDeparture?) {
        val lang = run.config.settings.language
        val phrase = h.phrase?.get(lang) ?: run.config.profileFor(CueType.WaterBottle, h.cueProfileId)?.phrase?.get(lang) ?: h.name
        val now = run.now
        val rule = when (p?.trigger) { "LeavingNow" -> "BTL-1"; "GeofenceExit" -> "BTL-2"; "ScheduledDeparture" -> "BTL-6"; else -> "GEN-4" }
        run.propose(Proposal(
            itemKey = key(h), notificationKey = key(h), type = CueType.WaterBottle, dueAt = now, cueId = cueId,
            title = Text("cue.bottle.title", mapOf("name" to h.name)), body = Text("cue.habit.body", mapOf("phrase" to phrase)),
            actions = listOf(CueAction(ActionKind.GotIt, Text("action.got_it")), CueAction(ActionKind.NotNeeded, Text("action.not_needed"))),
            why = WhyNow(rule, "why.bottle", run.ctx.facts() + mapOf("trigger" to (p?.trigger ?: "repeat"), "place" to (p?.placeId ?: "unknown"))),
            repeatIndex = repeat, speech = Text("speech.phrase", mapOf("text" to phrase)), shortName = Text("short.bottle"),
            silent = silent, profileId = h.cueProfileId,
            commit = { st, id ->
                val cur = st.bottles[h.id] ?: BottleState()
                val next = if (p != null) cur.copy(cue = ActiveCue(id, now, now), lastCueAt = now, pending = null,
                    lastCueByPlace = if (p.placeId != null) cur.lastCueByPlace + (p.placeId to now) else cur.lastCueByPlace)
                else cur.copy(cue = cur.cue?.copy(lastDeliveredAt = now, repeatsDone = repeat))
                st.copy(bottles = st.bottles + (h.id to next))
            },
        ))
    }
}
