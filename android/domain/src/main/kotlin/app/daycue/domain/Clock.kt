package app.daycue.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The only source of time for the rules engine (ARCHITECTURE.md §3.1).
 *
 * - [now]: wall-clock instant (UTC). Can jump on manual time changes.
 * - [zone]: current device time zone, used to resolve local-time schedules.
 * - [elapsedRealtime]: monotonic time since boot, unaffected by wall-clock changes. The engine
 *   only uses it to tell a real reboot (counter went backwards) from a process restart.
 */
interface Clock { fun now(): Instant; fun zone(): ZoneId; fun elapsedRealtime(): Duration }

/** A fixed, immutable clock (handy for previews and the UI query helpers). */
data class FixedClock(val instant: Instant, val zoneId: ZoneId, val elapsed: Duration = Duration.ZERO) : Clock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    override fun elapsedRealtime(): Duration = elapsed
}
