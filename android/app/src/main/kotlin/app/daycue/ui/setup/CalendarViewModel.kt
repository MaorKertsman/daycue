package app.daycue.ui.setup

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.daycue.R
import app.daycue.domain.config.CalendarConfig
import app.daycue.domain.config.CalendarPreferenceMode
import app.daycue.domain.config.CalendarRule
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.OverrideScope
import app.daycue.domain.edit.ConfigOp
import app.daycue.facade.CalendarChoice
import app.daycue.facade.CalendarPreviewRow
import app.daycue.integrations.calendar.CalendarSyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** Calendar access, selection, rules and the upcoming-events preview. Titles in the preview are untrusted data. */
class CalendarViewModel(app: Application) : SetupViewModel(app) {

    val config: StateFlow<DayCueConfig?> = facade.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), facade.snapshot.value?.config)
    val lastSync: StateFlow<CalendarSyncResult?> = facade.calendar.lastSync

    /** When the cached events were last refreshed (engine state), for the stale banner. */
    val syncedAt: StateFlow<Instant?> = facade.snapshot.map { it?.state?.calendar?.syncedAt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val permission = MutableStateFlow(facade.calendar.hasPermission())
    val calendars = MutableStateFlow<List<CalendarChoice>?>(null)
    val syncing = MutableStateFlow(false)

    /** Null while loading, then the rows (possibly empty: "No events in the next 7 days"). */
    val preview: StateFlow<List<CalendarPreviewRow>?> = facade.calendar.preview()
        .map<List<CalendarPreviewRow>, List<CalendarPreviewRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The engine's "why matched" sentence in the language the UI is shown in (not the config language). */
    fun why(row: CalendarPreviewRow, language: app.daycue.domain.config.Language): String {
        val cfg = config.value ?: return row.why
        return facade.calendar.whyText(cfg, row.decision, language)
    }

    val permissionName: String get() = facade.calendar.permission

    /** Re-read permission and the calendar list (call on resume and after the permission result). */
    fun reload() {
        viewModelScope.launch {
            permission.value = facade.calendar.hasPermission()
            calendars.value = facade.calendar.listCalendars()
        }
    }

    fun onPermissionResult() {
        facade.calendar.onPermissionChanged()
        reload()
        refresh()
    }

    fun refresh() {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            facade.calendar.refresh()
            syncing.value = false
            calendars.value = facade.calendar.listCalendars()
        }
    }

    fun setCalendarMode(choice: CalendarChoice, mode: CalendarPreferenceMode?, leads: List<Int>) {
        viewModelScope.launch {
            val id = choice.calendar.id
            if (mode == null) facade.calendar.deselectCalendar(id) else facade.calendar.selectCalendar(id, mode, leads)
            calendars.value = facade.calendar.listCalendars()
            SetupBus.post(Snack(R.string.su_cal_saved, undo = true))
        }
    }

    fun always(row: CalendarPreviewRow, scope: OverrideScope) { viewModelScope.launch { facade.calendar.always(row, scope); SetupBus.post(Snack(R.string.su_cal_always_done, true)) } }
    fun never(row: CalendarPreviewRow, scope: OverrideScope) { viewModelScope.launch { facade.calendar.never(row, scope); SetupBus.post(Snack(R.string.su_cal_never_done, true)) } }
    fun clear(row: CalendarPreviewRow, scope: OverrideScope) { viewModelScope.launch { facade.calendar.clearOverride(row, scope); SetupBus.post(Snack(R.string.su_cal_cleared, true)) } }
    fun neverForCalendar(calendarId: String) { viewModelScope.launch { facade.calendar.neverForCalendar(calendarId); SetupBus.post(Snack(R.string.su_cal_never_calendar_done, true)) } }

    suspend fun saveRule(rule: CalendarRule): EditResult = edit(listOf(ConfigOp.UpsertCalendarRule(rule)), R.string.su_rule_saved)
    fun deleteRule(id: String) { viewModelScope.launch { edit(listOf(ConfigOp.DeleteCalendarRule(id)), R.string.su_rule_deleted) } }
    fun setRuleEnabled(rule: CalendarRule, enabled: Boolean) { viewModelScope.launch { edit(listOf(ConfigOp.UpsertCalendarRule(rule.copy(enabled = enabled)))) } }
    fun reorder(ids: List<String>) { viewModelScope.launch { edit(listOf(ConfigOp.ReorderCalendarRules(ids)), undo = false) } }
    suspend fun saveCalendarConfig(config: CalendarConfig): EditResult = edit(listOf(ConfigOp.SetCalendarConfig(config)))
}
