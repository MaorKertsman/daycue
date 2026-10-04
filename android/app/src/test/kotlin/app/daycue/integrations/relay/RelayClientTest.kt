package app.daycue.integrations.relay

import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Medication
import app.daycue.domain.config.Place
import app.daycue.domain.config.Routine
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.edit.ConfigOp
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.demoHabit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalTime

/** Relay client against a fake relay and the real EngineHost (JVM, unit). */
class RelayClientTest {

    private fun Harness.seed() = runBlocking {
        val cfg = host.ensureLoaded().config
        val r = host.applyOps(listOf(
            ConfigOp.UpsertHabit(demoHabit(30)),
            ConfigOp.UpsertPlace(Place("office", "Office", center = GeoPoint(32.0, 34.0))),
            ConfigOp.UpsertRoutine(Routine("morning", "Morning", steps = listOf(app.daycue.domain.config.RoutineStep("s1", "Step one")))),
        ), cfg.version, "ui")
        check(r is ApplyOutcome.Applied) { "$r" }
        r.config.version
    }

    private fun Harness.version() = runBlocking { host.ensureLoaded().config.version }
    private fun interval() = ConfigOp.SetHabitInterval("demo", 45)

    @Test fun appliesOrdinaryChangeOnceAndAcksWithValidSignature() = runBlocking {
        val h = Harness(); val v = h.seed()
        val c = h.relay.queueApply(listOf(interval()), baseVersion = v)
        val r = h.client.sync("test")
        assertEquals(SyncStatus.Ok, r.status)
        assertEquals(v + 1, h.version())
        val a = h.relay.acks.single()
        assertEquals("applied", a.outcome)
        assertTrue("ack signature must verify over the RELAY.md string", a.signatureOk)
        assertEquals((v + 1).toString(), a.result["newVersion"]!!.jsonPrimitive.content)
        assertEquals(c.payloadHash, a.body["payloadHash"]!!.jsonPrimitive.content)
        assertEquals(45, ((h.host.ensureLoaded().config.habit("demo")) as IntervalHabit).intervalMin)
        assertTrue(h.audit.rows.any { it.action == "remote.config.apply.applied" && it.actor == "mcp:Claude" })
        // the engine's own config audit row exists too
        assertTrue(h.store.audit.any { it.action == "config.apply" && it.actor == "mcp" })
    }

    @Test fun ackSigningStringIsExactlyTheDocumentedOne() {
        assertEquals("daycue.ack.v1\ncmd_1\nabc\napplied\n42\n1791114002000", SigningStrings.ack("cmd_1", "abc", "applied", 42, 1791114002000))
        assertEquals("daycue.ack.v1\ncmd_1\nabc\nrejected\n\n7", SigningStrings.ack("cmd_1", "abc", "rejected", null, 7))
        assertEquals("daycue.signal.v1\nco_1\nactive\n1791114000000\n180", SigningStrings.signal("co_1", "active", 1791114000000, 180))
    }

    @Test fun redeliveredCommandIsNeverAppliedTwice() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        // the relay "loses" the ack: it keeps redelivering
        h.relay.online = true
        h.client.sync("1")
        h.relay.finalOutcome.clear() // relay never recorded it
        h.client.sync("2")
        h.client.sync("3")
        assertEquals("applied exactly once", v + 1, h.version())
        assertEquals(1, h.log.rows.size)
    }

    @Test fun offlineQueuedThenAppliedOnceAfterReconnect() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        h.relay.online = false
        assertEquals(SyncStatus.Offline, h.client.sync("off").status)
        assertEquals("nothing applied while offline", v, h.version())
        h.relay.online = true
        h.client.sync("on"); h.client.sync("again")
        assertEquals(v + 1, h.version())
        assertEquals("applied", h.relay.finalOutcome["cmd_1"])
    }

    @Test fun ackFailureKeepsAckOwedAndResendsWithoutReapplying() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        // pull works, ack fails: emulate by going offline right after the pull via a wrapper
        val flaky = object : RelayApi by h.relay {
            var failAck = true
            override suspend fun ack(commandId: String, body: JsonObject) { if (failAck) throw RelayException.Network(java.io.IOException("x")) else h.relay.ack(commandId, body) }
        }
        val client = RelayClient({ flaky }, { h.signer }, HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { h.clock.now().toEpochMilli() })
        client.sync("1")
        assertEquals(v + 1, h.version())
        assertEquals(1, client.owedAcks())
        flaky.failAck = false
        client.sync("2")
        assertEquals(0, client.owedAcks())
        assertEquals(v + 1, h.version())
        assertEquals("applied", h.relay.finalOutcome["cmd_1"])
    }

    @Test fun expiredCommandIsRejectedAndNeverApplied() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), ttlMs = 1_000) // valid ops, but expired by the time the phone sees it
        h.clock.advance(Duration.ofSeconds(5))
        h.client.sync("t")
        assertEquals(v, h.version())
        val a = h.relay.acks.single()
        assertEquals("rejected", a.outcome)
        assertEquals("expired", a.result["errors"]!!.toString().let { Regex("\"code\":\"(\\w+)\"").find(it)!!.groupValues[1] })
    }

    @Test fun baseVersionMismatchIsAConflict() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), baseVersion = v - 1)
        h.client.sync("t")
        val a = h.relay.acks.single()
        assertEquals("rejected", a.outcome)
        assertEquals(v.toString(), a.result["conflict"]!!.jsonObject["currentVersion"]!!.jsonPrimitive.content)
        assertEquals(v, h.version())
    }

    @Test fun invalidOpsAreRejectedWithErrors() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(ConfigOp.SetHabitInterval("demo", 1)), baseVersion = v) // below the allowed range
        h.client.sync("t")
        assertEquals("rejected", h.relay.acks.single().outcome)
        assertEquals(v, h.version())
        h.relay.queue("config.apply", buildJsonObject { put("ops", "nonsense") })
        h.client.sync("t2")
        assertEquals("rejected", h.relay.acks.last().outcome)
    }

    @Test fun destructiveChangeWaitsForOwnerThenApplies() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(ConfigOp.DeleteHabit("demo")), baseVersion = v)
        h.client.sync("t")
        assertEquals("awaiting_confirmation", h.relay.acks.single().outcome)
        assertEquals("nothing changed before the owner decides", v, h.version())
        assertNotNull(h.host.ensureLoaded().config.habit("demo"))
        assertEquals(1, h.client.pending.value.size)
        assertEquals(1, h.notifier.confirmations.size)
        h.client.sync("again") // must not re-prompt or apply
        assertEquals(1, h.notifier.confirmations.size)
        assertEquals(v, h.version())

        assertEquals(RelayClient.DecisionResult.Done, h.client.decide("cmd_1", accept = true))
        assertEquals(v + 1, h.version())
        assertNull(h.host.ensureLoaded().config.habit("demo"))
        assertEquals(listOf("awaiting_confirmation", "applied"), h.relay.acks.map { it.outcome })
        assertTrue(h.relay.acks.all { it.signatureOk })
        assertTrue(h.client.pending.value.isEmpty())
        assertEquals(RelayClient.DecisionResult.NotPending, h.client.decide("cmd_1", accept = true))
    }

    @Test fun declineRejectsAndChangesNothing() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(ConfigOp.DeleteRoutine("morning")), baseVersion = v)
        h.client.sync("t")
        h.client.decide("cmd_1", accept = false)
        assertEquals(listOf("awaiting_confirmation", "rejected"), h.relay.acks.map { it.outcome })
        assertEquals(v, h.version())
        assertTrue(h.audit.rows.any { it.action == "remote.config.apply.rejected" })
    }

    @Test fun confirmationAfterConfigMovedOnIsAConflict() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(ConfigOp.DeleteHabit("demo")), baseVersion = v)
        h.client.sync("t")
        h.host.applyOps(listOf(interval()), v, "ui") // local edit while waiting
        h.client.decide("cmd_1", accept = true)
        assertEquals("rejected", h.relay.acks.last().outcome)
        assertNotNull(h.host.ensureLoaded().config.habit("demo"))
    }

    @Test fun confirmationExpiryRejectsLocally() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(ConfigOp.DeleteHabit("demo")), baseVersion = v)
        h.client.sync("t")
        h.clock.advance(Duration.ofHours(2))
        h.client.refreshPending()
        assertEquals("rejected", h.relay.acks.last().outcome)
        assertNotNull(h.host.ensureLoaded().config.habit("demo"))
    }

    @Test fun medicationNeedsScopeLocalAllowanceAndConfirmation() = runBlocking {
        val h = Harness(); val v = h.seed()
        val med = ConfigOp.UpsertMedication(Medication("m1", "Synthetic med", listOf(LocalTime.of(9, 0))))
        h.relay.queueApply(listOf(med), baseVersion = v) // no medication scope
        h.client.sync("1")
        assertEquals("rejected", h.relay.acks.last().outcome)
        assertTrue(h.relay.acks.last().result.toString().contains("medication_scope_required"))

        h.relay.queueApply(listOf(med), baseVersion = v, scopes = listOf("config:write", "medication")) // scope but owner disallows
        h.client.sync("2")
        assertTrue(h.relay.acks.last().result.toString().contains("medication_disabled"))

        h.settings.update { it.copy(allowMedication = true) }
        h.relay.queueApply(listOf(med), baseVersion = v, scopes = listOf("config:write", "medication"))
        h.client.sync("3")
        assertEquals("awaiting_confirmation", h.relay.acks.last().outcome) // sensitive: always on-phone
        assertTrue(h.host.ensureLoaded().config.medications.isEmpty())
    }

    @Test fun localPolicyDenyAndAlwaysConfirmAndKillSwitch() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.settings.update { it.copy(configPolicy = ConfigPolicy.Deny) }
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        h.client.sync("1")
        assertTrue(h.relay.acks.last().result.toString().contains("remote_changes_disabled"))

        h.settings.update { it.copy(configPolicy = ConfigPolicy.AlwaysConfirm) }
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        h.client.sync("2")
        assertEquals("awaiting_confirmation", h.relay.acks.last().outcome)

        h.settings.update { it.copy(enabled = false) }
        val pulls = h.relay.pulls
        assertEquals(SyncStatus.Disabled, h.client.sync("3").status)
        assertEquals("kill switch: no pull at all", pulls, h.relay.pulls)
        assertEquals(v, h.version())
    }

    @Test fun missingGrantScopeIsRejectedByThePhoneToo() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), baseVersion = v, scopes = listOf("config:read"))
        h.client.sync("t")
        assertTrue(h.relay.acks.single().result.toString().contains("scope_missing"))
        assertEquals(v, h.version())
    }

    @Test fun previewDoesNotChangeAnything() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queue("config.preview", buildJsonObject {
            put("ops", app.daycue.domain.config.DayCueJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ConfigOp.serializer()), listOf(ConfigOp.DeleteHabit("demo"))))
        })
        h.client.sync("t")
        val a = h.relay.acks.single()
        assertEquals("applied", a.outcome)
        assertEquals("destructive", a.result["preview"]!!.jsonObject["sensitivity"]!!.jsonPrimitive.content)
        assertEquals(v, h.version())
    }

    @Test fun undoRevertsOnlyWhileTheTargetIsStillCurrent() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        h.client.sync("1")
        val applied = v + 1
        h.relay.queue("config.undo", buildJsonObject { put("targetVersion", applied); put("targetCommandId", "cmd_1") }, baseVersion = applied)
        h.client.sync("2")
        assertEquals("applied", h.relay.acks.last().outcome)
        assertEquals(applied + 1, h.version())
        assertEquals(30, (h.host.ensureLoaded().config.habit("demo") as IntervalHabit).intervalMin)
        // now the target is stale
        h.relay.queue("config.undo", buildJsonObject { put("targetVersion", applied) }, baseVersion = applied)
        h.client.sync("3")
        assertEquals("rejected", h.relay.acks.last().outcome)
    }

    @Test fun sessionControlMapsToEnginesEventsAndRoutineStartNeedsOwner() = runBlocking {
        val h = Harness(); h.seed()
        h.relay.queue("session.control", buildJsonObject { put("action", "start"); put("target", buildJsonObject { put("kind", "work_session") }) }, scopes = listOf("sessions:control"))
        h.client.sync("1")
        assertEquals("applied", h.relay.acks.last().outcome)
        assertNotNull(h.host.ensureLoaded().state.context.session)
        h.relay.queue("session.control", buildJsonObject { put("action", "pause"); put("target", buildJsonObject { put("kind", "work_session") }) }, scopes = listOf("sessions:control"))
        h.client.sync("2")
        assertTrue(h.relay.acks.last().result.toString().contains("unsupported_action"))
        h.relay.queue("session.control", buildJsonObject { put("action", "stop"); put("target", buildJsonObject { put("kind", "work_session") }) }, scopes = listOf("sessions:control"))
        h.client.sync("3")
        assertNull(h.host.ensureLoaded().state.context.session)

        h.relay.queue("session.control", buildJsonObject { put("action", "start"); put("target", buildJsonObject { put("kind", "routine"); put("id", "morning") }) }, scopes = listOf("sessions:control"))
        h.client.sync("4")
        assertEquals("awaiting_confirmation", h.relay.acks.last().outcome)
        assertTrue(h.relay.acks.last().result.toString().contains("Android 17"))
        assertTrue(h.client.pending.value.single().needsVisibleStart)
        var started: String? = null
        h.client.decide("cmd_4", true) { started = it; true }
        assertEquals("morning", started)
        assertEquals("applied", h.relay.acks.last().outcome)

        h.relay.queue("session.control", buildJsonObject { put("action", "start"); put("target", buildJsonObject { put("kind", "routine"); put("id", "nope") }) }, scopes = listOf("sessions:control"))
        h.client.sync("5")
        assertTrue(h.relay.acks.last().result.toString().contains("unknown_routine"))
    }

    @Test fun routineStartThatCannotRunStaysPendingForRetry() = runBlocking {
        val h = Harness(); h.seed()
        h.relay.queue("session.control", buildJsonObject { put("action", "start"); put("target", buildJsonObject { put("kind", "routine"); put("id", "morning") }) }, scopes = listOf("sessions:control"))
        h.client.sync("1")
        assertEquals(RelayClient.DecisionResult.Failed, h.client.decide("cmd_1", true) { false })
        assertEquals(1, h.client.pending.value.size)
    }

    @Test fun snapshotRedactsCoordinatesAndMedication() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.host.applyOps(listOf(ConfigOp.UpsertMedication(Medication("m1", "Synthetic med", listOf(LocalTime.of(9, 0))))), v, "ui")
        h.client.sync("1")
        var snap = h.relay.snapshots.last().jsonObject
        val text = snap.toString()
        assertFalse("no coordinates", text.contains("32.0") || text.contains("\"center\""))
        assertTrue(snap["config"]!!.jsonObject["places"]!!.toString().contains("\"hasLocation\":true"))
        assertFalse("no medication by default", text.contains("Synthetic med"))
        assertNull(snap["medication"])

        // owner allows + relay wants -> published
        h.relay.wantsMedication = true
        h.settings.update { it.copy(allowMedication = true) }
        h.client.invalidatePublished(); h.client.sync("2")
        snap = h.relay.snapshots.last().jsonObject
        assertTrue(snap["medication"]!!.toString().contains("Synthetic med"))
        // relay wants but owner forbids -> not published
        h.settings.update { it.copy(allowMedication = false) }
        h.client.invalidatePublished(); h.client.sync("3")
        assertNull(h.relay.snapshots.last().jsonObject["medication"])
    }

    @Test fun recentChangesTextIsGenericForSensitiveRowsAndMedicationIsTagged() {
        assertEquals("(sensitive change, details on the phone)" to true, AuditText.forRecentChanges("sensitive", "medications: details withheld -> changed (details withheld)"))
        assertEquals("habits[demo].intervalMin: 30 -> 45" to false, AuditText.forRecentChanges("ordinary", "habits[demo].intervalMin: 30 -> 45"))
    }

    @Test fun publishesSnapshotAfterAppliedChange() = runBlocking {
        val h = Harness(); val v = h.seed()
        h.client.sync("0")
        val before = h.relay.snapshots.size
        h.relay.queueApply(listOf(interval()), baseVersion = v)
        h.client.sync("1")
        assertTrue(h.relay.snapshots.size > before)
        assertEquals(v + 1, h.relay.snapshots.last().jsonObject["version"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun interruptedCommandIsAckedFailedNotReapplied() = runBlocking {
        val h = Harness(); val v = h.seed()
        val c = h.relay.queueApply(listOf(interval()), baseVersion = v)
        h.log.put(LogEntry(c.id, 0, "Claude", v, WireJson.encodeToString(WireCommand.serializer(), c), "received", null, null, null))
        h.client.sync("t")
        assertEquals("failed", h.relay.acks.single().outcome)
        assertEquals(v, h.version())
    }

    @Test fun unauthorizedIsReportedNotRetriedAsOffline() = runBlocking {
        val h = Harness()
        val bad = object : RelayApi by h.relay { override suspend fun pull(): PullResponse = throw RelayException.Http(401, "unauthorized", "x") }
        val client = RelayClient({ bad }, { h.signer }, HostEngineGateway(h.host, h.store), h.log, h.audit, h.settings, h.notifier, { 0L })
        assertEquals(SyncStatus.Unauthorized, client.sync("t").status)
    }
}
