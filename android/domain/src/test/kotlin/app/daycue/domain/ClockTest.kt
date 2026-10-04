package app.daycue.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockTest {
    private class FixedClock(
        private val instant: Instant,
        private val zoneId: ZoneId,
        private val elapsed: Duration,
    ) : Clock {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = zoneId
        override fun elapsedRealtime(): Duration = elapsed
    }

    @Test
    fun fixedClockReturnsInjectedValues() {
        val clock = FixedClock(
            instant = Instant.parse("2026-01-15T07:30:00Z"),
            zoneId = ZoneId.of("UTC"),
            elapsed = Duration.ofMinutes(5),
        )

        assertEquals(Instant.parse("2026-01-15T07:30:00Z"), clock.now())
        assertEquals(ZoneId.of("UTC"), clock.zone())
        assertEquals(Duration.ofMinutes(5), clock.elapsedRealtime())
    }
}
