package app.daycue.ui.app

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daycue.R
import app.daycue.facade.RelayFacade
import app.daycue.integrations.relay.GrantDecisionResult
import app.daycue.integrations.relay.PendingKind
import app.daycue.integrations.relay.RelayClient
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.theme.DayCueShapes
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.friendlyDiffLines
import app.daycue.ui.util.grantScopeLines
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch

/**
 * Content of `RemoteConfirmActivity` in the design system. The security-relevant parts are unchanged from the
 * placeholder: approval only happens on a **press and hold** (a tap just explains; TalkBack gets the long press as
 * a custom action through `onLongClickLabel`), the owner sees the unredacted lines, and what is shown comes from the
 * facade (`pending`, `grants`), never from the intent. The window flags stay in the Activity.
 */
@Composable
fun RemoteConfirmRoot(remote: RelayFacade, activity: Activity, focusCommand: String?, focusGrant: String?, close: () -> Unit) {
    val c = DayCueTheme.colors
    val pending by remote.pending.collectAsStateWithLifecycle()
    val grants by remote.grants.collectAsStateWithLifecycle()
    val commands = pending.sortedBy { if (it.commandId == focusCommand) 0 else 1 }
    val grantList = grants.filter { it.awaitsApproval }.sortedBy { if (it.id == focusGrant) 0 else 1 }
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.dc_remote_failed_hint)
    val holdHint = stringResource(R.string.dc_remote_hold_hint)
    val snackbar = remember { SnackbarHostState() }

    ScrollPage(stringResource(R.string.dc_remote_screen_title), close, snackbar = snackbar) {
        hint?.let { Text(it, style = DayCueTheme.type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp)) }
        if (commands.isEmpty() && grantList.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.dc_remote_screen_empty), style = DayCueTheme.type.body, color = c.ink)
            Spacer(Modifier.height(16.dp))
            SecondaryButton(stringResource(R.string.dc_remote_screen_close), close, Modifier.fillMaxWidth())
        }
        commands.forEachIndexed { i, p ->
            ApprovalBlock(
                title = stringResource(if (p.kind == PendingKind.RoutineStart) R.string.dc_remote_confirm_title_routine else R.string.dc_remote_confirm_title),
                from = p.clientLabel, lines = p.lines,
                onHintTap = { hint = holdHint },
                onDecline = { scope.launch { remote.decline(p.commandId) } },
                onApprove = { scope.launch { if (remote.confirm(activity, p.commandId) == RelayClient.DecisionResult.Failed) hint = failed } },
                divider = i < commands.lastIndex || grantList.isNotEmpty(),
            )
        }
        grantList.forEachIndexed { i, g ->
                        ApprovalBlock(
                title = stringResource(R.string.dc_remote_grant_title),
                from = g.label,
                lines = grantScopeLines(g),
                note = stringResource(R.string.app_remote_grant_note), friendly = false,
                onHintTap = { hint = holdHint },
                onDecline = { scope.launch { remote.declineGrant(g.id) } },
                onApprove = { scope.launch { if (remote.approveGrant(g.id) != GrantDecisionResult.Done) hint = failed } },
                divider = i < grantList.lastIndex,
            )
        }
    }
}

@Composable
private fun ApprovalBlock(
    title: String, from: String, lines: List<String>,
    onHintTap: () -> Unit, onDecline: () -> Unit, onApprove: () -> Unit, divider: Boolean,
    note: String? = null,
    friendly: Boolean = true,
) {
    val c = DayCueTheme.colors
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(title, style = DayCueTheme.type.title, color = c.ink)
        // The client's own label is shown as unverified text (RELAY.md 4.6).
        Text(stringResource(R.string.dc_remote_from, from.take(60)), style = DayCueTheme.type.bodySmall, color = c.ink2)
        Spacer(Modifier.height(8.dp))
        // Readable text only (never raw JSON), and never truncated: the screen scrolls instead.
        val shown = if (friendly) friendlyDiffLines(lines) else lines
        shown.forEach { line ->
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 10.dp).size(4.dp).background(c.ink2, CircleShape))
                Text(line, style = DayCueTheme.type.body, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
        if (note != null) Text(note, style = DayCueTheme.type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(DayCueSpacing.inRow))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldToApprove(stringResource(R.string.dc_remote_hold_to_approve), enabled = !busy, onTap = onHintTap) {
                if (!busy) { busy = true; onApprove() }
            }
            SecondaryButton(stringResource(R.string.dc_remote_decline), { if (!busy) { busy = true; onDecline() } }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
}

/**
 * A primary-looking button that approves only on press and hold; a plain tap explains how. While pressed a lighter
 * fill sweeps across the button over the long-press time, so the hold has visible feedback (the approval itself
 * still fires from `onLongClick`, which TalkBack exposes as a custom action).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HoldToApprove(label: String, enabled: Boolean, onTap: () -> Unit, onApprove: () -> Unit) {
    val c = DayCueTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val holdMs = androidx.compose.ui.platform.LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    val fill by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = if (pressed) androidx.compose.animation.core.tween(holdMs, easing = androidx.compose.animation.core.LinearEasing) else androidx.compose.animation.core.snap(),
        label = "hold",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DayCueSpacing.buttonHeight)
            .clip(DayCueShapes.button)
            .background(if (!enabled) c.outline else c.ink)
            .drawBehind {
                if (fill > 0f) {
                    val w = size.width * fill
                    val left = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - w else 0f
                    drawRect(c.paper.copy(alpha = 0.28f), topLeft = androidx.compose.ui.geometry.Offset(left, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                }
            }
            .combinedClickable(
                interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
                onClick = onTap, onLongClickLabel = label, onLongClick = onApprove,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DayCueTheme.type.button, color = if (enabled) c.paper else c.ink2)
    }
}
