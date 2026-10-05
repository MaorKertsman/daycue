package app.daycue.ui.today

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.daycue.R
import app.daycue.data.db.HistoryEventEntity
import app.daycue.domain.config.Environment
import app.daycue.domain.config.SessionKind
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.engine.PauseChoice
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.engine.PostureAction
import app.daycue.domain.engine.RoutineAction
import app.daycue.domain.engine.SessionAnswer
import app.daycue.domain.engine.SlotRef
import app.daycue.facade.DayCueFacade
import app.daycue.system.ReadinessReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A short message for the snackbar; [undo] (when set) is offered as the Undo action. */
class TodayMessage(@StringRes val text: Int, val undo: (() -> Unit)? = null)

/**
 * Today's ViewModel: a thin layer over [DayCueFacade]. It maps `todayView` + config + state into [TodayModel]
 * (pure [TodayMapper]) and forwards the owner's taps as engine events. No rules live here.
 */
class TodayViewModel(private val facade: DayCueFacade) : ViewModel() {

    val model: StateFlow<TodayModel?> = combine(facade.today(), facade.snapshot.filterNotNull()) { view, snap ->
        TodayMapper.map(view, snap.config, snap.state, ZoneId.systemDefault())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val readiness: StateFlow<ReadinessReport?> get() = facade.readiness

    private val _messages = MutableSharedFlow<TodayMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<TodayMessage> = _messages

    fun refreshReadiness() { viewModelScope.launch { facade.refreshReadiness() } }

    // ---- Due item actions (the engine decides what they mean; cueId = null for in-app taps) ----

    private fun dispatch(e: Event) { viewModelScope.launch { facade.dispatch(e) } }

    fun habitAck(habitId: String) = dispatch(Event.HabitAck(habitId))
    fun habitSnooze(habitId: String) = dispatch(Event.HabitSnooze(habitId))
    fun bottleAck(habitId: String, notNeeded: Boolean) = dispatch(Event.BottleAck(habitId, notNeeded))
    fun doseTaken(slot: SlotRef) = dispatch(Event.MedicationTaken(slot))
    fun doseSnooze(slot: SlotRef) = dispatch(Event.MedicationSnooze(slot))
    fun posture(action: PostureAction) = dispatch(Event.PostureControl(action))
    fun calendarAck(cueId: String) = dispatch(Event.CalendarAck(cueId))
    fun calendarSnooze(cueId: String) = dispatch(Event.CalendarSnooze(cueId))
    fun routineSkipToday(routineId: String) = dispatch(Event.RoutineControl(RoutineAction.PromptSkipToday(routineId)))
    fun sessionAnswer(placeId: String, answer: SessionAnswer, cueId: String?) = dispatch(Event.SessionPromptAnswer(placeId, answer, cueId))

    fun pause(target: PauseTarget, choice: PauseChoice) {
        viewModelScope.launch {
            facade.dispatch(Event.Pause(target, choice))
            _messages.emit(TodayMessage(R.string.app_msg_paused, undo = { dispatch(Event.Resume(target)) }))
        }
    }

    fun resume(target: PauseTarget) = dispatch(Event.Resume(target))

    // ---- Context controls, each with an Undo that restores the previous override state ----

    fun setEnvironment(value: Environment, duration: OverrideDuration) {
        val previous = model.value?.context?.envOverride
        viewModelScope.launch {
            facade.setEnvironment(value, duration)
            _messages.emit(TodayMessage(R.string.app_msg_context_set, undo = {
                viewModelScope.launch {
                    if (previous == null) facade.clearEnvironmentOverride() else facade.setEnvironment(previous.value, OverrideDuration.UntilTransition)
                }
            }))
        }
    }

    fun clearEnvironment() {
        val previous = model.value?.context?.envOverride
        viewModelScope.launch {
            facade.clearEnvironmentOverride()
            _messages.emit(TodayMessage(R.string.app_msg_context_auto, undo = {
                if (previous != null) viewModelScope.launch { facade.setEnvironment(previous.value, OverrideDuration.UntilTransition) }
            }))
        }
    }

    /** "I'm at <place>" / "Not at a saved place" (placeId null). Undo restores the previous manual place, or clears it. */
    fun setPlace(placeId: String?, duration: OverrideDuration) {
        val previous = model.value?.context?.placeOverride
        viewModelScope.launch {
            facade.setPlace(placeId, duration)
            _messages.emit(TodayMessage(R.string.app_msg_context_set, undo = {
                viewModelScope.launch {
                    if (previous == null) facade.clearPlaceOverride() else facade.setPlace(previous.value.placeId, OverrideDuration.UntilTransition)
                }
            }))
        }
    }

    fun clearPlace() {
        val previous = model.value?.context?.placeOverride
        viewModelScope.launch {
            facade.clearPlaceOverride()
            _messages.emit(TodayMessage(R.string.app_msg_context_auto, undo = {
                if (previous != null) viewModelScope.launch { facade.setPlace(previous.value.placeId, OverrideDuration.UntilTransition) }
            }))
        }
    }

    fun startSession(kind: SessionKind, duration: OverrideDuration) {
        viewModelScope.launch {
            facade.startSession(kind, duration)
            _messages.emit(TodayMessage(R.string.app_msg_session_started, undo = { viewModelScope.launch { facade.endSession() } }))
        }
    }

    fun endSession() {
        val previous = model.value?.context?.session
        viewModelScope.launch {
            facade.endSession()
            _messages.emit(TodayMessage(R.string.app_msg_session_ended, undo = {
                if (previous != null) viewModelScope.launch { facade.startSession(previous.kind, OverrideDuration.UntilChanged) }
            }))
        }
    }

    fun pauseDetection(duration: OverrideDuration) {
        viewModelScope.launch {
            facade.pauseAutoDetection(duration)
            _messages.emit(TodayMessage(R.string.app_msg_detection_paused, undo = { viewModelScope.launch { facade.resumeAutoDetection() } }))
        }
    }

    fun resumeDetection() { viewModelScope.launch { facade.resumeAutoDetection() } }

    fun leavingNow() {
        viewModelScope.launch {
            facade.leavingNow()
            _messages.emit(TodayMessage(R.string.app_msg_leaving_now))
        }
    }

    // ---- Item detail ----

    /** Last five history rows of an item key (`habit:<id>`), test rows excluded (GEN-10). */
    fun history(itemKey: String): Flow<List<HistoryEventEntity>> {
        val type = itemKey.substringBefore(':')
        val id = itemKey.substringAfter(':', itemKey)
        return facade.historyFor(if (type == "habit") "habit" else type, id, 12).map { rows -> rows.filter { !it.isTest }.take(5) }
    }

    class Factory(private val facade: DayCueFacade) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TodayViewModel(facade) as T
    }
}
