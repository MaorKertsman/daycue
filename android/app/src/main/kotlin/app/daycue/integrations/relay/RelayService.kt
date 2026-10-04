package app.daycue.integrations.relay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.daycue.AppContainer
import app.daycue.DayCueApplication
import app.daycue.data.repo.RoomEngineStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.TimeUnit

/** Optional push wake (FCM). The default does nothing, so the app builds and runs without `google-services.json`. */
interface PushProvider {
    val available: Boolean
    /** The registration token, or null when unavailable. */
    suspend fun token(): String?
}

object NoPushProvider : PushProvider {
    override val available = false
    override suspend fun token(): String? = null
}

sealed interface PairResult {
    data object Ok : PairResult
    data object InvalidUrl : PairResult
    data object CleartextNotAllowed : PairResult
    data class Rejected(val code: String, val message: String) : PairResult
    data class Offline(val message: String) : PairResult
}

/** `daycue-pair://` style text or a plain URL + code the owner copied from the relay owner API. */
object PairingLink {
    data class Parsed(val relayUrl: String?, val code: String?)

    /** Accepts `https://relay.example`, `daycue://pair?relay=<url>&code=ABCDE-FGHJK`, or a bare code. */
    fun parse(text: String): Parsed {
        val t = text.trim()
        if (t.startsWith("daycue://pair")) {
            val q = t.substringAfter('?', "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }
            return Parsed(q["relay"], q["code"])
        }
        if (t.startsWith("http://") || t.startsWith("https://")) return Parsed(t, null)
        if (Regex("[A-Za-z0-9]{5}-[A-Za-z0-9]{5}").matches(t)) return Parsed(null, t)
        return Parsed(null, null)
    }

    /** Cleartext only for debuggable builds (emulator relay at http://10.0.2.2); the network security config enforces it too. */
    fun validate(url: String, allowCleartext: Boolean): PairResult? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return PairResult.InvalidUrl
        if (u.host.isNullOrBlank()) return PairResult.InvalidUrl
        return when (u.scheme) {
            "https" -> null
            "http" -> if (allowCleartext) null else PairResult.CleartextNotAllowed
            else -> PairResult.InvalidUrl
        }
    }
}

/**
 * Android wiring of the relay client (pairing, credentials, triggers). Everything protocol-related is in
 * [RelayClient]; this class owns Keystore/WorkManager/AlarmManager and the app lifecycle.
 */
class RelayService(private val c: AppContainer) {
    private val app = c.app
    val settings: RelaySettingsStore by lazy { PrefsSettingsStore(app) }
    private val creds by lazy { KeystoreCredentialStore(app) }
    private val audit by lazy { RoomRemoteAudit(c.db.auditDao()) { c.clock.now().toEpochMilli() } }
    private val frequent by lazy { FrequentCheckScheduler(app) }
    @Volatile var push: PushProvider = loadPush()

    val client: RelayClient by lazy {
        RelayClient(
            apiProvider = { creds.load()?.let { HttpRelayApi(it.relayUrl, it.token) } },
            signerProvider = { creds.load()?.let { KeystoreSigner(it.keyAlias) } },
            engine = HostEngineGateway(c.host, RoomEngineStore(c.db)),
            log = RoomCommandLog(c.db.commandLogDao()),
            audit = audit,
            settings = settings,
            notifier = AndroidRemoteNotifier(app),
            nowMs = { c.clock.now().toEpochMilli() },
            recentChanges = { audit.recentChanges() },
            info = { Log.i(AppContainer.TAG, it) },
            grantStore = PrefsGrantStore(app),
            // The relay said 401: this phone is unpaired there. Stop background polling; the UI shows "pair again".
            onRevokedByRelay = { cancelTriggers() },
        )
    }

    val paired: StateFlow<Boolean> get() = pairedFlow.asStateFlow()
    private val pairedFlow = MutableStateFlow(false)

    internal fun credentialsForCalls(): Pair<String, String>? = creds.load()?.let { it.relayUrl to it.token }

    fun relayHost(): String? = creds.load()?.relayUrl?.let { runCatching { URI(it).host }.getOrNull() }

    private val debuggable get() = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Called once from the app container after the engine is loaded. */
    @OptIn(FlowPreview::class)
    fun start() {
        pairedFlow.value = creds.load() != null
        if (pairedFlow.value) { schedulePeriodic(); applyPushSettings() }
        // Publish after every config change, local or remote, and sync (a local change may unblock nothing, but
        // the relay's snapshot must follow the phone; RELAY.md 4.1).
        c.scope.launch {
            c.host.snapshot.filterNotNull().map { it.config.version }.distinctUntilChanged().debounce(1_500).collect {
                if (pairedFlow.value && settings.flow.value.enabled) client.sync("config-changed")
            }
        }
        // Re-evaluate the frequent-check policy whenever the engine's place or the settings change.
        c.scope.launch {
            c.host.snapshot.filterNotNull().map { it.state.context.place.value to it.config.version }.distinctUntilChanged().collect { reevaluateFrequent() }
        }
        c.scope.launch { settings.flow.collect { reevaluateFrequent() } }
        c.scope.launch { client.refreshPending() }
    }

    /** App open / foreground. */
    fun onAppOpen() { if (pairedFlow.value) requestSync("app-open") }

    fun requestSync(reason: String) {
        c.scope.launch { client.sync(reason) }
    }

    // ---- pairing ---------------------------------------------------------------------------------------

    suspend fun pair(relayUrl: String, code: String, label: String): PairResult {
        PairingLink.validate(relayUrl, debuggable)?.let { return it }
        val prev = creds.load()
        val alias = KeystoreSigner.otherAlias(prev?.keyAlias)
        val signer = try { KeystoreSigner.generate(alias) } catch (e: Exception) { return PairResult.Rejected("key_error", e.message ?: "Keystore error") }
        val fcm = if (push.available) runCatching { push.token() }.getOrNull() else null
        try {
            val r = HttpRelayApi.pair(relayUrl.trim(), code.trim(), signer.publicKeySpki(), label.take(60), fcm)
            creds.save(RemoteCredentials(relayUrl.trim().trimEnd('/'), r.deviceId, r.token, alias))
            prev?.let { KeystoreSigner.delete(it.keyAlias) } // re-pairing: the old key is useless now
            pairedFlow.value = true
            settings.update { it.copy(enabled = true) }
            client.onPaired()
            schedulePeriodic()
            requestSync("paired")
            applyPushSettings()
            return PairResult.Ok
        } catch (e: RelayException.Http) {
            if (prev == null || prev.keyAlias != alias) KeystoreSigner.delete(alias)
            return PairResult.Rejected(e.code, e.message ?: "")
        } catch (e: RelayException.Network) {
            if (prev == null || prev.keyAlias != alias) KeystoreSigner.delete(alias)
            return PairResult.Offline(e.message ?: "")
        }
    }

    /**
     * Unpair: revoke this phone on the relay (best effort, `DELETE /v1/phone/self`, so the credential stops working
     * at once), stop all triggers, delete the Keystore key and the stored credential. If the relay cannot be reached the
     * credential stays valid there until the owner revokes the device (`DELETE /v1/owner/devices/<id>`); the deleted
     * key can no longer sign acks or grant decisions either way.
     */
    suspend fun unpair(): Boolean {
        val revoked = runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { client.unpairRemote() } }.getOrNull() == true
        cancelTriggers()
        creds.load()?.let { KeystoreSigner.delete(it.keyAlias) }
        creds.clear()
        pairedFlow.value = false
        client.invalidatePublished()
        return revoked
    }

    /** Kill switch. Off: nothing is pulled, applied, published or checked; pending relay commands will expire. */
    fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(enabled = enabled) }
        if (enabled && pairedFlow.value) { schedulePeriodic(); client.invalidatePublished(); requestSync("enabled") } else cancelTriggers()
    }

    // ---- triggers --------------------------------------------------------------------------------------

    /**
     * Periodic background sync. WorkManager's minimum period is 15 minutes and the system may run it later
     * (Doze, standby buckets, battery saver): best effort, never a latency guarantee.
     */
    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<RelaySyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun enqueueNow(reason: String) {
        val req = OneTimeWorkRequestBuilder<RelaySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf("reason" to reason)).build()
        WorkManager.getInstance(app).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    private fun cancelTriggers() {
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(app).cancelUniqueWork(ONCE)
        frequent.cancel()
    }

    /** Evaluates [FrequentCheckPolicy] and arms or cancels the alarm chain. */
    suspend fun reevaluateFrequent() {
        val snap = c.host.ensureLoaded()
        val s = settings.flow.value
        if (pairedFlow.value && FrequentCheckPolicy.shouldPoll(snap.config, snap.state, s, c.clock.now(), c.clock.zone())) frequent.scheduleNext(s.frequentCheckMinutes)
        else frequent.cancel()
    }

    suspend fun onFrequentTick() {
        reevaluateFrequent()
        val snap = c.host.ensureLoaded()
        if (FrequentCheckPolicy.shouldPoll(snap.config, snap.state, settings.flow.value, c.clock.now(), c.clock.zone())) enqueueNow("frequent-check")
    }

    // ---- push ------------------------------------------------------------------------------------------

    fun applyPushSettings() {
        c.scope.launch {
            val s = settings.flow.value
            val cr = creds.load() ?: return@launch
            val api = HttpRelayApi(cr.relayUrl, cr.token)
            runCatching {
                if (s.pushWake && push.available) api.putPush(push.token(), s.useCompanionActivity)
                else api.putPush(null, false)
            }
        }
    }

    /** From the FCM service: the registration token changed. */
    fun onPushToken(token: String) {
        val s = settings.flow.value
        if (!pairedFlow.value || !s.pushWake) return
        c.scope.launch { creds.load()?.let { runCatching { HttpRelayApi(it.relayUrl, it.token).putPush(token, s.useCompanionActivity) } } }
    }

    /** From the FCM service: a data message `{type: sync}` arrived. Runs a sync through WorkManager (the receiver budget is short). */
    fun onPushWake() { if (pairedFlow.value && settings.flow.value.enabled) enqueueNow("push") }

    private fun loadPush(): PushProvider = runCatching {
        Class.forName("app.daycue.integrations.relay.FirebasePushProvider").getDeclaredConstructor(Context::class.java).newInstance(app) as PushProvider
    }.getOrDefault(NoPushProvider)

    companion object {
        const val PERIODIC = "daycue.relay.periodic"
        const val ONCE = "daycue.relay.once"
    }
}

class RelaySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DayCueApplication).container
        val r = container.relay.client.sync(inputData.getString("reason") ?: "worker")
        container.relay.reevaluateFrequent()
        // Offline/failed periodic runs are retried by the next period; one-time runs by their next trigger.
        return if (r.status == SyncStatus.Offline && inputData.getString("reason") != null) Result.retry() else Result.success()
    }
}
