package app.daycue.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.daycue.domain.engine.Event
import app.daycue.system.runAsync

/**
 * The engine's single next-wake alarm fired: feed `Tick`. Runs inside `goAsync()` (well under the 10 s
 * budget); ringing and routine playback move into foreground services started from the effects, which
 * an exact alarm is allowed to do from the background. Speech queued by this tick is given the rest of
 * the budget so the process isn't frozen mid-utterance.
 */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE) return
        val armedFor = intent.getLongExtra(AndroidWakeScheduler.EXTRA_AT_MS, -1L)
        val precision = intent.getStringExtra(AndroidWakeScheduler.EXTRA_PRECISION)
        val lateMs = if (armedFor > 0) System.currentTimeMillis() - armedFor else -1
        Log.i(TAG, "wake alarm fired (precision=$precision, late=${lateMs}ms)")
        runAsync(context, "tick") { app ->
            app.host.dispatch(Event.Tick)
            app.speech.awaitIdle(timeoutMs = 7_000)
        }
    }

    companion object {
        const val ACTION_WAKE = "app.daycue.action.WAKE"
        private const val TAG = "DayCue"
    }
}
