package app.daycue.integrations.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * A Windows companion paired with the relay, as listed by `GET /v1/phone/activity` (`companions[]`). [label] is chosen on
 * the PC and **unverified**. [fingerprint] = first 10 base32 characters of SHA-256 of the public key (RELAY.md 4.4.1), so
 * the owner can compare it with the one the companion shows.
 */
data class PairedCompanion(val id: String, val label: String, val fingerprint: String)

enum class CompanionListResult { Done, NotPaired, Offline, Unauthorized, Failed }

enum class CompanionRevokeResult {
    Done, NotPaired, NotFound,
    /** An older relay without the phone-side revoke endpoint: revoke it on the PC (Unpair) or with the owner API. */
    NotSupportedByRelay,
    /** The relay rejected the phone-key signature (403 `bad_signature`), or no device key was available (never a retry). */
    BadSignature,
    Offline, Unauthorized, Failed,
}

/** Companion list + revoke over [RelayApi] (JVM-tested with a fake API). */
class CompanionDirectory(private val api: () -> RelayApi?) {
    private val _companions = MutableStateFlow<List<PairedCompanion>>(emptyList())
    val companions: StateFlow<List<PairedCompanion>> = _companions.asStateFlow()

    suspend fun refresh(): CompanionListResult {
        val a = api() ?: return CompanionListResult.NotPaired
        return try {
            _companions.value = a.activity().companions.map { PairedCompanion(it.id, it.label, fingerprint(it.publicKey)) }
            CompanionListResult.Done
        } catch (e: RelayException.Http) {
            if (e.isUnauthorized) CompanionListResult.Unauthorized else CompanionListResult.Failed
        } catch (e: RelayException.Network) { CompanionListResult.Offline }
    }

    suspend fun revoke(companionId: String): CompanionRevokeResult {
        val a = api() ?: return CompanionRevokeResult.NotPaired
        return try {
            a.revokeCompanion(companionId)
            _companions.value = _companions.value.filterNot { it.id == companionId }
            CompanionRevokeResult.Done
        } catch (e: RelayException.Http) {
            when {
                e.isUnauthorized -> CompanionRevokeResult.Unauthorized
                e.status == 403 && e.code == "bad_signature" -> CompanionRevokeResult.BadSignature
                e.status == 0 && e.code == HttpRelayApi.NO_DEVICE_KEY -> CompanionRevokeResult.BadSignature
                e.status == 404 && e.code == "unknown_companion" -> CompanionRevokeResult.NotFound
                e.status == 404 || e.status == 405 -> CompanionRevokeResult.NotSupportedByRelay
                else -> CompanionRevokeResult.Failed
            }
        } catch (e: RelayException.Network) { CompanionRevokeResult.Offline }
    }

    fun clear() { _companions.value = emptyList() }

    companion object {
        private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** First 10 base32 characters of SHA-256 over the decoded SPKI (or over the text when it isn't base64). */
        fun fingerprint(publicKey: String): String {
            val bytes = runCatching { java.util.Base64.getDecoder().decode(publicKey.trim()) }
                .recoverCatching { java.util.Base64.getUrlDecoder().decode(publicKey.trim()) }
                .getOrElse { publicKey.toByteArray(Charsets.UTF_8) }
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder()
            var buf = 0; var bits = 0
            for (b in d) {
                buf = (buf shl 8) or (b.toInt() and 0xff); bits += 8
                while (bits >= 5 && sb.length < 10) { sb.append(B32[(buf shr (bits - 5)) and 31]); bits -= 5 }
                if (sb.length >= 10) break
            }
            return sb.toString()
        }
    }
}
