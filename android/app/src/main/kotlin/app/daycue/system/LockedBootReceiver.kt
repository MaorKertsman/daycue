package app.daycue.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import app.daycue.data.boot.BootEntry
import app.daycue.data.boot.LockedBootAlarms
import app.daycue.domain.config.DayCueJson

/**
 * `LOCKED_BOOT_COMPLETED` (direct-boot aware): before the first unlock Room is unreadable, so arm the
 * device-protected boot snapshot's next alarm/dose (ANDROID.md §5.4). Never touches the engine.
 */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        Log.i("DayCue", "LOCKED_BOOT_COMPLETED (unlocked=$unlocked)")
        if (!unlocked) LockedBootAlarms.armNext(context)
    }
}

/** The locked-boot fallback alarm fired. Ignored once the user has unlocked (the engine owns waking then). */
class LockedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        val entry = intent.getStringExtra(LockedBootAlarms.EXTRA_ENTRY)?.let {
            runCatching { DayCueJson.decodeFromString(BootEntry.serializer(), it) }.getOrNull()
        }
        Log.i("DayCue", "locked-boot alarm fired (unlocked=$unlocked, kind=${entry?.kind})")
        if (unlocked || entry == null) return
        LockedBootAlarms.fire(context, entry)
    }

    companion object { const val ACTION = "app.daycue.action.LOCKED_WAKE" }
}
