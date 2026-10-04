package app.daycue.integrations.relay

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Sensitivity
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.PreviousConfig
import kotlinx.coroutines.flow.StateFlow

/** The engine as the relay client sees it (implemented over `EngineHost`; no android.* here). */
interface EngineGateway {
    suspend fun config(): DayCueConfig
    suspend fun state(): EngineState
    suspend fun applyOps(ops: List<ConfigOp>, baseVersion: Long, commandId: String): ApplyOutcome
    suspend fun undo(): ApplyOutcome
    suspend fun previous(): PreviousConfig?
    suspend fun dispatch(event: Event)
}

data class RemoteCredentials(val relayUrl: String, val deviceId: String, val token: String, val keyAlias: String)

interface CredentialStore {
    fun load(): RemoteCredentials?
    fun save(c: RemoteCredentials)
    fun clear()
}

/** Owner's local policy for remote access (held on the phone only; the relay cannot change it). */
enum class ConfigPolicy {
    /** Ordinary changes apply automatically; sensitive/destructive ones need on-phone confirmation. */
    Auto,
    /** Every remote change waits for on-phone confirmation. */
    AlwaysConfirm,
    /** Remote config changes are rejected. */
    Deny,
}

enum class SessionPolicy { Allow, Deny }

data class RelaySettings(
    /** Kill switch: false = no sync, no snapshot, no commands are pulled or applied. */
    val enabled: Boolean = true,
    val configPolicy: ConfigPolicy = ConfigPolicy.Auto,
    val sessionPolicy: SessionPolicy = SessionPolicy.Allow,
    /** Medication labels may be published, and medication edits accepted (still always confirmed on the phone). */
    val allowMedication: Boolean = false,
    /** Fetch and use Windows companion activity signals. */
    val useCompanionActivity: Boolean = false,
    /** Opt-in "frequent check" polling (battery cost, see docs/setup/MCP.md). */
    val frequentCheck: Boolean = false,
    val frequentCheckMinutes: Int = 5,
    /** FCM wake requested (needs a Firebase-enabled build). */
    val pushWake: Boolean = false,
    /** Last `wants.medication` the relay reported. */
    val relayWantsMedication: Boolean = false,
)

interface RelaySettingsStore {
    val flow: StateFlow<RelaySettings>
    fun update(f: (RelaySettings) -> RelaySettings)
}

data class LogEntry(
    val commandId: String,
    val receivedAtMs: Long,
    val origin: String,
    val baseVersion: Long,
    /** The wire command as received (JSON). */
    val commandJson: String,
    /** received | awaiting_confirmation | applied | rejected | failed */
    val state: String,
    /** Pending ack: `{"outcome","result","ackedAt"}` (JSON). */
    val resultJson: String?,
    val appliedVersion: Long?,
    /** Set when the relay accepted the latest ack for this command; null = an ack is still owed. */
    val ackedAtMs: Long?,
)

/** `command_log`: dedupe by command id, at-most-once apply, and the queue of acks still owed. */
interface CommandLogStore {
    suspend fun get(id: String): LogEntry?
    suspend fun put(e: LogEntry)
    suspend fun unacked(): List<LogEntry>
    suspend fun awaiting(): List<LogEntry>
}

interface RemoteAudit {
    suspend fun record(actor: String, action: String, sensitivity: Sensitivity, summary: String, versionBefore: Long?, versionAfter: Long?, commandId: String?)
}

/** What the owner is asked to confirm on the phone. Shown by the UI (facade `pendingRemote`) and as a notification. */
data class PendingRemote(
    val commandId: String,
    val clientLabel: String,
    val kind: PendingKind,
    val sensitivity: Sensitivity,
    /** Unredacted diff lines (the owner is the audience). */
    val lines: List<String>,
    val expiresAtMs: Long,
    /** Routine start: confirming must happen from a visible Activity (Android 17 audio rules). */
    val needsVisibleStart: Boolean = false,
    val routineId: String? = null,
)

enum class PendingKind { ConfigChange, Undo, RoutineStart }

interface RemoteNotifier {
    fun confirmationNeeded(p: PendingRemote)
    fun cancel(commandId: String)
    fun appliedNotice(clientLabel: String, summary: String)
    /** A new connection waits for the owner's approval on the phone (M-7). */
    fun grantAwaitingApproval(g: RemoteGrant) = Unit
    fun cancelGrant(grantId: String) = Unit
    /** The relay answered 401: this phone was unpaired / revoked on the relay side. */
    fun phoneRevoked() = Unit
}

object NoopNotifier : RemoteNotifier {
    override fun confirmationNeeded(p: PendingRemote) = Unit
    override fun cancel(commandId: String) = Unit
    override fun appliedNotice(clientLabel: String, summary: String) = Unit
}

// ---- grants (RELAY.md 4.6, security review M-7) ---------------------------------------------------------------

enum class GrantApproval { Pending, Approved, NotRequired }

/**
 * A client connection (grant) known to the relay, as shown on the phone. [label] is supplied by the client
 * and **unverified**: show it as such. Until a grant is [GrantApproval.Pending]-approved its gated scopes are
 * not in [activeScopes].
 */
data class RemoteGrant(
    val id: String,
    val label: String,
    /** `oauth` (Claude.ai, ChatGPT ...) or `token` (Claude Code / stdio). */
    val kind: String,
    val scopes: List<String>,
    val activeScopes: List<String>,
    val approval: GrantApproval,
    val createdAtMs: Long,
    val lastUsedAtMs: Long?,
) {
    val awaitsApproval: Boolean get() = approval == GrantApproval.Pending
    val holdsMedication: Boolean get() = "medication" in scopes
    val canWrite: Boolean get() = "config:write" in scopes
    /** Scopes that need the owner's explicit approval (RELAY.md 3.1): the UI should ask for a deliberate gesture. */
    val gatedScopes: List<String> get() = scopes.filter { it == "config:write" || it == "sessions:control" || it == "medication" }
}

enum class GrantDecision(val wire: String) { Approve("approve"), Decline("decline"), Revoke("revoke") }

enum class GrantDecisionResult { Done, NotPaired, NotFound, Rejected, Offline, Unauthorized, Failed }

/** What the phone remembers about grants between runs (labels are not secrets; excluded from backup). */
data class GrantsState(
    val version: Long = -1,
    val items: List<WireGrantItem> = emptyList(),
    /** Pending grant ids the owner was already notified about (no repeat notifications). */
    val notified: Set<String> = emptySet(),
    /** The relay answered 401 and the owner was told "this phone was unpaired from the relay". */
    val revokedNotified: Boolean = false,
)

interface GrantStore {
    fun load(): GrantsState
    fun save(s: GrantsState)
}

class MemoryGrantStore(var state: GrantsState = GrantsState()) : GrantStore {
    override fun load() = state
    override fun save(s: GrantsState) { state = s }
}
