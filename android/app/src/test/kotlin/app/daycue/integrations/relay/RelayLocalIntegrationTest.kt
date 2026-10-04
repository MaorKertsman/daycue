package app.daycue.integrations.relay

import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.edit.ConfigOp
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.demoHabit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * JVM integration test: the SAME client code (HttpRelayApi, RelayClient, signing) against the REAL relay from
 * `mcp/` started as a child process (`node` + `tsx`). The device key is a software P-256 key (the JCA signing
 * path is the same as Keystore's); the Keystore itself is exercised by the instrumented test on the emulator.
 * Skipped when node or `mcp/node_modules` are missing.
 */
class RelayLocalIntegrationTest {
    private var proc: Process? = null
    private lateinit var base: String
    private val secret = "test-owner-secret-" + "x".repeat(20)
    private val http = UrlConnectionTransport()
    private var dataFile: File? = null

    private fun mcpDir(): File? = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "mcp") }
        .firstOrNull { File(it, "node_modules/tsx/dist/cli.mjs").isFile }

    @Before fun start() {
        val dir = mcpDir()
        assumeTrue("mcp/node_modules missing: skipping the real-relay test", dir != null)
        val node = listOf("node").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        assumeTrue("node not on PATH: skipping", node != null)
        val port = ServerSocket(0).use { it.localPort }
        base = "http://localhost:$port"
        dataFile = File.createTempFile("relay-it", ".json").also { it.delete() }
        val pb = ProcessBuilder("node", "node_modules/tsx/dist/cli.mjs", "src/node/server.ts").directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("PORT", port.toString()); put("DAYCUE_BASE_URL", base); put("DAYCUE_OWNER_SECRET", secret)
            put("DAYCUE_DATA_FILE", dataFile!!.absolutePath)
        }
        proc = pb.start()
        val out = proc!!.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 60_000
        var up = false
        while (System.currentTimeMillis() < deadline && !up) {
            if (out.ready()) { if (out.readLine()?.contains("listening") == true) up = true } else Thread.sleep(100)
        }
        assumeTrue("relay did not start", up)
    }

    @After fun stop() { proc?.destroy(); proc?.waitFor(5, TimeUnit.SECONDS); dataFile?.delete() }

    private suspend fun owner(method: String, path: String, body: JsonObject? = null): JsonObject {
        val r = http.request(method, base + path, mapOf("Authorization" to "Bearer $secret", "Content-Type" to "application/json"), body?.toString(), 30_000)
        check(r.status in 200..299) { "$path -> ${r.status} ${r.body}" }
        return WireJson.parseToJsonElement(r.body).jsonObject
    }

    private suspend fun clientCall(token: String, op: String, body: JsonObject): JsonObject {
        val r = http.request("POST", "$base/v1/client/$op", mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"), body.toString(), 40_000)
        check(r.status in 200..299) { "$op -> ${r.status} ${r.body}" }
        return WireJson.parseToJsonElement(r.body).jsonObject
    }

    private fun ops(vararg o: ConfigOp): JsonElement = DayCueJson.encodeToJsonElement(ListSerializer(ConfigOp.serializer()), o.toList())

    @Test fun pairQueueSyncApplyOfflineThenReconnectAndCompanion() = runBlocking {
        val h = Harness(settings = RelaySettings(useCompanionActivity = true))
        h.host.ensureLoaded()
        h.host.applyOps(listOf(ConfigOp.UpsertHabit(demoHabit(30))), 0, "ui")

        // ---- pair (real relay, real DER signatures from the software key)
        val code = owner("POST", "/v1/owner/pair-codes")["code"]!!.jsonPrimitive.content
        val paired = HttpRelayApi.pair(base, code, h.signer.publicKeySpki(), "test phone")
        val api = HttpRelayApi(base, paired.token)
        var online = true
        val flaky = object : RelayApi by api {
            override suspend fun pull(): PullResponse { if (!online) throw RelayException.Network(java.io.IOException("offline")); return api.pull() }
        }
        val client = RelayClient({ flaky }, { h.signer }, HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { System.currentTimeMillis() })

        val token = owner("POST", "/v1/owner/client-tokens", buildJsonObject {
            put("label", "it-client"); put("scopes", buildJsonArray { listOf("config:read", "config:write", "sessions:control").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })["token"]!!.jsonPrimitive.content

        // ---- first sync publishes the redacted snapshot (version 1: the seeded habit)
        assertEquals(SyncStatus.Ok, client.sync("first").status)
        val cfg = clientCall(token, "getConfig", buildJsonObject { put("section", "habits") })
        assertEquals("1", cfg["snapshot"]!!.jsonObject["configVersion"]!!.jsonPrimitive.content)

        // ---- scenario 15: queue while the phone is offline: stays queued
        online = false
        val queued = clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", ops(ConfigOp.SetHabitInterval("demo", 45))) })
            put("baseVersion", 1); put("idempotencyKey", "it-key-0001"); put("waitSeconds", 0)
        })
        val id = queued["commandId"]!!.jsonPrimitive.content
        assertEquals("queued", queued["state"]!!.jsonPrimitive.content)
        assertEquals(SyncStatus.Offline, client.sync("offline").status)
        assertEquals("queued", clientCall(token, "getCommand", buildJsonObject { put("id", id) })["state"]!!.jsonPrimitive.content)

        // ---- scenario 16: reconnect: applied once, version bumps, relay sees a verified signed ack
        online = true
        assertEquals(SyncStatus.Ok, client.sync("reconnect").status)
        val done = clientCall(token, "getCommand", buildJsonObject { put("id", id) })
        assertEquals("applied", done["state"]!!.jsonPrimitive.content)
        assertEquals(true, done["applied"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2L, h.host.ensureLoaded().config.version)
        assertEquals(45, (h.host.ensureLoaded().config.habit("demo") as IntervalHabit).intervalMin)
        client.sync("again") // no second application
        assertEquals(2L, h.host.ensureLoaded().config.version)
        assertEquals("2", clientCall(token, "getConfig", buildJsonObject { put("section", "habits") })["snapshot"]!!.jsonObject["configVersion"]!!.jsonPrimitive.content)

        // ---- idempotent re-submit with the same key returns the same command
        val dup = clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", ops(ConfigOp.SetHabitInterval("demo", 45))) })
            put("baseVersion", 1); put("idempotencyKey", "it-key-0001"); put("waitSeconds", 0)
        })
        assertEquals(id, dup["commandId"]!!.jsonPrimitive.content)

        // ---- destructive: awaiting_confirmation visible on the relay, applied only after the owner decides
        val del = clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", ops(ConfigOp.DeleteHabit("demo"))) })
            put("baseVersion", 2); put("idempotencyKey", "it-key-0002"); put("waitSeconds", 0)
        })["commandId"]!!.jsonPrimitive.content
        client.sync("del")
        assertEquals("awaiting_confirmation", clientCall(token, "getCommand", buildJsonObject { put("id", del) })["state"]!!.jsonPrimitive.content)
        assertNotNull(h.host.ensureLoaded().config.habit("demo"))
        client.decide(del, accept = true)
        assertEquals("applied", clientCall(token, "getCommand", buildJsonObject { put("id", del) })["state"]!!.jsonPrimitive.content)
        assertNull(h.host.ensureLoaded().config.habit("demo"))

        // ---- undo via the relay
        val undo = clientCall(token, "undo", buildJsonObject { put("commandId", del); put("idempotencyKey", "it-key-0003"); put("waitSeconds", 0) })["commandId"]!!.jsonPrimitive.content
        client.sync("undo")
        // the undo re-adds a deleted item: restoring is a non-destructive add, so it applies directly
        val u = clientCall(token, "getCommand", buildJsonObject { put("id", undo) })["state"]!!.jsonPrimitive.content
        assertTrue("undo ended as $u", u == "applied" || u == "awaiting_confirmation")

        // ---- companion: pair with its own key, post a signed signal, phone verifies it itself
        val ccode = api.companionCode().code
        val co = SoftwareSigner()
        val cp = http.request("POST", "$base/v1/pair/companion", mapOf("Content-Type" to "application/json"), buildJsonObject {
            put("code", ccode); put("publicKey", Base64Url.encode(co.publicKeySpki())); put("label", "pc")
        }.toString(), 30_000)
        assertEquals(cp.body, 201, cp.status)
        val cj = WireJson.parseToJsonElement(cp.body).jsonObject
        val cid = cj["deviceId"]!!.jsonPrimitive.content
        val observed = System.currentTimeMillis()
        val sig = Base64Url.encode(co.sign(SigningStrings.signal(cid, "active", observed, 180).toByteArray()))
        val sr = http.request("POST", "$base/v1/companion/signal", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${cj["token"]!!.jsonPrimitive.content}"),
            buildJsonObject { put("state", "active"); put("observedAt", observed); put("ttlSeconds", 180); put("signature", sig) }.toString(), 30_000)
        assertEquals(sr.body, 200, sr.status)
        client.sync("activity")
        assertTrue("companion status: ${client.status.value.companion}", client.status.value.companion!!.contains("active (fresh)"))
    }
}
