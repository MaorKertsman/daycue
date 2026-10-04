package app.daycue.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.daycue.MainActivity
import app.daycue.domain.engine.WakePrecision
import app.daycue.engine.ArmResult
import app.daycue.engine.UsedApi
import app.daycue.engine.WakeScheduler
import java.time.Instant

/**
 * Maps the engine's single next wake to AlarmManager (ANDROID.md §5.1):
 * `AlarmClock` -> `setAlarmClock`, `Exact` -> `setExactAndAllowWhileIdle`, `Inexact` -> `setAndAllowWhileIdle`.
 * Without exact-alarm access (API 31+ `canScheduleExactAlarms() == false`) everything falls back to
 * `setAndAllowWhileIdle` and the result is flagged `degraded` (readiness shows it).
 *
 * One PendingIntent with a fixed request code: arming always replaces the previous alarm, whatever API
 * armed it, so there is never more than one pending engine wake.
 */
class AndroidWakeScheduler(private val context: Context) : WakeScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun arm(at: Instant, precision: WakePrecision): ArmResult {
        val pi = wakeIntent(context, at, precision)
        val ms = at.toEpochMilli()
        val exactOk = ExactAlarmAccess.canScheduleExact(context)
        val used = try {
            when {
                !exactOk -> { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi); UsedApi.AllowWhileIdle }
                precision == WakePrecision.AlarmClock -> {
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(ms, showIntent(context)), pi); UsedApi.AlarmClock
                }
                precision == WakePrecision.Exact -> { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi); UsedApi.ExactAllowWhileIdle }
                else -> { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi); UsedApi.AllowWhileIdle }
            }
        } catch (e: SecurityException) {
            // Exact access revoked between the check and the call (API 31-32): degrade, never drop the wake.
            Log.w(TAG, "exact alarm refused, falling back to inexact", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            UsedApi.AllowWhileIdle
        }
        val degraded = precision != WakePrecision.Inexact && used == UsedApi.AllowWhileIdle
        return ArmResult(at, precision, used, degraded)
    }

    override fun cancel() {
        alarmManager.cancel(wakeIntent(context, null, null))
    }

    companion object {
        private const val TAG = "DayCue"
        const val REQUEST_CODE = 1001
        const val EXTRA_AT_MS = "app.daycue.extra.WAKE_AT_MS"
        const val EXTRA_PRECISION = "app.daycue.extra.WAKE_PRECISION"

        fun wakeIntent(context: Context, at: Instant?, precision: WakePrecision?): PendingIntent {
            val i = Intent(context, WakeAlarmReceiver::class.java).setAction(WakeAlarmReceiver.ACTION_WAKE)
            if (at != null) i.putExtra(EXTRA_AT_MS, at.toEpochMilli())
            if (precision != null) i.putExtra(EXTRA_PRECISION, precision.name)
            return PendingIntent.getBroadcast(context, REQUEST_CODE, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Tapping the system "next alarm" opens the app. */
        fun showIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 1002, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    }
}

object ExactAlarmAccess {
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
