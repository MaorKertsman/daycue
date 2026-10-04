package app.daycue.engine

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.edit.Sensitivity
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.HistoryEntry
import app.daycue.domain.engine.WakePrecision
import app.daycue.domain.signal.Signal
import java.time.Instant
import java.time.ZoneId

/*
 * Pure-Kotlin ports of the engine host. Android implementations live in `data.repo`, `scheduling` and
 * `delivery`; JVM tests use in-memory fakes. No android.* imports in this package.
 */

/** A previous config document available for undo. */
data class PreviousConfig(val version: Long, val config: DayCueConfig)

/** One applied config change, written atomically by [EngineStore.commitConfig]. */
data class ConfigCommit(
    val next: DayCueConfig,
    val previous: DayCueConfig,
    /** `ui` / `mcp` / `import` / `undo` / `engine` / `debug`. */
    val source: String,
    val commandId: String?,
    /** Push [previous] into `config_history` (false for undo, which walks history back). */
    val pushPrevious: Boolean,
    /** Undo: the `config_history` row consumed by this change. */
    val consumedHistoryVersion: Long?,
    val audit: AuditRecord,
)

data class AuditRecord(val actor: String, val action: String, val sensitivity: Sensitivity, val summary: String)

interface EngineStore {
    suspend fun loadConfig(): DayCueConfig?
    suspend fun loadState(): EngineState?
    /** First run only: stores the initial document (version 0) without a history row. */
    suspend fun seedConfig(config: DayCueConfig, at: Instant)
    /** One transaction: engine state + typed next-wake columns + history rows (+ latest signal). */
    suspend fun commitReduction(state: EngineState, history: List<HistoryEntry>, zone: ZoneId, signal: Signal?, at: Instant)
    /** One transaction: current row + history row (bounded, trimmed) + audit row. */
    suspend fun commitConfig(commit: ConfigCommit, at: Instant)
    suspend fun latestPrevious(): PreviousConfig?
}

/** Result of arming the single next-wake alarm. */
data class ArmResult(val at: Instant, val requested: WakePrecision, val used: UsedApi, val degraded: Boolean)

enum class UsedApi { AlarmClock, ExactAllowWhileIdle, AllowWhileIdle, None }

/** Executes `ScheduleWake` / `CancelWake` on the platform: exactly one pending alarm at any time. */
interface WakeScheduler {
    fun arm(at: Instant, precision: WakePrecision): ArmResult
    fun cancel()
}

/** Executes delivery effects (`Deliver`, `DismissCue`, `Speak`, `StartAlarm`, `StopAlarm`). Must not block. */
interface EffectSink {
    fun execute(effect: Effect, config: DayCueConfig, state: EngineState)
}

/** Device-protected snapshot of the next alarm-clock wake for locked boot (ANDROID.md §5.4). */
interface BootSnapshotWriter {
    fun write(state: EngineState, config: DayCueConfig)
}

interface HostLog {
    fun info(msg: String)
    fun warn(msg: String, t: Throwable? = null)
}

object NoopBootSnapshot : BootSnapshotWriter { override fun write(state: EngineState, config: DayCueConfig) = Unit }
