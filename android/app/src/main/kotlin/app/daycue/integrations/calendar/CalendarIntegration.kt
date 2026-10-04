package app.daycue.integrations.calendar

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.daycue.DayCueApplication
import app.daycue.data.db.CalendarEventCacheEntity
import app.daycue.data.db.DayCueDatabase
import app.daycue.domain.config.EventAvailability
import app.daycue.domain.engine.CalendarEvent
import app.daycue.engine.EngineHost
import app.daycue.integrations.location.LocationAccess
import app.daycue.domain.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Room `calendar_event_cache` writer (titles stay on-device; the table is never exported). */
class RoomCalendarCache(private val db: DayCueDatabase) : CalendarCache {
    override suspend fun replace(events: List<CalendarEvent>, fetchedAt: Instant) {
        db.withTransaction {
            val dao = db.calendarCacheDao()
            dao.clear()
            if (events.isNotEmpty()) dao.putAll(events.map {
                CalendarEventCacheEntity(
                    instanceKey = it.key, calendarId = it.calendarId.toLongOrNull() ?: -1, beginMs = it.start.toEpochMilli(),
                    endMs = it.end.toEpochMilli(), allDay = it.allDay, busy = it.availability == EventAvailability.Busy,
                    title = it.title, fetchedAtMs = fetchedAt.toEpochMilli(),
                )
            })
        }
    }
}

/**
 * Calendar refresh triggers (ADR 0004):
 * 1. WorkManager periodic sync every [PERIODIC_MIN] min (keeps the engine's `maxCacheAge` fresh; runs
 *    when the app isn't running; deferred by Doze to maintenance windows).
 * 2. WorkManager content-URI trigger on `content://com.android.calendar` (JobScheduler `TriggerContentUri`):
 *    runs [TRIGGER_DELAY_S]..[TRIGGER_MAX_DELAY_S] s after the provider changes (the device's sync adapter wrote
 *    something), also when the app isn't running; re-armed after every run.
 * 3. A `ContentObserver` while the process is alive (debounced [OBSERVER_DEBOUNCE_MS]).
 * 4. App open (first activity started, throttled) and config changes of the calendar selection/horizon.
 */
class CalendarIntegration(
    private val context: Context,
    private val host: EngineHost,
    private val db: DayCueDatabase,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    val source: CalendarSource by lazy { ProviderCalendarSource(context.contentResolver) }

    val sync: CalendarSync by lazy {
        CalendarSync(
            source = source, cache = RoomCalendarCache(db), clock = clock,
            config = { host.ensureLoaded().config },
            hasPermission = ::hasPermission,
            dispatch = { host.dispatch(it) },
            previous = { host.snapshot.value?.state?.calendar?.events.orEmpty() },
        )
    }

    fun hasPermission(): Boolean = LocationAccess.granted(context, Manifest.permission.READ_CALENDAR)

    @Volatile private var started = false
    @Volatile private var observerRegistered = false
    @Volatile private var lastAppOpenSync = 0L
    private var debounce: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { requestSync("observer", OBSERVER_DEBOUNCE_MS) }
    }

    fun start() {
        if (started) return
        started = true
        schedule()
        scope.launch {
            host.snapshot.filterNotNull().map { s -> s.config.calendarRules.let { it.calendars.map { p -> p.calendarId }.sorted() to it.syncHorizonDays } }
                .distinctUntilChanged().drop(1).collect { requestSync("config", 500) }
        }
        requestSync("process start", 2_000)
    }

    /** Call after the user granted/revoked `READ_CALENDAR` (the UI knows first). */
    fun onPermissionChanged() { schedule(); requestSync("permission", 0) }

    fun onAppOpened() {
        val now = System.currentTimeMillis()
        if (now - lastAppOpenSync < APP_OPEN_THROTTLE_MS) return
        lastAppOpenSync = now
        requestSync("app open", 0)
    }

    fun requestSync(reason: String, delayMs: Long) {
        synchronized(this) {
            debounce?.cancel()
            // Only the wait is cancelled by a newer request; a started sync always completes (it dispatches into the engine).
            debounce = scope.launch { if (delayMs > 0) delay(delayMs); withContext(NonCancellable) { runSync(reason) } }
        }
    }

    suspend fun runSync(reason: String): CalendarSyncResult {
        ensureObserver()
        val r = sync.sync(reason)
        Log.i(TAG, "calendar sync ($reason): ${r.status} events=${r.events} +${r.added} ~${r.changed} -${r.removed} dup=${r.duplicatesDropped}${r.error?.let { " error=$it" } ?: ""}")
        return r
    }

    private fun ensureObserver() {
        if (observerRegistered || !hasPermission()) return
        runCatching {
            context.contentResolver.registerContentObserver(ProviderCalendarSource.ROOT_URI, true, observer)
            observerRegistered = true
        }.onFailure { Log.w(TAG, "content observer registration failed", it) }
    }

    /** (Re)enqueues the periodic and the content-trigger work. Idempotent (KEEP). */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarSyncWorker>(PERIODIC_MIN, TimeUnit.MINUTES).build())
        wm.enqueueUniqueWork(TRIGGER_NAME, ExistingWorkPolicy.KEEP, triggerRequest())
    }

    companion object {
        const val TAG = "DayCueCalendar"
        const val PERIODIC_MIN = 30L
        const val TRIGGER_DELAY_S = 5L
        const val TRIGGER_MAX_DELAY_S = 60L
        const val OBSERVER_DEBOUNCE_MS = 3_000L
        const val APP_OPEN_THROTTLE_MS = 60_000L
        const val PERIODIC_NAME = "calendar-sync-periodic"
        const val TRIGGER_NAME = "calendar-sync-content-trigger"

        fun triggerRequest() = OneTimeWorkRequestBuilder<CalendarContentTriggerWorker>()
            .setConstraints(Constraints.Builder()
                .addContentUriTrigger(ProviderCalendarSource.ROOT_URI, true)
                .setTriggerContentUpdateDelay(TRIGGER_DELAY_S, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_S, TimeUnit.SECONDS)
                .build())
            .build()
    }
}

private fun Context.calendarIntegration(): CalendarIntegration? =
    (applicationContext as? DayCueApplication)?.container?.calendar

/** Periodic refresh (fallback for everything else). */
class CalendarSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cal = applicationContext.calendarIntegration() ?: return Result.success()
        cal.runSync("periodic")
        return Result.success()
    }
}

/** Runs when the calendar provider changed; re-arms itself (content triggers are one-shot). */
class CalendarContentTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cal = applicationContext.calendarIntegration() ?: return Result.success()
        try {
            cal.runSync("provider changed")
        } finally {
            // APPEND_OR_REPLACE: the next trigger is chained after this one finishes (REPLACE would cancel us).
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(CalendarIntegration.TRIGGER_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, CalendarIntegration.triggerRequest())
        }
        return Result.success()
    }
}

