package app.daycue.integrations.relay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.facade.RelayFacade
import app.daycue.ui.theme.DayCueTheme
import kotlinx.coroutines.launch

/**
 * The only place a remote change or a new connection can be **approved**.
 *
 * - `android:exported="false"`: other apps cannot start it. Notifications reach it through explicit, immutable
 *   PendingIntents (security review L-13).
 * - Intent extras only choose which item is shown first; they can never approve anything. The decision
 *   happens on screen: **press and hold** the approve button (a tap does nothing), so one stray or injected
 *   touch cannot approve. The window rejects touches while another window covers it
 *   (`filterTouchesWhenObscured`) and asks the system to hide overlays (API 31+).
 * - It reads what is pending from the facade (`remote.pending`, `remote.grants`), never from the intent.
 *
 * Deliberately plain: the UI engineer may restyle or replace the content; the manifest entry, the intent
 * contract ([intent]) and the hold gesture are the security-relevant parts.
 */
class RemoteConfirmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(true) }
        val remote = (application as DayCueApplication).container.remote
        val focusCommand = intent.getStringExtra(EXTRA_COMMAND)
        val focusGrant = intent.getStringExtra(EXTRA_GRANT)
        setContent {
            DayCueTheme {
                RemoteConfirmScreen(remote, this, focusCommand, focusGrant) { finish() }
            }
        }
    }

    companion object {
        const val EXTRA_COMMAND = "app.daycue.extra.REMOTE_COMMAND"
        const val EXTRA_GRANT = "app.daycue.extra.REMOTE_GRANT"

        /** Explicit intent for a PendingIntent or an in-app launch. [extra] only selects the item to show first. */
        fun intent(context: Context, extra: String? = null, value: String? = null): Intent =
            Intent(context, RemoteConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (extra != null && value != null) putExtra(extra, value) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteConfirmScreen(remote: RelayFacade, activity: Activity, focusCommand: String?, focusGrant: String?, close: () -> Unit) {
    val pending by remote.pending.collectAsStateWithLifecycle()
    val grants by remote.grants.collectAsStateWithLifecycle()
    val waitingGrants = grants.filter { it.awaitsApproval }
    val commands = pending.sortedBy { if (it.commandId == focusCommand) 0 else 1 }
    val grantList = waitingGrants.sortedBy { if (it.id == focusGrant) 0 else 1 }
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.dc_remote_failed_hint)
    val holdHint = stringResource(R.string.dc_remote_hold_hint)

    Scaffold { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.dc_remote_screen_title), style = MaterialTheme.typography.headlineSmall) }
            hint?.let { h -> item { Text(h, style = MaterialTheme.typography.bodyMedium) } }
            if (commands.isEmpty() && grantList.isEmpty()) {
                item { Text(stringResource(R.string.dc_remote_screen_empty)) }
                item { OutlinedButton(onClick = close) { Text(stringResource(R.string.dc_remote_screen_close)) } }
            }
            items(commands, key = { "c:" + it.commandId }) { p ->
                ApprovalCard(
                    title = stringResource(if (p.kind == PendingKind.RoutineStart) R.string.dc_remote_confirm_title_routine else R.string.dc_remote_confirm_title),
                    from = p.clientLabel,
                    lines = p.lines,
                    onHintTap = { hint = holdHint },
                    onDecline = { scope.launch { remote.decline(p.commandId) } },
                    onApprove = { scope.launch { if (remote.confirm(activity, p.commandId) == RelayClient.DecisionResult.Failed) hint = failed } },
                )
            }
            items(grantList, key = { "g:" + it.id }) { g ->
                val scopeNames = g.scopes.map { scopeLabel(it) }.joinToString(", ")
                ApprovalCard(
                    title = stringResource(R.string.dc_remote_grant_title),
                    from = g.label,
                    lines = listOf(stringResource(R.string.dc_remote_grant_body, g.label, scopeNames)),
                    onHintTap = { hint = holdHint },
                    onDecline = { scope.launch { remote.declineGrant(g.id) } },
                    onApprove = { scope.launch { if (remote.approveGrant(g.id) != GrantDecisionResult.Done) hint = failed } },
                )
            }
        }
    }
}

@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    "config:read" -> stringResource(R.string.dc_remote_scope_config_read)
    "config:write" -> stringResource(R.string.dc_remote_scope_config_write)
    "sessions:control" -> stringResource(R.string.dc_remote_scope_sessions_control)
    "activity:read" -> stringResource(R.string.dc_remote_scope_activity_read)
    "medication" -> stringResource(R.string.dc_remote_scope_medication)
    else -> scope.take(40)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApprovalCard(title: String, from: String, lines: List<String>, onHintTap: () -> Unit, onDecline: () -> Unit, onApprove: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dc_remote_from, from.take(60)), style = MaterialTheme.typography.bodySmall)
            lines.take(20).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (!busy) { busy = true; onDecline() } }, enabled = !busy) { Text(stringResource(R.string.dc_remote_decline)) }
                // Press and hold to approve. A tap only explains. TalkBack exposes the long press as an action.
                Surface(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).combinedClickable(
                        enabled = !busy,
                        onClick = onHintTap,
                        onLongClickLabel = stringResource(R.string.dc_remote_hold_to_approve),
                        onLongClick = { if (!busy) { busy = true; onApprove() } },
                    ),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.dc_remote_hold_to_approve))
                    }
                }
            }
        }
    }
}
