package app.daycue.ui.cues

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.data.db.HistoryEventEntity
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.ValidationError
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.query.TodayView
import app.daycue.engine.ApplyOutcome
import app.daycue.facade.DayCueFacade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot message for the snackbar host. [undo] adds the Undo action (reversible edits, UX section 0.5). */
data class CuesMessage(@StringRes val textRes: Int, val args: List<String> = emptyList(), val undo: Boolean = true)

/**
 * The single ViewModel behind the Cues tab. It only reads facade flows and sends `ConfigOp`s through
 * `facade.apply` (APP_API.md). Validation errors of the last rejected edit are kept by field path.
 */
class CuesViewModel(app: Application) : AndroidViewModel(app) {
    val facade: DayCueFacade = (app as DayCueApplication).container.facade

    val config: StateFlow<DayCueConfig?> = facade.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val engine: StateFlow<EngineState?> = facade.engineState.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val today: StateFlow<TodayView?> = facade.today(10_000).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val canUndo: StateFlow<Boolean> = facade.canUndo.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _errors = MutableStateFlow<List<ValidationError>>(emptyList())
    /** Errors of the most recent rejected edit, shown inline by field path. Cleared by the next accepted edit. */
    val errors: StateFlow<List<ValidationError>> = _errors.asStateFlow()

    private val _messages = MutableSharedFlow<CuesMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<CuesMessage> = _messages.asSharedFlow()

    fun clearErrors() { _errors.value = emptyList() }

    /**
     * Applies [ops] as one change set. Accepted: errors cleared, snackbar (with Undo unless [undoable] is false).
     * Rejected by validation: errors kept by path. Conflict: the user is told nothing was saved.
     */
    fun edit(
        ops: List<ConfigOp>,
        message: CuesMessage? = CuesMessage(R.string.cues_saved),
        onResult: (ApplyOutcome) -> Unit = {},
    ) {
        viewModelScope.launch {
            val out = facade.apply(ops)
            when (out) {
                is ApplyOutcome.Applied -> {
                    _errors.value = emptyList()
                    if (message != null) _messages.emit(message)
                }
                is ApplyOutcome.Invalid -> _errors.value = out.errors
                is ApplyOutcome.Conflict -> _messages.emit(CuesMessage(R.string.cues_conflict, undo = false))
                ApplyOutcome.NothingToUndo -> Unit
            }
            onResult(out)
        }
    }

    fun edit(op: ConfigOp, message: CuesMessage? = CuesMessage(R.string.cues_saved)) = edit(listOf(op), message)

    fun undo() {
        viewModelScope.launch { facade.undo() }
    }

    fun dispatch(event: Event) = facade.dispatchAsync(event)

    fun history(subjectType: String, subjectId: String, limit: Int = 50): Flow<List<HistoryEventEntity>> =
        facade.historyFor(subjectType, subjectId, limit)

    fun allHistory(limit: Int = 400): Flow<List<HistoryEventEntity>> = facade.history(limit)

    suspend fun preview(ops: List<ConfigOp>) = facade.preview(ops)
}

/** Extension kept here so the ViewModel stays the only caller of the facade's action functions. */
fun CuesViewModel.testReminder(type: app.daycue.domain.config.CueType) {
    viewModelScope.launch { facade.sendTestReminder(type) }
}

fun CuesViewModel.testAlarm(alarmId: String) {
    viewModelScope.launch { facade.testAlarm(alarmId) }
}

/** The cue profile id an item uses: its explicit one, else the type default. Null if none exists. */
fun CuesViewModel.profileId(type: app.daycue.domain.config.CueType, explicit: String?): String? =
    config.value?.profileFor(type, explicit)?.id
