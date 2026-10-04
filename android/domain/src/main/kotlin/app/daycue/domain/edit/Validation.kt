package app.daycue.domain.edit

import app.daycue.domain.config.AlarmSource
import app.daycue.domain.config.CalendarConfig
import app.daycue.domain.config.ContextCondition
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.FirstReminderPolicy
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.ReentryPolicy
import app.daycue.domain.config.RoutineTrigger
import app.daycue.domain.config.TransitionHabit
import kotlinx.serialization.Serializable

@Serializable
data class ValidationError(val path: String, val code: String, val message: String)

/** Validation ranges from PRODUCT.md (cited per field). */
object ConfigValidator {

    private class Acc {
        val errors = mutableListOf<ValidationError>()
        fun range(path: String, v: Int, min: Int, max: Int, rule: String) {
            if (v < min || v > max) errors += ValidationError(path, "out_of_range", "$v not in $min..$max ($rule)")
        }
        fun text(path: String, v: String, min: Int, max: Int) {
            val n = v.trim().length
            if (n < min || v.length > max) errors += ValidationError(path, "bad_length", "length ${v.length} not in $min..$max")
        }
        fun check(cond: Boolean, path: String, code: String, msg: String) { if (!cond) errors += ValidationError(path, code, msg) }
        fun unique(path: String, ids: List<String>) {
            ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += ValidationError("$path[$it]", "duplicate_id", "duplicate id '$it'") }
            ids.forEach { if (it.isBlank()) errors += ValidationError(path, "blank_id", "blank id") }
        }
    }

    fun validate(c: DayCueConfig): List<ValidationError> {
        val a = Acc()
        a.unique("habits", c.habits.map { it.id })
        a.unique("medications", c.medications.map { it.id })
        a.unique("routines", c.routines.map { it.id })
        a.unique("alarms", c.alarms.map { it.id })
        a.unique("places", c.places.map { it.id })
        a.unique("cueProfiles", c.cueProfiles.map { it.id })
        a.unique("calendarRules.rules", c.calendarRules.rules.map { it.id })
        val placeIds = c.places.map { it.id }.toSet()
        val profileIds = c.cueProfiles.map { it.id }.toSet()
        val routineIds = c.routines.map { it.id }.toSet()
        val alarmIds = c.alarms.map { it.id }.toSet()

        fun profileRef(path: String, id: String?) { if (id != null) a.check(id in profileIds, path, "unknown_ref", "unknown cue profile '$id'") }
        fun cond(path: String, cc: ContextCondition) {
            cc.places?.forEach { a.check(it in placeIds, "$path.places", "unknown_ref", "unknown place '$it'") }
        }
        fun pause(path: String, p: PauseSpec?) {
            when (p) {
                is PauseSpec.Until -> a.check(p.until.isAfter(p.setAt), path, "bad_pause", "pause end must be after setAt")
                is PauseSpec.UntilConditionEnds -> a.check(p.cap.isAfter(p.setAt), path, "bad_pause", "pause cap must be after setAt")
                else -> {}
            }
        }

        for (h in c.habits) {
            val p = "habits[${h.id}]"
            a.text("$p.name", h.name, 1, 40)
            pause("$p.pause", h.pause)
            when (h) {
                is IntervalHabit -> {
                    val (min, max, repMax) = when (h.kind) {
                        IntervalKind.Sunscreen -> Triple(30, 360, 5) // SUN 3.1
                        IntervalKind.Hydration -> Triple(15, 240, 3) // HYD §4
                        IntervalKind.Generic -> Triple(5, 720, 5)
                    }
                    a.range("$p.intervalMin", h.intervalMin, min, max, "PRODUCT §3.1/§4")
                    a.range("$p.repeat.maxRepeats", h.repeat.maxRepeats, 0, repMax, "GEN-4")
                    a.range("$p.repeat.everyMin", h.repeat.everyMin, 5, 60, "GEN-4")
                    a.range("$p.snoozeMin", h.snoozeMin, 5, 120, "§8.4")
                    a.check(h.days.isNotEmpty(), "$p.days", "empty", "at least one day")
                    (h.firstReminder as? FirstReminderPolicy.AfterDelay)?.let { a.range("$p.firstReminder.minutes", it.minutes, 0, 120, "FirstReminderPolicy") }
                    (h.reentry as? ReentryPolicy.RemindOnReentry)?.let { a.range("$p.reentry.graceMin", it.graceMin, 0, 30, "OutdoorReentryPolicy") }
                    cond("$p.condition", h.condition)
                    h.contextIntervals.forEachIndexed { i, ci ->
                        a.range("$p.contextIntervals[$i].intervalMin", ci.intervalMin, min, max, "HYD-5")
                        cond("$p.contextIntervals[$i].condition", ci.condition)
                    }
                    profileRef("$p.cueProfileId", h.cueProfileId)
                }
                is TransitionHabit -> {
                    a.range("$p.cooldownPerPlaceMin", h.cooldownPerPlaceMin, 0, 480, "BTL-4")
                    a.range("$p.dedupWindowMin", h.dedupWindowMin, 5, 120, "BTL-3")
                    a.range("$p.repeat.maxRepeats", h.repeat.maxRepeats, 0, 1, "§5")
                    a.range("$p.repeat.everyMin", h.repeat.everyMin, 5, 60, "GEN-4")
                    h.placeIds.forEach { a.check(it in placeIds, "$p.placeIds", "unknown_ref", "unknown place '$it'") }
                    h.scheduledDepartures.forEachIndexed { i, d ->
                        a.check(d.placeId in placeIds, "$p.scheduledDepartures[$i].placeId", "unknown_ref", "unknown place '${d.placeId}'")
                        a.check(d.days.isNotEmpty(), "$p.scheduledDepartures[$i].days", "empty", "at least one day")
                    }
                    profileRef("$p.cueProfileId", h.cueProfileId)
                }
            }
        }

        c.postureCycle.let { pc ->
            val p = "postureCycle"
            a.unique("$p.modes", pc.modes.map { it.id })
            pc.modes.forEach { m -> a.range("$p.modes[${m.id}].durationMin", m.durationMin, 5, 120, "§6") }
            if (pc.modes.isNotEmpty() || pc.enabled) a.check(pc.modes.any { it.enabled }, "$p.modes", "none_enabled", ">= 1 mode must be enabled (§6)")
            a.range("$p.confirmRepeat.maxRepeats", pc.confirmRepeat.maxRepeats, 0, 5, "§6")
            a.range("$p.confirmRepeat.everyMin", pc.confirmRepeat.everyMin, 2, 30, "§6")
            a.range("$p.shortInterruptionMin", pc.shortInterruptionMin, 0, 60, "§6")
            a.range("$p.snoozeMin", pc.snoozeMin, 1, 30, "§6")
            pc.extendOptionsMin.forEachIndexed { i, e -> a.range("$p.extendOptionsMin[$i]", e, 1, 120, "§6") }
            pause("$p.pause", pc.pause)
            profileRef("$p.cueProfileId", pc.cueProfileId)
        }

        a.check(c.medications.size <= 30, "medications", "too_many", "at most 30 items (§7)")
        for (m in c.medications) {
            val p = "medications[${m.id}]"
            a.text("$p.label", m.label, 1, 40)
            a.check(m.times.size in 1..12, "$p.times", "out_of_range", "1-12 times per day (§7)")
            a.check(m.times.toSet().size == m.times.size, "$p.times", "duplicate", "duplicate times")
            a.check(m.days.isNotEmpty(), "$p.days", "empty", "at least one day")
            if (m.startDate != null && m.endDate != null) a.check(!m.endDate.isBefore(m.startDate), "$p.endDate", "bad_range", "end before start")
            a.range("$p.repeat.maxRepeats", m.repeat.maxRepeats, 0, 12, "§7")
            a.range("$p.repeat.everyMin", m.repeat.everyMin, 5, 60, "§7")
            a.range("$p.snoozeMin", m.snoozeMin, 5, 60, "§7")
            a.range("$p.historyRetentionDays", m.historyRetentionDays, 30, 730, "§7")
            profileRef("$p.cueProfileId", m.cueProfileId)
        }

        for (r in c.routines) {
            val p = "routines[${r.id}]"
            a.text("$p.name", r.name, 1, 40)
            a.check(r.steps.isNotEmpty(), "$p.steps", "empty", "a routine needs at least one step")
            a.unique("$p.steps", r.steps.map { it.id })
            a.range("$p.recovery.thresholdMin", r.recovery.thresholdMin, 2, 60, "§10.1")
            (r.trigger as? RoutineTrigger.AfterAlarm)?.let { a.check(it.alarmId in alarmIds, "$p.trigger.alarmId", "unknown_ref", "unknown alarm '${it.alarmId}'") }
            (r.trigger as? RoutineTrigger.Schedule)?.let { a.check(it.days.isNotEmpty(), "$p.trigger.days", "empty", "at least one day") }
            r.steps.forEach { s ->
                val sp = "$p.steps[${s.id}]"
                a.text("$sp.name", s.name, 1, 40)
                a.check(s.phrase.length <= 200, "$sp.phrase", "bad_length", "phrase <= 200 chars")
                a.range("$sp.durationSec", s.durationSec, 0, 7200, "§10.1")
                a.range("$sp.repeat", s.repeat, 1, 10, "§10.1")
                profileRef("$sp.cueProfileId", s.cueProfileId)
            }
        }

        for (al in c.alarms) {
            val p = "alarms[${al.id}]"
            a.range("$p.snoozeMin", al.snoozeMin, 1, 30, "§11")
            a.range("$p.maxSnoozes", al.maxSnoozes, 0, 10, "§11")
            a.range("$p.spotifyStartTimeoutSec", al.spotifyStartTimeoutSec, 5, 30, "§11")
            a.range("$p.volumeRampSec", al.volumeRampSec, 0, 120, "§11")
            a.range("$p.ringTimeoutMin", al.ringTimeoutMin, 1, 30, "§11")
            al.followOnRoutineId?.let { a.check(it in routineIds, "$p.followOnRoutineId", "unknown_ref", "unknown routine '$it'") }
            if (al.days != null && al.days.isEmpty()) a.check(al.oneOffDate != null, "$p.days", "empty", "days empty and no oneOffDate")
            (al.source as? AlarmSource.SpotifyItem)?.let { a.check(it.uri.isNotBlank(), "$p.source.uri", "blank", "Spotify uri required") }
        }

        for (pl in c.places) {
            val p = "places[${pl.id}]"
            a.text("$p.name", pl.name, 1, 40)
            a.range("$p.radiusM", pl.radiusM, 50, 1000, "§1.2")
            pl.center?.let {
                a.check(it.lat in -90.0..90.0 && it.lng in -180.0..180.0, "$p.center", "out_of_range", "invalid coordinates")
            }
            pl.allowedRoutines.forEach { a.check(it in routineIds || c.habits.any { h -> h.id == it }, "$p.allowedRoutines", "unknown_ref", "unknown id '$it'") }
        }

        c.contextRules.let { r ->
            val p = "contextRules"
            a.range("$p.placeEnterDwellMin", r.placeEnterDwellMin, 1, 15, "CTX-2")
            a.range("$p.placeExitDwellMin", r.placeExitDwellMin, 0, 20, "CTX-3")
            a.range("$p.outdoorEnterDwellMin", r.outdoorEnterDwellMin, 0, 20, "CTX-4")
            a.range("$p.outdoorExitDwellMin", r.outdoorExitDwellMin, 0, 60, "CTX-5")
            a.range("$p.onFootSustainMin", r.onFootSustainMin, 0, 30, "CTX-6")
            a.range("$p.onFootHoldMin", r.onFootHoldMin, 5, 180, "CTX-6")
            val s = r.sessions
            a.range("$p.sessions.sustainedActiveToStartMin", s.sustainedActiveToStartMin, 1, 30, "§1.5")
            a.range("$p.sessions.idleToPauseMin", s.idleToPauseMin, 2, 60, "§1.5")
            a.range("$p.sessions.lockedToPauseMin", s.lockedToPauseMin, 0, 30, "§1.5")
            a.range("$p.sessions.pausedToEndMin", s.pausedToEndMin, 15, 240, "§1.5")
            a.range("$p.sessions.companionStaleToSuspendMin", s.companionStaleToSuspendMin, 3, 60, "§1.5")
            a.range("$p.sessions.suggestCooldownMin", s.suggestCooldownMin, 30, 1440, "§1.5")
            a.range("$p.sessions.manualSessionCapMin", s.manualSessionCapMin, 30, 1440, "§1.4")
        }

        validateCalendar(a, c.calendarRules, profileIds)

        c.settings.let { s ->
            val p = "settings"
            a.check(s.workDays.isNotEmpty(), "$p.workDays", "empty", "at least one work day")
            a.range("$p.collision.mergeWindowMin", s.collision.mergeWindowMin, 0, 10, "§8.3")
            a.range("$p.collision.minAudibleGapSec", s.collision.minAudibleGapSec, 0, 300, "§8.3")
            a.range("$p.collision.maxSpokenItems", s.collision.maxSpokenItems, 1, 5, "§8.3")
            a.range("$p.collision.speechMaxAgeSec", s.collision.speechMaxAgeSec, 30, 600, "§8.3")
            pause("$p.pauseAll", s.pauseAll)
        }
        c.cueProfiles.forEach { pr -> a.check(pr.phrase.en.length <= 200 && pr.phrase.he.length <= 200, "cueProfiles[${pr.id}].phrase", "bad_length", "phrase <= 200 chars") }
        return a.errors
    }

    private fun validateCalendar(a: Acc, cal: CalendarConfig, profileIds: Set<String>) {
        val p = "calendarRules"
        a.range("$p.syncHorizonDays", cal.syncHorizonDays, 1, 30, "§9")
        a.range("$p.maxCacheAgeHours", cal.maxCacheAgeHours, 6, 72, "§9")
        a.range("$p.snoozeMin", cal.snoozeMin, 1, 15, "§8.4")
        fun leads(path: String, l: List<Int>) = l.forEachIndexed { i, v -> a.range("$path[$i]", v, 0, 1440, "§9") }
        cal.rules.forEach { r ->
            a.text("$p.rules[${r.id}].name", r.name, 1, 40)
            a.check(r.anyOf.isNotEmpty(), "$p.rules[${r.id}].anyOf", "empty", "a rule needs at least one condition")
            a.check(r.noCue || r.leadsMin.isNotEmpty(), "$p.rules[${r.id}].leadsMin", "empty", "a cueing rule needs at least one lead time")
            leads("$p.rules[${r.id}].leadsMin", r.leadsMin)
        }
        cal.calendars.forEach { leads("$p.calendars[${it.calendarId}].leadsMin", it.leadsMin) }
        cal.overrides.forEach { o -> (o.decision as? app.daycue.domain.config.EventDecisionOverride.Always)?.let { leads("$p.overrides[${o.key}].leadsMin", it.leadsMin) } }
        cal.cueProfileId?.let { a.check(it in profileIds, "$p.cueProfileId", "unknown_ref", "unknown cue profile '$it'") }
    }
}
