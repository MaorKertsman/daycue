package app.daycue.ui.setup

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import app.daycue.R
import app.daycue.data.db.AuditLogEntity
import app.daycue.integrations.relay.ConfigPolicy
import app.daycue.integrations.relay.GrantDecisionResult
import app.daycue.integrations.relay.CompanionRevokeResult
import app.daycue.integrations.relay.PairResult
import app.daycue.integrations.relay.PairedCompanion
import app.daycue.integrations.relay.PendingRemote
import app.daycue.integrations.relay.RelaySettings
import app.daycue.integrations.relay.RelayStatus
import app.daycue.integrations.relay.RemoteGrant
import app.daycue.integrations.relay.SessionPolicy
import app.daycue.integrations.spotify.AlarmMusicState
import app.daycue.integrations.spotify.RecoveryAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the pairing form is doing. */
sealed interface PairUi {
    data object Idle : PairUi
    data object Working : PairUi
    data class Failed(val messageRes: Int) : PairUi
}

/** Remote access (MCP relay), the desktop companion link and Spotify, all over `facade.remote` / `facade.alarmMusic`. */
class IntegrationsViewModel(app: Application) : SetupViewModel(app) {
    private val remote = facade.remote

    val paired: StateFlow<Boolean> = remote.paired
    val settings: StateFlow<RelaySettings> = remote.settings
    val status: StateFlow<RelayStatus> = remote.status
    val grants: StateFlow<List<RemoteGrant>> = remote.grants
    val pending: StateFlow<List<PendingRemote>> = remote.pending
    val companions: StateFlow<List<PairedCompanion>> = remote.companions
    val alarmMusic: StateFlow<AlarmMusicState> = facade.alarmMusic
    val spotify: StateFlow<app.daycue.integrations.spotify.SpotifyAvailability> = facade.spotify
    fun refreshSpotify() = facade.refreshSpotify()

    /** Newest first. Only config changes and remote commands matter on this screen. */
    val audit: StateFlow<List<AuditLogEntity>> = facade.audit(80)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val configVersion: StateFlow<Long?> = facade.config.map { it.version as Long? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pairState = MutableStateFlow<PairUi>(PairUi.Idle)
    val companionCode = MutableStateFlow<CodeUi>(CodeUi.None)
    val syncing = MutableStateFlow(false)

    sealed interface CodeUi {
        data object None : CodeUi
        data object Loading : CodeUi
        data class Ready(val code: String, val createdAtMs: Long) : CodeUi
        data object Failed : CodeUi
    }

    fun relayHost(): String? = remote.relayHost()
    fun pushAvailable(): Boolean = remote.pushAvailable
    fun confirmationIntent(context: Context, commandId: String? = null, grantId: String? = null): Intent =
        remote.confirmationIntent(context, commandId, grantId)

    fun parsePairing(text: String) = remote.parsePairing(text)

    fun pair(url: String, code: String, label: String, replaceExisting: Boolean) {
        if (pairState.value == PairUi.Working) return
        viewModelScope.launch {
            pairState.value = PairUi.Working
            if (replaceExisting) remote.unpair()
            val result = remote.pair(url.trim(), code.trim().uppercase(), label.trim().ifEmpty { "DayCue phone" })
            pairState.value = when (result) {
                PairResult.Ok -> { post(R.string.su_remote_paired); PairUi.Idle }
                PairResult.InvalidUrl -> PairUi.Failed(R.string.su_pair_err_url)
                PairResult.CleartextNotAllowed -> PairUi.Failed(R.string.su_pair_err_https)
                is PairResult.Rejected -> PairUi.Failed(
                    when (result.code) {
                        "invalid_pair_code" -> R.string.su_pair_err_code
                        "locked" -> R.string.su_pair_err_locked
                        else -> R.string.su_pair_err_rejected
                    },
                )
                is PairResult.Offline -> PairUi.Failed(R.string.su_pair_err_offline)
            }
        }
    }

    fun clearPairError() { if (pairState.value is PairUi.Failed) pairState.value = PairUi.Idle }

    fun unpair(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(remote.unpair()) }
    }

    fun setEnabled(on: Boolean) = remote.setEnabled(on)
    fun setConfigPolicy(p: ConfigPolicy) = remote.setConfigPolicy(p)
    fun setSessionPolicy(p: SessionPolicy) = remote.setSessionPolicy(p)
    fun setAllowMedication(on: Boolean) = remote.setAllowMedication(on)
    fun setUseCompanionActivity(on: Boolean) = remote.setUseCompanionActivity(on)
    fun setFrequentCheck(on: Boolean, minutes: Int) = remote.setFrequentCheck(on, minutes)
    fun setPushWake(on: Boolean) = remote.setPushWake(on)

    fun syncNow() {
        if (syncing.value) return
        viewModelScope.launch { syncing.value = true; remote.syncNowAwait(); syncing.value = false }
    }

    fun refreshGrants() { viewModelScope.launch { remote.refreshGrants() } }

    fun refreshCompanions() { viewModelScope.launch { remote.refreshCompanions() } }

    /** Unpairs one desktop companion. The relay may not support this from the phone yet: then the owner is told to do it on the PC. */
    fun revokeCompanion(c: PairedCompanion) {
        viewModelScope.launch {
            when (remote.revokeCompanion(c.id)) {
                CompanionRevokeResult.Done -> post(R.string.su_companion_unpaired)
                CompanionRevokeResult.NotSupportedByRelay -> post(R.string.su_companion_unpair_on_pc)
                CompanionRevokeResult.BadSignature -> post(R.string.su_companion_cant_unpair)
                CompanionRevokeResult.Offline -> post(R.string.su_grant_offline)
                CompanionRevokeResult.Unauthorized -> post(R.string.su_grant_unauthorized)
                else -> post(R.string.su_grant_failed)
            }
        }
    }

    fun decline(grant: RemoteGrant) { viewModelScope.launch { report(remote.declineGrant(grant.id), R.string.su_grant_declined) } }
    fun revoke(grant: RemoteGrant) { viewModelScope.launch { report(remote.revokeGrant(grant.id), R.string.su_grant_revoked) } }

    private fun report(result: GrantDecisionResult, ok: Int) {
        when (result) {
            GrantDecisionResult.Done -> post(ok)
            GrantDecisionResult.Offline -> post(R.string.su_grant_offline)
            GrantDecisionResult.Unauthorized -> post(R.string.su_grant_unauthorized)
            else -> post(R.string.su_grant_failed)
        }
    }

    fun createCompanionCode() {
        if (companionCode.value == CodeUi.Loading) return
        viewModelScope.launch {
            companionCode.value = CodeUi.Loading
            val code = remote.createCompanionCode()
            companionCode.value = if (code == null) CodeUi.Failed else CodeUi.Ready(code, System.currentTimeMillis())
        }
    }

    /** Undo of the latest change (a new version is written; history is kept). */
    fun undoLatest() { viewModelScope.launch { facade.undo(); post(R.string.su_undone) } }

    fun alarmRecoveryIntent(action: RecoveryAction): Intent? = facade.alarmMusicRecoveryIntent(action)
}
