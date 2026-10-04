package app.daycue.domain.engine

import app.daycue.domain.config.CueType
import app.daycue.domain.config.MorningAlarm
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Morning alarms (PRODUCT §11). Ringing/Spotify/fallback playback is the app's job; the domain decides when. */
internal object AlarmModule {

    fun key(a: MorningAlarm) = "alarm:${a.id}"
    private const val BOOT_GRACE_MIN = 30L

    /** Next occurrence (date, instant) not yet handled, at or after the earliest unhandled date. */
    fun nextOccurrence(run: Run, a: MorningAlarm, st: AlarmState): Pair<LocalDate, Instant>? {
        if (!a.enabled) return null
        val days = a.days ?: run.config.settings.workDays
        val today = TimeMath.localDate(run.now, run.zone)
        var d = today.minusDays(1)
        repeat(10) {
            val ok = if (a.oneOffDate != null && days.isEmpty()) d == a.oneOffDate else (d.dayOfWeek in days || d == a.oneOffDate)
            if (ok && d != a.skipDate && (st.handledThrough == null || d.isAfter(st.handledThrough))) {
                return d to TimeMath.resolveLocal(d, a.time, run.zone) // ALM-6: DST as MED-6
            }
            d = d.plusDays(1)
        }
        return null
    }

    private fun get(run: Run, id: String) = run.st.alarms[id] ?: AlarmState()
    private fun put(run: Run, id: String, s: AlarmState) { run.st = run.st.copy(alarms = run.st.alarms + (id to s)) }

    private fun ring(run: Run, a: MorningAlarm, s: AlarmState, occ: LocalDate, scheduled: Instant, test: Boolean): AlarmState {
        val count = s.ring?.snoozeCount ?: 0
        run.effects += Effect.StartAlarm(a.id, "${a.id}@$occ", a.source, a.spotifyStartTimeoutSec, a.volumeRampSec, a.vibrate, count, a.maxSnoozes, isTest = test)
        run.st = run.st.copy(delivery = run.st.delivery.copy(ringingAlarmId = a.id))
        run.history(key(a), HistoryKind.AlarmRang, if (test) "ALM-5" else "ALM-1", detail = mapOf("occurrence" to occ.toString()), test = test)
        run.wake(run.now.plusMin(a.ringTimeoutMin), WakePrecision.Exact, "alarm timeout")
        return s.copy(ring = AlarmRing(occ, scheduled, run.now, count, null, test))
    }

    private fun stopRinging(run: Run, a: MorningAlarm, reason: String) {
        run.effects += Effect.StopAlarm(a.id, reason)
        if (run.st.delivery.ringingAlarmId == a.id) run.st = run.st.copy(delivery = run.st.delivery.copy(ringingAlarmId = null))
        Delivery.flushDeferredSpeech(run) // COL-4
    }

    fun handle(run: Run, ev: Event.AlarmControl) {
        val a = run.config.alarm(ev.alarmId) ?: return
        val s = get(run, a.id)
        when (ev.action) {
            AlarmAction.Test -> put(run, a.id, ring(run, a, s, TimeMath.localDate(run.now, run.zone), run.now, test = true))
            AlarmAction.Stop -> {
                val r = s.ring ?: return
                stopRinging(run, a, "stopped")
                put(run, a.id, s.copy(ring = null, handledThrough = if (r.test) s.handledThrough else maxOfDate(s.handledThrough, r.occurrence)))
                run.history(key(a), HistoryKind.AlarmStopped, "ALM-3", test = r.test)
                if (!r.test) a.followOnRoutineId?.let { RoutineModule.afterAlarm(run, it) }
            }
            AlarmAction.Snooze -> {
                val r = s.ring ?: return
                if (r.snoozedUntil != null) return // already snoozed (idempotent)
                if (r.snoozeCount >= a.maxSnoozes) return
                stopRinging(run, a, "snoozed")
                put(run, a.id, s.copy(ring = r.copy(snoozeCount = r.snoozeCount + 1, snoozedUntil = run.now.plusMin(a.snoozeMin))))
                run.history(key(a), HistoryKind.AlarmSnoozed, "ALM-3", detail = mapOf("count" to (r.snoozeCount + 1).toString()), test = r.test)
            }
            AlarmAction.SpotifyFellBack -> {
                run.st = run.st.copy(readiness = run.st.readiness.copy(lastAlarmSourceFallbackAt = run.now))
                run.history(key(a), HistoryKind.AlarmRang, "ALM-2", detail = mapOf("source" to "fallback"))
            }
        }
    }

    private fun maxOfDate(a: LocalDate?, b: LocalDate) = if (a == null || b.isAfter(a)) b else a

    fun evaluate(run: Run) {
        val now = run.now
        // Deleted / disabled alarms that are ringing stop.
        for ((id, s) in run.st.alarms) {
            val a = run.config.alarm(id)
            if ((a == null || !a.enabled) && s.ring != null && s.ring.test.not()) {
                run.effects += Effect.StopAlarm(id, "disabled")
                if (run.st.delivery.ringingAlarmId == id) run.st = run.st.copy(delivery = run.st.delivery.copy(ringingAlarmId = null))
                run.st = run.st.copy(alarms = run.st.alarms + (id to s.copy(ring = null)))
            }
        }
        for (a in run.config.alarms) {
            var s = get(run, a.id)
            val r = s.ring
            if (r != null) {
                if (r.snoozedUntil != null) {
                    if (!now.isBefore(r.snoozedUntil)) s = ring(run, a, s.copy(ring = r.copy(snoozedUntil = null)), r.occurrence, r.scheduledAt, r.test)
                    else run.wake(r.snoozedUntil, WakePrecision.AlarmClock, "alarm snooze")
                } else {
                    val timeout = r.startedAt.plusMin(a.ringTimeoutMin)
                    if (!now.isBefore(timeout)) {
                        // §11 ringTimeout: auto-snooze while snoozes remain, else stop (no follow-on routine).
                        stopRinging(run, a, "timeout")
                        s = if (r.snoozeCount < a.maxSnoozes) s.copy(ring = r.copy(snoozeCount = r.snoozeCount + 1, snoozedUntil = now.plusMin(a.snoozeMin)))
                        else s.copy(ring = null, handledThrough = if (r.test) s.handledThrough else maxOfDate(s.handledThrough, r.occurrence))
                        run.history(key(a), if (s.ring == null) HistoryKind.AlarmStopped else HistoryKind.AlarmSnoozed, "ALM-3", detail = mapOf("reason" to "timeout"), test = r.test)
                        s.ring?.snoozedUntil?.let { run.wake(it, WakePrecision.AlarmClock, "alarm snooze") }
                    } else run.wake(timeout, WakePrecision.Exact, "alarm timeout")
                }
                put(run, a.id, s)
                continue
            }
            val occ = nextOccurrence(run, a, s) ?: continue
            val (date, at) = occ
            if (!at.isAfter(now)) {
                val late = Duration.between(at, now)
                when {
                    late > Duration.ofMinutes(BOOT_GRACE_MIN) -> {
                        run.history(key(a), HistoryKind.MissedPowerOff, "ALM-6", detail = mapOf("occurrence" to date.toString()))
                        s = s.copy(handledThrough = date)
                    }
                    run.isReboot -> {
                        // ALM-6 says "rings once on boot"; Android 15+ forbids starting the ringing FGS from
                        // BOOT_COMPLETED (ANDROID.md §7), so the domain posts a P1 notification instead.
                        bootNotice(run, a, date)
                        s = s.copy(handledThrough = date)
                    }
                    else -> s = ring(run, a, s, date, at, test = false)
                }
                put(run, a.id, s)
                nextOccurrence(run, a, s)?.let { run.wake(it.second, WakePrecision.AlarmClock, "alarm") }
            } else run.wake(at, WakePrecision.AlarmClock, "alarm")
        }
    }

    private fun bootNotice(run: Run, a: MorningAlarm, date: LocalDate) {
        run.propose(Proposal(
            itemKey = key(a), notificationKey = key(a), type = CueType.Alarm, dueAt = run.now, cueId = null,
            title = Text("cue.alarm.missed_boot.title", mapOf("time" to a.time.toString())), body = Text("cue.alarm.missed_boot.body", mapOf("time" to a.time.toString())),
            actions = emptyList(), why = WhyNow("ALM-6", "why.alarm.boot", mapOf("occurrence" to date.toString())),
            speech = null, shortName = Text("short.alarm"),
            commit = { st, _ -> st },
        ))
    }
}
