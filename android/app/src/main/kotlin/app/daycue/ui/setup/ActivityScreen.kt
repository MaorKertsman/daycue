package app.daycue.ui.setup

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.data.db.AuditLogEntity
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.util.friendlyDiffSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Recent configuration changes with their source and Undo (UX 1.1: "Activity & undo"). */
class ActivityViewModel(app: Application) : SetupViewModel(app) {
    val audit: StateFlow<List<AuditLogEntity>?> = facade.audit(80)
        .map<List<AuditLogEntity>, List<AuditLogEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val version: StateFlow<Long?> = facade.config.map { it.version as Long? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun undoLatest() { viewModelScope.launch { facade.undo(); post(R.string.su_undone) } }
}

@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val vm: ActivityViewModel = viewModel()
    val audit by vm.audit.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    SetupFrame(stringResource(R.string.su_activity_title), onBack) {
        val all = audit
        if (all == null) { repeat(3) { SkeletonRow() }; return@SetupFrame }
        val changes = all.filter { it.action.startsWith("config.") || it.action.endsWith(".applied") }
        if (changes.isEmpty()) {
            Gap(16)
            StateBlock(StateBlockKind.Empty, stringResource(R.string.su_activity_empty), body = stringResource(R.string.su_activity_empty_body))
            return@SetupFrame
        }
        val newest = changes.first()
        changes.take(40).forEach { e ->
            val undoable = e == newest && e.action != "config.undo" && e.versionAfter != null && e.versionAfter == version
            DayCueRow(
                primary = if (e.summary.isBlank()) stringResource(R.string.su_audit_no_summary) else friendlyDiffSummary(e.summary),
                secondary = "${sourceWord(e)} · ${whenText(e.atMs)}",
                trailing = if (undoable) ({ DayCueTextButton(stringResource(R.string.su_undo), { vm.undoLatest() }) }) else null,
            )
        }
    }
}

@Composable
private fun sourceWord(e: AuditLogEntity): String = when {
    e.action == "config.import" -> stringResource(R.string.su_source_import)
    e.action == "config.undo" -> stringResource(R.string.su_source_undo)
    e.actor.startsWith("mcp") || e.action.startsWith("remote.") -> actorWord(if (e.actor.startsWith("mcp")) e.actor else "mcp")
    e.actor == "system" -> stringResource(R.string.su_actor_system)
    else -> stringResource(R.string.su_actor_you)
}
