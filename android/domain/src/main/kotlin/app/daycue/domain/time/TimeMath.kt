@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package app.daycue.domain.time

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

/**
 * A local-time window. `end < start` crosses midnight. `start == end` means the whole day.
 * The window "belongs" to the date on which it starts (relevant for day filters).
 */
@Serializable
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = end < start
    val wholeDay: Boolean get() = start == end

    override fun toString(): String = "$start-$end"
}

object TimeMath {

    /**
     * Resolve a local date/time in [zone] to an instant with explicit DST handling (MED-6, ALM-6):
     * - a nonexistent local time (spring-forward gap) resolves to the first valid instant after it,
     *   i.e. the transition instant;
     * - an ambiguous local time (fall-back overlap) resolves to the first (earlier) occurrence only.
     */
    fun resolveLocal(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val ldt = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(ldt)
        return when {
            offsets.isEmpty() -> rules.getTransition(ldt).instant // gap: transition instant
            else -> ldt.atOffset(offsets.first()).toInstant() // overlap: earlier offset first
        }
    }

    fun localDate(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()

    /** The "day" an instant belongs to, given the day boundary (`settings.dayStartsAt`, default 04:00). */
    fun dayOf(at: Instant, zone: ZoneId, dayStartsAt: LocalTime): LocalDate {
        val z = at.atZone(zone)
        return if (z.toLocalTime() < dayStartsAt) z.toLocalDate().minusDays(1) else z.toLocalDate()
    }

    fun dayStart(day: LocalDate, zone: ZoneId, dayStartsAt: LocalTime): Instant = resolveLocal(day, dayStartsAt, zone)

    fun nextDayBoundary(at: Instant, zone: ZoneId, dayStartsAt: LocalTime): Instant =
        dayStart(dayOf(at, zone, dayStartsAt).plusDays(1), zone, dayStartsAt)

    /** Concrete [start, end) of a window occurrence that starts on [date]. */
    fun occurrence(date: LocalDate, window: TimeWindow, zone: ZoneId): Pair<Instant, Instant> {
        val s = resolveLocal(date, window.start, zone)
        val e = when {
            window.wholeDay -> resolveLocal(date.plusDays(1), window.start, zone)
            window.crossesMidnight -> resolveLocal(date.plusDays(1), window.end, zone)
            else -> resolveLocal(date, window.end, zone)
        }
        return s to e
    }

    /** The window occurrence containing [at], or null. */
    fun windowContaining(at: Instant, zone: ZoneId, window: TimeWindow, days: Set<DayOfWeek> = ALL_DAYS): Pair<Instant, Instant>? {
        val d = localDate(at, zone)
        for (date in listOf(d.minusDays(1), d)) {
            if (date.dayOfWeek !in days) continue
            val (s, e) = occurrence(date, window, zone)
            if (!at.isBefore(s) && at.isBefore(e)) return s to e
        }
        return null
    }

    fun inWindow(at: Instant, zone: ZoneId, window: TimeWindow?, days: Set<DayOfWeek> = ALL_DAYS): Boolean =
        if (window == null) days.contains(localDate(at, zone).dayOfWeek) else windowContaining(at, zone, window, days) != null

    /** Next instant >= [at] at which the window is open (== [at] if already open). Null if days is empty. */
    fun nextOpen(at: Instant, zone: ZoneId, window: TimeWindow?, days: Set<DayOfWeek> = ALL_DAYS): Instant? {
        if (days.isEmpty()) return null
        if (window == null) {
            if (localDate(at, zone).dayOfWeek in days) return at
            var d = localDate(at, zone).plusDays(1)
            repeat(8) {
                if (d.dayOfWeek in days) return resolveLocal(d, LocalTime.MIDNIGHT, zone)
                d = d.plusDays(1)
            }
            return null
        }
        if (windowContaining(at, zone, window, days) != null) return at
        var d = localDate(at, zone).minusDays(1)
        repeat(10) {
            if (d.dayOfWeek in days) {
                val (s, _) = occurrence(d, window, zone)
                if (s.isAfter(at)) return s
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** Start of the next occurrence strictly after [at]. */
    fun nextStart(at: Instant, zone: ZoneId, window: TimeWindow, days: Set<DayOfWeek> = ALL_DAYS): Instant? {
        var d = localDate(at, zone).minusDays(1)
        repeat(10) {
            if (d.dayOfWeek in days) {
                val (s, _) = occurrence(d, window, zone)
                if (s.isAfter(at)) return s
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** End of the currently open occurrence, if open. */
    fun currentWindowEnd(at: Instant, zone: ZoneId, window: TimeWindow?, days: Set<DayOfWeek> = ALL_DAYS): Instant? =
        if (window == null) null else windowContaining(at, zone, window, days)?.second

    fun minutes(m: Int): Duration = Duration.ofMinutes(m.toLong())
    fun seconds(s: Int): Duration = Duration.ofSeconds(s.toLong())

    fun ZonedDateTime.inst(): Instant = toInstant()
}

fun minOfNotNull(vararg xs: Instant?): Instant? = xs.filterNotNull().minOrNull()
fun Instant.plusMin(m: Int): Instant = plusSeconds(m * 60L)
fun Instant.plusMin(m: Long): Instant = plusSeconds(m * 60L)
fun Instant.plusSec(s: Int): Instant = plusSeconds(s.toLong())
