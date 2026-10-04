package app.daycue.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.daycue.data.db.ConfigHistoryEntity
import app.daycue.data.db.DayCueDatabase
import app.daycue.data.repo.RoomEngineStore
import app.daycue.domain.Clock
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.WakePrecision
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.ArmResult
import app.daycue.engine.EffectSink
import app.daycue.engine.EngineHost
import app.daycue.engine.HostLog
import app.daycue.engine.UsedApi
import app.daycue.engine.WakeScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Real Room (in-memory) behind EngineHost: persistence, history rows, bounded config history, re-arm after a "new process". */
@RunWith(AndroidJUnit4::class)
class RoomEngineStoreTest {
    private lateinit var db: DayCueDatabase
    private var now = Instant.parse("2026-10-05T07:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
        override fun zone(): ZoneId = ZoneId.of("Asia/Jerusalem")
        override fun elapsedRealtime(): Duration = Duration.ofHours(2).plus(Duration.between(Instant.parse("2026-10-05T07:00:00Z"), now))
    }
    private var armed: Pair<Instant, WakePrecision>? = null
    private val scheduler = object : WakeScheduler {
        override fun arm(at: Instant, precision: WakePrecision): ArmResult { armed = at to precision; return ArmResult(at, precision, UsedApi.ExactAllowWhileIdle, false) }
        override fun cancel() { armed = null }
    }
    private val delivered = mutableListOf<Effect.Deliver>()
    private val sink = object : EffectSink {
        override fun execute(effect: Effect, config: DayCueConfig, state: EngineState) { if (effect is Effect.Deliver) delivered += effect }
    }
    private val log = object : HostLog { override fun info(msg: String) = Unit; override fun warn(msg: String, t: Throwable?) = Unit }
    private fun host() = EngineHost(RoomEngineStore(db), clock, scheduler, sink, log = log)

    private val habit = IntervalHabit("demo", IntervalKind.Generic, "Demo habit", enabled = true, intervalMin = 5, repeat = RepeatPolicy(5, 1), snoozeMin = 5)

    @Before fun setUp() { db = DayCueDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext) }
    @After fun tearDown() = db.close()

    @Test
    fun persistsStateHistoryAndRearmsFromStorage() = runBlocking {
        val h = host()
        val v0 = h.ensureLoaded().config.version
        assertTrue(h.applyOps(listOf(ConfigOp.UpsertHabit(habit)), v0, "ui") is ApplyOutcome.Applied)
        val row = db.engineStateDao().current()!!
        assertNotNull(row.nextWakeAtMs)
        assertEquals("exact", row.nextWakePrecision)
        assertEquals(1L, row.configVersion)
        assertEquals(1, db.configDao().historyCount())
        assertEquals("config.apply", db.auditDao().recent(1).single().action)

        now = Instant.ofEpochMilli(row.nextWakeAtMs!!)
        armed = null
        val fresh = host() // new process: nothing cached
        fresh.dispatch(Event.Tick)
        assertEquals(1, delivered.count { it.cue.itemKey == "habit:demo" })
        assertTrue(db.historyDao().all().any { it.kind == "Delivered" && it.subjectType == "habit" && it.subjectId == "demo" })
        assertEquals(EngineState.decode(db.engineStateDao().current()!!.json).nextWakeAt, armed!!.first)
    }

    @Test
    fun configHistoryIsBoundedAndUndoWalksBack() = runBlocking {
        val h = host()
        var v = h.ensureLoaded().config.version
        h.applyOps(listOf(ConfigOp.UpsertHabit(habit)), v, "ui"); v++
        repeat(ConfigHistoryEntity.MAX_ROWS + 5) { i ->
            assertTrue(h.applyOps(listOf(ConfigOp.SetHabitInterval("demo", 10 + i)), v, "ui") is ApplyOutcome.Applied); v++
        }
        assertEquals(ConfigHistoryEntity.MAX_ROWS, db.configDao().historyCount())
        val before = (h.ensureLoaded().config.habit("demo") as IntervalHabit).intervalMin
        assertTrue(h.undo() is ApplyOutcome.Applied)
        assertEquals(before - 1, (h.ensureLoaded().config.habit("demo") as IntervalHabit).intervalMin)
        assertEquals(ConfigHistoryEntity.MAX_ROWS - 1, db.configDao().historyCount())
        assertTrue(h.ensureLoaded().config.version > v)
    }
}
