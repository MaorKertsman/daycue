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
    override fun confirmationNeeded(p: PendingRemote) { confirmations += p }
    override fun cancel(commandId: String) = Unit
    override fun appliedNotice(clientLabel: String, summary: String) { applied += summary }
}

/**
 * In-memory relay: queues commands, hands out the not-yet-final ones on every pull (at-least-once, like the
 * real relay), verifies ack signatures with the registered key and records every ack.
 */
class FakeRelay(private val signer: DeviceSigner, private val nowMs: () -> Long) : RelayApi {
    data class Ack(val id: String, val outcome: String, val result: JsonObject, val ackedAt: Long, val signatureOk: Boolean, val body: JsonObject)

    val commands = mutableListOf<WireCommand>()
    val acks = mutableListOf<Ack>()
    val finalOutcome = mutableMapOf<String, String>()
    var online = true
    var wantsMedication = false
    var snapshots = mutableListOf<JsonElement>()
    var activity = ActivityResponse()
    var pulls = 0
    private var seq = 0

    fun queue(type: String, payload: JsonObject, baseVersion: Long? = null, scopes: List<String> = listOf("config:read", "config:write"), ttlMs: Long = 3_600_000, label: String = "Claude"): WireCommand {
        val c = WireCommand(
            id = "cmd_${++seq}", type = type, payload = payload, baseVersion = baseVersion, idempotencyKey = "key$seq",
            payloadHash = "hash$seq" + "0".repeat(20), createdAt = nowMs(), expiresAt = nowMs() + ttlMs, grant = WireGrant(label, scopes),
        )
        commands += c
        return c
    }

    fun queueApply(ops: List<ConfigOp>, baseVersion: Long? = null, scopes: List<String> = listOf("config:read", "config:write"), ttlMs: Long = 3_600_000) =
        queue("config.apply", buildJsonObject { put("ops", DayCueJson.encodeToJsonElement(ListSerializer(ConfigOp.serializer()), ops)) }, baseVersion, scopes, ttlMs)

    private fun check() { if (!online) throw RelayException.Network(java.io.IOException("offline")) }

    override suspend fun pull(): PullResponse {
        check(); pulls++
        return PullResponse(commands.filter { finalOutcome[it.id] == null && acks.none { a -> a.id == it.id && a.outcome == "awaiting_confirmation" } }, Wants(wantsMedication), nowMs())
    }

    override suspend fun ack(commandId: String, body: JsonObject) {
        check()
        val c = commands.first { it.id == commandId }
        val outcome = body["outcome"]!!.jsonPrimitive.content
        val result = body["result"]?.jsonObject ?: JsonObject(emptyMap())
        val ackedAt = body["ackedAt"]!!.jsonPrimitive.content.toLong()
        val nv = result["newVersion"]?.jsonPrimitive?.content?.toLong()
        val msg = SigningStrings.ack(c.id, c.payloadHash, outcome, nv, ackedAt)
        val ok = body["payloadHash"]!!.jsonPrimitive.content == c.payloadHash &&
            EcdsaVerify.verify(signer.publicKeySpki(), msg, Base64Url.decode(body["signature"]!!.jsonPrimitive.content))
        acks += Ack(commandId, outcome, result, ackedAt, ok, body)
        if (!ok) throw RelayException.Http(403, "bad_signature", "bad signature")
        if (outcome != "awaiting_confirmation") finalOutcome[commandId] = outcome
    }

    override suspend fun putSnapshot(body: JsonElement): SnapshotResponse { check(); snapshots += body; return SnapshotResponse(true, Wants(wantsMedication), 0, nowMs()) }
    override suspend fun putPush(fcmToken: String?, wakeOnActivity: Boolean?) { check() }
    override suspend fun activity(): ActivityResponse { check(); return activity }
    override suspend fun companionCode() = CompanionCode("ABCDE-FGHJK")
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
    val client = RelayClient(
        apiProvider = { relay }, signerProvider = { signer }, engine = HostEngineGateway(host, store),
        log = log, audit = audit, settings = this.settings, notifier = notifier, nowMs = { clock.now().toEpochMilli() },
    )
}

val JsonObject.str: (String) -> String? get() = { k -> this[k]?.jsonPrimitive?.content }
