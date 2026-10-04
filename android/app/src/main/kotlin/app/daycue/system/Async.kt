package app.daycue.system

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import app.daycue.AppContainer
import app.daycue.DayCueApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Runs [block] off the main thread while keeping the broadcast alive via `goAsync()`. The timeout keeps
 * us inside the 10 s receiver budget; whatever happens, `finish()` is called exactly once.
 */
fun BroadcastReceiver.runAsync(context: Context, label: String, timeoutMs: Long = 9_000, block: suspend (AppContainer) -> Unit) {
    val pending = goAsync()
    val app = (context.applicationContext as DayCueApplication).container
    app.scope.launch {
        try {
            withTimeout(timeoutMs) { block(app) }
        } catch (t: Throwable) {
            Log.w("DayCue", "receiver work '$label' failed or timed out", t)
        } finally {
            pending.finish()
        }
    }
}
