package app.daycue.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.integrations.relay.ConfigPolicy
import app.daycue.integrations.relay.GrantApproval
import app.daycue.integrations.relay.PendingKind
import app.daycue.integrations.relay.PendingRemote
import app.daycue.integrations.relay.RemoteGrant
import app.daycue.integrations.relay.SessionPolicy
import app.daycue.integrations.relay.SyncStatus
import app.daycue.integrations.spotify.AlarmMusicState
import app.daycue.integrations.spotify.RecoveryAction
import app.daycue.integrations.spotify.SpotifyFailure
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueSwitch
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.StatusKind
import app.daycue.ui.components.StatusNotch
import app.daycue.ui.components.StatusText
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.durationDescription
import app.daycue.ui.util.durationText
import app.daycue.ui.util.is24Hour
import app.daycue.ui.util.clockText
import app.daycue.ui.util.ltr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---- Integrations home -------------------------------------------------------------------------------------------

@Composable
fun IntegrationsScreen(onBack: () -> Unit, push: (String) -> Unit) {
    val vm: IntegrationsViewModel = viewModel()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val grants by vm.grants.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val waiting = pending.size + grants.count { it.awaitsApproval }

    SetupFrame(stringResource(R.string.su_integrations_title), onBack) {
        Hint(stringResource(R.string.su_integrations_intro))

        SectionHeader(stringResource(R.string.su_int_computer_header))
        SettingRow(
            stringResource(R.string.su_companion_title),
            when {
                !paired -> stringResource(R.string.su_companion_needs_remote)
                settings.useCompanionActivity -> companionWord(status.companion)
                else -> stringResource(R.string.su_companion_unused)
            },
            { push(SetupRoutes.COMPANION) },
        )

        SectionHeader(stringResource(R.string.su_int_remote_header))
        SettingRow(
            stringResource(R.string.su_remote_title),
            when {
                status.unpairedByRelay -> stringResource(R.string.su_remote_unpaired_by_relay_short)
                !paired -> stringResource(R.string.su_remote_not_set_up)
                !settings.enabled -> stringResource(R.string.su_remote_disabled)
                waiting > 0 -> pluralStringResource(R.plurals.su_remote_waiting, waiting, waiting)
                else -> stringResource(R.string.su_remote_on_host, vm.relayHost() ?: "")
            },
            { push(SetupRoutes.REMOTE) },
        )

        SectionHeader(stringResource(R.string.su_int_music_header))
        SettingRow(stringResource(R.string.su_spotify_title), stringResource(R.string.su_spotify_off_by_default), { push(SetupRoutes.SPOTIFY) })

        SectionHeader(stringResource(R.string.su_int_calendar_header))
        SettingRow(stringResource(R.string.su_cal_title), stringResource(R.string.su_int_calendar_hint), { push(SetupRoutes.CALENDAR) })
    }
}

@Composable
private fun companionWord(raw: String?): String = stringResource(
    when {
        raw == null -> R.string.su_comp_none
        raw.startsWith("active") -> R.string.su_comp_active
        raw.startsWith("idle") -> R.string.su_comp_idle
        raw.startsWith("locked") -> R.string.su_comp_locked
        raw.startsWith("asleep") -> R.string.su_comp_asleep
        raw.startsWith("paused") -> R.string.su_comp_paused
        raw.startsWith("ignored") || raw.contains("signature") -> R.string.su_comp_ignored
        raw.contains("stale") || raw.startsWith("unknown") -> R.string.su_comp_stale
        else -> R.string.su_comp_none
    },
)

// ---- Desktop companion --------------------------------------------------------------------------------------------

@Composable
fun CompanionScreen(onBack: () -> Unit) {
    val vm: IntegrationsViewModel = viewModel()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val code by vm.companionCode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = DayCueTheme.colors

    SetupFrame(stringResource(R.string.su_companion_title), onBack) {
        Hint(stringResource(R.string.su_companion_intro))

        SectionHeader(stringResource(R.string.su_companion_data_header))
        Para(stringResource(R.string.su_companion_data_sent))
        Hint(stringResource(R.string.su_companion_data_never))

        SectionHeader(stringResource(R.string.su_companion_pair_header))
        if (!paired) {
            StateBlock(StateBlockKind.Empty, stringResource(R.string.su_companion_needs_remote), body = stringResource(R.string.su_companion_needs_remote_body))
        } else {
            PrimaryButton(stringResource(R.string.su_companion_code_make), { vm.createCompanionCode() }, Modifier.fillMaxWidth(), enabled = code != IntegrationsViewModel.CodeUi.Loading)
            Gap(8)
            when (val state = code) {
                IntegrationsViewModel.CodeUi.None -> Hint(stringResource(R.string.su_companion_code_hint))
                IntegrationsViewModel.CodeUi.Loading -> Hint(stringResource(R.string.su_companion_code_loading))
                IntegrationsViewModel.CodeUi.Failed -> StateBlock(StateBlockKind.Error, stringResource(R.string.su_companion_code_failed), body = stringResource(R.string.su_companion_code_failed_body), actionLabel = stringResource(R.string.su_try_again), onAction = { vm.createCompanionCode() })
                is IntegrationsViewModel.CodeUi.Ready -> {
                    // The code sits on `surface` with a quiet zone and no decoration.
                    Column(Modifier.fillMaxWidth().background(c.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.code.ltr(), style = DayCueTheme.type.display.copy(fontFamily = FontFamily.Monospace), color = c.ink)
                        Hint(stringResource(R.string.su_companion_code_valid, formatClock(state.createdAtMs + 10 * 60_000L)))
                    }
                    DayCueTextButton(stringResource(R.string.su_copy), {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("code", state.code))
                    })
                    Hint(stringResource(R.string.su_companion_code_steps))
                }
            }
        }

        SectionHeader(stringResource(R.string.su_companion_status_header))
        DayCueRow(
            primary = stringResource(R.string.su_companion_status),
            secondary = companionWord(status.companion),
            extra = status.lastSyncAtMs?.let { at -> { Text(stringResource(R.string.su_companion_checked, agoText(Instant.ofEpochMilli(at))), style = DayCueTheme.type.bodySmall, color = c.ink2) } },
        )
        SwitchRow(
            stringResource(R.string.su_companion_use), settings.useCompanionActivity,
            { vm.setUseCompanionActivity(it) },
            secondary = stringResource(R.string.su_companion_use_hint),
            enabled = paired,
        )
        Hint(stringResource(R.string.su_companion_manual_ok))

        SectionHeader(stringResource(R.string.su_frequent_header))
        FrequentCheck(vm)

        SectionHeader(stringResource(R.string.su_companion_unpair_header))
        Para(stringResource(R.string.su_companion_unpair_how))
        Hint(stringResource(R.string.su_companion_devices_note))
    }
}

@Composable
private fun FrequentCheck(vm: IntegrationsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    SwitchRow(
        stringResource(R.string.su_frequent_switch), settings.frequentCheck,
        { vm.setFrequentCheck(it, settings.frequentCheckMinutes) },
        secondary = stringResource(R.string.su_frequent_hint),
        enabled = paired,
    )
    if (settings.frequentCheck) {
        MinutesStepper(R.string.su_frequent_every, settings.frequentCheckMinutes, 3, 30, R.string.su_frequent_every_hint) { v -> vm.setFrequentCheck(true, v) }
    }
    Hint(stringResource(R.string.su_frequent_battery))
}

private fun formatClockOf(ms: Long, is24: Boolean, locale: java.util.Locale): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return clockText(t.hour, t.minute, is24, locale)
}

@Composable
private fun formatClock(ms: Long): String = formatClockOf(ms, is24Hour(LocalContext.current), currentLocale()).ltr()

// ---- Remote access (MCP) ----------------------------------------------------------------------------------------------

@Composable
fun RemoteScreen(onBack: () -> Unit, focusId: String?) {
    val vm: IntegrationsViewModel = viewModel()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val grants by vm.grants.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val audit by vm.audit.collectAsStateWithLifecycle()
    val version by vm.configVersion.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = DayCueTheme.colors
    var policySheet by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var revokeFor by remember { mutableStateOf<RemoteGrant?>(null) }
    var showAudit by rememberSaveable { mutableStateOf(false) }
    var unpairNote by remember { mutableStateOf<Boolean?>(null) }

    SetupFrame(stringResource(R.string.su_remote_title), onBack) {
        Hint(stringResource(R.string.su_remote_intro))

        if (status.unpairedByRelay) {
            StateBlock(StateBlockKind.Error, stringResource(R.string.su_remote_unpaired_by_relay), body = stringResource(R.string.su_remote_unpaired_by_relay_body))
            PairForm(vm, replaceExisting = true)
            return@SetupFrame
        }
        if (!paired) {
            Para(stringResource(R.string.su_remote_off_default))
            PairForm(vm, replaceExisting = false)
            return@SetupFrame
        }

        // Waiting for the owner comes first.
        val pendingGrants = grants.filter { it.awaitsApproval }
        if (pending.isNotEmpty() || pendingGrants.isNotEmpty()) {
            SectionHeader(stringResource(R.string.su_remote_waiting_header))
            Hint(stringResource(R.string.su_remote_waiting_hint))
            pending.forEach { p -> PendingRow(p, onReview = { context.startActivitySafely(vm.confirmationIntent(context, commandId = p.commandId)) }, highlight = focusId == p.commandId) }
            pendingGrants.forEach { g ->
                GrantRow(
                    g, highlight = focusId == g.id,
                    actions = {
                        DayCueTextButton(stringResource(R.string.su_review), { context.startActivitySafely(vm.confirmationIntent(context, grantId = g.id)) })
                        DayCueTextButton(stringResource(R.string.su_decline), { vm.decline(g) })
                    },
                )
            }
        }

        SectionHeader(stringResource(R.string.su_remote_state_header))
        SwitchRow(
            stringResource(R.string.su_remote_enabled), settings.enabled, { vm.setEnabled(it) },
            secondary = stringResource(if (settings.enabled) R.string.su_remote_enabled_on else R.string.su_remote_enabled_off),
        )
        DayCueRow(
            primary = stringResource(R.string.su_remote_relay),
            secondary = vm.relayHost() ?: "",
        )
        val result = status.lastResult
        DayCueRow(
            primary = stringResource(R.string.su_remote_sync),
            secondary = syncSummary(result, status.lastSyncAtMs),
            secondaryColor = if (result == SyncStatus.Failed || result == SyncStatus.Unauthorized) c.error.ink else c.ink2,
            leading = if (result == SyncStatus.Failed || result == SyncStatus.Unauthorized) ({ StatusNotch() }) else null,
            trailing = { DayCueTextButton(stringResource(if (syncing) R.string.su_syncing else R.string.su_sync_now), { vm.syncNow() }, enabled = !syncing) },
        )
        Hint(stringResource(R.string.su_remote_cadence))

        SectionHeader(stringResource(R.string.su_remote_rules_header))
        SettingRow(stringResource(R.string.su_remote_policy), configPolicyWord(settings.configPolicy), { policySheet = true })
        Hint(stringResource(R.string.su_remote_ordinary_vs_sensitive))
        SwitchRow(
            stringResource(R.string.su_remote_sessions), settings.sessionPolicy == SessionPolicy.Allow,
            { vm.setSessionPolicy(if (it) SessionPolicy.Allow else SessionPolicy.Deny) },
            secondary = stringResource(R.string.su_remote_sessions_hint),
        )
        SwitchRow(
            stringResource(R.string.su_remote_medication), settings.allowMedication, { vm.setAllowMedication(it) },
            secondary = stringResource(if (settings.allowMedication) R.string.su_remote_medication_on else R.string.su_remote_medication_off),
        )

        SectionHeader(stringResource(R.string.su_remote_clients_header))
        Hint(stringResource(R.string.su_remote_clients_hint))
        val active = grants.filter { !it.awaitsApproval }
        if (active.isEmpty()) {
            Para(stringResource(R.string.su_remote_clients_none))
        } else {
            active.forEach { g ->
                GrantRow(g, highlight = focusId == g.id, actions = { DayCueTextButton(stringResource(R.string.su_revoke), { revokeFor = g }) })
            }
        }
        DayCueTextButton(stringResource(R.string.su_remote_refresh), { vm.refreshGrants() })

        SectionHeader(stringResource(R.string.su_remote_recent_header))
        val remoteChanges = audit.filter { it.action.startsWith("remote.config") || it.action.startsWith("remote.undo") }
            .filter { it.action.substringAfterLast('.') in setOf("applied", "rejected", "awaiting_confirmation") }
        if (remoteChanges.isEmpty()) {
            Para(stringResource(R.string.su_remote_recent_none))
        } else {
            val newestApplied = remoteChanges.firstOrNull { it.action.endsWith(".applied") }
            remoteChanges.take(6).forEach { e ->
                val outcome = e.action.substringAfterLast('.')
                val undoable = e == newestApplied && e.versionAfter != null && e.versionAfter == version
                DayCueRow(
                    primary = e.summary.ifBlank { stringResource(R.string.su_audit_no_summary) },
                    secondary = "${actorWord(e.actor)} · ${whenText(e.atMs)}",
                    extra = {
                        StatusText(when (outcome) { "applied" -> StatusKind.Applied; "rejected" -> StatusKind.Rejected; else -> StatusKind.Waiting })
                    },
                    trailing = if (undoable) ({ DayCueTextButton(stringResource(R.string.su_undo), { vm.undoLatest() }) }) else null,
                )
            }
            Hint(stringResource(R.string.su_remote_undo_hint))
        }

        SectionHeader(stringResource(R.string.su_audit_header))
        DayCueRow(
            primary = stringResource(R.string.su_audit_show),
            secondary = stringResource(R.string.su_audit_hint),
            trailing = { GlyphIcon(if (showAudit) Glyph.ChevronDown else Glyph.Chevron, c.ink2) },
            onClick = { showAudit = !showAudit },
        )
        if (showAudit) {
            if (audit.isEmpty()) Para(stringResource(R.string.su_audit_empty))
            audit.take(30).forEach { e ->
                DayCueRow(primary = e.summary.ifBlank { e.action }, secondary = "${actorWord(e.actor)} · ${whenText(e.atMs)}")
            }
        }

        SectionHeader(stringResource(R.string.su_frequent_header))
        FrequentCheck(vm)
        SwitchRow(
            stringResource(R.string.su_push_switch), settings.pushWake, { vm.setPushWake(it) },
            secondary = stringResource(if (vm.pushAvailable()) R.string.su_push_available else R.string.su_push_unavailable),
        )
        Hint(stringResource(if (vm.pushAvailable()) R.string.su_push_ready else R.string.su_push_not_built))

        Gap(24)
        DestructiveButton(stringResource(R.string.su_remote_unpair), { confirmUnpair = true }, Modifier.fillMaxWidth())
        Hint(stringResource(R.string.su_remote_unpair_hint))
        unpairNote?.let { confirmed -> FieldNote(stringResource(if (confirmed) R.string.su_unpair_done else R.string.su_unpair_offline), error = !confirmed) }
    }

    if (policySheet) {
        val opts = listOf(ConfigPolicy.Auto, ConfigPolicy.AlwaysConfirm, ConfigPolicy.Deny)
        ChoiceSheet(
            stringResource(R.string.su_remote_policy),
            opts.map { PolicyOption(configPolicyWord(it), stringResource(configPolicyHint(it))) },
            opts.indexOf(settings.configPolicy),
            { vm.setConfigPolicy(opts[it]) },
            { policySheet = false },
        )
    }
    if (confirmUnpair) {
        DayCueDialog(
            stringResource(R.string.su_remote_unpair_title), stringResource(R.string.su_remote_unpair_body),
            stringResource(R.string.su_remote_unpair), stringResource(R.string.su_cancel),
            onConfirm = { confirmUnpair = false; vm.unpair { unpairNote = it } },
            onDismiss = { confirmUnpair = false }, destructive = true,
        )
    }
    revokeFor?.let { g ->
        DayCueDialog(
            stringResource(R.string.su_revoke_title, g.label), stringResource(R.string.su_revoke_body),
            stringResource(R.string.su_revoke), stringResource(R.string.su_cancel),
            onConfirm = { revokeFor = null; vm.revoke(g) },
            onDismiss = { revokeFor = null }, destructive = true,
        )
    }
}

@Composable
private fun PairForm(vm: IntegrationsViewModel, replaceExisting: Boolean) {
    val state by vm.pairState.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    val working = state == PairUi.Working
    Hint(stringResource(R.string.su_pair_intro))
    DayCueTextField(
        url,
        { value ->
            val parsed = vm.parsePairing(value)
            if (parsed.relayUrl != null && value.startsWith("daycue://")) { url = parsed.relayUrl ?: value; parsed.code?.let { code = it } } else url = value
            vm.clearPairError()
        },
        stringResource(R.string.su_pair_url), helper = stringResource(R.string.su_pair_url_hint),
        error = (state as? PairUi.Failed)?.messageRes?.takeIf { it == R.string.su_pair_err_url || it == R.string.su_pair_err_https }?.let { stringResource(it) },
    )
    Gap(8)
    DayCueTextField(
        code, { code = it.uppercase(); vm.clearPairError() }, stringResource(R.string.su_pair_code), helper = stringResource(R.string.su_pair_code_hint),
        error = (state as? PairUi.Failed)?.messageRes?.takeIf { it != R.string.su_pair_err_url && it != R.string.su_pair_err_https }?.let { stringResource(it) },
    )
    Gap(8)
    DayCueTextField(label, { label = it }, stringResource(R.string.su_pair_label), helper = stringResource(R.string.su_pair_label_hint))
    Gap(12)
    PrimaryButton(
        stringResource(if (working) R.string.su_pairing else R.string.su_pair_action),
        { vm.pair(url, code, label, replaceExisting) },
        Modifier.fillMaxWidth(), enabled = !working && url.isNotBlank() && code.isNotBlank(),
    )
    if (working) Hint(stringResource(R.string.su_pair_slow))
    Hint(stringResource(R.string.su_pair_privacy))
}

@Composable
private fun PendingRow(p: PendingRemote, onReview: () -> Unit, highlight: Boolean) {
    val c = DayCueTheme.colors
    DayCueRow(
        primary = when (p.kind) {
            PendingKind.ConfigChange -> stringResource(R.string.su_pending_change)
            PendingKind.Undo -> stringResource(R.string.su_pending_undo)
            PendingKind.RoutineStart -> stringResource(R.string.su_pending_routine)
        },
        secondary = stringResource(R.string.su_pending_from, p.clientLabel),
        extra = {
            // The command is not applied until the owner approves it: never "done" here.
            StatusText(StatusKind.Waiting, detail = sensitivityWord(p.sensitivity.name))
            p.lines.firstOrNull()?.let { Text(it, style = DayCueTheme.type.bodySmall, color = c.ink2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (p.lines.size > 1) Text(pluralStringResource(R.plurals.su_pending_more_lines, p.lines.size - 1, p.lines.size - 1), style = DayCueTheme.type.bodySmall, color = c.ink2)
            Text(stringResource(R.string.su_pending_expires, formatClock(p.expiresAtMs)), style = DayCueTheme.type.bodySmall, color = c.ink2)
        },
        leading = { StatusNotch(error = false) },
        trailing = { DayCueTextButton(stringResource(R.string.su_review), onReview) },
    )
}

@Composable
private fun GrantRow(g: RemoteGrant, highlight: Boolean, actions: @Composable () -> Unit) {
    val c = DayCueTheme.colors
    DayCueRow(
        primary = g.label,
        secondary = stringResource(R.string.su_grant_unverified, stringResource(if (g.kind == "oauth") R.string.su_grant_kind_oauth else R.string.su_grant_kind_token)),
        extra = {
            val gated = g.scopes.filter { isGatedScope(it) }
            g.scopes.sortedBy { KNOWN_SCOPES.indexOf(it) }.forEach { s ->
                val on = s in g.activeScopes
                val line = stringResource(scopeText(s)) + when {
                    isGatedScope(s) && !on -> " · " + stringResource(R.string.su_scope_off_until_approved)
                    else -> ""
                }
                Text("· $line", style = DayCueTheme.type.bodySmall, color = if (s == "medication") c.error.ink else c.ink)
            }
            if (g.holdsMedication) Text(stringResource(R.string.su_scope_medication_warn), style = DayCueTheme.type.bodySmall, color = c.error.ink)
            Text(
                if (g.awaitsApproval) stringResource(R.string.su_grant_pending)
                else stringResource(R.string.su_grant_last_used, g.lastUsedAtMs?.let { agoText(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.su_grant_never_used)),
                style = DayCueTheme.type.bodySmall, color = c.ink2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            if (gated.isEmpty() && g.approval == GrantApproval.NotRequired) Unit
        },
    )
}

private fun scopeText(scope: String): Int = when (scope) {
    "config:read" -> R.string.su_scope_config_read
    "activity:read" -> R.string.su_scope_activity_read
    "config:write" -> R.string.su_scope_config_write
    "sessions:control" -> R.string.su_scope_sessions
    "medication" -> R.string.su_scope_medication
    else -> R.string.su_scope_other
}

@Composable
private fun sensitivityWord(name: String): String = stringResource(
    when (name) {
        "destructive" -> R.string.su_sens_destructive
        "sensitive" -> R.string.su_sens_sensitive
        else -> R.string.su_sens_ordinary
    },
)

@Composable
private fun configPolicyWord(p: ConfigPolicy) = stringResource(
    when (p) {
        ConfigPolicy.Auto -> R.string.su_policy_auto
        ConfigPolicy.AlwaysConfirm -> R.string.su_policy_always
        ConfigPolicy.Deny -> R.string.su_policy_deny
    },
)

private fun configPolicyHint(p: ConfigPolicy): Int = when (p) {
    ConfigPolicy.Auto -> R.string.su_policy_auto_hint
    ConfigPolicy.AlwaysConfirm -> R.string.su_policy_always_hint
    ConfigPolicy.Deny -> R.string.su_policy_deny_hint
}

@Composable
private fun actorWord(actor: String): String = when {
    actor == "user" || actor == "owner" -> stringResource(R.string.su_actor_you)
    actor == "mcp" -> stringResource(R.string.su_actor_remote_plain)
    actor == "system" -> stringResource(R.string.su_actor_system)
    actor.startsWith("mcp:") -> stringResource(R.string.su_actor_remote, actor.removePrefix("mcp:"))
    else -> actor
}

@Composable
private fun whenText(ms: Long): String = agoText(Instant.ofEpochMilli(ms))

@Composable
private fun syncSummary(result: SyncStatus?, at: Long?): String {
    val word = when (result) {
        SyncStatus.Ok -> stringResource(R.string.su_sync_ok)
        SyncStatus.Offline -> stringResource(R.string.su_sync_offline)
        SyncStatus.Unauthorized -> stringResource(R.string.su_sync_unauthorized)
        SyncStatus.Failed -> stringResource(R.string.su_sync_failed)
        SyncStatus.Disabled -> stringResource(R.string.su_sync_disabled)
        SyncStatus.NotPaired -> stringResource(R.string.su_sync_not_paired)
        null -> stringResource(R.string.su_sync_none)
    }
    return if (at != null) "$word · ${agoText(Instant.ofEpochMilli(at))}" else word
}

// ---- Spotify ------------------------------------------------------------------------------------------------------------

@Composable
fun SpotifyScreen(onBack: () -> Unit) {
    val vm: IntegrationsViewModel = viewModel()
    val state by vm.alarmMusic.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = DayCueTheme.colors

    SetupFrame(stringResource(R.string.su_spotify_title), onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CueMark(CueType.Alarm)
            Para(stringResource(R.string.su_spotify_off_by_default), Modifier.padding(start = 16.dp))
        }
        Hint(stringResource(R.string.su_spotify_intro))

        SectionHeader(stringResource(R.string.su_spotify_policy_header))
        Para(stringResource(R.string.su_spotify_policy))
        Hint(stringResource(R.string.su_spotify_limit))

        SectionHeader(stringResource(R.string.su_spotify_status_header))
        when (val s = state) {
            AlarmMusicState.Idle -> DayCueRow(stringResource(R.string.su_spotify_idle), secondary = stringResource(R.string.su_spotify_idle_body))
            AlarmMusicState.Connecting -> DayCueRow(stringResource(R.string.su_spotify_connecting))
            AlarmMusicState.Playing -> DayCueRow(stringResource(R.string.su_spotify_playing), secondary = stringResource(R.string.su_spotify_playing_body))
            is AlarmMusicState.FellBack -> {
                StateBlock(
                    StateBlockKind.Error,
                    stringResource(if (s.stoppedAfterPlaying) R.string.su_spotify_stopped else R.string.su_spotify_fellback),
                    body = stringResource(failureText(s.failure)) + " " + stringResource(R.string.su_spotify_tone_covers),
                )
                val label = recoveryLabel(s.recovery)
                val intent = vm.alarmRecoveryIntent(s.recovery)
                if (label != null && intent != null) {
                    SecondaryButton(stringResource(label), { context.startActivitySafely(intent) }, Modifier.fillMaxWidth())
                } else if (s.recovery == RecoveryAction.Retry || s.recovery == RecoveryAction.AuthorizeSpotify) {
                    Hint(stringResource(R.string.su_spotify_authorize_from_alarm))
                }
            }
        }
        Hint(stringResource(R.string.su_spotify_status_note))

        SectionHeader(stringResource(R.string.su_spotify_use_header))
        Para(stringResource(R.string.su_spotify_use))
        Hint(stringResource(R.string.su_spotify_offline))
    }
}

private fun failureText(f: SpotifyFailure): Int = when (f) {
    SpotifyFailure.NotInstalled -> R.string.su_spf_not_installed
    SpotifyFailure.NotAuthorized -> R.string.su_spf_not_authorized
    SpotifyFailure.NoNetwork -> R.string.su_spf_no_network
    SpotifyFailure.RemoteUnavailable -> R.string.su_spf_remote
    SpotifyFailure.AccountRestriction -> R.string.su_spf_account
    SpotifyFailure.Timeout -> R.string.su_spf_timeout
    SpotifyFailure.SdkNotBundled -> R.string.su_spf_sdk
    SpotifyFailure.Unknown -> R.string.su_spf_unknown
}

private fun recoveryLabel(a: RecoveryAction): Int? = when (a) {
    RecoveryAction.InstallSpotify -> R.string.su_spr_install
    RecoveryAction.AuthorizeSpotify -> R.string.su_spr_open_authorize
    RecoveryAction.CheckNetwork -> R.string.su_spr_network
    RecoveryAction.OpenSpotify -> R.string.su_spr_open
    RecoveryAction.CheckAccount -> R.string.su_spr_account
    RecoveryAction.Retry, RecoveryAction.None -> null
}
