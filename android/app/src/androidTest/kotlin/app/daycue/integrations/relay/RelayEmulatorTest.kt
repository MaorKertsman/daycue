package app.daycue.integrations.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.daycue.data.db.DayCueDatabase
import app.daycue.data.repo.RoomEngineStore
import app.daycue.domain.Clock
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.WakePrecision
import app.daycue.engine.ArmResult
import app.daycue.engine.EffectSink
import app.daycue.engine.EngineHost
import app.daycue.engine.HostLog
import app.daycue.engine.UsedApi
import app.daycue.engine.WakeScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * EMULATOR test: real Android Keystore ECDSA key (non-exportable), real Room, the real EngineHost and the REAL
 * local relay from `mcp/` reached at the host (`http://10.0.2.2:<port>`). Run with
 * `-Pandroid.testInstrumentationRunnerArguments.relayUrl=http://10.0.2.2:8787 -P...ownerSecret=<secret>`;
 * skipped when those arguments are absent.
 */
@RunWith(AndroidJUnit4::class)
class RelayEmulatorTest {
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: DayCueDatabase
    private val http = UrlConnectionTransport()
    private val alias = "daycue_relay_device_instrumented_test"

    private val clock = object : Clock {
        override fun now(): Instant = Instant.now()
        override fun zone(): ZoneId = ZoneId.systemDefault()
        override fun elapsedRealtime(): Duration = Duration.ofMillis(android.os.SystemClock.elapsedRealtime())
    }
    private val scheduler = object : WakeScheduler {
        override fun arm(at: Instant, precision: WakePrecision) = ArmResult(at, precision, UsedApi.ExactAllowWhileIdle, false)
        override fun cancel() = Unit
    }
    private val sink = object : EffectSink { override fun execute(effect: Effect, config: DayCueConfig, state: EngineState) = Unit }
    private val log = object : HostLog { override fun info(msg: String) = Unit; override fun warn(msg: String, t: Throwable?) = Unit }

    @Before fun setUp() { db = DayCueDatabase.inMemory(ctx) }
    @After fun tearDown() { db.close(); KeystoreSigner.delete(alias) }

    private suspend fun owner(url: String, secret: String, path: String, body: JsonObject? = null): JsonObject {
        val r = http.request("POST", url + path, mapOf("Authorization" to "Bearer $secret", "Content-Type" to "application/json"), (body ?: JsonObject(emptyMap())).toString(), 30_000)
        check(r.status in 200..299) { "$path -> ${r.status} ${r.body}" }
        return WireJson.parseToJsonElement(r.body).jsonObject
    }

    private suspend fun client(url: String, token: String, op: String, body: JsonObject): JsonObject {
        val r = http.request("POST", "$url/v1/client/$op", mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"), body.toString(), 40_000)
        check(r.status in 200..299) { "$op -> ${r.status} ${r.body}" }
        return WireJson.parseToJsonElement(r.body).jsonObject
    }

    @Test fun credentialStoreRoundTripsAndClears() {
        val s = KeystoreCredentialStore(ctx)
        s.clear()
        s.save(RemoteCredentials("https://relay.example", "ph_1", "dcd_secret", "alias_a"))
        assertEquals(RemoteCredentials("https://relay.example", "ph_1", "dcd_secret", "alias_a"), s.load())
        val raw = ctx.getSharedPreferences("daycue_relay_secure", 0).getString("blob", "")!!
        assertTrue("token must not be stored in clear", !raw.contains("dcd_secret"))
        s.clear()
        assertEquals(null, s.load())
    }

    @Test fun keystoreKeySignsAcksTheRealRelayAccepts() = runBlocking {
        val url = args.getString("relayUrl"); val secret = args.getString("ownerSecret")
        assumeTrue("relayUrl/ownerSecret instrumentation arguments missing: skipped", url != null && secret != null)
        url!!; secret!!

        val signer = KeystoreSigner.generate(alias)
        val host = EngineHost(RoomEngineStore(db), clock, scheduler, sink, log = log)
        val v0 = host.ensureLoaded().config.version
        host.applyOps(listOf(ConfigOp.UpsertHabit(IntervalHabit("demo", IntervalKind.Generic, "Demo habit", enabled = true, intervalMin = 30, repeat = RepeatPolicy(5, 1), snoozeMin = 5))), v0, "ui")

        val code = owner(url, secret, "/v1/owner/pair-codes")["code"]!!.jsonPrimitive.content
        val paired = HttpRelayApi.pair(url, code, signer.publicKeySpki(), "emulator")
        var online = true
        val real = HttpRelayApi(url, paired.token)
        val api = object : RelayApi by real {
            override suspend fun pull(): PullResponse { if (!online) throw RelayException.Network(java.io.IOException("offline")); return real.pull() }
        }
        val settings = object : RelaySettingsStore {
            private val s = kotlinx.coroutines.flow.MutableStateFlow(RelaySettings())
            override val flow = s
            override fun update(f: (RelaySettings) -> RelaySettings) { s.value = f(s.value) }
        }
        val c = RelayClient({ api }, { signer }, HostEngineGateway(host, RoomEngineStore(db)), RoomCommandLog(db.commandLogDao()),
            RoomRemoteAudit(db.auditDao()) { System.currentTimeMillis() }, settings, NoopNotifier, { System.currentTimeMillis() })

        val token = owner(url, secret, "/v1/owner/client-tokens", buildJsonObject {
            put("label", "emu-client"); put("scopes", buildJsonArray { listOf("config:read", "config:write").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })["token"]!!.jsonPrimitive.content

        assertEquals(SyncStatus.Ok, c.sync("first").status)
        val base = host.ensureLoaded().config.version

        // queue while offline: stays queued; applied exactly once after reconnect (scenarios 15/16)
        online = false
        val ops = DayCueJson.encodeToJsonElement(ListSerializer(ConfigOp.serializer()), listOf(ConfigOp.SetHabitInterval("demo", 45)))
        val id = client(url, token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", ops) })
            put("baseVersion", base); put("idempotencyKey", "emu-key-0001"); put("waitSeconds", 0)
        })["commandId"]!!.jsonPrimitive.content
        assertEquals(SyncStatus.Offline, c.sync("offline").status)
        assertEquals("queued", client(url, token, "getCommand", buildJsonObject { put("id", id) })["state"]!!.jsonPrimitive.content)

        online = true
        assertEquals(SyncStatus.Ok, c.sync("reconnect").status)
        val done = client(url, token, "getCommand", buildJsonObject { put("id", id) })
        // "applied" only exists on the relay if it verified the Keystore signature
        assertEquals("applied", done["state"]!!.jsonPrimitive.content)
        assertEquals(base + 1, host.ensureLoaded().config.version)
        c.sync("again")
        assertEquals(base + 1, host.ensureLoaded().config.version)
        assertEquals("applied", db.commandLogDao().get(id)!!.state)
    }
}
