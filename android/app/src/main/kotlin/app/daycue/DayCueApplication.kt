package app.daycue

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.util.Log
import app.daycue.delivery.AlarmRingingService
import app.daycue.devtools.DevTools
import app.daycue.domain.engine.AlarmAction
import app.daycue.domain.engine.Event
import kotlinx.coroutines.launch

/**
 * Process entry point: builds the [AppContainer], creates notification channels once, and replays
 * `BootCompleted` into the engine on every process start (DOMAIN.md: "send on boot and on every process
 * start"; a no-op when nothing changed). Before the first unlock (direct boot) only the device-protected
 * fallback runs and Room is not touched.
 */
class DayCueApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching { container.channels.ensureBase() }.onFailure { Log.w(AppContainer.TAG, "channel setup failed", it) }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        DevTools.setEnabled(this, debuggable)
        if (!getSystemService(UserManager::class.java).isUserUnlocked) {
            Log.i(AppContainer.TAG, "process start before first unlock: engine not started")
            return
        }
        AlarmRingingService.fallbackReporter = { alarmId ->
            container.scope.launch { container.host.dispatch(Event.AlarmControl(alarmId, AlarmAction.SpotifyFellBack)) }
        }
        container.scope.launch { container.host.dispatch(Event.BootCompleted) }
    }
}
