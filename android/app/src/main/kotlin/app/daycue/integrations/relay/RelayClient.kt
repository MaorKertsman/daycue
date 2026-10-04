package app.daycue.integrations.relay

import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.SessionKind
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Preview
import app.daycue.domain.edit.Sensitivity
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.engine.RoutineAction
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.ConfigDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class SyncStatus { Ok, Disabled, NotPaired, Offline, Unauthorized, Failed }

data class SyncResult(
    val status: SyncStatus,
    val commandsHandled: Int = 0,
    val published: Boolean = false,
    val activityFed: Boolean = false,
    val error: String? = null,
)

/** Observable relay state for the UI. */
data class RelayStatus(
    val paired: Boolean = false,
    val lastSyncAtMs: Long? = null,
    val lastResult: SyncStatus? = null,
    val lastError: String? = null,
    val lastPublishedVersion: Long? = null,
    /** Last companion check, e.g. `active (fresh)`, `unknown (stale)`, `bad signature`. */
    val companion: String? = null,
)

/**
 * Phone side of the relay protocol (docs/architecture/RELAY.md section 4): pull, dedupe via `command_log`,
 * expiry, `baseVersion`, scope + sensitivity gating, apply through the single edit path, signed acks,
 * snapshot publishing and companion activity. No android.* here, so it runs on the JVM against a fake or the
 * real local relay.
 *
 * Invariants: a command id is applied at most once (log row written before any effect); the relay's
 * `applied` always follows a real apply; an interrupted command is acked `failed` (outcome unknown), never
 * re-applied; sensitive/destructive changes never apply without the owner's decision on the phone.
 */
class RelayClient(
    private val apiProvider: () -> RelayApi?,
    private val signerProvider: () -> DeviceSigner?,
    private val engine: EngineGateway,
    private val log: CommandLogStore,
    private val audit: RemoteAudit,
    private val settings: RelaySettingsStore,
    private val notifier: RemoteNotifier,
    private val nowMs: () -> Long,
    private val recentChanges: suspend () -> List<RecentChange> = { emptyList() },
    private val info: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<List<PendingRemote>>(emptyList())
    private val _status = MutableStateFlow(RelayStatus())
    private var lastPublished: Pair<Long, Boolean>? = null
    private var lastFedObservedAt = 0L

    /** Commands waiting for the owner's decision on the phone. */
    val pending: StateFlow<List<PendingRemote>> = _pending.asStateFlow()
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    // ------------------------------------------------------------------------------------------------
    // Sync

    suspend fun sync(reason: String = "manual"): SyncResult = mutex.withLock { syncLocked(reason) }

    private suspend fun syncLocked(reason: String): SyncResult {
        val s = settings.flow.value
        val api = apiProvider()
        if (api == null) return result(SyncResult(SyncStatus.NotPaired), paired = false)
        if (!s.enabled) return result(SyncResult(SyncStatus.Disabled))
        info("relay sync ($reason)")
        try {
            flushUnacked(api)
            var handled = 0
            val pull = api.pull()
            if (pull.wants.medication != s.relayWantsMedication) settings.update { it.copy(relayWantsMedication = pull.wants.medication) }
            for (cmd in pull.commands) { handle(api, cmd); handled++ }
            var fed = false
            if (settings.flow.value.useCompanionActivity) fed = fetchActivity(api)
            val cfg = engine.config()
            val withMed = settings.flow.value.let { it.allowMedication && it.relayWantsMedication }
            val published = if (lastPublished != cfg.version to withMed) publishLocked(api) else false
            refreshPending()
            return result(SyncResult(SyncStatus.Ok, handled, published, fed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException.Http) {
            return result(SyncResult(if (e.isUnauthorized) SyncStatus.Unauthorized else SyncStatus.Failed, error = e.message))
        } catch (e: RelayException.Network) {
            return result(SyncResult(SyncStatus.Offline, error = e.message))
        }
    }

    private fun result(r: SyncResult, paired: Boolean = true): SyncResult {
        _status.value = _status.value.copy(paired = paired, lastSyncAtMs = nowMs(), lastResult = r.status, lastError = r.error)
        return r
    }

    /** Publishes the redacted snapshot now (call after every applied change, local or remote, and on app start). */
    suspend fun publishSnapshot(): Boolean = mutex.withLock {
        val api = apiProvider() ?: return@withLock false
        if (!settings.flow.value.enabled) return@withLock false
        try { publishLocked(api) } catch (e: RelayException) { _status.value = _status.value.copy(lastError = e.message); false }
    }

    private suspend fun publishLocked(api: RelayApi, retry: Boolean = true): Boolean {
        val s = settings.flow.value
        val include = s.allowMedication && s.relayWantsMedication
        val cfg = engine.config()
        val body = SnapshotBuilder.build(cfg, engine.state(), nowMs(), include, recentChanges())
        require(body.toString().length <= SnapshotBuilder.MAX_BYTES) { "snapshot too large" }
        val resp = try { api.putSnapshot(body) } catch (e: RelayException.Http) {
            if (e.status == 409) { info("relay holds a newer snapshot; skipped"); return false }
            throw e
        }
        lastPublished = cfg.version to include
        _status.value = _status.value.copy(lastPublishedVersion = cfg.version)
        if (resp.wants.medication != s.relayWantsMedication) {
            settings.update { it.copy(relayWantsMedication = resp.wants.medication) }
            if (retry && s.allowMedication) return publishLocked(api, retry = false)
        }
        return true
    }

    /** Unpublishes nothing remotely; used when settings change so the next sync republishes. */
    fun invalidatePublished() { lastPublished = null }

    // ------------------------------------------------------------------------------------------------
    // Companion activity

    private suspend fun fetchActivity(api: RelayApi): Boolean {
        val r = api.activity()
        val checked = r.all.map { CompanionFeed.check(it, r.companions, java.time.Instant.ofEpochMilli(nowMs())) }
        val sig = CompanionFeed.combine(checked)
        _status.value = _status.value.copy(companion = describe(checked, sig))
        if (sig != null && sig.observedAt.toEpochMilli() > lastFedObservedAt) {
            lastFedObservedAt = sig.observedAt.toEpochMilli()
            engine.dispatch(Event.SignalObserved(sig))
            return true
        }
        return false
    }

    private fun describe(checked: List<CompanionFeed.Checked>, fed: app.daycue.domain.signal.CompanionActivity?): String =
        if (checked.isEmpty()) "no signal"
        else if (fed != null) "${fed.state.name.lowercase()} (fresh)"
        else checked.joinToString { c -> when (c.reason) {
            CompanionFeed.Reason.Stale -> "unknown (stale)"
            CompanionFeed.Reason.BadSignature -> "ignored: bad signature"
            CompanionFeed.Reason.UnknownCompanion -> "ignored: unknown companion"
            else -> c.reason.name
        } }

    // ------------------------------------------------------------------------------------------------
    // Commands

    private sealed interface Decision {
        class Final(val outcome: String, val result: JsonObject, val appliedVersion: Long? = null, val sensitivity: Sensitivity = Sensitivity.ordinary, val summary: String = "") : Decision
        class Await(val pending: PendingRemote, val result: JsonObject) : Decision
    }

    private fun reject(code: String, message: String, extra: JsonObject.() -> JsonObject = { this }) =
        Decision.Final("rejected", buildJsonObject {
            put("errors", buildJsonArray { add(buildJsonObject { put("code", code); put("message", message) }) })
            put("message", message)
        }.extra(), summary = "$code: $message")

    private suspend fun handle(api: RelayApi, cmd: WireCommand) {
        val existing = log.get(cmd.id)
        if (existing != null) { // at-least-once delivery: never re-apply
            when {
                existing.state == "received" -> { // crashed mid-command: the outcome is unknown
                    val d = Decision.Final("failed", buildJsonObject { put("message", "Interrupted before the phone recorded an outcome; check the config before retrying.") }, summary = "interrupted")
                    finish(api, existing, cmd, d)
                }
                existing.ackedAtMs == null -> trySendAck(api, existing)
                else -> Unit
            }
            return
        }
        val entry = LogEntry(cmd.id, nowMs(), cmd.grant.clientLabel, cmd.baseVersion ?: -1, WireJson.encodeToString(WireCommand.serializer(), cmd), "received", null, null, null)
        log.put(entry)
        val d = try {
            evaluate(cmd, confirmed = false)
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            Decision.Final("failed", buildJsonObject { put("message", "Unexpected error: ${t.javaClass.simpleName}") }, summary = "error")
        }
        when (d) {
            is Decision.Final -> finish(api, entry, cmd, d)
            is Decision.Await -> {
                val body = ackBody(cmd.id, "awaiting_confirmation", d.result, nowMs())
                val e2 = entry.copy(state = "awaiting_confirmation", resultJson = body.toString(), ackedAtMs = null)
                log.put(e2)
                audit.record(actor(cmd), "remote.${cmd.type}.awaiting_confirmation", d.pending.sensitivity, d.pending.lines.joinToString("; ").take(500), null, null, cmd.id)
                notifier.confirmationNeeded(d.pending)
                trySendAck(api, e2)
            }
        }
    }

    private fun actor(cmd: WireCommand) = "mcp:${cmd.grant.clientLabel.take(60)}"

    private suspend fun finish(api: RelayApi?, entry: LogEntry, cmd: WireCommand, d: Decision.Final) {
        val ackedAt = nowMs()
        val body = ackBody(cmd.id, d.outcome, d.result, ackedAt)
        val e2 = entry.copy(state = d.outcome, resultJson = body.toString(), appliedVersion = d.appliedVersion, ackedAtMs = null)
        log.put(e2)
        audit.record(actor(cmd), "remote.${cmd.type}.${d.outcome}", d.sensitivity, d.summary.take(500), null, d.appliedVersion, cmd.id)
        notifier.cancel(cmd.id)
        if (d.outcome == "applied" && cmd.type != "config.preview") notifier.appliedNotice(cmd.grant.clientLabel, d.summary)
        if (api != null) trySendAck(api, e2)
    }

    private fun ackBody(id: String, outcome: String, result: JsonObject, ackedAt: Long) = buildJsonObject {
        put("outcome", outcome); put("result", result); put("ackedAt", ackedAt)
    }

    private suspend fun flushUnacked(api: RelayApi) {
        for (e in log.unacked()) trySendAck(api, e)
    }

    /** Signs and sends the owed ack. Network failure leaves it owed (retried at the next sync). */
    private suspend fun trySendAck(api: RelayApi, e: LogEntry) {
        val signer = signerProvider() ?: return
        val stored = e.resultJson?.let { WireJson.parseToJsonElement(it).jsonObject } ?: return
        val cmd = WireJson.decodeFromString(WireCommand.serializer(), e.commandJson)
        val outcome = stored["outcome"]!!.jsonPrimitive.content
        val result = stored["result"]?.jsonObject ?: JsonObject(emptyMap())
        val ackedAt = stored["ackedAt"]!!.jsonPrimitive.long()
        val newVersion = result["newVersion"]?.jsonPrimitive?.longOrNull
        val sig = signer.sign(SigningStrings.ack(cmd.id, cmd.payloadHash, outcome, newVersion, ackedAt).toByteArray(Charsets.UTF_8))
        val body = buildJsonObject {
            put("outcome", outcome); put("result", result); put("ackedAt", ackedAt)
            put("payloadHash", cmd.payloadHash); put("signature", Base64Url.encode(sig))
        }
        try {
            api.ack(cmd.id, body)
            log.put(e.copy(ackedAtMs = nowMs()))
        } catch (x: RelayException.Http) {
            when {
                x.status == 409 || x.status == 404 -> { info("ack for ${cmd.id} not needed: ${x.code}"); log.put(e.copy(ackedAtMs = nowMs())) }
                x.isUnauthorized -> throw x
                else -> { info("ack for ${cmd.id} rejected: ${x.code}"); _status.value = _status.value.copy(lastError = "ack ${x.status} ${x.code}") }
            }
        } catch (x: RelayException.Network) { /* stays owed */ }
    }

    private fun JsonPrimitive.long(): Long = longOrNull ?: error("not a number")

    // ---- evaluation ------------------------------------------------------------------------------

    private suspend fun evaluate(cmd: WireCommand, confirmed: Boolean): Decision {
        val s = settings.flow.value
        if (nowMs() >= cmd.expiresAt) return reject("expired", "The command expired before the phone handled it; nothing was changed.")
        val needs = when (cmd.type) { "session.control" -> "sessions:control"; "config.preview", "config.apply", "config.undo" -> "config:write"; else -> null }
            ?: return reject("unknown_command", "Unsupported command type ${cmd.type}")
        if (needs !in cmd.grant.scopes) return reject("scope_missing", "The grant does not include $needs")
        return when (cmd.type) {
            "config.preview" -> preview(cmd)
            "config.apply" -> if (s.configPolicy == ConfigPolicy.Deny) reject("remote_changes_disabled", "The owner disabled remote config changes on the phone.") else apply(cmd, confirmed)
            "config.undo" -> if (s.configPolicy == ConfigPolicy.Deny) reject("remote_changes_disabled", "The owner disabled remote config changes on the phone.") else undo(cmd, confirmed)
            else -> if (s.sessionPolicy == SessionPolicy.Deny) reject("remote_sessions_disabled", "The owner disabled remote session control on the phone.") else session(cmd, confirmed)
        }
    }

    private fun parseOps(cmd: WireCommand): List<ConfigOp>? = try {
        val el = cmd.payload["ops"] ?: return null
        DayCueJson.decodeFromJsonElement(ListSerializer(ConfigOp.serializer()), el).takeIf { it.isNotEmpty() && it.size <= 50 }
    } catch (e: Exception) { null }

    private fun touchesMedication(ops: List<ConfigOp>, p: Preview) =
        ops.any { it is ConfigOp.UpsertMedication || it is ConfigOp.DeleteMedication || it is ConfigOp.SetMedicationTimes || it is ConfigOp.SetMedicationTravelPolicy || it is ConfigOp.SetMedicationEndDate } ||
            p.lines.any { it.path.startsWith("medications") }

    private fun errorsJson(p: Preview) = buildJsonArray {
        p.errors.forEach { e -> add(buildJsonObject { put("path", e.path); put("code", e.code); put("message", e.message) }) }
    }

    private suspend fun preview(cmd: WireCommand): Decision {
        val ops = parseOps(cmd) ?: return reject("bad_ops", "payload.ops must be 1-50 valid ConfigOp objects")
        val cfg = engine.config()
        val medScope = "medication" in cmd.grant.scopes
        val p = ConfigEditor.preview(cfg, ops, redactMedicationLabels = !medScope)
        val diff = buildJsonArray {
            p.lines.filter { Redaction.summary(listOf(it.text)) != "(details withheld)" }.forEach { l ->
                add(buildJsonObject { put("path", l.path); put("before", l.before); put("after", l.after) })
            }
        }
        val res = buildJsonObject {
            put("sensitivity", p.sensitivity.name)
            put("preview", buildJsonObject { put("diff", diff); put("sensitivity", p.sensitivity.name) })
            if (p.errors.isNotEmpty()) put("errors", errorsJson(p))
            put("message", if (p.valid) "Preview only; nothing was changed." else "The ops are not valid; nothing was changed.")
        }
        return Decision.Final("applied", res, sensitivity = p.sensitivity, summary = "preview of ${ops.size} ops")
    }

    private suspend fun apply(cmd: WireCommand, confirmed: Boolean): Decision {
        val ops = parseOps(cmd) ?: return reject("bad_ops", "payload.ops must be 1-50 valid ConfigOp objects")
        val cfg = engine.config()
        val base = cmd.baseVersion ?: cfg.version
        if (base != cfg.version) return conflict(cfg.version, base)
        val medScope = "medication" in cmd.grant.scopes
        val full = ConfigEditor.preview(cfg, ops)
        if (!full.valid) return Decision.Final("rejected", buildJsonObject { put("errors", errorsJson(full)); put("message", "Validation failed") }, summary = "invalid ops")
        if (touchesMedication(ops, full)) {
            if (!medScope) return reject("medication_scope_required", "Changing medication needs the medication scope, which this grant does not have.")
            if (!settings.flow.value.allowMedication) return reject("medication_disabled", "The owner does not allow remote medication changes on the phone.")
        }
        val needsConfirm = full.sensitivity != Sensitivity.ordinary || settings.flow.value.configPolicy == ConfigPolicy.AlwaysConfirm
        val redacted = ConfigEditor.preview(cfg, ops, redactMedicationLabels = !medScope)
        val summary = Redaction.summary(redacted.lines.map { it.text })
        if (needsConfirm && !confirmed) {
            val p = PendingRemote(cmd.id, cmd.grant.clientLabel, PendingKind.ConfigChange, full.sensitivity, full.lines.map { it.text }, cmd.expiresAt)
            return Decision.Await(p, buildJsonObject {
                put("sensitivity", full.sensitivity.name); put("summary", summary)
                put("message", "Waiting for the owner to confirm this ${full.sensitivity.name} change on the phone.")
            })
        }
        return when (val r = engine.applyOps(ops, base, cmd.id)) {
            is ApplyOutcome.Applied -> Decision.Final("applied", buildJsonObject {
                put("newVersion", r.config.version); put("sensitivity", full.sensitivity.name); put("summary", summary)
            }, appliedVersion = r.config.version, sensitivity = full.sensitivity, summary = summary)
            is ApplyOutcome.Invalid -> Decision.Final("rejected", buildJsonObject {
                put("errors", buildJsonArray { r.errors.forEach { e -> add(buildJsonObject { put("path", e.path); put("code", e.code); put("message", e.message) }) } })
            }, summary = "invalid")
            is ApplyOutcome.Conflict -> conflict(r.currentVersion, r.baseVersion)
            ApplyOutcome.NothingToUndo -> reject("internal", "unexpected")
        }
    }

    private fun conflict(current: Long, base: Long) = Decision.Final("rejected", buildJsonObject {
        put("conflict", buildJsonObject { put("currentVersion", current) })
        put("message", "The config is at version $current, not $base. Read it again and retry.")
    }, summary = "conflict: base $base, current $current")

    private suspend fun undo(cmd: WireCommand, confirmed: Boolean): Decision {
        val target = cmd.payload["targetVersion"]?.jsonPrimitive?.longOrNull ?: return reject("bad_payload", "targetVersion required")
        val cfg = engine.config()
        if (cfg.version != target) return conflict(cfg.version, target)
        val targetCmd = cmd.payload["targetCommandId"]?.jsonPrimitive?.contentOrNull
        if (targetCmd != null && log.get(targetCmd)?.appliedVersion != target) return reject("undo_not_possible", "That command did not produce version $target on this phone.")
        val prev = engine.previous() ?: return reject("nothing_to_undo", "There is nothing to undo.")
        if (prev.version != target - 1) return reject("undo_not_possible", "The previous document is not the one before version $target.")
        val ops = ConfigDiff.ops(cfg, prev.config)
        val full = ConfigEditor.preview(cfg, ops)
        val medScope = "medication" in cmd.grant.scopes
        if (touchesMedication(ops, full)) {
            if (!medScope) return reject("medication_scope_required", "Undoing this touches medication, which needs the medication scope.")
            if (!settings.flow.value.allowMedication) return reject("medication_disabled", "The owner does not allow remote medication changes on the phone.")
        }
        val needsConfirm = full.sensitivity != Sensitivity.ordinary || settings.flow.value.configPolicy == ConfigPolicy.AlwaysConfirm
        val summary = "Undo of version $target: " + Redaction.summary(ConfigEditor.preview(cfg, ops, redactMedicationLabels = !medScope).lines.map { it.text })
        if (needsConfirm && !confirmed) {
            val p = PendingRemote(cmd.id, cmd.grant.clientLabel, PendingKind.Undo, full.sensitivity, full.lines.map { it.text }, cmd.expiresAt)
            return Decision.Await(p, buildJsonObject { put("sensitivity", full.sensitivity.name); put("summary", summary); put("message", "Waiting for the owner to confirm this undo on the phone.") })
        }
        return when (val r = engine.undo()) {
            is ApplyOutcome.Applied -> Decision.Final("applied", buildJsonObject {
                put("newVersion", r.config.version); put("sensitivity", full.sensitivity.name); put("summary", summary)
            }, appliedVersion = r.config.version, sensitivity = full.sensitivity, summary = summary)
            is ApplyOutcome.Conflict -> conflict(r.currentVersion, r.baseVersion)
            is ApplyOutcome.Invalid -> Decision.Final("rejected", buildJsonObject { put("message", "Undo is no longer valid") }, summary = "invalid")
            ApplyOutcome.NothingToUndo -> reject("nothing_to_undo", "There is nothing to undo.")
        }
    }

    private suspend fun session(cmd: WireCommand, confirmed: Boolean): Decision {
        val action = cmd.payload["action"]?.jsonPrimitive?.contentOrNull ?: return reject("bad_payload", "action required")
        val target = cmd.payload["target"]?.jsonObject ?: return reject("bad_payload", "target required")
        val kind = target["kind"]?.jsonPrimitive?.contentOrNull
        val id = target["id"]?.jsonPrimitive?.contentOrNull
        fun ok(msg: String) = Decision.Final("applied", buildJsonObject { put("message", msg); put("summary", msg) }, summary = msg)
        when (kind) {
            "work_session" -> return when (action) {
                "start" -> {
                    val k = if (id.equals("studying", true) || id.equals("study", true)) SessionKind.Studying else SessionKind.Working
                    engine.dispatch(Event.StartSession(k, OverrideDuration.UntilChanged)); ok("Started a manual ${k.name.lowercase()} session")
                }
                "stop" -> { engine.dispatch(Event.EndSession); ok("Ended the session") }
                else -> reject("unsupported_action", "Sessions pause and resume by themselves from computer activity; only start and stop can be sent remotely.")
            }
            "routine" -> {
                val rid = id ?: return reject("bad_payload", "target.id (routine id) required")
                val cfg = engine.config()
                if (cfg.routine(rid) == null) return reject("unknown_routine", "No routine with that id")
                return when (action) {
                    "start" -> {
                        if (!confirmed) {
                            val p = PendingRemote(cmd.id, cmd.grant.clientLabel, PendingKind.RoutineStart, Sensitivity.ordinary, listOf("Start routine '${cfg.routine(rid)!!.name}'"), cmd.expiresAt, needsVisibleStart = true, routineId = rid)
                            Decision.Await(p, buildJsonObject {
                                put("message", "Routine audio can only start from a user action on Android 17, so the owner must tap Start on the phone.")
                                put("summary", "Start routine: needs on-phone confirmation")
                            })
                        } else ok("Routine started after on-phone confirmation")
                    }
                    "pause" -> { engine.dispatch(Event.RoutineControl(RoutineAction.Pause)); ok("Routine paused") }
                    "resume" -> { engine.dispatch(Event.RoutineControl(RoutineAction.Resume)); ok("Routine resumed") }
                    "stop" -> { engine.dispatch(Event.RoutineControl(RoutineAction.Cancel)); ok("Routine stopped") }
                    else -> reject("bad_payload", "unknown action $action")
                }
            }
            else -> return reject("bad_payload", "target.kind must be routine or work_session")
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Owner decision on the phone

    enum class DecisionResult { Done, NotPending, Failed }

    /**
     * The owner decided. [routineStarter] is invoked for a routine start and must start it from a visible
     * Activity (returns false when that was not possible; the command is then rejected honestly).
     */
    suspend fun decide(commandId: String, accept: Boolean, routineStarter: suspend (String) -> Boolean = { false }): DecisionResult = mutex.withLock {
        val e = log.get(commandId)
        if (e == null || e.state != "awaiting_confirmation") return@withLock DecisionResult.NotPending
        val cmd = WireJson.decodeFromString(WireCommand.serializer(), e.commandJson)
        val d: Decision.Final = if (!accept) {
            Decision.Final("rejected", buildJsonObject {
                put("errors", buildJsonArray { add(buildJsonObject { put("code", "declined_by_owner"); put("message", "The owner declined on the phone.") }) })
                put("message", "The owner declined on the phone.")
            }, summary = "declined by owner")
        } else {
            val r = try { evaluate(cmd, confirmed = true) } catch (t: CancellationException) { throw t } catch (t: Throwable) {
                Decision.Final("failed", buildJsonObject { put("message", "Unexpected error: ${t.javaClass.simpleName}") }, summary = "error")
            }
            when {
                r is Decision.Final && r.outcome == "applied" && cmd.type == "session.control" && cmd.payload["target"]?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull == "routine" -> {
                    val rid = cmd.payload["target"]!!.jsonObject["id"]!!.jsonPrimitive.content
                    if (routineStarter(rid)) r else reject("start_needs_visible_screen", "The routine could not be started from the current screen; open DayCue and confirm again.")
                }
                r is Decision.Final -> r
                else -> Decision.Final("failed", buildJsonObject { put("message", "Still needs confirmation") }, summary = "unexpected")
            }
        }
        // A start that could not run stays pending so the owner can retry from a visible screen.
        if (d.outcome == "rejected" && accept && d.summary.startsWith("start_needs_visible_screen")) return@withLock DecisionResult.Failed
        finish(apiProvider(), e, cmd, d)
        refreshPending()
        DecisionResult.Done
    }

    /** Rebuilds [pending] from `command_log` (survives process death). Expired ones are rejected locally. */
    suspend fun refreshPending() {
        val list = mutableListOf<PendingRemote>()
        for (e in log.awaiting()) {
            val cmd = WireJson.decodeFromString(WireCommand.serializer(), e.commandJson)
            if (nowMs() >= cmd.expiresAt) {
                finish(apiProvider(), e, cmd, reject("expired", "The owner did not confirm in time; nothing was changed."))
                continue
            }
            buildPending(cmd)?.let { list += it }
        }
        _pending.value = list
    }

    private suspend fun buildPending(cmd: WireCommand): PendingRemote? {
        val cfg = engine.config()
        return when (cmd.type) {
            "config.apply" -> parseOps(cmd)?.let { ops -> ConfigEditor.preview(cfg, ops).let { p -> PendingRemote(cmd.id, cmd.grant.clientLabel, PendingKind.ConfigChange, p.sensitivity, p.lines.map { it.text }, cmd.expiresAt) } }
            "config.undo" -> PendingRemote(cmd.id, cmd.grant.clientLabel, PendingKind.Undo, Sensitivity.sensitive, listOf("Undo of version ${cmd.payload["targetVersion"]?.jsonPrimitive?.contentOrNull}"), cmd.expiresAt)
            "session.control" -> {
                val rid = cmd.payload["target"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                PendingRemote(cmd.id, cmd.grant.clientLabel, PendingKind.RoutineStart, Sensitivity.ordinary, listOf("Start routine '${rid?.let { cfg.routine(it)?.name } ?: rid}'"), cmd.expiresAt, needsVisibleStart = true, routineId = rid)
            }
            else -> null
        }
    }

    /** Count of acks still owed (UI/diagnostics). */
    suspend fun owedAcks(): Int = log.unacked().size


}
