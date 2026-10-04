package app.daycue.domain.engine

import app.daycue.domain.config.CueType
import app.daycue.domain.config.LockScreenPresentation
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MedicationQuietHours
import app.daycue.domain.config.TravelPolicy
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Instant
import java.time.LocalDate

/**
 * Medication fixed schedules (PRODUCT §7). Never suppressed, deferred or paused by context, quiet hours,
 * global pause or collisions (MED-3). Only `Taken` marks taken (MED-2). No advice anywhere (MED-10).
 */
internal object MedicationModule {

    fun slotKey(s: MedSlot) = "med:${s.key}"
    private const val MERGED_KEY = "med:merged"

    fun medDay(run: Run, m: Medication): LocalDate = TimeMath.dayOf(run.now, m.travelPolicy.zone(run.zone), run.config.settings.dayStartsAt)
    private fun slotDay(run: Run, m: Medication, s: MedSlot) = TimeMath.dayOf(s.dueAt, m.travelPolicy.zone(run.zone), run.config.settings.dayStartsAt)

    fun handle(run: Run, ev: Event) {
        val ms = run.st.medication
        when (ev) {
            is Event.MedicationTaken -> {
                val s = ms.slots[ev.slot.key] ?: return
                val m = run.config.medication(s.medicationId) ?: return
                if (s.status == SlotStatus.Taken || s.status == SlotStatus.Skipped) return // idempotent
                if (slotDay(run, m, s) != medDay(run, m)) return // MED-5: today's slots only (history edits are separate)
                s.cue?.let { run.dismiss(slotKey(s), it.cueId, "taken") }
                put(run, s.copy(status = SlotStatus.Taken, takenAt = run.now, snoozedUntil = null))
                run.history(slotKey(s), HistoryKind.Taken, "MED-2", ev.cueId ?: s.cue?.cueId)
                closeMergedIfResolved(run)
            }
            is Event.MedicationSnooze -> {
                val s = ms.slots[ev.slot.key] ?: return
                val m = run.config.medication(s.medicationId) ?: return
                val cue = s.cue ?: return
                if (ev.cueId != null && ev.cueId != cue.cueId) return
                if (s.status != SlotStatus.Due) return
                run.dismiss(slotKey(s), cue.cueId, "snoozed")
                put(run, s.copy(cue = null, snoozedUntil = run.now.plusMin(m.snoozeMin)))
                run.history(slotKey(s), HistoryKind.Snoozed, "GEN-3", cue.cueId)
            }
            is Event.MedicationSkip -> {
                val s = ms.slots[ev.slot.key] ?: return
                if (s.status == SlotStatus.Taken || s.status == SlotStatus.Skipped) return
                s.cue?.let { run.dismiss(slotKey(s), it.cueId, "skipped") }
                put(run, s.copy(status = SlotStatus.Skipped, snoozedUntil = null))
                run.history(slotKey(s), HistoryKind.Skipped, "MED-1")
                closeMergedIfResolved(run)
            }
            else -> {}
        }
    }

    private fun put(run: Run, s: MedSlot) {
        val ms = run.st.medication
        run.st = run.st.copy(medication = ms.copy(slots = ms.slots + (s.key to s)))
    }

    private fun closeMergedIfResolved(run: Run) {
        val ms = run.st.medication
        val merged = ms.mergedCue ?: return
        if (ms.slots.values.none { it.mergedCueId == merged.cueId && it.status == SlotStatus.Due }) {
            run.dismiss(MERGED_KEY, merged.cueId, "resolved")
            run.st = run.st.copy(medication = run.st.medication.copy(mergedCue = null))
        }
    }

    fun evaluate(run: Run) {
        val now = run.now
        var ms = run.st.medication
        val meds = run.config.medications
        // tracking-since for new items
        val tracking = ms.trackingSince.filterKeys { id -> meds.any { it.id == id } } + meds.filter { it.id !in ms.trackingSince }.associate { it.id to now }
        val slots = ms.slots.toMutableMap()
        val wanted = mutableSetOf<String>()
        for (m in meds) {
            val zone = m.travelPolicy.zone(run.zone)
            val today = TimeMath.dayOf(now, zone, run.config.settings.dayStartsAt)
            for (date in listOf(today.minusDays(1), today, today.plusDays(1))) {
                if (!m.activeOn(date)) continue
                for (t in m.times) {
                    val ref = SlotRef(m.id, date, t)
                    wanted += ref.key
                    val dueAt = TimeMath.resolveLocal(date, t, zone) // MED-6 DST handling
                    val existing = slots[ref.key]
                    if (existing == null) {
                        // A previous-day dose from before the medication was tracked never existed for the user:
                        // materializing it would surface it as "Not confirmed" (bug found by the app host, 2026-10-04).
                        if (date.isBefore(today) && dueAt.isBefore(tracking[m.id]!!)) continue
                        val late = dueAt.isBefore(tracking[m.id]!!) || (run.event is Event.ConfigChanged && dueAt.isBefore(now))
                        slots[ref.key] = MedSlot(m.id, date, t, dueAt, materializedAt = now, noCueReason = if (late) "created_after_time" else null)
                    } else if (existing.dueAt != dueAt && existing.status != SlotStatus.Taken) {
                        slots[ref.key] = existing.copy(dueAt = dueAt) // MED-7: recompute under the item's policy
                    }
                }
            }
        }
        // Drop slots no longer scheduled (unless taken) and anything older than two days.
        for ((k, s) in slots.toMap()) {
            val m = run.config.medication(s.medicationId)
            val tooOld = s.date.isBefore(TimeMath.localDate(now, run.zone).minusDays(2))
            if (m == null || tooOld || (k !in wanted && s.status != SlotStatus.Taken)) {
                s.cue?.let { run.dismiss(slotKey(s), it.cueId, if (m == null) "deleted" else "unscheduled") }
                slots -= k
            }
        }
        ms = ms.copy(slots = slots, trackingSince = tracking)

        // status + day boundary (MED-1 "Not confirmed")
        for ((k, s0) in ms.slots) {
            val m = run.config.medication(s0.medicationId) ?: continue
            var s = s0
            if (s.status == SlotStatus.Upcoming && !now.isBefore(s.dueAt)) s = s.copy(status = SlotStatus.Due)
            if (s.status == SlotStatus.Due && !s.notConfirmedRecorded && slotDay(run, m, s).isBefore(medDay(run, m))) {
                run.history(slotKey(s), HistoryKind.NotConfirmed, "MED-1")
                s = s.copy(notConfirmedRecorded = true)
            }
            if (s != s0) ms = ms.copy(slots = ms.slots + (k to s))
        }
        run.st = run.st.copy(medication = ms)

        // MED-7 / MED-8 merged recovery cue.
        if (run.recovery && ms.mergedCue == null && run.once.add("med-recovery")) {
            val candidates = ms.slots.values.filter { s ->
                val m = run.config.medication(s.medicationId)!!
                s.status == SlotStatus.Due && s.noCueReason == null && slotDay(run, m, s) == medDay(run, m) &&
                    (if (run.isReboot) true else s.cue == null && s.snoozedUntil == null)
            }
            if (candidates.size >= 2) { proposeMerged(run, candidates); return }
            if (candidates.size == 1 && run.isReboot) {
                val s = candidates.first()
                proposeSlot(run, run.config.medication(s.medicationId)!!, s, null, 0, "MED-8")
                return
            }
        }

        for (s in ms.slots.values) {
            val m = run.config.medication(s.medicationId) ?: continue
            if (s.status == SlotStatus.Upcoming) { if (s.noCueReason == null) run.wake(s.dueAt, WakePrecision.AlarmClock, "medication"); continue }
            if (s.status != SlotStatus.Due || s.noCueReason != null || s.mergedCueId != null) continue
            if (slotDay(run, m, s) != medDay(run, m)) continue // previous day: Not confirmed, no new cues
            val cue = s.cue
            if (cue == null) {
                val eff = s.snoozedUntil ?: s.dueAt
                if (!eff.isAfter(now)) proposeSlot(run, m, s, null, 0, if (s.snoozedUntil != null) "GEN-3" else "MED-1")
                else run.wake(eff, WakePrecision.AlarmClock, "medication")
            } else if (!cue.exhausted) {
                val at = cue.lastDeliveredAt.plusMin(m.repeat.everyMin)
                if (cue.repeatsDone < m.repeat.maxRepeats) {
                    if (!at.isAfter(now)) proposeSlot(run, m, s, cue.cueId, cue.repeatsDone + 1, "MED-4")
                    else run.wake(at, WakePrecision.AlarmClock, "medication repeat")
                } else if (!now.isBefore(at)) {
                    // MED-4: after the last repeat the notification stays and the slot stays Due.
                    put(run, s.copy(cue = cue.copy(exhausted = true)))
                    run.history(slotKey(s), HistoryKind.Unanswered, "MED-4", cue.cueId)
                } else run.wake(at, WakePrecision.Inexact, "medication unanswered")
            }
        }
        if (meds.isNotEmpty()) run.wake(TimeMath.nextDayBoundary(now, run.zone, run.config.settings.dayStartsAt), WakePrecision.Inexact, "medication day")

        // MED-7 one-time informational notice about travel policies.
        if (run.zoneChanged && meds.isNotEmpty() && ms.policyNoticeZone != run.zone) {
            val follow = meds.count { it.travelPolicy is TravelPolicy.FollowLocalTime }
            run.propose(Proposal(
                itemKey = "med:policy", notificationKey = "med:policy", type = CueType.Notice, dueAt = now, cueId = null,
                title = Text("cue.medication.policy.title"),
                body = Text("cue.medication.policy.body", mapOf("followLocal" to follow.toString(), "keepHome" to (meds.size - follow).toString(), "zone" to run.zone.id)),
                actions = listOf(CueAction(ActionKind.Open, Text("action.open"))), why = WhyNow("MED-7", "why.medication.policy"),
                lockScreen = LockScreenVisibility.Private, publicTitle = Text("cue.medication.generic.title"), silent = true,
                commit = { st, _ -> st.copy(medication = st.medication.copy(policyNoticeZone = run.zone)) },
            ))
        }
    }

    private fun lock(m: Medication) = when (m.lockScreen) {
        LockScreenPresentation.Full -> LockScreenVisibility.Public
        LockScreenPresentation.Generic -> LockScreenVisibility.Private
        LockScreenPresentation.Hidden -> LockScreenVisibility.Secret
    }

    private fun proposeSlot(run: Run, m: Medication, s: MedSlot, cueId: String?, repeat: Int, rule: String) {
        val now = run.now
        val silent = m.quietHours == MedicationQuietHours.DeliverSilently && run.quietUntil() != null
        run.propose(Proposal(
            itemKey = slotKey(s), notificationKey = slotKey(s), type = CueType.Medication, dueAt = now, cueId = cueId,
            title = Text("cue.medication.title", mapOf("label" to m.label, "time" to s.time.toString())),
            body = Text("cue.medication.body", mapOf("time" to s.time.toString())),
            actions = listOf(
                CueAction(ActionKind.Taken, Text("action.taken")),
                CueAction(ActionKind.Snooze, Text("action.snooze", mapOf("minutes" to m.snoozeMin.toString())), m.snoozeMin),
            ),
            why = WhyNow(rule, "why.medication", mapOf("slot" to s.key, "dueAt" to s.dueAt.toString(), "travelPolicy" to m.travelPolicy.toString())),
            repeatIndex = repeat, lockScreen = lock(m), publicTitle = Text("cue.medication.generic.title"),
            // SPK-4: never the label unless speakLabel.
            speech = if (m.speakLabel) Text("speech.medication.label", mapOf("label" to m.label)) else Text("speech.medication.generic"),
            shortName = Text("short.medication"), silent = silent, profileId = m.cueProfileId, ongoing = true,
            commit = { st, id ->
                val cur = st.medication.slots[s.key] ?: s
                val c = if (repeat == 0) ActiveCue(id, now, now) else (cur.cue ?: ActiveCue(id, now, now)).copy(lastDeliveredAt = now, repeatsDone = repeat)
                st.copy(medication = st.medication.copy(slots = st.medication.slots + (s.key to cur.copy(cue = c, snoozedUntil = null, status = SlotStatus.Due))))
            },
        ))
    }

    private fun proposeMerged(run: Run, slots: List<MedSlot>) {
        val now = run.now
        val first = run.config.medication(slots.first().medicationId)!!
        run.propose(Proposal(
            itemKey = MERGED_KEY, notificationKey = MERGED_KEY, type = CueType.Medication, dueAt = now, cueId = null,
            title = Text("cue.medication.merged.title", mapOf("count" to slots.size.toString())),
            body = Text("cue.medication.merged.body", mapOf("count" to slots.size.toString())),
            actions = listOf(CueAction(ActionKind.Open, Text("action.open"))),
            why = WhyNow(if (run.isReboot) "MED-8" else "MED-7", "why.medication.merged", mapOf("slots" to slots.joinToString(",") { it.key })),
            lockScreen = LockScreenVisibility.Private, publicTitle = Text("cue.medication.generic.title"),
            speech = Text("speech.medication.merged", mapOf("count" to slots.size.toString())), shortName = Text("short.medication"),
            silent = first.quietHours == MedicationQuietHours.DeliverSilently && run.quietUntil() != null, ongoing = true,
            commit = { st, id ->
                var ms = st.medication
                for (s in slots) {
                    val cur = ms.slots[s.key] ?: continue
                    ms = ms.copy(slots = ms.slots + (s.key to cur.copy(mergedCueId = id, cue = cur.cue?.copy(exhausted = true) ?: ActiveCue(id, now, now, exhausted = true), snoozedUntil = null)))
                }
                st.copy(medication = ms.copy(mergedCue = ActiveCue(id, now, now, exhausted = true)))
            },
        ))
        // Individual notifications are replaced by the merged one (never one per slot).
        slots.forEach { s -> s.cue?.let { run.dismiss(slotKey(s), it.cueId, "merged") } }
    }

    @Suppress("unused") private fun unused(i: Instant) = i
}
