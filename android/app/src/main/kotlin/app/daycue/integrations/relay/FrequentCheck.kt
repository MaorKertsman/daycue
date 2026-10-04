package app.daycue.integrations.relay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.daycue.DayCueApplication
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.config.SessionStart
import app.daycue.domain.engine.EngineState
import app.daycue.domain.time.TimeWindow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Opt-in "frequent check" (ARCHITECTURE 3.5): poll the relay every few minutes, but only while the engine
 * believes the phone is at a work/study-enabled saved place, inside the permitted session hours and days.
 */
object FrequentCheckPolicy {
    fun shouldPoll(cfg: DayCueConfig, state: EngineState, s: RelaySettings, now: Instant, zone: ZoneId): Boolean {
        if (!s.enabled || !s.frequentCheck || !s.useCompanionActivity) return false
        val place = state.context.place.value
        if (place.kind != PlaceKind.Saved) return false
        val p = cfg.place(place.placeId ?: return false) ?: return false
        if (p.sessionStart == SessionStart.Off && p.allowedActivities.isEmpty()) return false
        val rules = cfg.contextRules.sessions
        val local = now.atZone(zone)
        val days = rules.permittedDays ?: cfg.settings.workDays
        return local.dayOfWeek in days && inWindow(rules.permittedHours, local.toLocalTime())
    }

    fun inWindow(w: TimeWindow, t: java.time.LocalTime): Boolean = when {
        w.wholeDay -> true
        w.crossesMidnight -> t >= w.start || t < w.end
        else -> t >= w.start && t < w.end
    }
}

/**
 * Chain of inexact `setAndAllowWhileIdle` alarms (no exact-alarm privilege used; the engine's single exact
 * wake is untouched). In Doze the OS may defer each to roughly 9 minutes or more, so the real cadence is
 * "every few minutes while the screen is on or the device is awake, slower when dozing".
 */
class FrequentCheckScheduler(private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, FrequentCheckReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scheduleNext(minutes: Int) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pending())
    }

    fun cancel() = am.cancel(pending())

    private companion object { const val REQUEST = 0x4C43; const val ACTION = "app.daycue.relay.FREQUENT_CHECK" }
}

/** Alarm tick: re-evaluate the policy, re-arm if still applicable, and hand the network work to WorkManager. */
class FrequentCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as DayCueApplication).container
        val pr = goAsync()
        container.scope.launch {
            try { container.relay.onFrequentTick() } finally { pr.finish() }
        }
    }
}
