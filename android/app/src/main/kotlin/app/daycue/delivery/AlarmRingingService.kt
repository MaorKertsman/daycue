package app.daycue.delivery

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.daycue.R
import app.daycue.actions.CueActionReceiver
import app.daycue.data.boot.LockedBootAlarms
import app.daycue.domain.engine.ActionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the alarm screen shows; null when nothing rings. */
data class RingingAlarm(
    val alarmId: String, val title: String, val time: String, val canSnooze: Boolean, val locked: Boolean, val isTest: Boolean,
    /** The alarm's own name (raw; the screen localizes default names) and its snooze length, for the screen. */
    val name: String = "", val snoozeMin: Int = 9,
)

/**
 * Ringing morning alarm (ANDROID.md §6.3, §7): `mediaPlayback` foreground service started from the
 * exact-alarm broadcast (`StartAlarm` effect), playing a bundled original tone on the alarm stream
 * (`USAGE_ALARM`, the Android 17 background-audio exemption for exact-alarm holders) with a volume ramp
 * and vibration, a full-screen intent to [AlarmActivity], and Snooze / Stop actions.
 *
 * ALM-2: the configured source is tried through [AlarmMusicPlayer] first (no-op for now); the local tone
 * always plays when it doesn't start, so an alarm is never silent.
 *
 * Direct-boot aware: in `locked` mode (started by [LockedBootAlarms] before first unlock) it uses no
 * credential-encrypted storage, shows a generic title and handles Stop / Snooze itself.
 */
class AlarmRingingService : Service() {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var current: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> { stopRinging(); stopSelf() }
            ACTION_LOCKED_STOP -> { stopRinging(); stopSelf() }
            ACTION_LOCKED_SNOOZE -> {
                val min = current?.getIntExtra(EXTRA_SNOOZE_MIN, 9) ?: 9
                LockedBootAlarms.snooze(this, min)
                stopRinging(); stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        current = intent
        val locked = intent.getBooleanExtra(EXTRA_LOCKED, false)
        val alarm = RingingAlarm(
            alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: "alarm",
            title = intent.getStringExtra(EXTRA_TITLE) ?: "",
            time = intent.getStringExtra(EXTRA_TIME) ?: "",
            canSnooze = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 0) < intent.getIntExtra(EXTRA_MAX_SNOOZES, 3),
            locked = locked,
            isTest = intent.getBooleanExtra(EXTRA_TEST, false),
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            snoozeMin = intent.getIntExtra(EXTRA_SNOOZE_MIN, 9),
        )
        val notification = buildNotification(alarm, intent.getStringExtra(EXTRA_SNOOZE_LABEL), intent.getStringExtra(EXTRA_STOP_LABEL))
        ServiceCompat.startForeground(this, FGS_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        _ringing.value = alarm
        // D7: with DayCue on screen the system does not show a full-screen intent (only a heads-up), so open the alarm
        // screen directly; allowed because the app has a visible window. Otherwise the full-screen intent does it.
        if (AlarmScreenLaunch.startDirectly(app.daycue.AppVisibility.visible, locked)) {
            runCatching { startActivity(AlarmActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "alarm screen could not be started directly", it) }
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daycue:alarm").apply { acquire(MAX_RING_MS) }

        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)
        // Async sources (Spotify): the local tone rings from the first moment and stays until the source reports
        // CONFIRMED playback; if it stops or fails the tone is (back) on. Launching a link is never success.
        val asyncSource = sourceUri != null && !locked &&
            runCatching { AlarmMusicPlayer.current.startAsync(this, alarm.alarmId, sourceUri, musicCallbacks(alarm.alarmId, intent.getStringExtra(EXTRA_TONE))) }.getOrDefault(false)
        val musicStarted = !asyncSource && runCatching { AlarmMusicPlayer.current.start(this, sourceUri) }.getOrDefault(false)
        if (!musicStarted) playTone(intent.getStringExtra(EXTRA_TONE), intent.getIntExtra(EXTRA_RAMP_SEC, 30))
        if (sourceUri != null && !musicStarted && !asyncSource && !locked) {
            // ALM-2: tell the engine the fallback played (readiness + ALM-5 test report).
            fallbackReporter?.invoke(alarm.alarmId)
        }
        if (intent.getBooleanExtra(EXTRA_VIBRATE, true)) {
            CuePlayer.vibrator(this).vibrate(VibrationEffect.createWaveform(CueMedia.vibrations.getValue("continuous"), 0))
        }
        handler.postDelayed({ Log.w(TAG, "alarm safety cap reached"); stopRinging(); stopSelf() }, MAX_RING_MS)
        Log.i(TAG, "alarm ringing ${alarm.alarmId} locked=$locked test=${alarm.isTest} music=$musicStarted")
    }

    private fun musicCallbacks(alarmId: String, toneId: String?) = object : AlarmMusicPlayer.Callbacks {
        private var reported = false
        override fun onConfirmed() { handler.post {
            if (_ringing.value?.alarmId != alarmId) return@post
            runCatching { player?.stop() }; player?.release(); player = null // streaming took over; the tone is silenced
        } }
        override fun onLost() { handler.post {
            if (_ringing.value?.alarmId != alarmId) return@post
            if (player == null) playTone(toneId, 0) // (back) on at full volume
            if (!reported) { reported = true; fallbackReporter?.invoke(alarmId) }
        } }
    }

    private fun playTone(toneId: String?, rampSec: Int) {
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                val afd = resources.openRawResourceFd(CueMedia.alarmTone(toneId))
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                setVolume(if (rampSec > 0) 0.05f else 1f, if (rampSec > 0) 0.05f else 1f)
                start()
            }
            if (rampSec > 0) ramp(System.currentTimeMillis(), rampSec * 1000L)
        }.onFailure { Log.e(TAG, "alarm tone failed", it) }
    }

    private fun ramp(startMs: Long, durationMs: Long) {
        val p = player ?: return
        val f = ((System.currentTimeMillis() - startMs).toFloat() / durationMs).coerceIn(0.05f, 1f)
        runCatching { p.setVolume(f, f) }
        if (f < 1f) handler.postDelayed({ ramp(startMs, durationMs) }, 500)
    }

    private fun buildNotification(a: RingingAlarm, snoozeLabel: String?, stopLabel: String?): Notification {
        val full = PendingIntent.getActivity(this, 0, AlarmActivity.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, "alarm")
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(a.title)
            .setContentText(a.time)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // Not setSilent(): NotificationCompat implements it with a suppressive group-alert behaviour, which
            // also suppresses the full-screen intent. The "alarm" channel has no sound; the service plays audio.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        val key = "alarm:${a.alarmId}"
        if (a.canSnooze) b.addAction(0, snoozeLabel ?: "Snooze", actionIntent(a, ActionKind.Snooze, key))
        b.addAction(0, stopLabel ?: "Stop", actionIntent(a, ActionKind.Stop, key))
        return b.build()
    }

    private fun actionIntent(a: RingingAlarm, kind: ActionKind, itemKey: String): PendingIntent =
        if (a.locked) {
            val i = Intent(this, AlarmRingingService::class.java).setAction(if (kind == ActionKind.Stop) ACTION_LOCKED_STOP else ACTION_LOCKED_SNOOZE)
            PendingIntent.getService(this, kind.ordinal, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else CueActionReceiver.pending(this, itemKey, null, kind, null)

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        player?.release(); player = null
        runCatching { AlarmMusicPlayer.current.stop() }
        CuePlayer.vibrator(this).cancel()
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        _ringing.value = null
    }

    override fun onDestroy() {
        stopRinging()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DayCue"
        const val FGS_ID = 0xA1A
        private const val MAX_RING_MS = 30 * 60 * 1000L

        const val ACTION_START = "app.daycue.alarm.START"
        const val ACTION_STOP = "app.daycue.alarm.STOP"
        const val ACTION_LOCKED_STOP = "app.daycue.alarm.LOCKED_STOP"
        const val ACTION_LOCKED_SNOOZE = "app.daycue.alarm.LOCKED_SNOOZE"
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NAME = "name"
        const val EXTRA_TIME = "time"
        const val EXTRA_TONE = "tone"
        const val EXTRA_SOURCE_URI = "sourceUri"
        const val EXTRA_RAMP_SEC = "rampSec"
        const val EXTRA_VIBRATE = "vibrate"
        const val EXTRA_SNOOZE_COUNT = "snoozeCount"
        const val EXTRA_MAX_SNOOZES = "maxSnoozes"
        const val EXTRA_SNOOZE_MIN = "snoozeMin"
        const val EXTRA_TEST = "test"
        const val EXTRA_LOCKED = "locked"
        const val EXTRA_SNOOZE_LABEL = "snoozeLabel"
        const val EXTRA_STOP_LABEL = "stopLabel"

        private val _ringing = MutableStateFlow<RingingAlarm?>(null)
        /** Process-wide ringing state for [AlarmActivity] / the UI. */
        val ringing: StateFlow<RingingAlarm?> = _ringing.asStateFlow()

        /** Set by AppContainer: reports `AlarmControl(SpotifyFellBack)`. */
        @Volatile var fallbackReporter: ((String) -> Unit)? = null

        fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent.setClass(context, AlarmRingingService::class.java).setAction(ACTION_START))
        }

        @Volatile private var instance: AlarmRingingService? = null

        /** Stops ringing (StopAlarm effect). Uses the live instance: no service start from the background. */
        fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
            instance?.let { it.handler.post { it.stopRinging(); it.stopSelf() } }
        }
    }
}

/** D7 decision (JVM-tested): open [AlarmActivity] ourselves only while a DayCue Activity is visible. */
object AlarmScreenLaunch {
    fun startDirectly(appVisible: Boolean, locked: Boolean): Boolean = appVisible && !locked
}

/**
 * ALM-2 seam for streaming alarm sources (Spotify is out of scope for now). [start] returns true only if
 * playback actually started; otherwise the service plays the bundled local tone.
 */
interface AlarmMusicPlayer {
    fun start(context: Context, sourceUri: String?): Boolean
    fun stop()

    /**
     * Asynchronous source (Spotify). Return true if this player takes the source: the service then rings the local
     * tone immediately and silences it only on [Callbacks.onConfirmed]; [Callbacks.onLost] (failure or playback
     * stopped later) brings the tone back. The reason is published by the player (integrations/spotify
     * `AlarmMusicStatus`, facade `alarmMusic`). Default: not handled, the synchronous [start] path is used.
     */
    fun startAsync(context: Context, alarmId: String, sourceUri: String, callbacks: Callbacks): Boolean = false

    interface Callbacks {
        fun onConfirmed()
        fun onLost()
    }

    object NoOp : AlarmMusicPlayer {
        override fun start(context: Context, sourceUri: String?) = false
        override fun stop() = Unit
    }

    companion object { @Volatile var current: AlarmMusicPlayer = NoOp }
}
