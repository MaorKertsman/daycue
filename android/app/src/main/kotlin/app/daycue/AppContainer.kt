package app.daycue

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import app.daycue.integrations.calendar.CalendarIntegration
import app.daycue.integrations.location.LocationIntegration
import app.daycue.data.boot.BootSnapshotStore
import app.daycue.data.db.DayCueDatabase
import app.daycue.data.repo.RoomEngineStore
import app.daycue.delivery.AndroidEffectSink
import app.daycue.delivery.ChannelRegistry
import app.daycue.delivery.CuePlayer
import app.daycue.delivery.NotificationDelivery
import app.daycue.delivery.NotificationSync
import app.daycue.system.AppLanguage
import app.daycue.delivery.SpeechPrefs
import app.daycue.delivery.SpeechQueue
import app.daycue.delivery.TextResolver
import app.daycue.domain.engine.Event
import app.daycue.engine.AndroidClock
import app.daycue.engine.EngineHost
import app.daycue.engine.HostLog
import app.daycue.facade.DayCueFacade
import app.daycue.facade.RelayFacade
import app.daycue.integrations.relay.RelayService
import app.daycue.integrations.spotify.SpotifyIntegration
import app.daycue.scheduling.AndroidWakeScheduler
import app.daycue.system.Readiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Manual DI (ANDROID.md §2). Everything is lazy so direct-boot components (which run before the first
 * unlock, when Room is unreadable) can use the container without touching credential storage.
 * The UI uses [facade] only.
 */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = AndroidClock

    val log = object : HostLog {
        override fun info(msg: String) { Log.i(TAG, msg) }
        override fun warn(msg: String, t: Throwable?) { Log.w(TAG, msg, t) }
    }

    val db: DayCueDatabase by lazy { DayCueDatabase.build(app) }
    val text by lazy { TextResolver(app) }
    val channels by lazy { ChannelRegistry(app) }
    val notifications by lazy { NotificationDelivery(app, channels, text) }
    val speechPrefs by lazy { SpeechPrefs(app) }
    val speech: SpeechQueue by lazy {
        SpeechQueue(
            app, speechPrefs,
            respectDnd = { host.snapshot.value?.config?.settings?.quietHours?.respectSystemDnd ?: true },
            onOutcome = { cueId, ok, reason ->
                scope.launch { host.dispatch(if (ok) Event.SpeechFinished(cueId) else Event.SpeechFailed(cueId, reason ?: "unknown")) }
            },
        )
    }
    val scheduler by lazy { AndroidWakeScheduler(app) }
    val host: EngineHost by lazy {
        EngineHost(
            store = RoomEngineStore(db), clock = clock, scheduler = scheduler,
            sink = AndroidEffectSink(app, notifications, speech, text),
            bootSnapshot = BootSnapshotStore(app, clock), log = log,
            firstRun = { language.firstRunSeed() },
        )
    }
    /** App language (per-app locale + config language as one setting, VALIDATION D8). */
    val language: AppLanguage by lazy { AppLanguage(app, { host }, { text }, scope, log) }

    /**
     * Start-of-process work, in order: `BootCompleted` (reboot detection, recompute), then tell the engine which
     * notifications the system really shows so lost ones are re-posted (VALIDATION D1). Also called after
     * `BOOT_COMPLETED` when the process was already running before the first unlock.
     */
    suspend fun bootAndReconcile() {
        host.dispatch(Event.BootCompleted)
        reconcileNotifications()
    }

    suspend fun reconcileNotifications() {
        NotificationSync.observe(app, notifications.canPost())?.let { host.dispatch(it) }
    }
    val readiness by lazy { Readiness(app, channels) }
    val cuePlayer by lazy { CuePlayer(app, speech) }
    val facade: DayCueFacade by lazy { DayCueFacade(this) }
    /** Relay / remote access (integrations/relay). Started from [DayCueApplication]. */
    val relay: RelayService by lazy { RelayService(this) }
    val remote: RelayFacade by lazy { RelayFacade(this) }
    /** Spotify alarm source (integrations/spotify). Registered with the ringing service from [DayCueApplication]. */
    val spotify: SpotifyIntegration by lazy { SpotifyIntegration(this) }

    // ---- On-device context producers (integrations/location, integrations/calendar) ----
    val location: LocationIntegration by lazy { LocationIntegration(app, host, scope) }
    val calendar: CalendarIntegration by lazy { CalendarIntegration(app, host, db, clock, scope) }

    /**
     * Starts geofence/activity-transition registration and calendar sync (idempotent). Runs at process
     * start once the user storage is unlocked; boot/package receivers call it again.
     */
    fun startIntegrations() {
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return
        location.start()
        calendar.start()
    }

    init {
        startIntegrations()
        // App open: refresh calendar (throttled) and repair registrations (no-op when unchanged).
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0
            private var languageChecked = false
            override fun onActivityStarted(a: Activity) {
                if (startedCount++ == 0) {
                    AppVisibility.visible = true
                    calendar.onAppOpened(); location.onAppOpened()
                    // Before API 33 AppCompat loads the stored per-app locale with the first Activity, so the
                    // start reconcile of the language waits for it (API 33+ does it at process start).
                    if (android.os.Build.VERSION.SDK_INT < 33 && !languageChecked) { languageChecked = true; scope.launch { language.reconcileOnStart() } }
                }
            }
            override fun onActivityStopped(a: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0) AppVisibility.visible = false
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** History retention: medication rows per `historyRetentionDays` (max over medications, default 90), others 400 days. */
    suspend fun housekeeping() {
        val cfg = host.ensureLoaded().config
        val now = clock.now()
        val medDays = cfg.medications.maxOfOrNull { it.historyRetentionDays } ?: 90
        val h = db.historyDao()
        val m = h.deleteOlder("medication", now.minus(Duration.ofDays(medDays.toLong())).toEpochMilli())
        val o = h.deleteOlderExceptMedication(now.minus(Duration.ofDays(400)).toEpochMilli())
        if (m + o > 0) Log.i(TAG, "housekeeping: removed $m medication and $o other history rows")
        // Remote command texts (ops) and audit rows: 30 and 400 days (security review L-10; the relay keeps commands 14 days).
        val c = db.commandLogDao().deleteFinishedOlder(now.minus(Duration.ofDays(30)).toEpochMilli())
        val a = db.auditDao().deleteOlder(now.minus(Duration.ofDays(400)).toEpochMilli())
        if (c + a > 0) Log.i(TAG, "housekeeping: removed $c command log and $a audit rows")
    }

    companion object { const val TAG = "DayCue" }
}

/** True while any DayCue Activity is started (visible). Used to open the alarm screen directly (VALIDATION D7). */
object AppVisibility {
    @Volatile var visible: Boolean = false
}
