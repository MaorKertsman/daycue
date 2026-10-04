package app.daycue.integrations.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire protocol of docs/architecture/RELAY.md section 4 (phone side). */
internal val WireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable data class WireGrant(val clientLabel: String = "", val scopes: List<String> = emptyList())

@Serializable
data class WireCommand(
    val id: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val baseVersion: Long? = null,
    val idempotencyKey: String = "",
    val payloadHash: String,
    val createdAt: Long = 0,
    val expiresAt: Long,
    val grant: WireGrant = WireGrant(),
)

@Serializable data class Wants(val medication: Boolean = false)

@Serializable data class PullResponse(val commands: List<WireCommand> = emptyList(), val wants: Wants = Wants(), val serverTime: Long = 0)

@Serializable data class PairResponse(val deviceId: String, val token: String, val serverTime: Long = 0)

@Serializable data class SnapshotResponse(val ok: Boolean = true, val wants: Wants = Wants(), val pendingCommands: Int = 0, val serverTime: Long = 0)

@Serializable data class WireCompanion(val id: String, val label: String = "", val publicKey: String)

@Serializable
data class WireSignal(
    val companionId: String,
    val state: String,
    val observedAt: Long,
    val ttlSeconds: Int,
    /** New relay shape. */
    val signature: String? = null,
    /** Legacy single-signal shape used `sig`. */
    val sig: String? = null,
) { val signatureOrSig: String? get() = signature ?: sig }

@Serializable
data class ActivityResponse(
    val signals: List<WireSignal> = emptyList(),
    val signal: WireSignal? = null,
    val companions: List<WireCompanion> = emptyList(),
    val serverTime: Long = 0,
) {
    /** `signals` (per-companion slots) with fallback to the legacy single `signal`. */
    val all: List<WireSignal> get() = signals.ifEmpty { listOfNotNull(signal) }
}

@Serializable data class CompanionCode(val code: String, val expiresAt: String = "")

/** Typed relay failures. 4xx are never retried (except 429); network failures and 5xx are "try later". */
sealed class RelayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(val status: Int, val code: String, message: String) : RelayException("HTTP $status $code: $message") {
        val isUnauthorized get() = status == 401
        val retryable get() = status >= 500 || status == 429
    }
    class Network(cause: Throwable) : RelayException("network: ${cause.message}", cause)
}

/** Phone-side view of the relay endpoints (the only thing [RelayClient] knows about HTTP). */
interface RelayApi {
    suspend fun pull(): PullResponse
    suspend fun ack(commandId: String, body: JsonObject)
    suspend fun putSnapshot(body: JsonElement): SnapshotResponse
    suspend fun putPush(fcmToken: String?, wakeOnActivity: Boolean?)
    suspend fun activity(): ActivityResponse
    suspend fun companionCode(): CompanionCode
}
