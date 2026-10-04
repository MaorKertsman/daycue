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
    /** The relay refuses secrets below ~128 bits of entropy: generate a real one (never a committed value). */
    private val secret = Base64Url.encode(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
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
        val client = RelayClient({ flaky }, { h.signer }, HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { System.currentTimeMillis() },
            grantStore = h.grantStore, onRevokedByRelay = { h.revokedCallbacks++ })

        val minted = owner("POST", "/v1/owner/client-tokens", buildJsonObject {
            put("label", "it-client"); put("scopes", buildJsonArray { listOf("config:read", "config:write", "sessions:control").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })
        val token = minted["token"]!!.jsonPrimitive.content
        val grantId = minted["grantId"]!!.jsonPrimitive.content

        // ---- first sync publishes the redacted snapshot (version 1: the seeded habit) and learns about the new grant
        assertEquals(SyncStatus.Ok, client.sync("first").status)
        // M-7: a write grant created with the owner secret alone is NOT active; the phone is told and must approve
        assertEquals(listOf(grantId), h.notifier.grantNotices.map { it.id })
        assertTrue(client.grants.value.single().awaitsApproval)
        val denied = runCatching { clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", ops(ConfigOp.SetHabitInterval("demo", 45))) })
            put("baseVersion", 1); put("idempotencyKey", "it-key-denied"); put("waitSeconds", 0)
        }) }.exceptionOrNull()
        assertTrue("write is refused until the phone approves: $denied", denied != null && denied.message!!.contains("403"))
        // the owner approves on the phone: decision signed with the Keystore-equivalent key, verified by the real relay
        assertEquals(GrantDecisionResult.Done, client.decideGrant(grantId, GrantDecision.Approve))
        assertEquals(GrantApproval.Approved, client.grants.value.single().approval)
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

        // ---- M-5: an alarm change needs the owner now (it used to apply silently); declining leaves the alarm alone
        h.host.applyOps(listOf(ConfigOp.UpsertAlarm(app.daycue.domain.config.MorningAlarm("it-alarm", "Alarm", enabled = true, time = java.time.LocalTime.of(7, 0), days = null))), h.host.ensureLoaded().config.version, "ui")
        client.sync("republish")
        val off = clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", ops(ConfigOp.SetAlarmEnabled("it-alarm", false))) })
            put("baseVersion", h.host.ensureLoaded().config.version); put("idempotencyKey", "it-key-alarm"); put("waitSeconds", 0)
        })["commandId"]!!.jsonPrimitive.content
        client.sync("alarm")
        assertEquals("awaiting_confirmation", clientCall(token, "getCommand", buildJsonObject { put("id", off) })["state"]!!.jsonPrimitive.content)
        client.decide(off, accept = false)
        assertEquals("rejected", clientCall(token, "getCommand", buildJsonObject { put("id", off) })["state"]!!.jsonPrimitive.content)
        assertEquals(true, h.host.ensureLoaded().config.alarms.first { it.id == "it-alarm" }.enabled)

        // ---- the recomputed payload hash and the ack v2 signature hold up against the relay's own canonical JSON,
        // including quotes, backslashes, non-ASCII and an emoji in user text
        val odd = "Say \"hi\" \\ שלום 😀 é"
        val oddId = clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply")
            put("payload", buildJsonObject { put("ops", ops(ConfigOp.UpsertRoutine(app.daycue.domain.config.Routine("odd", odd, steps = listOf(app.daycue.domain.config.RoutineStep("s1", odd)))))) })
            put("baseVersion", h.host.ensureLoaded().config.version); put("idempotencyKey", "it-key-odd"); put("waitSeconds", 0)
        })["commandId"]!!.jsonPrimitive.content
        client.sync("odd")
        val oddState = clientCall(token, "getCommand", buildJsonObject { put("id", oddId) })
        assertEquals(oddState.toString(), "applied", oddState["state"]!!.jsonPrimitive.content)
        assertEquals(odd, h.host.ensureLoaded().config.routine("odd")!!.name)
        // an unknown op type is a typed rejection end to end
        val unk = clientCall(token, "submit", buildJsonObject {
            put("type", "config.apply"); put("payload", buildJsonObject { put("ops", buildJsonArray { add(buildJsonObject { put("type", "teleport"); put("id", "x") }) }) })
            put("baseVersion", h.host.ensureLoaded().config.version); put("idempotencyKey", "it-key-unk"); put("waitSeconds", 0)
        })["commandId"]!!.jsonPrimitive.content
        client.sync("unknown op")
        val unkState = clientCall(token, "getCommand", buildJsonObject { put("id", unk) })
        assertEquals("rejected", unkState["state"]!!.jsonPrimitive.content)
        assertTrue(unkState.toString(), unkState.toString().contains("unsupported_op"))

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

    /** Owner revokes the phone on the relay: the phone learns it (401), says so once and stops. */
    @Test fun ownerRevokingThePhoneIsDetectedAndUnpairRevokesTheCredential() = runBlocking {
        val h = Harness()
        h.host.ensureLoaded()
        val code = owner("POST", "/v1/owner/pair-codes")["code"]!!.jsonPrimitive.content
        val paired = HttpRelayApi.pair(base, code, h.signer.publicKeySpki(), "test phone")
        val api = HttpRelayApi(base, paired.token)
        val client = RelayClient({ api }, { h.signer }, HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { System.currentTimeMillis() },
            grantStore = h.grantStore, onRevokedByRelay = { h.revokedCallbacks++ })
        assertEquals(SyncStatus.Ok, client.sync("1").status)

        owner("DELETE", "/v1/owner/devices/${paired.deviceId}")
        assertEquals(SyncStatus.Unauthorized, client.sync("2").status)
        assertTrue(client.status.value.unpairedByRelay)
        assertEquals(1, h.notifier.revokedNotices)

        // Pair again, then unpair from the phone: DELETE /v1/phone/self revokes the credential at once.
        val code2 = owner("POST", "/v1/owner/pair-codes")["code"]!!.jsonPrimitive.content
        val paired2 = HttpRelayApi.pair(base, code2, h.signer.publicKeySpki(), "test phone")
        val api2 = HttpRelayApi(base, paired2.token)
        val client2 = RelayClient({ api2 }, { h.signer }, HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { System.currentTimeMillis() })
        assertEquals(SyncStatus.Ok, client2.sync("3").status)
        assertTrue("relay confirmed the self-revoke", client2.unpairRemote())
        val after = runCatching { api2.pull() }.exceptionOrNull()
        assertTrue("old credential must now be refused: $after", after is RelayException.Http && after.isUnauthorized)
        // the relay no longer has a paired phone: a second self-revoke is a clean failure, not a crash
        assertTrue(!client2.unpairRemote())
    }
}
