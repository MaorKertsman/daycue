package app.daycue.facade

import android.content.Context
import android.content.Intent
import app.daycue.AppContainer
import app.daycue.delivery.RoutinePlaybackService
import app.daycue.integrations.relay.ConfigPolicy
import app.daycue.integrations.relay.GrantDecision
import app.daycue.integrations.relay.GrantDecisionResult
import app.daycue.integrations.relay.RemoteConfirmActivity
import app.daycue.integrations.relay.RemoteGrant
import app.daycue.integrations.relay.PairResult
import app.daycue.integrations.relay.PairingLink
import app.daycue.integrations.relay.PendingRemote
import app.daycue.integrations.relay.RelayClient
import app.daycue.integrations.relay.RelaySettings
import app.daycue.integrations.relay.RelayStatus
import app.daycue.integrations.relay.SessionPolicy
import app.daycue.integrations.relay.HttpRelayApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Remote access (MCP relay) for the UI: pairing, kill switch, local scope settings, the on-phone
 * confirmation queue and sync status. Reached as `facade.remote` (docs/architecture/APP_API.md section 10).
 * The relay never changes these settings; they live only on the phone.
 */
class RelayFacade(private val c: AppContainer) {
    private val relay get() = c.relay

    // ---- observe -------------------------------------------------------------------------------------

    val paired: StateFlow<Boolean> get() = relay.paired
    val settings: StateFlow<RelaySettings> get() = relay.settings.flow
    val status: StateFlow<RelayStatus> get() = relay.client.status

    /** Remote changes waiting for the owner. Show a confirmation screen (diff lines, sensitivity, Approve / Decline). */
    val pending: StateFlow<List<PendingRemote>> get() = relay.client.pending

    /** Host name of the paired relay (never the credential), or null. */
    fun relayHost(): String? = relay.relayHost()

    // ---- connections (grants), RELAY.md 4.6 -------------------------------------------------------------

    /**
     * Every client connection the relay knows (Claude.ai, ChatGPT, Claude Code ...), as of the last sync. Labels are
     * client-supplied and **unverified**: show them as such. A connection with [RemoteGrant.awaitsApproval] has
     * its write / session / medication scopes switched off until the owner approves it here.
     */
    val grants: StateFlow<List<RemoteGrant>> get() = relay.client.grants

    /** Connections that wait for the owner. Also announced by a notification that opens [confirmationIntent]. */
    val pendingGrants: Flow<List<RemoteGrant>> get() = relay.client.grants.map { list -> list.filter { it.awaitsApproval } }

    /** Re-read the list from the relay (pull to refresh). */
    suspend fun refreshGrants(): GrantDecisionResult = relay.client.refreshGrants()

    /**
     * Approve a connection, signed with the phone key. [approvedScopes] narrows what is approved (never widens);
     * null approves everything it asked for. Call only from in-app UI after a deliberate gesture
     * (the confirmation Activity uses press-and-hold; a biometric prompt for connections that hold the
     * `medication` scope is recommended, see [RemoteGrant.holdsMedication]).
     */
    suspend fun approveGrant(grantId: String, approvedScopes: Set<String>? = null): GrantDecisionResult =
        relay.client.decideGrant(grantId, GrantDecision.Approve, approvedScopes)

    /** Decline a pending connection (it is revoked on the relay). */
    suspend fun declineGrant(grantId: String): GrantDecisionResult = relay.client.decideGrant(grantId, GrantDecision.Decline)

    /** Revoke any connection (also an active one). */
    suspend fun revokeGrant(grantId: String): GrantDecisionResult = relay.client.decideGrant(grantId, GrantDecision.Revoke)

    /**
     * The relay answered 401: this phone was unpaired on the relay (owner revoked it, or re-paired another phone).
     * Show "This phone was unpaired from the relay" with a Pair again action; a notification was posted once.
     */
    val unpairedByRelay: Flow<Boolean> get() = relay.client.status.map { it.unpairedByRelay }

    /**
     * Intent for the non-exported confirmation screen that lists pending changes and connections. The only
     * surface that can approve anything; [commandId] / [grantId] merely choose what to show first.
     */
    fun confirmationIntent(context: Context, commandId: String? = null, grantId: String? = null): Intent = when {
        commandId != null -> RemoteConfirmActivity.intent(context, RemoteConfirmActivity.EXTRA_COMMAND, commandId)
        grantId != null -> RemoteConfirmActivity.intent(context, RemoteConfirmActivity.EXTRA_GRANT, grantId)
        else -> RemoteConfirmActivity.intent(context)
    }

    // ---- pairing -------------------------------------------------------------------------------------

    /** Splits pasted text (URL, `daycue://pair?relay=..&code=..`, or a bare code) into relay URL and code. */
    fun parsePairing(text: String): PairingLink.Parsed = PairingLink.parse(text)

    suspend fun pair(relayUrl: String, code: String, deviceLabel: String = "DayCue phone"): PairResult = relay.pair(relayUrl, code, deviceLabel)

    /**
     * Revokes this phone on the relay (best effort, `DELETE /v1/phone/self`), then always deletes the Keystore key and the
     * credential. Returns true when the relay confirmed; false (offline, already revoked) means the owner should also
     * revoke the device on the relay.
     */
    suspend fun unpair(): Boolean = relay.unpair()

    /** Kill switch ("Disable remote access"): off = nothing is pulled, applied, published or polled. */
    fun setEnabled(enabled: Boolean) = relay.setEnabled(enabled)

    /** A code for pairing the Windows companion (valid 10 minutes). Null when not paired / offline. */
    suspend fun createCompanionCode(): String? = runCatching {
        val cr = relayCredentialsForUi() ?: return null
        HttpRelayApi(cr.first, cr.second).companionCode().code
    }.getOrNull()

    // ---- local scope settings ------------------------------------------------------------------------

    fun setConfigPolicy(p: ConfigPolicy) { relay.settings.update { it.copy(configPolicy = p) } }
    fun setSessionPolicy(p: SessionPolicy) { relay.settings.update { it.copy(sessionPolicy = p) } }

    /** Medication labels may be published to the relay (only when a client holds the medication scope) and remote medication edits accepted (always confirmed on the phone). */
    fun setAllowMedication(allow: Boolean) { relay.settings.update { it.copy(allowMedication = allow) }; relay.client.invalidatePublished(); relay.requestSync("settings") }

    fun setUseCompanionActivity(use: Boolean) { relay.settings.update { it.copy(useCompanionActivity = use) }; relay.applyPushSettings(); if (use) relay.requestSync("settings") }

    /** Opt-in. Battery cost: an inexact alarm every [minutes] (3..30) only at a work/study place inside permitted hours. */
    fun setFrequentCheck(enabled: Boolean, minutes: Int = 5) { relay.settings.update { it.copy(frequentCheck = enabled, frequentCheckMinutes = minutes.coerceIn(3, 30)) } }

    /** Needs a Firebase-enabled build (docs/setup/MCP.md); without it this only records the wish. */
    fun setPushWake(enabled: Boolean) { relay.settings.update { it.copy(pushWake = enabled) }; relay.applyPushSettings() }
    val pushAvailable: Boolean get() = relay.push.available

    // ---- sync ----------------------------------------------------------------------------------------

    fun syncNow() = relay.requestSync("manual")
    suspend fun syncNowAwait() = relay.client.sync("manual")

    // ---- confirmation ---------------------------------------------------------------------------------

    /**
     * The owner approved. **Only call this from in-app UI after a deliberate gesture** (the non-exported
     * `RemoteConfirmActivity` does; never from an intent, deep link or notification action). Call from a visible Activity: a routine start begins playback through
     * [RoutinePlaybackService] (Android 17 requires a user action for audio). Returns whether it was done.
     */
    suspend fun confirm(activityContext: Context, commandId: String): RelayClient.DecisionResult =
        relay.client.decide(commandId, accept = true) { routineId -> RoutinePlaybackService.start(activityContext, routineId, false); true }

    suspend fun decline(commandId: String): RelayClient.DecisionResult = relay.client.decide(commandId, accept = false)

    // ---- paired Windows companions -----------------------------------------------------------------------

    private val companionDirectory = app.daycue.integrations.relay.CompanionDirectory {
        relayCredentialsForUi()?.let { (url, token) -> HttpRelayApi(url, token) }
    }

    /** Paired desktop companions as of the last [refreshCompanions] (labels unverified; compare [PairedCompanion.fingerprint]). */
    val companions: StateFlow<List<app.daycue.integrations.relay.PairedCompanion>> get() = companionDirectory.companions

    /** Re-read the list from the relay (`GET /v1/phone/activity`). */
    suspend fun refreshCompanions(): app.daycue.integrations.relay.CompanionListResult = companionDirectory.refresh()

    /**
     * Unpair (revoke) one companion. Needs the relay endpoint `DELETE /v1/phone/companions/:id`, which the relay does not
     * have yet: until it does the result is `NotSupportedByRelay` and the UI should say "Unpair it on the PC".
     */
    suspend fun revokeCompanion(companionId: String): app.daycue.integrations.relay.CompanionRevokeResult = companionDirectory.revoke(companionId)

    private fun relayCredentialsForUi(): Pair<String, String>? = relay.credentialsForCalls()
}
