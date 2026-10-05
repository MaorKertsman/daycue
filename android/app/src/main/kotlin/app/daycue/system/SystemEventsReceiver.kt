package app.daycue.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.daycue.data.boot.LockedBootAlarms
import app.daycue.domain.engine.Event

/**
 * System broadcasts that invalidate the armed wake or the time base (ANDROID.md §5.3). All are on the
 * implicit-broadcast exception list. Nothing here decides policy: each maps to one engine event and the
 * engine recomputes; `EngineHost` re-arms the single alarm after every reduce.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val event = when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Event.BootCompleted
            Intent.ACTION_TIME_CHANGED -> Event.TimeChanged
            Intent.ACTION_TIMEZONE_CHANGED -> Event.TimezoneChanged
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Event.Tick
            Intent.ACTION_LOCALE_CHANGED -> null
            else -> return
        }
        Log.i("DayCue", "system broadcast $action -> ${event?.let { it::class.simpleName } ?: "channel rename"}")
        runAsync(context, action) { app ->
            if (action == Intent.ACTION_BOOT_COMPLETED) LockedBootAlarms.cancelLockedAlarm(context) // unlocked: the engine owns waking again
            if (event != null) app.host.dispatch(event) else app.channels.refreshNames()
            // The process may have started before the first unlock (direct boot) and skipped the start-up reconcile:
            // after boot no notification survives, so re-post what the engine still lists as visible (D1).
            if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) app.reconcileNotifications()
            if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) app.housekeeping()
        }
    }
}
