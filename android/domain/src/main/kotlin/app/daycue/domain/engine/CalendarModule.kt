package app.daycue.domain.engine

import app.daycue.domain.config.CalendarCondition
import app.daycue.domain.config.CalendarConfig
import app.daycue.domain.config.CalendarDefaultPolicy
import app.daycue.domain.config.CalendarPreferenceMode
import app.daycue.domain.config.CalendarReminderSupplementPolicy
import app.daycue.domain.config.CalendarRule
import app.daycue.domain.config.CueType
import app.daycue.domain.config.EventDecisionOverride
import app.daycue.domain.config.OverrideScope
import app.daycue.domain.config.TentativeHandling
import app.daycue.domain.time.plusMin
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** "Why matched" for one event (CAL-1 preview, GEN-9). */
@Serializable
data class CalendarDecision(
    val eventKey: String,
    val cue: Boolean,
    val leadsMin: List<Int>,
    /** Step of PRODUCT §9.1 that decided (0–5). */
    val step: Int,
    val ruleId: String? = null,
    /** Machine-readable reason, e.g. "canceled", "override_never", "rule_match", "has_own_reminders", "default". */
    val reason: String,
    /** Which condition of the rule matched (e.g. "attendees", "keywords"). */
    val matched: String? = null,
    val kind: String? = null,
)

/** Calendar rule evaluation (PRODUCT §9.1). Titles are only substring-matched and displayed (CAL-5). */
object CalendarRules {

    /**
     * Text key for the generic kind word ("event") of a calendar cue whose deciding rule has no kind text.
     * Cues then use the `.event` key variants (`cue.calendar.title.event`, `cue.calendar.generic.title.event`,
     * `speech.calendar.generic.event`) and carry `kindKey = UNMATCHED_KIND_KEY` instead of a `kind` argument;
     * the app resolves it to the localized word where a template (e.g. the user's phrase template) needs `{kind}`.
     */
    const val UNMATCHED_KIND_KEY = "calendar.kind.event"

    fun decide(cal: CalendarConfig, ev: CalendarEvent): CalendarDecision {
        val k = ev.key
        val override = cal.overrides.firstOrNull { it.scope == OverrideScope.Instance && it.key == k }
            ?: ev.seriesId?.let { sid -> cal.overrides.firstOrNull { it.scope == OverrideScope.Series && it.key == sid } }
        // Step 0: hard exclusions (all-day only unless step 1 says Always).
        if (ev.status == EventStatus.Canceled) return CalendarDecision(k, false, emptyList(), 0, reason = "canceled")
        if (ev.self == SelfResponse.Declined) return CalendarDecision(k, false, emptyList(), 0, reason = "declined")
        if (cal.tentative == TentativeHandling.Exclude && (ev.status == EventStatus.Tentative || ev.self == SelfResponse.Tentative))
            return CalendarDecision(k, false, emptyList(), 0, reason = "tentative_excluded")
        if (ev.allDay && override?.decision !is EventDecisionOverride.Always) return CalendarDecision(k, false, emptyList(), 0, reason = "all_day")
        // Step 1: per-event / per-series override.
        when (val d = override?.decision) {
            is EventDecisionOverride.Always -> return CalendarDecision(k, true, d.leadsMin, 1, reason = "override_always_${override.scope.name.lowercase()}", kind = if (d.asMeeting) "meeting" else null)
            EventDecisionOverride.Never -> return CalendarDecision(k, false, emptyList(), 1, reason = "override_never_${override.scope.name.lowercase()}")
            null -> {}
        }
        // Step 2: calendar preference.
        cal.calendars.firstOrNull { it.calendarId == ev.calendarId }?.let { p ->
            when (p.mode) {
                CalendarPreferenceMode.Always -> return CalendarDecision(k, true, p.leadsMin, 2, reason = "calendar_always")
                CalendarPreferenceMode.Never -> return CalendarDecision(k, false, emptyList(), 2, reason = "calendar_never")
                CalendarPreferenceMode.Rules -> {}
            }
        }
        // OnlyWhenNoReminder: an event with its own reminders is never cued by steps 3–5.
        if (cal.supplement == CalendarReminderSupplementPolicy.OnlyWhenNoReminder && ev.hasOwnReminders)
            return CalendarDecision(k, false, emptyList(), 4, reason = "has_own_reminders")
        // Step 3: content rules in user order, first match decisive.
        for (r in cal.rules) {
            if (!r.enabled) continue
            val m = matchedCondition(r, ev) ?: continue
            return if (r.noCue) CalendarDecision(k, false, emptyList(), 3, r.id, "rule_no_cue", m)
            else CalendarDecision(k, true, r.leadsMin, 3, r.id, "rule_match", m, r.kind.en.ifBlank { r.name })
        }
        // Step 4: own reminders.
        if (cal.supplement == CalendarReminderSupplementPolicy.SupplementOnMatch && ev.hasOwnReminders)
            return CalendarDecision(k, false, emptyList(), 4, reason = "has_own_reminders")
        // Step 5: default.
        return when (val d = cal.defaultPolicy) {
            CalendarDefaultPolicy.NoCue -> CalendarDecision(k, false, emptyList(), 5, reason = "default")
            is CalendarDefaultPolicy.Cue -> CalendarDecision(k, true, d.leadsMin, 5, reason = "default")
        }
    }

    fun matchedCondition(r: CalendarRule, ev: CalendarEvent): String? = r.anyOf.firstNotNullOfOrNull { c ->
        when (c) {
            is CalendarCondition.Attendees -> "attendees".takeIf { ev.otherAttendees >= c.min }
            CalendarCondition.ConferencingLink -> "conferencing".takeIf { ev.hasConferencingLink }
            is CalendarCondition.Keywords -> c.words.firstOrNull { w -> w.isNotBlank() && ev.title.contains(w, ignoreCase = true) }?.let { "keyword" }
            is CalendarCondition.Color -> "color".takeIf { ev.color != null && c.colors.any { it.equals(ev.color, true) } }
            is CalendarCondition.Availability -> "availability".takeIf { ev.availability == c.availability }
        }
    }

    /** CAL-4. */
    fun isMeeting(cal: CalendarConfig, ev: CalendarEvent): Boolean {
        val d = decide(cal, ev)
        if (d.step == 0) return false
        if (ev.availability != app.daycue.domain.config.EventAvailability.Busy) return false
        if (d.step == 1) return d.kind == "meeting"
        return cal.rules.any { it.enabled && it.isMeeting && matchedCondition(it, ev) != null }
    }
}

internal object CalendarModule {

    fun key(ev: CalendarEvent) = "cal:${ev.key}"

    private fun cacheFresh(run: Run): Boolean {
        val at = run.st.calendar.syncedAt ?: return false
        return run.now.isBefore(at.plus(Duration.ofHours(run.config.calendarRules.maxCacheAgeHours.toLong())))
    }

    fun meetingNow(run: Run): CalendarEvent? {
        if (!cacheFresh(run)) return null
        val now = run.now
        return run.st.calendar.events.filter { !now.isBefore(it.start) && now.isBefore(it.end) && CalendarRules.isMeeting(run.config.calendarRules, it) }
            .minByOrNull { it.end }
    }

    fun meetingWakes(run: Run) {
        if (!cacheFresh(run)) return
        val now = run.now
        for (ev in run.st.calendar.events) {
            if (!CalendarRules.isMeeting(run.config.calendarRules, ev)) continue
            if (ev.start.isAfter(now)) run.wake(ev.start, WakePrecision.Exact, "meeting start")
            if (ev.end.isAfter(now)) run.wake(ev.end, WakePrecision.Exact, "meeting end")
        }
        run.st.calendar.syncedAt?.let { run.wake(it.plus(Duration.ofHours(run.config.calendarRules.maxCacheAgeHours.toLong())), WakePrecision.Inexact, "calendar cache age") }
    }

    fun handle(run: Run, ev: Event) {
        val cs = run.st.calendar
        when (ev) {
            is Event.CalendarSynced -> {
                if (cs.syncedAt != null && ev.syncedAt.isBefore(cs.syncedAt)) return // older sync result
                val byKey = ev.events.associateBy { it.key }
                val records = cs.records.toMutableMap()
                for ((k, rec) in cs.records) {
                    val e = byKey[k]
                    val decision = e?.let { CalendarRules.decide(run.config.calendarRules, it) }
                    // CAL-2: canceled/declined/removed retract; moved events reschedule (old cue retracted).
                    if (e == null || decision?.cue != true || e.start != rec.start) {
                        rec.cue?.let { run.dismiss("cal:$k", it.cueId, if (e == null) "removed" else if (decision?.cue != true) "excluded" else "moved") }
                        records -= k
                        run.history("cal:$k", HistoryKind.Retracted, "CAL-2", rec.cue?.cueId, mapOf("reason" to if (e == null) "removed" else if (decision?.cue != true) decision?.reason ?: "" else "moved"))
                    }
                }
                run.st = run.st.copy(calendar = CalendarState(ev.events, ev.syncedAt, records))
            }
            is Event.CalendarAck -> {
                val (k, rec) = cs.records.entries.firstOrNull { it.value.cue?.cueId == ev.cueId } ?: return
                run.dismiss("cal:$k", ev.cueId, "ack")
                run.st = run.st.copy(calendar = cs.copy(records = cs.records + (k to rec.copy(cue = null, snoozedUntil = null))))
                run.history("cal:$k", HistoryKind.Acked, "CAL-1", ev.cueId)
            }
            is Event.CalendarSnooze -> {
                val (k, rec) = cs.records.entries.firstOrNull { it.value.cue?.cueId == ev.cueId } ?: return
                val until = minOf(run.now.plusMin(run.config.calendarRules.snoozeMin), rec.start) // §8.4 never later than start
                run.dismiss("cal:$k", ev.cueId, "snoozed")
                run.st = run.st.copy(calendar = cs.copy(records = cs.records + (k to rec.copy(cue = null, snoozedUntil = until))))
                run.history("cal:$k", HistoryKind.Snoozed, "GEN-3", ev.cueId)
            }
            else -> {}
        }
    }

    fun evaluate(run: Run) {
        if (!cacheFresh(run)) return // maxCacheAge: no new calendar cues
        val now = run.now
        val cal = run.config.calendarRules
        for (ev in run.st.calendar.events) {
            if (!now.isBefore(ev.start)) continue
            val d = CalendarRules.decide(cal, ev)
            if (!d.cue || d.leadsMin.isEmpty()) continue
            val rec = run.st.calendar.records[ev.key] ?: CalendarCueRecord(ev.key, ev.start)
            val snooze = rec.snoozedUntil
            if (snooze != null) {
                if (!now.isBefore(snooze)) propose(run, ev, d, rec, rec.leadMin ?: d.leadsMin.min(), emptySet(), "GEN-3")
                else run.wake(snooze, WakePrecision.Exact, "calendar snooze")
                continue
            }
            val due = d.leadsMin.filter { it !in rec.consumedLeads && !ev.start.minusSeconds(it * 60L).isAfter(now) }
            if (due.isNotEmpty()) {
                // CAL-3 / GEN-6: only the latest due lead is delivered; earlier ones are consumed silently.
                propose(run, ev, d, rec, due.min(), due.toSet(), if (due.size > 1) "CAL-3" else "CAL-1")
            }
            d.leadsMin.filter { it !in rec.consumedLeads && it !in due }.map { ev.start.minusSeconds(it * 60L) }.filter { it.isAfter(now) }.minOrNull()
                ?.let { run.wake(it, WakePrecision.Exact, "calendar lead") }
        }
    }

    private fun propose(run: Run, ev: CalendarEvent, d: CalendarDecision, rec: CalendarCueRecord, lead: Int, consumed: Set<Int>, rule: String) {
        val cal = run.config.calendarRules
        val lang = run.config.settings.language
        // User kind text from the deciding rule, if any. Otherwise no English fallback word: the cue uses the
        // `.event` key variants and carries `kindKey` (a localizable text key) for templates that need {kind}.
        val userKind = d.ruleId?.let { id -> cal.rules.firstOrNull { it.id == id }?.kind?.get(lang) }?.ifBlank { null } ?: d.kind?.ifBlank { null }
        val sfx = if (userKind == null) ".event" else ""
        val kindArgs = if (userKind != null) mapOf("kind" to userKind) else mapOf("kindKey" to CalendarRules.UNMATCHED_KIND_KEY)
        val minutes = Duration.between(run.now, ev.start).toMinutes().coerceAtLeast(0).toString()
        // The title travels as data for display (CAL-5); lock-screen exposure is governed by `lockScreen`/`publicTitle`.
        val args = kindArgs + mapOf("minutes" to minutes, "start" to ev.start.toString(), "title" to ev.title)
        val now = run.now
        run.propose(Proposal(
            itemKey = key(ev), notificationKey = key(ev), type = CueType.Calendar, dueAt = now, cueId = null,
            title = Text("cue.calendar.title$sfx", args), body = Text("cue.calendar.body", mapOf("template" to cal.phraseTemplate.get(lang)) + args),
            actions = listOf(CueAction(ActionKind.GotIt, Text("action.got_it")), CueAction(ActionKind.Snooze, Text("action.snooze", mapOf("minutes" to cal.snoozeMin.toString())), cal.snoozeMin)),
            why = WhyNow(rule, "why.calendar", mapOf("step" to d.step.toString(), "rule" to (d.ruleId ?: ""), "reason" to d.reason, "matched" to (d.matched ?: ""), "leadMin" to lead.toString())),
            lockScreen = if (cal.showTitlesOnLockScreen) LockScreenVisibility.Public else LockScreenVisibility.Private,
            publicTitle = Text("cue.calendar.generic.title$sfx", kindArgs + mapOf("minutes" to minutes)),
            // SPK-4: title only if speakTitles.
            speech = if (cal.speakTitles) Text("speech.calendar.title", kindArgs + mapOf("minutes" to minutes, "title" to ev.title))
                else Text("speech.calendar.generic$sfx", kindArgs + mapOf("minutes" to minutes)),
            shortName = Text("short.calendar"),
            silent = run.quietUntil() != null, // QH-2
            profileId = cal.cueProfileId,
            commit = { st, id ->
                val cur = st.calendar.records[ev.key] ?: rec
                st.copy(calendar = st.calendar.copy(records = st.calendar.records + (ev.key to cur.copy(consumedLeads = cur.consumedLeads + consumed + lead,
                    cue = ActiveCue(id, now, now, exhausted = true), leadMin = lead, snoozedUntil = null))))
            },
        ))
    }

    @Suppress("unused") private fun unused(i: Instant) = i
}
