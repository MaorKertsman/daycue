package app.daycue.integrations.spotify

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import app.daycue.AppContainer
import app.daycue.BuildConfig
import app.daycue.delivery.AlarmMusicPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * Wiring for the Spotify alarm source. Without the SDK AAR (the default build, see docs/setup/SPOTIFY.md) the
 * player still registers: a Spotify alarm then rings the local tone and reports [SpotifyFailure.SdkNotBundled].
 */
class SpotifyIntegration(private val c: AppContainer) {
    val status = AlarmMusicStatus()
    val alarmMusic: StateFlow<AlarmMusicState> get() = status.state

    private val ctx: Context get() = c.app

    val player: SpotifyAlarmMusicPlayer by lazy {
        SpotifyAlarmMusicPlayer(
            scope = c.scope, status = status,
            remoteFactory = { createRemote() },
            installed = { isSpotifyInstalled(ctx) },
            online = { isOnline(ctx) },
            timeoutMs = { alarmId -> (c.host.snapshot.value?.config?.alarm(alarmId)?.spotifyStartTimeoutSec ?: 10) * 1000L },
        )
    }

    /** Registers the player with the ringing service. Called from the application's onCreate. */
    fun install() { AlarmMusicPlayer.current = player }

    private fun createRemote(): SpotifyRemote? {
        if (!BuildConfig.SPOTIFY_SDK) return null
        return runCatching {
            Class.forName("app.daycue.integrations.spotify.AppRemoteSpotifyRemote")
                .getDeclaredConstructor(Context::class.java, String::class.java, String::class.java)
                .newInstance(ctx, BuildConfig.SPOTIFY_CLIENT_ID, REDIRECT_URI) as SpotifyRemote
        }.getOrNull()
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        /** Register exactly this redirect URI on the Spotify dashboard. */
        const val REDIRECT_URI = "app-daycue://spotify-callback"

        fun isSpotifyInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0); true }.getOrDefault(false)

        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        /** The system screen/app that performs [action]; the alarm screen starts it. Null when nothing to open. */
        fun recoveryIntent(context: Context, action: RecoveryAction): Intent? = when (action) {
            RecoveryAction.InstallSpotify -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            RecoveryAction.AuthorizeSpotify, RecoveryAction.OpenSpotify, RecoveryAction.CheckAccount ->
                context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            RecoveryAction.CheckNetwork -> Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            RecoveryAction.Retry, RecoveryAction.None -> null
        }

    }
}
