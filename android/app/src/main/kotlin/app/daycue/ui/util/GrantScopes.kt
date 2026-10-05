package app.daycue.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.daycue.R
import app.daycue.integrations.relay.RemoteGrant

/** Display order of the scopes a remote connection can hold. */
internal val GRANT_SCOPE_ORDER = listOf("config:read", "activity:read", "config:write", "sessions:control", "medication")

private fun scopeRes(scope: String): Int = when (scope) {
    "config:read" -> R.string.su_scope_config_read
    "activity:read" -> R.string.su_scope_activity_read
    "config:write" -> R.string.su_scope_config_write
    "sessions:control" -> R.string.su_scope_sessions
    "medication" -> R.string.su_scope_medication
    else -> R.string.su_scope_other
}

/**
 * One line per scope with its state ("See your setup · on", "Medication · off until you approve"). The hold-to-approve
 * screen and the Setup remote-access list both call this, so the wording is identical in both places.
 */
@Composable
fun grantScopeLines(g: RemoteGrant): List<String> =
    g.scopes.sortedBy { GRANT_SCOPE_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }.map { s ->
        val on = s in g.activeScopes
        "${stringResource(scopeRes(s))} · ${stringResource(if (on) R.string.su_scope_state_on else R.string.su_scope_off_until_approved)}"
    } + (if (g.holdsMedication) listOf(stringResource(R.string.su_scope_medication_warn)) else emptyList())

@Composable
fun grantScopeIsWarning(g: RemoteGrant, index: Int): Boolean {
    val sorted = g.scopes.sortedBy { GRANT_SCOPE_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    return index >= sorted.size || sorted[index] == "medication"
}
