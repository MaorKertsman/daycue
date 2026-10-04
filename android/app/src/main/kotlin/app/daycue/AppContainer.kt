package app.daycue

import android.app.Application
import android.util.Log
import app.daycue.data.boot.BootSnapshotStore
import app.daycue.data.db.DayCueDatabase
import app.daycue.data.repo.RoomEngineStore
import app.daycue.delivery.AndroidEffectSink
import app.daycue.delivery.ChannelRegistry
import app.daycue.delivery.CuePlayer
import app.daycue.delivery.NotificationDelivery
import app.daycue.delivery.SpeechPrefs
import app.daycue.delivery.SpeechQueue
import app.daycue.delivery.TextResolver
import app.daycue.domain.config.Language
import app.daycue.domain.engine.Event
import app.daycue.engine.AndroidClock
import app.daycue.engine.EngineHost
import app.daycue.engine.HostLog
import app.daycue.facade.DayCueFacade
import app.daycue.scheduling.AndroidWakeScheduler
import app.daycue.system.Readiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Locale

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
            defaultLanguage = { if (Locale.getDefault().language in setOf("he", "iw")) Language.he else Language.en },
        )
    }
    val readiness by lazy { Readiness(app, channels) }
    val cuePlayer by lazy { CuePlayer(app, speech) }
    val facade: DayCueFacade by lazy { DayCueFacade(this) }

    /** History retention: medication rows per `historyRetentionDays` (max over medications, default 90), others 400 days. */
    suspend fun housekeeping() {
        val cfg = host.ensureLoaded().config
        val now = clock.now()
        val medDays = cfg.medications.maxOfOrNull { it.historyRetentionDays } ?: 90
        val h = db.historyDao()
        val m = h.deleteOlder("medication", now.minus(Duration.ofDays(medDays.toLong())).toEpochMilli())
        val o = h.deleteOlderExceptMedication(now.minus(Duration.ofDays(400)).toEpochMilli())
        if (m + o > 0) Log.i(TAG, "housekeeping: removed $m medication and $o other history rows")
    }

    companion object { const val TAG = "DayCue" }
}
