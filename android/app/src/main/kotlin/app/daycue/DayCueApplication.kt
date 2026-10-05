package app.daycue

import android.app.Application
import android.os.UserManager
import android.util.Log
import app.daycue.delivery.AlarmRingingService
import app.daycue.domain.engine.AlarmAction
import app.daycue.domain.engine.Event
import kotlinx.coroutines.launch

/**
 * Process entry point: builds the [AppContainer], creates notification channels once, and replays
 * `BootCompleted` into the engine on every process start (DOMAIN.md: "send on boot and on every process
 * start"; a no-op when nothing changed), followed by `NotificationsObserved` so notifications the system
 * lost are re-posted (VALIDATION D1). Before the first unlock (direct boot) only the device-protected
 * fallback runs and Room is not touched.
 */
class DayCueApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching { container.channels.ensureBase() }.onFailure { Log.w(AppContainer.TAG, "channel setup failed", it) }
        if (!getSystemService(UserManager::class.java).isUserUnlocked) {
            Log.i(AppContainer.TAG, "process start before first unlock: engine not started")
            return
        }
        AlarmRingingService.fallbackReporter = { alarmId ->
            container.scope.launch { container.host.dispatch(Event.AlarmControl(alarmId, AlarmAction.SpotifyFellBack)) }
        }
        container.scope.launch {
            // One coroutine, in order: reboot detection + recompute, re-post lost notifications (D1), then the language
            // reconcile (D8; API 33+ can read the per-app locale before any Activity exists).
            container.bootAndReconcile()
            if (android.os.Build.VERSION.SDK_INT >= 33) container.language.reconcileOnStart()
            container.language.followConfig()
        }
        container.relay.start() // relay triggers (no-op until paired)
        container.spotify.install() // Spotify alarm source (the local tone always rings first)
        lastLocales = resources.configuration.locales.toLanguageTags()
    }

    private var lastLocales: String? = null

    /** A per-app language picked in system settings while the process runs reaches the config at once (D8). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val tags = newConfig.locales.toLanguageTags()
        if (lastLocales == null || tags == lastLocales) return
        lastLocales = tags
        if (getSystemService(UserManager::class.java).isUserUnlocked) container.scope.launch { container.language.reconcileOnStart() }
    }
}
