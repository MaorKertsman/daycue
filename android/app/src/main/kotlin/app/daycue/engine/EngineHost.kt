package app.daycue.engine

import app.daycue.domain.Clock
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Language
import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Preview
import app.daycue.domain.edit.RemoteRedaction
import app.daycue.domain.edit.ValidationError
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.Engine
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.WakePrecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/** What the UI observes: the current document and the engine state reduced against it. */
data class HostSnapshot(val config: DayCueConfig, val state: EngineState)

sealed interface ApplyOutcome {
    data class Applied(val config: DayCueConfig, val preview: Preview) : ApplyOutcome
    data class Invalid(val errors: List<ValidationError>) : ApplyOutcome
    data class Conflict(val currentVersion: Long, val baseVersion: Long) : ApplyOutcome
    data object NothingToUndo : ApplyOutcome
}

data class DispatchResult(val state: EngineState, val effects: List<Effect>, val arm: ArmResult?)

/**
 * The single serialized caller of [Engine.reduce] (ANDROID.md §3 `engine`).
 *
 * Every dispatch, under one [Mutex]: load config + state (cached in memory after the first load; this
 * class is the only writer) -> reduce -> persist state + history in one transaction -> re-arm exactly
 * one alarm from `state.nextWakeAt` (always, not only on `ScheduleWake`, so a reboot or a lost alarm is
 * repaired by any dispatch) -> write the locked-boot snapshot -> execute delivery effects in order.
 * `ApplyConfigOps` effects run through [applyOps]'s path and are followed by `ConfigChanged`.
 *
 * Config edits ([applyOps], [undo]) take the same lock, so a reduce never sees a half-applied edit.
 */
class EngineHost(
    private val store: EngineStore,
    private val clock: Clock,
    private val scheduler: WakeScheduler,
    private val sink: EffectSink,
    private val bootSnapshot: BootSnapshotWriter = NoopBootSnapshot,
    private val log: HostLog,
    private val defaultLanguage: () -> Language = { Language.en },
) {
    private val mutex = Mutex()
    private var config: DayCueConfig? = null
    private var state: EngineState? = null
    private val _snapshot = MutableStateFlow<HostSnapshot?>(null)
    val snapshot: StateFlow<HostSnapshot?> = _snapshot.asStateFlow()

    @Volatile var lastArm: ArmResult? = null
        private set

    suspend fun dispatch(event: Event): DispatchResult = mutex.withLock { dispatchLocked(event, depth = 0) }

    /** Loads (and on first run seeds) config + state without reducing. */
    suspend fun ensureLoaded(): HostSnapshot = mutex.withLock { loadLocked() }

    suspend fun applyOps(ops: List<ConfigOp>, baseVersion: Long, source: String, commandId: String? = null): ApplyOutcome =
        mutex.withLock { applyLocked(ops, baseVersion, source, commandId, action = "config.apply", pushPrevious = true, consumed = null, depth = 0) }

    /** Restores the most recent previous document as a new version (walks `config_history` back). */
    suspend fun undo(): ApplyOutcome = mutex.withLock {
        val cur = loadLocked().config
        val prev = store.latestPrevious() ?: return@withLock ApplyOutcome.NothingToUndo
        val ops = ConfigDiff.ops(cur, prev.config)
        applyLocked(ops, cur.version, "undo", null, action = "config.undo", pushPrevious = false, consumed = prev.version, depth = 0)
    }

    suspend fun canUndo(): Boolean = mutex.withLock { store.latestPrevious() != null }

    fun preview(ops: List<ConfigOp>, redactMedicationLabels: Boolean = false): Preview? =
        _snapshot.value?.config?.let { ConfigEditor.preview(it, ops, redactMedicationLabels) }

    // ---------------------------------------------------------------------------------------------

    private suspend fun loadLocked(): HostSnapshot {
        val c = config ?: (store.loadConfig() ?: Defaults.config(defaultLanguage()).also {
            store.seedConfig(it, clock.now())
            log.info("seeded first-run config (language=${it.settings.language})")
        }).also { config = it }
        val s = state ?: (store.loadState() ?: EngineState()).also { state = it }
        return HostSnapshot(c, s).also { _snapshot.value = it }
    }

    private suspend fun dispatchLocked(event: Event, depth: Int): DispatchResult {
        val snap = loadLocked()
        val reduction = try {
            Engine.reduce(snap.config, snap.state, event, clock)
        } catch (t: Throwable) {
            // A domain bug must not leave the phone without a wake: keep the old state, retry soon.
            log.warn("reduce failed for ${event::class.simpleName}; arming 15 min retry", t)
            val retry = scheduler.arm(clock.now().plus(Duration.ofMinutes(15)), WakePrecision.Inexact)
            lastArm = retry
            return DispatchResult(snap.state, emptyList(), retry)
        }
        val newState = reduction.state
        val history = reduction.effects.filterIsInstance<Effect.RecordHistory>().map { it.entry }
        val signal = (event as? Event.SignalObserved)?.signal
        store.commitReduction(newState, history, clock.zone(), signal, clock.now())
        state = newState
        _snapshot.value = HostSnapshot(snap.config, newState)

        val arm = rearm(newState)
        runCatching { bootSnapshot.write(newState, snap.config) }.onFailure { log.warn("boot snapshot write failed", it) }

        val followUps = mutableListOf<Effect.ApplyConfigOps>()
        for (e in reduction.effects) {
            when (e) {
                is Effect.ScheduleWake, Effect.CancelWake, is Effect.RecordHistory -> Unit // handled above
                is Effect.ApplyConfigOps -> followUps += e
                else -> runCatching { sink.execute(e, snap.config, newState) }.onFailure { log.warn("effect ${e::class.simpleName} failed", it) }
            }
        }
        log.info("reduce ${eventName(event)} -> ${reduction.effects.size} effects, next=${newState.nextWakeAt} ${newState.nextWakePrecision} (${newState.nextWakeReason}) armed=${arm?.used}")

        var last = DispatchResult(newState, reduction.effects, arm)
        if (depth < 3) {
            for (f in followUps) {
                val cur = config!!
                val r = applyLocked(f.ops, cur.version, "engine", null, action = "config.apply", pushPrevious = true, consumed = null, depth = depth + 1)
                if (r !is ApplyOutcome.Applied) log.warn("engine-requested ops (${f.reason}) not applied: $r")
                last = last.copy(state = state!!, arm = lastArm)
            }
        }
        return last
    }

    private fun rearm(s: EngineState): ArmResult? {
        val at = s.nextWakeAt
        val result = if (at == null) { scheduler.cancel(); null } else scheduler.arm(at, s.nextWakePrecision ?: WakePrecision.Exact)
        lastArm = result
        return result
    }

    private suspend fun applyLocked(
        ops: List<ConfigOp>, baseVersion: Long, source: String, commandId: String?,
        action: String, pushPrevious: Boolean, consumed: Long?, depth: Int,
    ): ApplyOutcome {
        val cur = loadLocked().config
        return when (val r = ConfigEditor.applyOps(cur, ops, baseVersion)) {
            is ApplyResult.Invalid -> ApplyOutcome.Invalid(r.errors)
            is ApplyResult.Conflict -> ApplyOutcome.Conflict(r.currentVersion, r.baseVersion)
            is ApplyResult.Applied -> {
                val preview = ConfigEditor.preview(cur, ops)
                val actor = when (source) { "ui", "import", "undo" -> "user"; "mcp" -> "mcp"; else -> "system" }
                store.commitConfig(
                    ConfigCommit(r.config, r.previous, source, commandId, pushPrevious, consumed,
                        // Audit rows feed `status.recentChanges`, which other grants can read: store the Strict remote text
                        // (no coordinates, no medication content), never the raw on-phone preview (security review H-1/M-8).
                        AuditRecord(actor, action, preview.sensitivity, ConfigEditor.previewForRemote(cur, ops, RemoteRedaction.Strict).summary(4000))),
                    clock.now(),
                )
                config = r.config
                _snapshot.value = HostSnapshot(r.config, state ?: EngineState())
                log.info("$action from $source: v${cur.version} -> v${r.config.version} (${ops.size} ops, ${preview.sensitivity})")
                dispatchLocked(Event.ConfigChanged, depth)
                ApplyOutcome.Applied(r.config, preview)
            }
        }
    }

    private fun eventName(e: Event): String = e::class.simpleName ?: "event"
}
