package app.daycue.engine

import app.daycue.domain.Clock
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.HistoryEntry
import app.daycue.domain.engine.WakePrecision
import app.daycue.domain.signal.Signal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TestClock(var instant: Instant, var zoneId: ZoneId = ZoneId.of("Asia/Jerusalem"), var elapsed: Duration = Duration.ofHours(3)) : Clock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    override fun elapsedRealtime(): Duration = elapsed
    fun advance(d: Duration) { instant = instant.plus(d); elapsed = elapsed.plus(d) }
}

/** In-memory [EngineStore] with the same transactional contract as RoomEngineStore (whole-commit swaps). */
class MemoryStore : EngineStore {
    var config: DayCueConfig? = null
    var state: EngineState? = null
    val history = mutableListOf<HistoryEntry>()
    val configHistory = linkedMapOf<Long, DayCueConfig>()
    val audit = mutableListOf<AuditRecord>()
    val signals = mutableListOf<Signal>()
    var commits = 0

    override suspend fun loadConfig() = config
    override suspend fun loadState() = state
    override suspend fun seedConfig(config: DayCueConfig, at: Instant) { if (this.config == null) this.config = config }
    override suspend fun commitReduction(state: EngineState, history: List<HistoryEntry>, zone: ZoneId, signal: Signal?, at: Instant) {
        this.state = state; this.history += history; signal?.let { signals += it }; commits++
    }
    override suspend fun commitConfig(commit: ConfigCommit, at: Instant) {
        if (commit.pushPrevious) configHistory[commit.previous.version] = commit.previous
        commit.consumedHistoryVersion?.let { configHistory.remove(it) }
        config = commit.next
        audit += commit.audit
    }
    override suspend fun latestPrevious() = configHistory.entries.maxByOrNull { it.key }?.let { PreviousConfig(it.key, it.value) }
}

class FakeScheduler(var exactAllowed: Boolean = true) : WakeScheduler {
    var armed: Pair<Instant, WakePrecision>? = null
    var used: UsedApi = UsedApi.None
    val calls = mutableListOf<String>()
    override fun arm(at: Instant, precision: WakePrecision): ArmResult {
        armed = at to precision
        used = when {
            !exactAllowed -> UsedApi.AllowWhileIdle
            precision == WakePrecision.AlarmClock -> UsedApi.AlarmClock
            precision == WakePrecision.Exact -> UsedApi.ExactAllowWhileIdle
            else -> UsedApi.AllowWhileIdle
        }
        calls += "arm $at $precision"
        return ArmResult(at, precision, used, degraded = precision != WakePrecision.Inexact && used == UsedApi.AllowWhileIdle)
    }
    override fun cancel() { armed = null; calls += "cancel" }
}

/** Records effects together with the persisted state at execution time (persist-before-execute check). */
class RecordingSink(private val store: MemoryStore) : EffectSink {
    val effects = mutableListOf<Effect>()
    val persistedAtExecute = mutableListOf<EngineState?>()
    override fun execute(effect: Effect, config: DayCueConfig, state: EngineState) {
        effects += effect
        persistedAtExecute += store.state
    }
    fun delivered() = effects.filterIsInstance<Effect.Deliver>().map { it.cue }
    fun clear() { effects.clear(); persistedAtExecute.clear() }
}

object SilentLog : HostLog {
    val lines = mutableListOf<String>()
    override fun info(msg: String) { lines += msg }
    override fun warn(msg: String, t: Throwable?) { lines += "WARN $msg ${t?.message}" }
}

fun demoHabit(intervalMin: Int = 2) = IntervalHabit(
    id = "demo", kind = IntervalKind.Generic, name = "Demo habit", enabled = true, intervalMin = intervalMin,
    repeat = RepeatPolicy(everyMin = 5, maxRepeats = 1), snoozeMin = 5,
    phrase = LocalizedText("Time for the demo habit", "הגיע הזמן להרגל לדוגמה"),
)
