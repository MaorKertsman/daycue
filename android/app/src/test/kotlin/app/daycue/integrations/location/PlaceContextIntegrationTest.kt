package app.daycue.integrations.location

import app.daycue.domain.config.Confidence
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Environment
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.context.ContextSource
import app.daycue.domain.context.PlaceValue
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.query.Queries
import app.daycue.engine.EngineHost
import app.daycue.engine.FakeScheduler
import app.daycue.engine.MemoryStore
import app.daycue.engine.NoopBootSnapshot
import app.daycue.engine.RecordingSink
import app.daycue.engine.SilentLog
import app.daycue.engine.TestClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Geofence broadcast decode -> EngineHost -> domain context (unit level, real engine, fake platform).
 * Acceptance scenario 7 (boundary jitter, stale signals) and 6 (manual context without location).
 */
class PlaceContextIntegrationTest {
    private val clock = TestClock(Instant.parse("2026-10-05T07:00:00Z"))
    private val store = MemoryStore()
    private val host = EngineHost(store, clock, FakeScheduler(), RecordingSink(store), NoopBootSnapshot, SilentLog)
    private val home = Defaults.HOME

    private suspend fun setup() {
        host.ensureLoaded()
        host.applyOps(listOf(ConfigOp.SetPlaceLocation(home, GeoPoint(10.0, 20.0), 150)), host.ensureLoaded().config.version, "ui")
    }

    private suspend fun geofence(transition: Int, at: Instant = clock.now()) {
        val known = host.ensureLoaded().config.places.filter { it.active }.map { it.id }.toSet()
        GeofenceSignals.decode(null, transition, listOf(home), at.toEpochMilli(), clock.now(), known).signals.forEach { host.dispatch(Event.SignalObserved(it)) }
    }

    private suspend fun advance(min: Long) { clock.advance(Duration.ofMinutes(min)); host.dispatch(Event.Tick) }
    private fun place() = host.snapshot.value!!.state.context.place.value
    private fun transitions() = host.snapshot.value!!.state.context.lastTransitionAt

    @Test fun enterConfirmsAfterDwellAndBoundaryJitterDoesNotFlap() = runBlocking {
        setup()
        host.dispatch(Event.SignalObserved(app.daycue.domain.signal.GeofenceSnapshot(emptySet(), clock.now())))
        assertEquals(PlaceValue.ELSEWHERE, place())
        geofence(Geofence.GEOFENCE_TRANSITION_ENTER)
        advance(2); assertEquals("before dwell keeps previous value", PlaceValue.ELSEWHERE, place())
        advance(1); assertEquals(PlaceValue.saved(home), place())
        val confirmedAt = transitions()
        // Jitter at the boundary: exit, re-enter 2 min later, exit + enter again (all inside the 5 min exit dwell).
        repeat(3) {
            advance(10); geofence(Geofence.GEOFENCE_TRANSITION_EXIT)
            advance(2); geofence(Geofence.GEOFENCE_TRANSITION_ENTER)
            assertEquals(PlaceValue.saved(home), place())
        }
        advance(30)
        assertEquals(PlaceValue.saved(home), place())
        assertEquals("no extra meaningful transition", confirmedAt, transitions())
        // A real exit is confirmed after the exit dwell.
        geofence(Geofence.GEOFENCE_TRANSITION_EXIT)
        advance(4); assertEquals(PlaceValue.saved(home), place())
        advance(1); assertEquals(PlaceValue.ELSEWHERE, place())
    }

    @Test fun osDwellConfirmsImmediately() = runBlocking {
        setup()
        geofence(Geofence.GEOFENCE_TRANSITION_DWELL)
        host.dispatch(Event.Tick)
        assertEquals(PlaceValue.saved(home), place())
    }

    @Test fun staleExitFromAQueueIsIgnored() = runBlocking {
        setup()
        geofence(Geofence.GEOFENCE_TRANSITION_DWELL); host.dispatch(Event.Tick)
        geofence(Geofence.GEOFENCE_TRANSITION_EXIT, at = clock.now().minus(Duration.ofMinutes(40)))
        advance(10)
        assertEquals(PlaceValue.saved(home), place())
    }

    @Test fun locationUnavailableGivesUnknownAndManualContextStillWorks() = runBlocking {
        setup()
        geofence(Geofence.GEOFENCE_TRANSITION_DWELL); host.dispatch(Event.Tick)
        assertEquals(PlaceValue.saved(home), place())
        // Permission revoked / GEOFENCE_NOT_AVAILABLE.
        GeofenceSignals.decode(GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE, -1, emptyList(), null, clock.now(), setOf(home)).signals
            .forEach { host.dispatch(Event.SignalObserved(it)) }
        assertEquals(PlaceValue.UNKNOWN, place())
        host.dispatch(Event.OverrideEnvironment(Environment.Outdoor, OverrideDuration.For(60)))
        val snap = host.snapshot.value!!
        val ctx = Queries.todayView(snap.config, snap.state, clock).context
        assertEquals(Environment.Outdoor, ctx.environment.value)
        assertEquals(Confidence.High, ctx.environment.confidence)
        assertEquals(ContextSource.Manual, ctx.environment.source)
        // Leaving now (BTL-1) also works without location.
        host.dispatch(Event.LeavingNow)
        Unit
    }
}
