package app.daycue.delivery

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.actions.ActionMapper
import app.daycue.actions.CueActionReceiver
import app.daycue.domain.engine.ActionKind
import app.daycue.domain.engine.Cue
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.RoutineAction
import app.daycue.domain.engine.RoutineTestMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Routine playback (ANDROID.md §7): `mediaPlayback` foreground service that exists only while a routine
 * runs and that is only ever started by a user action (in-app Start, or the "Start" button of the
 * routine-due notification, which is a foreground-service PendingIntent). That gives it the while-in-use
 * capability Android 17 requires for speech. Never started from a background alarm (RTN-9).
 *
 * While it runs, routine step cues (`routine:<id>`) are shown as this service's foreground notification
 * (updated in place, alerting through the `routine` channel) instead of a separate notification, and
 * their speech plays through [SpeechQueue] in this process. The service stops itself when the engine
 * state has no run any more.
 */
class RoutinePlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    @Volatile var routineKey: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first: the 5 s FGS deadline must not depend on the engine.
        ServiceCompat.startForeground(this, FGS_ID, placeholder(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        val app = (application as DayCueApplication).container
        val event: Event? = when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ROUTINE_ID)?.let {
                Event.RoutineControl(RoutineAction.Start(it, test = intent.getStringExtra(EXTRA_TEST)?.let { t -> RoutineTestMode.valueOf(t) },
                    replaceCurrent = intent.getBooleanExtra(EXTRA_REPLACE, false)))
            }
            ACTION_CUE -> {
                val itemKey = intent.getStringExtra(CueActionReceiver.EXTRA_ITEM_KEY)
                val kind = intent.getStringExtra(CueActionReceiver.EXTRA_KIND)?.let { runCatching { ActionKind.valueOf(it) }.getOrNull() }
                if (itemKey != null && kind != null) ActionMapper.map(itemKey, ActionMapper.Tap.Action(kind, null), intent.getStringExtra(CueActionReceiver.EXTRA_CUE_ID)) else null
            }
            else -> null
        }
        // Claim the routine before the first step cue is delivered, so it lands in this notification.
        (event as? Event.RoutineControl)?.action?.let { a ->
            when (a) { is RoutineAction.Start -> routineKey = "routine:${a.routineId}"; is RoutineAction.PromptStart -> routineKey = "routine:${a.routineId}"; else -> Unit }
        }
        scope.launch {
            if (event != null) app.host.dispatch(event)
            ensureWatching(app)
        }
        return START_NOT_STICKY
    }

    private fun ensureWatching(app: app.daycue.AppContainer) {
        if (watch != null) return
        watch = scope.launch {
            app.host.snapshot.filterNotNull().collect { snap ->
                val run = snap.state.routine.run
                routineKey = run?.let { "routine:${it.routineId}" }
                if (run == null) {
                    Log.i(TAG, "routine playback: no run, stopping service")
                    ServiceCompat.stopForeground(this@RoutinePlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** Called by the effect sink for `routine:<id>` cues while this service runs. */
    fun show(cue: Cue, notification: Notification) {
        val n = NotificationCompat.Builder(this, notification).setOngoing(true).build()
        NotificationManagerCompat.from(this).cancel(cue.notificationKey, NotificationDelivery.NOTIFICATION_ID)
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            @Suppress("MissingPermission") NotificationManagerCompat.from(this).notify(FGS_ID, n)
        }
        Log.i(TAG, "routine playback shows ${cue.id}")
    }

    private fun placeholder(): Notification = NotificationCompat.Builder(this, ChannelRegistry.ROUTINE_PLAYBACK)
        .setSmallIcon(R.drawable.ic_stat_daycue)
        .setContentTitle(getString(R.string.dc_routine_playback_title_alt))
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(DeepLinks.pending(this, "routine:", null))
        .build()

    override fun onDestroy() {
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DayCue"
        const val FGS_ID = 0xB0B
        const val ACTION_START = "app.daycue.routine.START"
        const val ACTION_CUE = "app.daycue.routine.CUE"
        const val EXTRA_ROUTINE_ID = "routineId"
        const val EXTRA_REPLACE = "replace"
        const val EXTRA_TEST = "test"

        @Volatile var instance: RoutinePlaybackService? = null
            private set

        /** From a foreground Activity (user action) only. */
        fun start(context: Context, routineId: String, replaceCurrent: Boolean = false, test: RoutineTestMode? = null) {
            ContextCompat.startForegroundService(context, Intent(context, RoutinePlaybackService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_ROUTINE_ID, routineId).putExtra(EXTRA_REPLACE, replaceCurrent).putExtra(EXTRA_TEST, test?.name))
        }

        fun cueIntent(context: Context, itemKey: String, cueId: String?, kind: String, minutes: Int?): Intent =
            Intent(context, RoutinePlaybackService::class.java).setAction(ACTION_CUE)
                .putExtra(CueActionReceiver.EXTRA_ITEM_KEY, itemKey).putExtra(CueActionReceiver.EXTRA_CUE_ID, cueId)
                .putExtra(CueActionReceiver.EXTRA_KIND, kind).putExtra(CueActionReceiver.EXTRA_MINUTES, minutes ?: -1)
    }
}
