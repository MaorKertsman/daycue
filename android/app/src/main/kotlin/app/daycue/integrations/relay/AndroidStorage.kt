package app.daycue.integrations.relay

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.daycue.data.db.AuditDao
import app.daycue.data.db.AuditLogEntity
import app.daycue.data.db.CommandLogDao
import app.daycue.data.db.CommandLogEntity
import app.daycue.domain.edit.Sensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ECDSA P-256 key in Android Keystore: the private key never leaves the secure hardware/TEE (non-exportable)
 * and needs no user authentication, so background syncs can sign acks. StrongBox is used when present.
 */
class KeystoreSigner(private val alias: String) : DeviceSigner {
    private val ks get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun publicKeySpki(): ByteArray = ks.getCertificate(alias).publicKey.encoded

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(ks.getKey(alias, null) as PrivateKey); update(message); sign()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS_A = "daycue_relay_device_a"
        const val ALIAS_B = "daycue_relay_device_b"

        /** Generates a fresh key under [alias] (replacing any old one) and returns its signer. */
        fun generate(alias: String): KeystoreSigner {
            delete(alias)
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            try { gen.initialize(spec(true)); gen.generateKeyPair() }
            catch (e: StrongBoxUnavailableException) { gen.initialize(spec(false)); gen.generateKeyPair() }
            catch (e: java.security.ProviderException) { gen.initialize(spec(false)); gen.generateKeyPair() }
            return KeystoreSigner(alias)
        }

        fun delete(alias: String) {
            runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias) }
        }

        fun otherAlias(current: String?) = if (current == ALIAS_A) ALIAS_B else ALIAS_A
    }
}

/**
 * Relay URL + device credential, AES-GCM encrypted with a Keystore key (not exportable) and stored in
 * app-private preferences (excluded from backup: `allowBackup=false`).
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("daycue_relay_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KeystoreSigner.PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreSigner.PROVIDER).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    override fun save(c: RemoteCredentials) {
        val plain = listOf(c.relayUrl, c.deviceId, c.token, c.keyAlias).joinToString("\n").toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain)
        prefs.edit().putString("blob", Base64Url.encode(cipher.iv + ct)).apply()
    }

    override fun load(): RemoteCredentials? = runCatching {
        val blob = Base64Url.decode(prefs.getString("blob", null) ?: return null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        val p = String(cipher.doFinal(blob.copyOfRange(12, blob.size))).split("\n")
        RemoteCredentials(p[0], p[1], p[2], p[3])
    }.getOrNull()

    override fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(KeystoreSigner.PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private companion object { const val KEY_ALIAS = "daycue_relay_cred_v1" }
}

/** Remote-access settings in app-private preferences (not part of the synced config; the relay cannot change them). */
class PrefsSettingsStore(context: Context) : RelaySettingsStore {
    private val prefs = context.getSharedPreferences("daycue_relay_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val flow: StateFlow<RelaySettings> = state.asStateFlow()

    private fun read() = RelaySettings(
        enabled = prefs.getBoolean("enabled", true),
        configPolicy = runCatching { ConfigPolicy.valueOf(prefs.getString("configPolicy", null)!!) }.getOrDefault(ConfigPolicy.Auto),
        sessionPolicy = runCatching { SessionPolicy.valueOf(prefs.getString("sessionPolicy", null)!!) }.getOrDefault(SessionPolicy.Allow),
        allowMedication = prefs.getBoolean("allowMedication", false),
        useCompanionActivity = prefs.getBoolean("useCompanionActivity", false),
        frequentCheck = prefs.getBoolean("frequentCheck", false),
        frequentCheckMinutes = prefs.getInt("frequentCheckMinutes", 5),
        pushWake = prefs.getBoolean("pushWake", false),
        relayWantsMedication = prefs.getBoolean("relayWantsMedication", false),
    )

    @Synchronized
    override fun update(f: (RelaySettings) -> RelaySettings) {
        val n = f(state.value)
        prefs.edit()
            .putBoolean("enabled", n.enabled).putString("configPolicy", n.configPolicy.name).putString("sessionPolicy", n.sessionPolicy.name)
            .putBoolean("allowMedication", n.allowMedication).putBoolean("useCompanionActivity", n.useCompanionActivity)
            .putBoolean("frequentCheck", n.frequentCheck).putInt("frequentCheckMinutes", n.frequentCheckMinutes.coerceIn(3, 30))
            .putBoolean("pushWake", n.pushWake).putBoolean("relayWantsMedication", n.relayWantsMedication)
            .apply()
        state.value = n
    }
}

class RoomCommandLog(private val dao: CommandLogDao) : CommandLogStore {
    override suspend fun get(id: String) = dao.get(id)?.toLog()
    override suspend fun put(e: LogEntry) = dao.put(CommandLogEntity(e.commandId, e.receivedAtMs, e.origin, e.baseVersion, e.commandJson, e.state, e.resultJson, e.appliedVersion, e.ackedAtMs))
    override suspend fun unacked() = dao.unacked().map { it.toLog() }
    override suspend fun awaiting() = dao.awaiting().map { it.toLog() }

    private fun CommandLogEntity.toLog() = LogEntry(commandId, receivedAtMs, origin, baseVersion, opsJson, state, resultJson, appliedVersion, ackedAtMs)
}

class RoomRemoteAudit(private val dao: AuditDao, private val nowMs: () -> Long) : RemoteAudit {
    override suspend fun record(actor: String, action: String, sensitivity: Sensitivity, summary: String, versionBefore: Long?, versionAfter: Long?, commandId: String?) {
        dao.insert(AuditLogEntity(atMs = nowMs(), actor = actor, action = action, sensitivity = sensitivity.name, summary = summary, versionBefore = versionBefore, versionAfter = versionAfter, commandId = commandId))
    }

    /** `status.recentChanges` source: config changes only, sensitive ones withheld. */
    suspend fun recentChanges(limit: Int = 10): List<RecentChange> =
        dao.recent(60).filter { it.action.startsWith("config.") }.take(limit)
            .map { RecentChange(it.versionAfter, it.atMs, it.actor.substringBefore(':'), Redaction.auditSummary(it.sensitivity, it.summary)) }
}
