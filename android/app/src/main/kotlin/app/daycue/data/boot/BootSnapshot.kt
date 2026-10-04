package app.daycue.data.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.daycue.R
import app.daycue.domain.Clock
import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.engine.EngineState
import app.daycue.domain.query.Queries
import app.daycue.delivery.AlarmRingingService
import app.daycue.engine.BootSnapshotWriter
import app.daycue.system.LockedAlarmReceiver
import app.daycue.scheduling.AndroidWakeScheduler
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Locked-boot fallback (ANDROID.md §5.4). After each reduce, the next few alarm-clock-class items
 * (morning alarms, medication doses) are written to **device-protected** storage, with no user text:
 * kind, instant, local time and the alarm's ring settings. `LOCKED_BOOT_COMPLETED` (before the first
 * unlock, when Room is unreadable) arms the earliest one; when it fires while still locked, an alarm
 * rings with a generic title and a dose posts a generic "Medication reminder". After unlock,
 * `BOOT_COMPLETED` cancels this fallback and the engine takes over.
 */
@Serializable
data class BootEntry(
    val kind: String,
    val atMs: Long,
    val time: String,
    val alarmId: String? = null,
    val snoozeMin: Int = 9,
    val rampSec: Int = 30,
    val vibrate: Boolean = true,
)

class BootSnapshotStore(private val context: Context, private val clock: Clock) : BootSnapshotWriter {
    private var lastKey: Any? = null

    override fun write(state: EngineState, config: DayCueConfig) {
        val key = listOf(config.version, state.alarms, state.medication.slots.mapValues { it.value.status }, clock.now().epochSecond / 3600)
        if (key == lastKey) return
        lastKey = key
        val view = Queries.todayView(config, state, clock, limit = 100)
        val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(clock.zone())
        val entries = view.upcoming.filter { it.at != null && (it.type == CueType.Alarm || it.type == CueType.Medication) }
            .sortedBy { it.at }.take(MAX_ENTRIES).map { u ->
                val alarm = if (u.type == CueType.Alarm) config.alarm(u.itemKey.removePrefix("alarm:")) else null
                BootEntry(
                    kind = if (u.type == CueType.Alarm) KIND_ALARM else KIND_MEDICATION,
                    atMs = u.at!!.toEpochMilli(), time = hhmm.format(u.at),
                    alarmId = alarm?.id, snoozeMin = alarm?.snoozeMin ?: 9, rampSec = alarm?.volumeRampSec ?: 30, vibrate = alarm?.vibrate ?: true,
                )
            }
        prefs(context).edit().putString(KEY_ENTRIES, DayCueJson.encodeToString(ListSerializer(BootEntry.serializer()), entries)).apply()
    }

    companion object {
        const val KIND_ALARM = "alarm"
        const val KIND_MEDICATION = "medication"
        private const val MAX_ENTRIES = 6
        private const val KEY_ENTRIES = "entries"

        fun prefs(context: Context): SharedPreferences =
            context.createDeviceProtectedStorageContext().getSharedPreferences("boot_snapshot", Context.MODE_PRIVATE)

        fun read(context: Context): List<BootEntry> = runCatching {
            prefs(context).getString(KEY_ENTRIES, null)?.let { DayCueJson.decodeFromString(ListSerializer(BootEntry.serializer()), it) }
        }.getOrNull().orEmpty()
    }
}

/** Arming and firing of the locked-boot fallback alarm. Uses device-protected storage only. */
object LockedBootAlarms {
    private const val TAG = "DayCue"
    private const val REQUEST = 2001
    const val EXTRA_ENTRY = "entry"

    private fun pi(context: Context, entry: BootEntry?): PendingIntent {
        val i = Intent(context, LockedAlarmReceiver::class.java).setAction(LockedAlarmReceiver.ACTION)
        entry?.let { i.putExtra(EXTRA_ENTRY, DayCueJson.encodeToString(BootEntry.serializer(), it)) }
        return PendingIntent.getBroadcast(context, REQUEST, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Arms the earliest future entry with `setAlarmClock`. Returns it (null if none). */
    fun armNext(context: Context, now: Instant = Instant.now(), afterMs: Long = now.toEpochMilli()): BootEntry? {
        val next = BootSnapshotStore.read(context).filter { it.atMs > afterMs }.minByOrNull { it.atMs } ?: return null
        arm(context, next)
        return next
    }

    private fun arm(context: Context, e: BootEntry) {
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(e.atMs, AndroidWakeScheduler.showIntent(context)), pi(context, e)) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.atMs, pi(context, e)) }
        Log.i(TAG, "locked-boot fallback armed: ${e.kind} at ${Instant.ofEpochMilli(e.atMs)}")
    }

    fun snooze(context: Context, minutes: Int) {
        val e = BootEntry(BootSnapshotStore.KIND_ALARM, System.currentTimeMillis() + minutes * 60_000L, "", snoozeMin = minutes)
        arm(context, e)
    }

    fun cancelLockedAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pi(context, null))
        NotificationManagerCompat.from(context).cancel(LOCKED_TAG, 1)
    }

    /** The fallback fired while the user is still locked. */
    fun fire(context: Context, e: BootEntry) {
        val title: String
        if (e.kind == BootSnapshotStore.KIND_ALARM) {
            title = context.getString(R.string.dc_alarm_generic_title).replace("{time}", e.time)
            AlarmRingingService.start(context, Intent()
                .putExtra(AlarmRingingService.EXTRA_ALARM_ID, e.alarmId ?: "locked")
                .putExtra(AlarmRingingService.EXTRA_TITLE, title)
                .putExtra(AlarmRingingService.EXTRA_TIME, e.time)
                .putExtra(AlarmRingingService.EXTRA_RAMP_SEC, e.rampSec)
                .putExtra(AlarmRingingService.EXTRA_VIBRATE, e.vibrate)
                .putExtra(AlarmRingingService.EXTRA_SNOOZE_MIN, e.snoozeMin)
                .putExtra(AlarmRingingService.EXTRA_LOCKED, true)
                .putExtra(AlarmRingingService.EXTRA_SNOOZE_LABEL, context.getString(R.string.dc_action_snooze_alt))
                .putExtra(AlarmRingingService.EXTRA_STOP_LABEL, context.getString(R.string.dc_action_stop)))
        } else {
            title = context.getString(R.string.dc_cue_medication_generic_title)
            val n = NotificationCompat.Builder(context, CueType.Medication.channelId)
                .setSmallIcon(R.drawable.ic_stat_daycue)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.dc_alarm_generic_body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // generic text only
                .build()
            @Suppress("MissingPermission")
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) NotificationManagerCompat.from(context).notify(LOCKED_TAG, 1, n)
        }
        Log.i(TAG, "locked-boot fallback fired: ${e.kind} (${e.time})")
        armNext(context, afterMs = e.atMs)
    }

    const val LOCKED_TAG = "locked"
}
