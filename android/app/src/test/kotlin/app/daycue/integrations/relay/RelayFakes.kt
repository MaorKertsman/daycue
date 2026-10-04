package app.daycue.integrations.relay

import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Sensitivity
import app.daycue.domain.config.DayCueJson
import app.daycue.engine.EngineHost
import app.daycue.engine.MemoryStore
import app.daycue.engine.FakeScheduler
import app.daycue.engine.RecordingSink
import app.daycue.engine.SilentLog
import app.daycue.engine.TestClock
import app.daycue.engine.NoopBootSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

/** Software P-256 key (JVM stand-in for the Keystore key; same JCA signing path). */
class SoftwareSigner : DeviceSigner {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override fun publicKeySpki(): ByteArray = kp.public.encoded
    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(message); sign() }
}

class MemorySettings(initial: RelaySettings = RelaySettings()) : RelaySettingsStore {
    private val s = MutableStateFlow(initial)
    override val flow: StateFlow<RelaySettings> = s
    override fun update(f: (RelaySettings) -> RelaySettings) { s.value = f(s.value) }
}

class MemoryLog : CommandLogStore {
    val rows = linkedMapOf<String, LogEntry>()
    override suspend fun get(id: String) = rows[id]
    override suspend fun put(e: LogEntry) { rows[e.commandId] = e }
    override suspend fun unacked() = rows.values.filter { it.ackedAtMs == null && it.state != "received" }
    override suspend fun awaiting() = rows.values.filter { it.state == "awaiting_confirmation" }
}

class MemoryAudit : RemoteAudit {
    data class Row(val actor: String, val action: String, val sensitivity: Sensitivity, val summary: String)
    val rows = mutableListOf<Row>()
    override suspend fun record(actor: String, action: String, sensitivity: Sensitivity, summary: String, versionBefore: Long?, versionAfter: Long?, commandId: String?) {
        rows += Row(actor, action, sensitivity, summary)
    }
}

class RecordingNotifier : RemoteNotifier {
    val confirmations = mutableListOf<PendingRemote>()
    val applied = mutableListOf<String>()
    val grantNotices = mutableListOf<RemoteGrant>()
    val cancelledGrants = mutableListOf<String>()
    var revokedNotices = 0
    override fun confirmationNeeded(p: PendingRemote) { confirmations += p }
    override fun cancel(commandId: String) = Unit
    override fun appliedNotice(clientLabel: String, summary: String) { applied += summary }
    override fun grantAwaitingApproval(g: RemoteGrant) { grantNotices += g }
    override fun cancelGrant(grantId: String) { cancelledGrants += grantId }
    override fun phoneRevoked() { revokedNotices++ }
}

/**
 * In-memory relay: queues commands, hands out the not-yet-final ones on every pull (at-least-once, like the
 * real relay), verifies ack signatures with the registered key and records every ack.
 */
class FakeRelay(private val signer: DeviceSigner, private val nowMs: () -> Long) : RelayApi {
    data class Ack(val id: String, val outcome: String, val result: JsonObject, val ackedAt: Long, val signatureOk: Boolean, val body: JsonObject) {
        val v2: Boolean get() = body["signatureVersion"]?.jsonPrimitive?.content == "2"
    }
    data class GrantCall(val id: String, val decision: String, val approvedScopes: List<String>, val signatureOk: Boolean)

    val commands = mutableListOf<WireCommand>()
    val acks = mutableListOf<Ack>()
    val finalOutcome = mutableMapOf<String, String>()
    var online = true
    var wantsMedication = false
    var snapshots = mutableListOf<JsonElement>()
    var activity = ActivityResponse()
    var grantsList = GrantsList(1, emptyList())
    val grantCalls = mutableListOf<GrantCall>()
    var selfRevoked = false
    /** Make every call answer 401 (the relay revoked this phone). */
    var unauthorized = false
    var pulls = 0
    private var seq = 0

    fun queue(type: String, payload: JsonObject, baseVersion: Long? = null, scopes: List<String> = listOf("config:read", "config:write"), ttlMs: Long = 3_600_000, label: String = "Claude"): WireCommand {
        val c = WireCommand(
            id = "cmd_${++seq}", type = type, payload = payload, baseVersion = baseVersion, idempotencyKey = "key$seq",
            payloadHash = RelayClient.payloadHashOf(type, payload, baseVersion), createdAt = nowMs(), expiresAt = nowMs() + ttlMs, grant = WireGrant(label, scopes),
        )
        commands += c
        return c
    }

    fun queueApply(ops: List<ConfigOp>, baseVersion: Long? = null, scopes: List<String> = listOf("config:read", "config:write"), ttlMs: Long = 3_600_000) =
        queue("config.apply", buildJsonObject { put("ops", DayCueJson.encodeToJsonElement(ListSerializer(ConfigOp.serializer()), ops)) }, baseVersion, scopes, ttlMs)

    private fun check() {
        if (!online) throw RelayException.Network(java.io.IOException("offline"))
        if (unauthorized) throw RelayException.Http(401, "unauthorized", "Invalid device credential")
    }

    override suspend fun pull(): PullResponse {
        check(); pulls++
        return PullResponse(commands.filter { finalOutcome[it.id] == null && acks.none { a -> a.id == it.id && a.outcome == "awaiting_confirmation" } }, Wants(wantsMedication), grantsList, nowMs())
    }

    override suspend fun ack(commandId: String, body: JsonObject) {
        check()
        val c = commands.first { it.id == commandId }
        val outcome = body["outcome"]!!.jsonPrimitive.content
        val result = body["result"]?.jsonObject ?: JsonObject(emptyMap())
        val ackedAt = body["ackedAt"]!!.jsonPrimitive.content.toLong()
        val nv = result["newVersion"]?.jsonPrimitive?.content?.toLong()
        val v2 = body["signatureVersion"]?.jsonPrimitive?.content == "2"
        val msg = if (v2) SigningStrings.ackV2(c.id, c.payloadHash, outcome, nv, ackedAt, CanonicalJson.sha256Hex(CanonicalJson.encode(body["result"])))
        else SigningStrings.ack(c.id, c.payloadHash, outcome, nv, ackedAt)
        val ok = body["payloadHash"]!!.jsonPrimitive.content == c.payloadHash &&
            EcdsaVerify.verify(signer.publicKeySpki(), msg, Base64Url.decode(body["signature"]!!.jsonPrimitive.content))
        acks += Ack(commandId, outcome, result, ackedAt, ok, body)
        if (!ok) throw RelayException.Http(403, "bad_signature", "bad signature")
        if (outcome != "awaiting_confirmation") finalOutcome[commandId] = outcome
    }

    override suspend fun putSnapshot(body: JsonElement): SnapshotResponse { check(); snapshots += body; return SnapshotResponse(true, Wants(wantsMedication), grantsList.version, 0, nowMs()) }
    override suspend fun putPush(fcmToken: String?, wakeOnActivity: Boolean?) { check() }
    override suspend fun activity(): ActivityResponse { check(); return activity }
    override suspend fun companionCode() = CompanionCode("ABCDE-FGHJK")

    override suspend fun grants(): GrantsResponse { check(); return GrantsResponse(grantsList, nowMs()) }

    override suspend fun decideGrant(grantId: String, body: JsonObject): GrantDecisionResponse {
        check()
        val decision = body["decision"]!!.jsonPrimitive.content
        val scopes = (body["approvedScopes"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val decidedAt = body["decidedAt"]!!.jsonPrimitive.content.toLong()
        val ok = EcdsaVerify.verify(signer.publicKeySpki(), SigningStrings.grant(grantId, decision, scopes, decidedAt), Base64Url.decode(body["signature"]!!.jsonPrimitive.content))
        grantCalls += GrantCall(grantId, decision, scopes, ok)
        if (!ok) throw RelayException.Http(403, "bad_signature", "bad signature")
        val g = grantsList.items.firstOrNull { it.id == grantId } ?: throw RelayException.Http(404, "not_found", "Unknown or already revoked grant")
        val items = when (decision) {
            "approve" -> grantsList.items.map { if (it.id == grantId) it.copy(approval = "approved", scopes = scopes.ifEmpty { g.scopes }, activeScopes = scopes.ifEmpty { g.scopes }) else it }
            else -> grantsList.items.filter { it.id != grantId }
        }
        grantsList = GrantsList(grantsList.version + 1, items)
        return GrantDecisionResponse(true, if (decision == "approve") "approved" else null, if (decision != "approve") true else null, grantsList, nowMs())
    }

    override suspend fun unpairSelf() { check(); selfRevoked = true }
}

/** Everything needed to drive a [RelayClient] on the JVM with the real [EngineHost]. */
class Harness(
    val clock: TestClock = TestClock(Instant.parse("2026-10-05T07:00:00Z")),
    settings: RelaySettings = RelaySettings(),
) {
    val store = MemoryStore()
    val sink = RecordingSink(store)
    val host = EngineHost(store, clock, FakeScheduler(), sink, NoopBootSnapshot, SilentLog)
    val signer = SoftwareSigner()
    val relay = FakeRelay(signer) { clock.now().toEpochMilli() }
    val settings = MemorySettings(settings)
    val log = MemoryLog()
    val audit = MemoryAudit()
    val notifier = RecordingNotifier()
    val grantStore = MemoryGrantStore()
    var revokedCallbacks = 0
    val client = RelayClient(
        apiProvider = { relay }, signerProvider = { signer }, engine = HostEngineGateway(host, store),
        log = log, audit = audit, settings = this.settings, notifier = notifier, nowMs = { clock.now().toEpochMilli() },
        grantStore = grantStore, onRevokedByRelay = { revokedCallbacks++ },
    )
}

val JsonObject.str: (String) -> String? get() = { k -> this[k]?.jsonPrimitive?.content }
