package app.daycue.facade

import android.content.Context
import app.daycue.AppContainer
import app.daycue.delivery.RoutinePlaybackService
import app.daycue.integrations.relay.ConfigPolicy
import app.daycue.integrations.relay.PairResult
import app.daycue.integrations.relay.PairingLink
import app.daycue.integrations.relay.PendingRemote
import app.daycue.integrations.relay.RelayClient
import app.daycue.integrations.relay.RelaySettings
import app.daycue.integrations.relay.RelayStatus
import app.daycue.integrations.relay.SessionPolicy
import app.daycue.integrations.relay.HttpRelayApi
import kotlinx.coroutines.flow.StateFlow

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

    // ---- pairing -------------------------------------------------------------------------------------

    /** Splits pasted text (URL, `daycue://pair?relay=..&code=..`, or a bare code) into relay URL and code. */
    fun parsePairing(text: String): PairingLink.Parsed = PairingLink.parse(text)

    suspend fun pair(relayUrl: String, code: String, deviceLabel: String = "DayCue phone"): PairResult = relay.pair(relayUrl, code, deviceLabel)

    /** Deletes the Keystore key and the credential. The owner should also revoke the device on the relay. */
    suspend fun unpair() = relay.unpair()

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
     * The owner approved. Call from a visible Activity: a routine start begins playback through
     * [RoutinePlaybackService] (Android 17 requires a user action for audio). Returns whether it was done.
     */
    suspend fun confirm(activityContext: Context, commandId: String): RelayClient.DecisionResult =
        relay.client.decide(commandId, accept = true) { routineId -> RoutinePlaybackService.start(activityContext, routineId, false); true }

    suspend fun decline(commandId: String): RelayClient.DecisionResult = relay.client.decide(commandId, accept = false)

    private fun relayCredentialsForUi(): Pair<String, String>? = relay.credentialsForCalls()
}
