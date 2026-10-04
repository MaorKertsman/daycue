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
}

object NoopNotifier : RemoteNotifier {
    override fun confirmationNeeded(p: PendingRemote) = Unit
    override fun cancel(commandId: String) = Unit
    override fun appliedNotice(clientLabel: String, summary: String) = Unit
}
