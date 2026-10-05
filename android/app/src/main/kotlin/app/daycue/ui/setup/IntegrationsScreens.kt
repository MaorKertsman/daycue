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
import androidx.compose.runtime.LaunchedEffect
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
import app.daycue.ui.util.grantScopeIsWarning
import app.daycue.ui.util.grantScopeLines
import app.daycue.ui.util.clockText
import app.daycue.ui.util.friendlyDiffLines
import app.daycue.ui.util.friendlyDiffSummary
import app.daycue.ui.util.friendlyDiffTitle
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
    val spotifyState by vm.spotify.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshSpotify(); onPauseOrDispose { } }

    SetupFrame(stringResource(R.string.su_integrations_title), onBack) {
        Hint(stringResource(R.string.su_integrations_intro))
        SettingRow(
            stringResource(R.string.su_companion_title),
            when {
                !paired || !settings.useCompanionActivity -> stringResource(R.string.su_status_not_set_up)
                else -> companionWord(status.companion)
            },
            { push(SetupRoutes.COMPANION) },
        )
        SettingRow(
            stringResource(R.string.su_remote_title),
            when {
                status.unpairedByRelay -> stringResource(R.string.su_status_needs_attention)
                !paired -> stringResource(R.string.su_status_not_set_up)
                !settings.enabled -> stringResource(R.string.su_remote_disabled)
                waiting > 0 -> pluralStringResource(R.plurals.su_remote_waiting, waiting, waiting)
                else -> stringResource(R.string.su_status_connected)
            },
            { push(SetupRoutes.REMOTE) },
        )
        SettingRow(stringResource(R.string.su_spotify_title), spotifyWord(spotifyState), { push(SetupRoutes.SPOTIFY) })
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
        Hint(stringResource(R.string.su_companion_intro_short))
        LearnMore(stringResource(R.string.su_companion_data_sent) + "\n\n" + stringResource(R.string.su_companion_data_never), label = stringResource(R.string.su_what_is_sent))

        SectionHeader(stringResource(R.string.su_companion_pair_header))
        if (!paired) {
            StateBlock(StateBlockKind.Empty, stringResource(R.string.su_companion_needs_remote), body = stringResource(R.string.su_companion_needs_remote_body))
        } else {
            PrimaryButton(stringResource(R.string.su_companion_code_make), { vm.createCompanionCode() }, Modifier.fillMaxWidth(), enabled = code != IntegrationsViewModel.CodeUi.Loading)
            Gap(8)
            when (val state = code) {
                IntegrationsViewModel.CodeUi.None -> Unit
                IntegrationsViewModel.CodeUi.Loading -> Hint(stringResource(R.string.su_companion_code_loading))
                IntegrationsViewModel.CodeUi.Failed -> StateBlock(StateBlockKind.Error, stringResource(R.string.su_companion_code_failed), body = stringResource(R.string.su_companion_code_failed_body), actionLabel = stringResource(R.string.su_try_again), onAction = { vm.createCompanionCode() })
                is IntegrationsViewModel.CodeUi.Ready -> {
                    // The code sits on surface with a quiet zone and no decoration.
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

        SectionHeader(stringResource(R.string.su_frequent_header))
        FrequentCheck(vm)

        LearnMore(stringResource(R.string.su_companion_unpair_how) + "\n\n" + stringResource(R.string.su_companion_devices_note), label = stringResource(R.string.su_how_to_remove))
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
}

private fun formatClockOf(ms: Long, is24: Boolean, locale: java.util.Locale): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return clockText(t.hour, t.minute, is24, locale)
}

@Composable
private fun formatClock(ms: Long): String = formatClockOf(ms, is24Hour(LocalContext.current), currentLocale()).ltr()

@Composable
private fun spotifyWord(s: app.daycue.integrations.spotify.SpotifyAvailability): String = stringResource(
    when {
        !s.available || !s.installed -> R.string.su_status_not_set_up
        s.connection == app.daycue.integrations.spotify.SpotifyConnection.FellBack -> R.string.su_status_needs_attention
        else -> R.string.su_status_connected
    },
)

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
    val companions by vm.companions.collectAsStateWithLifecycle()
    LaunchedEffect(paired) { if (paired) vm.refreshCompanions() }
    val context = LocalContext.current
    val c = DayCueTheme.colors
    var policySheet by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var revokeFor by remember { mutableStateOf<RemoteGrant?>(null) }
    var unpairNote by remember { mutableStateOf<Boolean?>(null) }

    SetupFrame(stringResource(R.string.su_remote_title), onBack) {
        Hint(stringResource(R.string.su_remote_intro_short))
        LearnMore(stringResource(R.string.su_remote_intro) + "\n\n" + stringResource(R.string.su_pair_privacy), label = stringResource(R.string.su_what_is_sent))

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
        val result = status.lastResult
        val relayProblem = result == SyncStatus.Failed || result == SyncStatus.Unauthorized
        DayCueRow(
            primary = stringResource(
                when {
                    relayProblem -> R.string.su_relay_attention
                    result == SyncStatus.Offline -> R.string.su_relay_offline
                    result == SyncStatus.Ok -> R.string.su_relay_connected
                    else -> R.string.su_remote_relay
                },
            ),
            secondary = syncSummary(result, status.lastSyncAtMs),
            secondaryColor = if (relayProblem) c.error.ink else c.ink2,
            leading = if (relayProblem) ({ StatusNotch() }) else null,
            trailing = { DayCueTextButton(stringResource(if (syncing) R.string.su_syncing else R.string.su_sync_now), { vm.syncNow() }, enabled = !syncing) },
        )
        // The relay's address is a technical detail: it lives behind "Details", never in the row itself.
        var details by rememberSaveable { mutableStateOf(false) }
        DayCueTextButton(stringResource(if (details) R.string.su_details_hide else R.string.su_details), { details = !details })
        if (details) {
            Hint(stringResource(R.string.su_remote_paired_with, vm.relayHost() ?: stringResource(R.string.su_remote_host_unknown)))
            Hint(stringResource(R.string.su_remote_cadence))
        }

        SectionHeader(stringResource(R.string.su_remote_rules_header))
        SettingRow(stringResource(R.string.su_remote_policy), configPolicyWord(settings.configPolicy), { policySheet = true })
        SwitchRow(
            stringResource(R.string.su_remote_sessions), settings.sessionPolicy == SessionPolicy.Allow,
            { vm.setSessionPolicy(if (it) SessionPolicy.Allow else SessionPolicy.Deny) },
        )
        SwitchRow(
            stringResource(R.string.su_remote_medication), settings.allowMedication, { vm.setAllowMedication(it) },
            secondary = stringResource(if (settings.allowMedication) R.string.su_remote_medication_on else R.string.su_remote_medication_off),
        )

        SectionHeader(stringResource(R.string.su_remote_clients_header))
        val active = grants.filter { !it.awaitsApproval }
        if (active.isEmpty()) {
            Para(stringResource(R.string.su_remote_clients_none))
        } else {
            active.forEach { g ->
                GrantRow(g, highlight = focusId == g.id, actions = { DayCueTextButton(stringResource(R.string.su_revoke), { revokeFor = g }) })
            }
        }
        DayCueTextButton(stringResource(R.string.su_remote_refresh), { vm.refreshGrants(); vm.refreshCompanions() })

        // Desktop companions paired with this relay: the name is chosen on the PC (unverified); compare the key fingerprint.
        if (companions.isNotEmpty()) {
            SectionHeader(stringResource(R.string.su_companions_header))
            companions.forEachIndexed { i, comp ->
                DayCueRow(
                    primary = comp.label.take(60),
                    secondary = stringResource(R.string.su_companion_unverified),
                    extra = {
                        Text(stringResource(R.string.su_companion_fingerprint, comp.fingerprint.chunked(5).joinToString("-").ltr()), style = DayCueTheme.type.bodySmall, color = c.ink2)
                    },
                    trailing = { DayCueTextButton(stringResource(R.string.su_companion_unpair), { vm.revokeCompanion(comp) }) },
                    divider = i < companions.lastIndex,
                )
            }
        }

        SectionHeader(stringResource(R.string.su_remote_recent_header))
        val remoteChanges = audit.filter { it.action.startsWith("remote.config") || it.action.startsWith("remote.undo") }
            .filter { it.action.substringAfterLast('.') in setOf("applied", "rejected", "awaiting_confirmation", "expired") }
        if (remoteChanges.isEmpty()) {
            Para(stringResource(R.string.su_remote_recent_none))
        } else {
            val newestApplied = remoteChanges.firstOrNull { it.action.endsWith(".applied") }
            remoteChanges.take(8).forEach { e ->
                val outcome = e.action.substringAfterLast('.')
                val undoable = e == newestApplied && e.versionAfter != null && e.versionAfter == version
                DayCueRow(
                    primary = if (e.summary.isBlank()) stringResource(R.string.su_audit_no_summary) else friendlyDiffSummary(e.summary),
                    secondary = "${actorWord(e.actor)} · ${whenText(e.atMs)}",
                    extra = {
                        StatusText(when (outcome) { "applied" -> StatusKind.Applied; "rejected" -> StatusKind.Rejected; "expired" -> StatusKind.Expired; else -> StatusKind.Waiting })
                    },
                    trailing = if (undoable) ({ DayCueTextButton(stringResource(R.string.su_undo), { vm.undoLatest() }) }) else null,
                )
            }
        }

        SectionHeader(stringResource(R.string.su_frequent_header))
        FrequentCheck(vm)
        if (vm.pushAvailable()) {
            SwitchRow(stringResource(R.string.su_push_switch), settings.pushWake, { vm.setPushWake(it) }, secondary = stringResource(R.string.su_push_available))
        } else {
            Hint(stringResource(R.string.su_push_cadence))
        }

        Gap(16)
        DayCueTextButton(stringResource(R.string.su_remote_unpair), { confirmUnpair = true }, color = c.error.ink)
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
}

@Composable
private fun PendingRow(p: PendingRemote, onReview: () -> Unit, highlight: Boolean) {
    val c = DayCueTheme.colors
    val fallback = when (p.kind) {
        PendingKind.ConfigChange -> stringResource(R.string.su_pending_change)
        PendingKind.Undo -> stringResource(R.string.su_pending_undo)
        PendingKind.RoutineStart -> stringResource(R.string.su_pending_routine)
    }
    val friendly = if (p.kind == PendingKind.ConfigChange) friendlyDiffLines(p.lines) else emptyList()
    val title = if (p.kind == PendingKind.ConfigChange) friendlyDiffTitle(p.lines, fallback) else fallback
    DayCueRow(
        primary = title,
        secondary = stringResource(R.string.su_pending_from, p.clientLabel),
        extra = {
            // The command is not applied until the owner approves it: never "done" here. A diff is never cut off.
            StatusText(StatusKind.Waiting, detail = sensitivityWord(p.sensitivity.name))
            if (friendly.size > 1) friendly.forEach { Text("· $it", style = DayCueTheme.type.bodySmall, color = c.ink) }
            Text(stringResource(R.string.su_pending_expires, formatClock(p.expiresAtMs)), style = DayCueTheme.type.bodySmall, color = c.ink2)
        },
        leading = { CueMark(targetMark(p)) },
        trailing = { DayCueTextButton(stringResource(R.string.su_review), onReview) },
    )
}

/** The cue mark of what a pending change targets (REVIEW-2 C15): never the error diamond. */
private fun targetMark(p: PendingRemote): CueType {
    val path = p.lines.firstOrNull().orEmpty().substringBefore(":")
    return when {
        p.kind == PendingKind.RoutineStart || path.startsWith("routines") -> CueType.Routine
        path.startsWith("habits[hydration") -> CueType.Hydration
        path.startsWith("habits[sunscreen") -> CueType.Sunscreen
        path.startsWith("habits[bottle") -> CueType.Bottle
        path.startsWith("habits") -> CueType.Hydration
        path.startsWith("medications") -> CueType.Medication
        path.startsWith("alarms") -> CueType.Alarm
        path.startsWith("calendarRules") -> CueType.Calendar
        path.startsWith("postureCycle") -> CueType.Posture
        else -> CueType.Alarm
    }
}

@Composable
private fun GrantRow(g: RemoteGrant, highlight: Boolean, actions: @Composable () -> Unit) {
    val c = DayCueTheme.colors
    DayCueRow(
        primary = g.label,
        secondary = if (g.awaitsApproval) stringResource(R.string.su_grant_pending)
        else stringResource(R.string.su_grant_last_used, g.lastUsedAtMs?.let { agoText(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.su_grant_never_used)),
        extra = {
            // One line per scope with its state: the same function as the hold-to-approve screen.
            val lines = grantScopeLines(g)
            lines.forEachIndexed { i, line ->
                val warn = grantScopeIsWarning(g, i)
                Text("· $line", style = DayCueTheme.type.bodySmall, color = if (warn && g.holdsMedication) c.error.ink else c.ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        },
    )
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
internal fun actorWord(actor: String): String = when {
    actor == "user" || actor == "owner" -> stringResource(R.string.su_actor_you)
    actor == "mcp" -> stringResource(R.string.su_actor_remote_plain)
    actor == "system" -> stringResource(R.string.su_actor_system)
    actor.startsWith("mcp:") -> stringResource(R.string.su_actor_remote, actor.removePrefix("mcp:"))
    else -> actor
}

@Composable
internal fun whenText(ms: Long): String = agoText(Instant.ofEpochMilli(ms))

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
    val avail by vm.spotify.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = DayCueTheme.colors
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshSpotify(); onPauseOrDispose { } }

    SetupFrame(stringResource(R.string.su_spotify_title), onBack) {
        Hint(stringResource(R.string.su_spotify_intro))
        LearnMore(stringResource(R.string.su_spotify_policy) + "\n\n" + stringResource(R.string.su_spotify_limit))

        SectionHeader(stringResource(R.string.su_spotify_status_header))
        if (!avail.available) {
            DayCueRow(stringResource(R.string.su_status_not_set_up), secondary = stringResource(R.string.su_spotify_unavailable))
            return@SetupFrame
        }
        if (!avail.installed) {
            DayCueRow(stringResource(R.string.su_status_not_set_up), secondary = stringResource(R.string.su_spotify_not_installed))
            vm.alarmRecoveryIntent(RecoveryAction.InstallSpotify)?.let { i ->
                SecondaryButton(stringResource(R.string.su_spr_install), { context.startActivitySafely(i) }, Modifier.fillMaxWidth())
            }
            return@SetupFrame
        }
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
        Hint(stringResource(R.string.su_spotify_use))
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
