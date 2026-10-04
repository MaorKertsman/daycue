package app.daycue.domain.testing

import app.daycue.domain.Clock
import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.config.Habit
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.QuietHours
import app.daycue.domain.config.TransitionHabit
import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Cue
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.Engine
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.HistoryKind
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.signal.CompanionActivity
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.signal.GeofenceSnapshot
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.GeofenceTransitionKind
import app.daycue.domain.signal.MotionActivity
import app.daycue.domain.signal.MotionKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertTrue
import kotlin.test.fail

/** Controllable clock: wall time, zone and monotonic elapsed time move independently. */
class FakeClock(var instant: Instant, var zoneId: ZoneId, var elapsed: Duration = Duration.ofHours(5)) : Clock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    override fun elapsedRealtime(): Duration = elapsed
    fun advance(d: Duration) { instant = instant.plus(d); elapsed = elapsed.plus(d) }
}

/**
 * Small scenario DSL. Times are written as "HH:mm" on the scenario date in the scenario zone.
 * [advanceTo] behaves like the real app: it fires the single next-wake alarm (Tick) every time it
 * comes due, and checks after every reduce that `nextWakeAt` is never in the past.
 */
class Scenario(
    var config: DayCueConfig = Defaults.config(),
    val date: LocalDate = LocalDate.of(2026, 10, 5), // a Monday
    zone: ZoneId = ZoneId.of("UTC"),
    start: String = "06:00",
) {
    val clock = FakeClock(LocalDateTime.of(date, LocalTime.parse(start)).atZone(zone).toInstant(), zone)
    var state = EngineState()
    val all = mutableListOf<Effect>()
    var last: List<Effect> = emptyList()
    val now: Instant get() = clock.instant

    init {
        // quiet hours off by default so tests opt in explicitly
        config = config.copy(settings = config.settings.copy(quietHours = QuietHours(enabled = false)))
        send(Event.BootCompleted)
    }

    fun t(hhmm: String, day: LocalDate = date): Instant = LocalDateTime.of(day, LocalTime.parse(hhmm)).atZone(clock.zoneId).toInstant()

    fun send(e: Event): List<Effect> {
        val r = Engine.reduce(config, state, e, clock)
        state = r.state
        last = r.effects
        all += r.effects
        state.nextWakeAt?.let { assertTrue(it.isAfter(clock.instant), "nextWakeAt ${it} not after now ${clock.instant} (after $e)") }
        return r.effects
    }

    /** Fire due wakes up to [target], then set the clock to [target] (no event at target itself). */
    fun advanceTo(target: Instant) {
        var guard = 0
        while (true) {
            val w = state.nextWakeAt ?: break
            if (w.isAfter(target)) break
            if (guard++ > 5000) fail("too many wakes")
            clock.advance(Duration.between(clock.instant, w))
            send(Event.Tick)
        }
        if (target.isAfter(clock.instant)) clock.advance(Duration.between(clock.instant, target))
    }

    fun advanceTo(hhmm: String) = advanceTo(t(hhmm))
    fun advance(minutes: Long) = advanceTo(clock.instant.plus(Duration.ofMinutes(minutes)))
    fun at(hhmm: String, e: Event): List<Effect> { advanceTo(hhmm); return send(e) }

    fun apply(vararg ops: ConfigOp) {
        when (val r = ConfigEditor.applyOps(config, ops.toList(), config.version)) {
            is ApplyResult.Applied -> config = r.config
            else -> fail("ops rejected: $r")
        }
        send(Event.ConfigChanged)
    }

    fun enable(id: String) = apply(ConfigOp.SetHabitEnabled(id, true))
    fun updateHabit(id: String, f: (IntervalHabit) -> IntervalHabit) = apply(ConfigOp.UpsertHabit(f(config.habit(id) as IntervalHabit)))
    fun updateBottle(f: (TransitionHabit) -> TransitionHabit) = apply(ConfigOp.UpsertHabit(f(config.habit(Defaults.WATER_BOTTLE) as TransitionHabit)))

    // ---- process death / reboot ----

    /** Simulate process death: persist + reload config and state as JSON, then the app replays BootCompleted. */
    fun processRestart(): List<Effect> {
        config = ConfigCodec.decode(ConfigCodec.encode(config))
        state = EngineState.decode(EngineState.encode(state))
        return send(Event.BootCompleted)
    }

    /** Phone off for [offMinutes], then boots (elapsedRealtime restarts). */
    fun reboot(offMinutes: Long): List<Effect> {
        state = EngineState.decode(EngineState.encode(state))
        clock.instant = clock.instant.plus(Duration.ofMinutes(offMinutes))
        clock.elapsed = Duration.ofSeconds(40)
        return send(Event.BootCompleted)
    }

    // ---- synthetic context ----

    fun outdoors(d: OverrideDuration = OverrideDuration.UntilChanged) = send(Event.OverrideEnvironment(Environment.Outdoor, d))
    fun indoors(d: OverrideDuration = OverrideDuration.UntilChanged) = send(Event.OverrideEnvironment(Environment.Indoor, d))
    fun enter(placeId: String) = send(Event.SignalObserved(GeofenceTransition(placeId, GeofenceTransitionKind.Enter, now)))
    fun exit(placeId: String) = send(Event.SignalObserved(GeofenceTransition(placeId, GeofenceTransitionKind.Exit, now)))
    fun outsideAll() = send(Event.SignalObserved(GeofenceSnapshot(emptySet(), now)))
    fun walking() = send(Event.SignalObserved(MotionActivity(MotionKind.OnFoot, now)))
    fun companion(s: CompanionState) = send(Event.SignalObserved(CompanionActivity(s, now)))

    // ---- inspection ----

    val delivered: List<Cue> get() = all.filterIsInstance<Effect.Deliver>().map { it.cue }
    fun deliveredFor(itemKey: String): List<Cue> = delivered.filter { it.itemKey == itemKey }
    fun lastDelivered(): List<Cue> = last.filterIsInstance<Effect.Deliver>().map { it.cue }
    fun dismissedKeys(): List<String> = all.filterIsInstance<Effect.DismissCue>().map { it.notificationKey }
    fun history(kind: HistoryKind? = null) = all.filterIsInstance<Effect.RecordHistory>().map { it.entry }.filter { kind == null || it.kind == kind }
    fun clear() { all.clear() }

    fun habit(id: String): Habit = config.habit(id)!!
}

fun sunscreenKey() = "habit:${Defaults.SUNSCREEN}"
fun hydrationKey() = "habit:${Defaults.HYDRATION}"
fun bottleKey() = "habit:${Defaults.WATER_BOTTLE}"
