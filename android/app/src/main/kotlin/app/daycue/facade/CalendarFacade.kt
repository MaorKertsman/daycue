package app.daycue.facade

import android.Manifest
import app.daycue.AppContainer
import app.daycue.domain.config.CalendarPreference
import app.daycue.domain.config.CalendarPreferenceMode
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.EventDecisionOverride
import app.daycue.domain.config.Language
import app.daycue.domain.config.OverrideScope
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.CalendarDecision
import app.daycue.domain.engine.CalendarEvent
import app.daycue.domain.engine.EngineState
import app.daycue.domain.query.Queries
import app.daycue.engine.ApplyOutcome
import app.daycue.integrations.calendar.CalendarSyncResult
import app.daycue.integrations.calendar.DeviceCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant

/** One device calendar for the selection list. */
data class CalendarChoice(val calendar: DeviceCalendar, val selected: Boolean, val mode: CalendarPreferenceMode?, val leadsMin: List<Int>)

/**
 * One upcoming event in the CAL-1 preview. [decision] is the domain's "why matched" (step, rule,
 * reason, matched condition); [why] is the same in words. [nextCueAt] = the next lead not yet delivered.
 * `event.title` is untrusted text: display only.
 */
data class CalendarPreviewRow(
    val event: CalendarEvent,
    val decision: CalendarDecision,
    val nextCueAt: Instant?,
    val calendarName: String?,
    val why: String,
    /** Overrides currently set for this instance / its series (for the one-tap toggles' state). */
    val instanceOverride: EventDecisionOverride?,
    val seriesOverride: EventDecisionOverride?,
)

/** Google Calendar via the Android Calendar Provider (read-only; ADR 0004). APP_API.md §11. */
class CalendarFacade internal constructor(private val c: AppContainer, private val f: DayCueFacade) {

    /** The only permission needed. Request it with `RequestPermission`, then call [onPermissionChanged]. */
    val permission: String = Manifest.permission.READ_CALENDAR

    fun hasPermission(): Boolean = c.calendar.hasPermission()

    fun onPermissionChanged() = c.calendar.onPermissionChanged()

    val lastSync: StateFlow<CalendarSyncResult?> get() = c.calendar.sync.last

    /** Sync now (Readiness "Refresh", pull-to-refresh). */
    suspend fun refresh(): CalendarSyncResult = c.calendar.runSync("ui")

    private val names = MutableStateFlow<Map<String, String>>(emptyMap())

    /** All device calendars (empty without permission), with the current selection from config. */
    suspend fun listCalendars(): List<CalendarChoice> {
        if (!hasPermission()) return emptyList()
        val cals = withContext(Dispatchers.IO) { runCatching { c.calendar.source.calendars() }.getOrDefault(emptyList()) }
        names.value = cals.associate { it.id.toString() to it.displayName }
        val prefs = c.host.ensureLoaded().config.calendarRules.calendars.associateBy { it.calendarId }
        return cals.map { cal -> prefs[cal.id.toString()].let { p -> CalendarChoice(cal, p != null, p?.mode, p?.leadsMin.orEmpty()) } }
    }

    /** Select a calendar (default mode `Rules`: the content rules decide). */
    suspend fun selectCalendar(calendarId: Long, mode: CalendarPreferenceMode = CalendarPreferenceMode.Rules, leadsMin: List<Int> = listOf(10)): ApplyOutcome =
        f.apply(listOf(ConfigOp.SetCalendarPreference(CalendarPreference(calendarId.toString(), mode, leadsMin))))

    suspend fun deselectCalendar(calendarId: Long): ApplyOutcome = f.apply(listOf(ConfigOp.RemoveCalendarPreference(calendarId.toString())))

    /** CAL-1 "Never for this calendar". */
    suspend fun neverForCalendar(calendarId: String): ApplyOutcome =
        f.apply(listOf(ConfigOp.SetCalendarPreference(CalendarPreference(calendarId, CalendarPreferenceMode.Never))))

    /** Upcoming events with decision + why, recomputed on every engine/config change. */
    fun preview(): Flow<List<CalendarPreviewRow>> =
        c.host.snapshot.filterNotNull().combine(names) { s, n -> rows(s.config, s.state, n, Instant.now()) }.flowOn(Dispatchers.Default)

    /**
     * One-tap correction (CAL-1): `Always for this event` / `Never for this event`, for this instance or the
     * whole series. [leadsMin] for Always defaults to the leads the event would otherwise get, else 10 min.
     */
    suspend fun always(row: CalendarPreviewRow, scope: OverrideScope = OverrideScope.Instance, leadsMin: List<Int>? = null): ApplyOutcome =
        setOverride(row, scope, EventDecisionOverride.Always(leadsMin ?: row.decision.leadsMin.ifEmpty { listOf(10) }))

    suspend fun never(row: CalendarPreviewRow, scope: OverrideScope = OverrideScope.Instance): ApplyOutcome =
        setOverride(row, scope, EventDecisionOverride.Never)

    /** Removes the override (back to the rules). */
    suspend fun clearOverride(row: CalendarPreviewRow, scope: OverrideScope): ApplyOutcome = setOverride(row, scope, null)

    private suspend fun setOverride(row: CalendarPreviewRow, scope: OverrideScope, d: EventDecisionOverride?): ApplyOutcome {
        val key = if (scope == OverrideScope.Series) row.event.seriesId ?: row.event.key else row.event.key
        val effScope = if (scope == OverrideScope.Series && row.event.seriesId == null) OverrideScope.Instance else scope
        return f.apply(listOf(ConfigOp.SetEventOverride(key, effScope, d)))
    }

    private fun rows(cfg: DayCueConfig, st: EngineState, names: Map<String, String>, now: Instant): List<CalendarPreviewRow> {
        val lang = cfg.settings.language
        val ov = cfg.calendarRules.overrides
        return Queries.calendarPreview(cfg, st).filter { (e, _) -> e.end.isAfter(now) }.map { (e, d) ->
            val consumed = st.calendar.records[e.key]?.takeIf { it.start == e.start }?.consumedLeads.orEmpty()
            val next = if (!d.cue) null else d.leadsMin.filter { it !in consumed }.map { e.start.minusSeconds(it * 60L) }.filter { it.isAfter(now) }.minOrNull()
            CalendarPreviewRow(
                e, d, next, names[e.calendarId], whyText(cfg, d, lang),
                ov.firstOrNull { it.scope == OverrideScope.Instance && it.key == e.key }?.decision,
                e.seriesId?.let { sid -> ov.firstOrNull { it.scope == OverrideScope.Series && it.key == sid }?.decision },
            )
        }
    }

    /** "Why matched" in words (`strings_engine.xml` `dc_calmatch_*`). */
    fun whyText(cfg: DayCueConfig, d: CalendarDecision, lang: Language = cfg.settings.language): String {
        val ruleName = d.ruleId?.let { id -> cfg.calendarRules.rules.firstOrNull { it.id == id }?.name }.orEmpty()
        val reason = if (d.reason == "default" && d.cue) "default_cue" else d.reason
        val matched = d.matched?.let { c.text.app("calmatch.cond.$it", lang) }.orEmpty()
        return c.text.app("calmatch.$reason", lang, mapOf("rule" to ruleName, "matched" to matched, "leads" to d.leadsMin.joinToString(", ")))
    }
}
