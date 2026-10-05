package app.daycue.ui.readiness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.engine.Event
import app.daycue.facade.ContextFix
import app.daycue.facade.ContextReadinessItem
import app.daycue.facade.ContextReadinessReport
import app.daycue.facade.DayCueFacade
import app.daycue.facade.LocationFix
import app.daycue.integrations.relay.RelayStatus
import app.daycue.integrations.relay.SyncStatus
import app.daycue.system.ReadinessReport
import app.daycue.ui.app.PermissionActions
import app.daycue.ui.app.ScrollPage
import app.daycue.ui.app.rememberFacade
import app.daycue.ui.app.rememberPermissionActions
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.ReadinessRow
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.StatusNotch
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessStatus as SystemStatus
import app.daycue.ui.components.ReadinessStatus as UiStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadinessViewModel(private val facade: DayCueFacade) : ViewModel() {
    val readiness: StateFlow<ReadinessReport?> get() = facade.readiness
    val context: StateFlow<ContextReadinessReport?> get() = facade.contextReadiness
    val relay: StateFlow<RelayStatus> get() = facade.remote.status
    val paired: StateFlow<Boolean> get() = facade.remote.paired
    val alarmEnabled: StateFlow<Boolean> = facade.config.map { c -> c.alarms.any { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _testSent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val testSent: StateFlow<Boolean> get() = _testSent

    fun refresh() {
        viewModelScope.launch { facade.refreshReadiness() }
        viewModelScope.launch { facade.refreshContextReadiness() }
    }

    fun afterPermissionResult() {
        viewModelScope.launch {
            facade.places.onPermissionsChanged()
            facade.refreshReadiness()
            facade.refreshContextReadiness()
        }
    }

    fun sendTest() {
        viewModelScope.launch {
            facade.sendTestReminder()
            _testSent.value = true
        }
    }

    fun nextLocationPermissions(): List<String> = facade.places.nextPermissionStep().permissions
    fun activityPermissions(): List<String> = facade.places.activityRecognitionPermissions()
    fun locationIntent(fix: LocationFix) = facade.places.fixIntent(fix)
    fun syncCalendarNow() { viewModelScope.launch { facade.calendar.refresh(); facade.refreshContextReadiness() } }

    class Factory(private val facade: DayCueFacade) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadinessViewModel(facade) as T
    }
}

private fun SystemStatus.ui(): UiStatus = when (this) {
    SystemStatus.Ready -> UiStatus.Ready
    SystemStatus.Limited -> UiStatus.Limited
    SystemStatus.Off -> UiStatus.Off
    SystemStatus.NotNeeded -> UiStatus.NotNeeded
    SystemStatus.Checking -> UiStatus.Checking
}

/**
 * Reminder readiness (UX 3.13): what can delay or hide a reminder, one consequence sentence and one repair action
 * per row, plus a real test reminder. Statuses refresh whenever the screen resumes (after the system screen).
 * Reachable from Today (the last Next row) and from Setup.
 */
@Composable
fun ReadinessScreen(onBack: () -> Unit) {
    val facade = rememberFacade()
    val vm: ReadinessViewModel = viewModel(factory = ReadinessViewModel.Factory(facade))
    val actions = rememberPermissionActions(facade) { vm.afterPermissionResult() }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(onBack = onBack)
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val report by vm.readiness.collectAsState()
    val ctx by vm.context.collectAsState()
    val alarm by vm.alarmEnabled.collectAsState()
    val relay by vm.relay.collectAsState()
    val paired by vm.paired.collectAsState()
    val testSent by vm.testSent.collectAsState()
    val c = DayCueTheme.colors

    ScrollPage(stringResource(R.string.app_rd_title), onBack, snackbar = snackbar) {
        Spacer(Modifier.height(8.dp))
        val r = report
        if (r == null) {
            Text(stringResource(R.string.readiness_checking), style = DayCueTheme.type.headline, color = c.ink)
        } else {
            val problems = ReadinessCopy.problemCount(r, alarm)
            Text(
                if (problems == 0) stringResource(R.string.app_rd_all_ready) else pluralStringResource(R.plurals.app_rd_problems, problems, problems),
                style = DayCueTheme.type.headline,
                color = c.ink,
            )
        }
        Spacer(Modifier.height(DayCueSpacing.inRow))
        PrimaryButton(stringResource(R.string.app_rd_send_test), { vm.sendTest() }, Modifier.fillMaxWidth())
        if (testSent) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_rd_test_sent), style = DayCueTheme.type.bodySmall, color = c.ink2)
        }

        SectionHeader(stringResource(R.string.app_rd_section_reminders))
        if (r != null) {
            val rows = ReadinessCopy.sorted(r).filter { it.id != ReadinessId.FullScreenIntent || alarm }
            rows.forEachIndexed { i, item ->
                ReadinessRow(
                    name = stringResource(ReadinessCopy.name(item.id)),
                    status = item.status.ui(),
                    consequence = stringResource(ReadinessCopy.consequence(item)),
                    onFix = if (item.hasFix) ({ fix(item.id, actions) }) else null,
                    fixLabel = stringResource(ReadinessCopy.fixLabel(item)),
                    divider = i < rows.lastIndex,
                )
            }
        }

        val contextRows = ctx?.items.orEmpty().filter { it.status != SystemStatus.NotNeeded }
        if (contextRows.isNotEmpty()) {
            SectionHeader(stringResource(R.string.app_rd_section_context))
            contextRows.forEachIndexed { i, item ->
                ReadinessRow(
                    name = stringResource(ReadinessCopy.contextName(item.id)),
                    status = item.status.ui(),
                    consequence = stringResource(ReadinessCopy.contextWhy(item.id)),
                    onFix = item.fix?.let { f -> { contextFix(f, vm, actions) } },
                    divider = i < contextRows.lastIndex,
                )
            }
        }

        SectionHeader(stringResource(R.string.app_rd_section_connections))
        ConnectionRow(stringResource(R.string.app_rd_remote), remoteText(paired, relay), divider = true)
        ConnectionRow(stringResource(R.string.app_rd_companion), companionText(paired, relay), divider = false)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_rd_optional_note), style = DayCueTheme.type.bodySmall, color = c.ink2)
    }
}

private fun fix(id: ReadinessId, actions: PermissionActions) {
    if (id == ReadinessId.Notifications) actions.requestNotifications() else actions.openFix(id)
}

private fun contextFix(f: ContextFix, vm: ReadinessViewModel, actions: PermissionActions) {
    when (f) {
        ContextFix.RequestForegroundLocation, ContextFix.RequestPreciseLocation, ContextFix.RequestBackgroundLocation ->
            actions.requestRuntime(vm.nextLocationPermissions())
        ContextFix.RequestActivityRecognition -> actions.requestRuntime(vm.activityPermissions())
        ContextFix.RequestCalendar -> actions.requestCalendar()
        ContextFix.LocationSettings -> actions.openIntent(vm.locationIntent(LocationFix.LocationSettings))
        ContextFix.PlayServices -> actions.openIntent(vm.locationIntent(LocationFix.PlayServices))
        ContextFix.AppSettings -> actions.openIntent(vm.locationIntent(LocationFix.AppSettings))
        ContextFix.SyncNow -> vm.syncCalendarNow()
    }
}

@Composable
private fun remoteText(paired: Boolean, s: RelayStatus): String = when {
    !paired -> stringResource(R.string.app_rd_not_set_up)
    s.unpairedByRelay || s.lastResult == SyncStatus.Unauthorized -> stringResource(R.string.app_rd_remote_unpaired)
    s.lastResult == SyncStatus.Offline -> stringResource(R.string.app_rd_remote_offline)
    s.lastResult == SyncStatus.Disabled -> stringResource(R.string.app_rd_remote_off)
    s.lastResult == SyncStatus.Failed -> stringResource(R.string.app_rd_remote_failed)
    else -> stringResource(R.string.app_rd_remote_ok)
}

@Composable
private fun companionText(paired: Boolean, s: RelayStatus): String = when {
    !paired || s.companion == null -> stringResource(R.string.app_rd_companion_none)
    s.companion.startsWith("active") -> stringResource(R.string.app_rd_companion_active)
    else -> stringResource(R.string.app_rd_companion_quiet)
}

@Composable
private fun ConnectionRow(name: String, value: String, divider: Boolean) {
    DayCueRow(
        primary = name,
        secondary = value,
        leading = {},
        divider = divider,
    )
}

