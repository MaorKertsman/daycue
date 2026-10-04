package app.daycue.integrations.calendar

import app.daycue.domain.Clock
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.engine.CalendarEvent
import app.daycue.domain.engine.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

enum class CalendarSyncStatus { Ok, NoPermission, NothingSelected, Failed }

data class CalendarSyncResult(
    val status: CalendarSyncStatus,
    val at: Instant,
    val reason: String,
    val events: Int = 0,
    val added: Int = 0,
    val changed: Int = 0,
    val removed: Int = 0,
    val duplicatesDropped: Int = 0,
    val error: String? = null,
)

/** Room `calendar_event_cache` (on-device only, never exported). */
interface CalendarCache {
    suspend fun replace(events: List<CalendarEvent>, fetchedAt: Instant)
}

/**
 * Provider -> engine. Each sync reads the whole bounded window (`now .. now + syncHorizonDays`, instances
 * overlapping it, so in-progress meetings count for Activity = Meeting), maps + dedupes, diffs against the
 * previous snapshot (engine state), replaces the Room cache and dispatches `CalendarSynced(events, syncedAt)`. The
 * engine reconciles pending cues from the full list (CAL-2: moved -> reschedule, canceled/declined/removed
 * -> retract), so a full snapshot is always correct; the diff is for logging and the facade.
 *
 * Never writes to the provider.
 */
class CalendarSync(
    private val source: CalendarSource,
    private val cache: CalendarCache,
    private val clock: Clock,
    private val config: suspend () -> DayCueConfig,
    private val hasPermission: () -> Boolean,
    private val dispatch: suspend (Event) -> Unit,
    /** The previous snapshot as the engine holds it (full domain model): the provider has no sync tokens, so we diff ourselves. */
    private val previous: () -> List<CalendarEvent>,
) {
    private val mutex = Mutex()
    private val _last = MutableStateFlow<CalendarSyncResult?>(null)
    val last: StateFlow<CalendarSyncResult?> = _last.asStateFlow()

    suspend fun sync(reason: String): CalendarSyncResult = mutex.withLock {
        val now = clock.now()
        val result = try {
            run(now, reason)
        } catch (t: Throwable) {
            // Keep the previous cache and engine state; the engine's maxCacheAge stops calendar cues if this persists.
            CalendarSyncResult(CalendarSyncStatus.Failed, now, reason, error = t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        _last.value = result
        result
    }

    private suspend fun run(now: Instant, reason: String): CalendarSyncResult {
        val cfg = config()
        if (!hasPermission()) {
            // No access: nothing is known any more. Clear rather than cue from stale data (CAL-6: everything else unaffected).
            clearIfNeeded(now)
            return CalendarSyncResult(CalendarSyncStatus.NoPermission, now, reason)
        }
        val selected = cfg.calendarRules.calendars.mapNotNull { it.calendarId.toLongOrNull() }.toSet()
        if (selected.isEmpty()) {
            clearIfNeeded(now)
            return CalendarSyncResult(CalendarSyncStatus.NothingSelected, now, reason)
        }
        val calendars = source.calendars().associateBy { it.id }
        val ids = selected.filter { it in calendars }.toSet()
        val horizon = Duration.ofDays(cfg.calendarRules.syncHorizonDays.coerceIn(1, 30).toLong())
        val raw = source.instances(now.toEpochMilli(), now.plus(horizon).toEpochMilli(), ids)
        val eventIds = raw.map { it.eventId }.toSet()
        val attendees = if (eventIds.isEmpty()) emptyList() else source.attendees(eventIds)
        val reminders = if (eventIds.isEmpty()) emptyList() else runCatching { source.reminders(eventIds) }.getOrNull()
        val events = CalendarMapper.mapAll(raw, calendars, attendees, reminders, clock.zone(), now)
        val d = diff(previous(), events)
        cache.replace(events, now)
        dispatch(Event.CalendarSynced(events, now))
        return CalendarSyncResult(CalendarSyncStatus.Ok, now, reason, events.size, d.added, d.changed, d.removed,
            duplicatesDropped = raw.count { !it.deleted } - events.size - raw.count { r -> !r.deleted && endedBefore(r, now) })
    }

    private fun endedBefore(r: RawInstance, now: Instant) = !CalendarMapper.end(r, clock.zone()).isAfter(now) && !CalendarMapper.start(r, clock.zone()).isAfter(now)

    private suspend fun clearIfNeeded(now: Instant) {
        if (previous().isNotEmpty()) {
            cache.replace(emptyList(), now)
            dispatch(Event.CalendarSynced(emptyList(), now))
        }
    }

    data class Diff(val added: Int, val changed: Int, val removed: Int)

    companion object {
        fun diff(old: List<CalendarEvent>, new: List<CalendarEvent>): Diff {
            val o = old.associateBy { it.key }; val n = new.associateBy { it.key }
            return Diff(
                added = n.keys.count { it !in o },
                changed = n.count { (k, v) -> o[k]?.let { it != v } == true },
                removed = o.keys.count { it !in n },
            )
        }
    }
}
