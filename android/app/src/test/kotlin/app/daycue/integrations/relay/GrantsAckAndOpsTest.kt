package app.daycue.integrations.relay

import app.daycue.domain.edit.ConfigOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ack v2, forward-compatible op decoding, payload hash, grants (RELAY.md 4.6) and unpair, against the fake relay. */
class GrantsAckAndOpsTest {
    private fun Harness.seed(): Long = runBlocking {
        val cfg = host.ensureLoaded().config
        (host.applyOps(listOf(ConfigOp.UpsertHabit(app.daycue.engine.demoHabit(30))), cfg.version, "ui") as app.daycue.engine.ApplyOutcome.Applied).config.version
    }

    private fun payload(vararg ops: JsonObject) = buildJsonObject { put("ops", JsonArray(ops.toList())) }

    // ---- canonical JSON / ack v2 -------------------------------------------------------------------------

    @Test fun canonicalJsonMatchesTheRelaysEncoding() {
        // Vectors hand-derived from mcp/src/util.ts canonicalJson (sorted keys, JSON.stringify strings, no spaces).
        val el = buildJsonObject {
            put("b", 1); put("a", buildJsonArray { add(JsonPrimitive(true)); add(kotlinx.serialization.json.JsonNull); add(JsonPrimitive("x\n\"\\\u0001éא")) })
            put("n", buildJsonObject { put("z", "") ; put("y", 1791183600000L) })
        }
        assertEquals("""{"a":[true,null,"x\n\"\\\u0001é${'א'}"],"b":1,"n":{"y":1791183600000,"z":""}}""", CanonicalJson.encode(el))
        assertEquals("null", CanonicalJson.encode(null))
        assertEquals("{}", CanonicalJson.encode(JsonObject(emptyMap())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CanonicalJson.sha256Hex(""))
    }

    @Test fun acksAreSignedV2AndTheSignatureCoversTheResult() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(ConfigOp.SetHabitInterval("demo", 45)), baseVersion = v)
        h.client.sync("t")
        val a = h.relay.acks.single()
        assertTrue("the fake relay verified the v2 signature", a.signatureOk)
        assertTrue(a.v2)
        // Independent check: the same signature must NOT verify once the reported summary is altered.
        val cmd = h.relay.commands.single()
        val tampered = JsonObject(a.result + ("summary" to JsonPrimitive("something else")))
        val msg = SigningStrings.ackV2(cmd.id, cmd.payloadHash, "applied", v + 1, a.ackedAt, CanonicalJson.sha256Hex(CanonicalJson.encode(tampered)))
        assertFalse(EcdsaVerify.verify(h.signer.publicKeySpki(), msg, Base64Url.decode(a.body["signature"]!!.jsonPrimitive.content)))
        assertEquals("daycue.ack.v2\ncmd_1\nabc\napplied\n42\n7\nff", SigningStrings.ackV2("cmd_1", "abc", "applied", 42, 7, "ff"))
    }

    @Test fun payloadHashIsRecomputedAndAMismatchIsRejectedNotApplied() = runBlocking {
        val h = Harness(); val v = h.seed()
        val good = h.relay.queueApply(listOf(ConfigOp.SetHabitInterval("demo", 45)), baseVersion = v)
        assertTrue(RelayClient.payloadHashMatches(good))
        // a relay that swaps the content after hashing
        val forged = good.copy(id = "cmd_forged", payload = payload(buildJsonObject { put("type", "deleteHabit"); put("id", "demo") }))
        h.relay.commands += forged
        h.client.sync("t")
        val a = h.relay.acks.first { it.id == "cmd_forged" }
        assertEquals("rejected", a.outcome)
        assertTrue(a.result.toString().contains("payload_hash_mismatch"))
        assertNotNull(h.host.ensureLoaded().config.habit("demo"))
    }

    // ---- ConfigOpCodec.decodeList -------------------------------------------------------------------------

    @Test fun unknownOpsAreRejectedWithTypedCodesAndNothingIsApplied() = runBlocking {
        val h = Harness(); val v = h.seed()
        val good = buildJsonObject { put("type", "setHabitInterval"); put("id", "demo"); put("minutes", 45) }
        // a valid op next to an unknown one: the known subset must NOT be applied
        h.relay.queue("config.apply", payload(good, buildJsonObject { put("type", "futureOp"); put("x", 1) }), baseVersion = v)
        h.client.sync("1")
        val a = h.relay.acks.last()
        assertEquals("rejected", a.outcome)
        assertEquals("unsupported_op", a.result["errors"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("ops[1]", a.result["errors"]!!.jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(v, h.host.ensureLoaded().config.version)

        // an unknown nested polymorphic subtype
        h.relay.queue("config.apply", payload(buildJsonObject { put("type", "upsertHabit"); put("habit", buildJsonObject { put("type", "teleportHabit"); put("id", "x") }) }), baseVersion = v)
        h.client.sync("2")
        assertTrue(h.relay.acks.last().result.toString().contains("unsupported_type"))

        // malformed, not an array, empty, too many
        h.relay.queue("config.apply", payload(buildJsonObject { put("type", "setHabitInterval"); put("id", "demo") }), baseVersion = v)
        h.relay.queue("config.apply", buildJsonObject { put("ops", "nonsense") }, baseVersion = v)
        h.relay.queue("config.apply", payload(), baseVersion = v)
        h.relay.queue("config.apply", JsonObject(mapOf("ops" to JsonArray(List(51) { good }))), baseVersion = v)
        h.client.sync("3")
        val codes = h.relay.acks.takeLast(4).map { Regex("\"code\":\"(\\w+)\"").find(it.result.toString())!!.groupValues[1] }
        assertEquals(listOf("bad_op", "bad_ops", "bad_ops", "too_many"), codes)
        assertEquals(v, h.host.ensureLoaded().config.version)
        // preview takes the same path
        h.relay.queue("config.preview", payload(buildJsonObject { put("type", "futureOp") }))
        h.client.sync("4")
        assertEquals("rejected", h.relay.acks.last().outcome)
    }

    // ---- grants -----------------------------------------------------------------------------------------

    private fun grant(id: String, label: String = "Claude", scopes: List<String> = listOf("config:read", "config:write"), approval: String = "pending") =
        WireGrantItem(id, label, "oauth", scopes, if (approval == "pending") scopes.filter { it == "config:read" } else scopes, approval, 1_000, null)

    @Test fun aNewPendingGrantIsPersistedNotifiedOnceAndSurvivesARestart() = runBlocking {
        val h = Harness()
        h.relay.grantsList = GrantsList(2, listOf(grant("gr_1"), grant("gr_ro", scopes = listOf("config:read"), approval = "not_required")))
        h.client.sync("1")
        assertEquals(1, h.notifier.grantNotices.size)
        assertEquals("gr_1", h.notifier.grantNotices.single().id)
        assertTrue(h.client.grants.value.first { it.id == "gr_1" }.awaitsApproval)
        assertEquals(2L, h.grantStore.load().version)
        h.client.sync("2") // unchanged list: no new notice
        assertEquals(1, h.notifier.grantNotices.size)
        assertTrue(h.audit.rows.any { it.action == "grant.awaiting_approval" })
        // restart: a new client over the same store restores the list and does not notify again
        val again = RelayClient({ h.relay }, { h.signer }, app.daycue.integrations.relay.HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { h.clock.now().toEpochMilli() }, grantStore = h.grantStore)
        assertEquals(2, again.grants.value.size)
        again.sync("3")
        assertEquals(1, h.notifier.grantNotices.size)
        // a second pending grant notifies once more
        h.relay.grantsList = GrantsList(3, h.relay.grantsList.items + grant("gr_2", "ChatGPT"))
        again.sync("4")
        assertEquals(listOf("gr_1", "gr_2"), h.notifier.grantNotices.map { it.id })
    }

    @Test fun approvingSignsWithThePhoneKeyAndActivatesTheGrant() = runBlocking {
        val h = Harness()
        h.relay.grantsList = GrantsList(2, listOf(grant("gr_1", scopes = listOf("config:read", "config:write", "medication"))))
        h.client.sync("1")
        assertEquals(GrantDecisionResult.Done, h.client.decideGrant("gr_1", GrantDecision.Approve, setOf("config:write", "config:read")))
        val call = h.relay.grantCalls.single()
        assertTrue("signature verifies with the phone key over daycue.grant.v1", call.signatureOk)
        assertEquals("approve", call.decision)
        assertEquals(listOf("config:read", "config:write"), call.approvedScopes.sorted())
        assertEquals(GrantApproval.Approved, h.client.grants.value.single().approval)
        assertTrue("the pending notification was cancelled", "gr_1" in h.notifier.cancelledGrants)
        assertEquals("daycue.grant.v1\ngr_1\napprove\nconfig:read config:write\n1791183600000", SigningStrings.grant("gr_1", "approve", listOf("config:write", "config:read"), 1791183600000))
        assertEquals("daycue.grant.v1\ngr_1\nrevoke\n\n5", SigningStrings.grant("gr_1", "revoke", emptyList(), 5))
    }

    @Test fun declineAndRevokeRemoveTheGrantAndAnUnknownOneIsReported() = runBlocking {
        val h = Harness()
        h.relay.grantsList = GrantsList(2, listOf(grant("gr_1"), grant("gr_2", approval = "approved")))
        h.client.sync("1")
        assertEquals(GrantDecisionResult.Done, h.client.decideGrant("gr_1", GrantDecision.Decline))
        assertEquals(GrantDecisionResult.Done, h.client.decideGrant("gr_2", GrantDecision.Revoke))
        assertEquals(listOf("decline", "revoke"), h.relay.grantCalls.map { it.decision })
        assertTrue(h.relay.grantCalls.all { it.signatureOk })
        assertTrue(h.client.grants.value.isEmpty())
        assertEquals(GrantDecisionResult.NotFound, h.client.decideGrant("gr_gone", GrantDecision.Approve))
        h.relay.online = false
        assertEquals(GrantDecisionResult.Offline, h.client.decideGrant("gr_1", GrantDecision.Approve))
    }

    @Test fun grantListIsAlsoRefreshedFromTheSnapshotResponseVersion() = runBlocking {
        val h = Harness()
        h.client.sync("0") // publishes; stored grants version -1 -> relay version 1 differs -> fetched
        assertEquals(1L, h.grantStore.load().version)
        h.relay.grantsList = GrantsList(2, listOf(grant("gr_new")))
        h.client.invalidatePublished()
        assertTrue(h.client.publishSnapshot())
        assertEquals(listOf("gr_new"), h.notifier.grantNotices.map { it.id })
    }

    @Test fun relayRevokingThePhoneIsReportedOnceAndStopsBackgroundWork() = runBlocking {
        val h = Harness()
        h.client.sync("ok")
        assertFalse(h.client.status.value.unpairedByRelay)
        h.relay.unauthorized = true
        assertEquals(SyncStatus.Unauthorized, h.client.sync("1").status)
        assertTrue(h.client.status.value.unpairedByRelay)
        h.client.sync("2")
        assertEquals("one notification, not one per sync", 1, h.notifier.revokedNotices)
        assertEquals(2, h.revokedCallbacks) // the service cancels triggers each time (idempotent)
        // pairing again clears the state
        h.client.onPaired(); h.relay.unauthorized = false
        h.client.sync("3")
        assertFalse(h.client.status.value.unpairedByRelay)
    }

    @Test fun unpairRevokesOnTheRelayBestEffortAndForgetsGrants() = runBlocking {
        val h = Harness()
        h.relay.grantsList = GrantsList(2, listOf(grant("gr_1")))
        h.client.sync("1")
        assertTrue(h.client.unpairRemote())
        assertTrue(h.relay.selfRevoked)
        assertTrue(h.client.grants.value.isEmpty())
        assertEquals(GrantsState(), h.grantStore.load())
        // offline: local unpairing still completes
        val h2 = Harness(); h2.relay.online = false
        assertFalse(h2.client.unpairRemote())
        assertFalse(h2.relay.selfRevoked)
    }

    @Test fun mappingKeepsLabelsBoundedAndFlagsGatedScopes() {
        val g = RemoteGrant("g", "x", "token", listOf("config:read", "medication", "sessions:control"), listOf("config:read"), GrantApproval.Pending, 0, null)
        assertTrue(g.awaitsApproval); assertTrue(g.holdsMedication)
        assertEquals(listOf("medication", "sessions:control"), g.gatedScopes)
        assertNull(g.lastUsedAtMs)
    }
}
