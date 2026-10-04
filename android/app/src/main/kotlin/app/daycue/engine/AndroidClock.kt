package app.daycue.engine

import android.os.SystemClock
import app.daycue.domain.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * The real [Clock]. `zone()` reads the process default zone on every call: Android resets it in every
 * running app process when `ACTION_TIMEZONE_CHANGED` is broadcast, so a long-lived process follows
 * timezone changes. `elapsedRealtime()` includes deep sleep and resets only on reboot, which is how the
 * engine tells a reboot from a process restart.
 */
object AndroidClock : Clock {
    override fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())
    override fun zone(): ZoneId = TimeZone.getDefault().toZoneId()
    override fun elapsedRealtime(): Duration = Duration.ofMillis(SystemClock.elapsedRealtime())
}
