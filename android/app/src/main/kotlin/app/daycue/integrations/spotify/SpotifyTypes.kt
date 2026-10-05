package app.daycue.integrations.spotify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why Spotify playback could not be confirmed. The local alarm tone covers every one of these. */
enum class SpotifyFailure {
    /** The Spotify app is not installed. */
    NotInstalled,
    /** Not signed in to Spotify, DayCue not authorized for App Remote, or the authorization expired. */
    NotAuthorized,
    /** No network connection (App Remote needs Spotify to be online). */
    NoNetwork,
    /** The remote connection to the Spotify app failed or dropped (Spotify not running / not startable from the background / no usable device). */
    RemoteUnavailable,
    /** Spotify refused playback for this account (for example Premium required). */
    AccountRestriction,
    /** Playback was not confirmed within the alarm's start timeout. */
    Timeout,
    /** This build does not include the Spotify SDK (see docs/setup/SPOTIFY.md). */
    SdkNotBundled,
    Unknown,
}

/** The one thing the owner can do about a failure; the alarm screen maps it to a button. */
enum class RecoveryAction { InstallSpotify, AuthorizeSpotify, CheckNetwork, OpenSpotify, CheckAccount, Retry, None }

fun SpotifyFailure.recovery(): RecoveryAction = when (this) {
    SpotifyFailure.NotInstalled -> RecoveryAction.InstallSpotify
    SpotifyFailure.NotAuthorized -> RecoveryAction.AuthorizeSpotify
    SpotifyFailure.NoNetwork -> RecoveryAction.CheckNetwork
    SpotifyFailure.RemoteUnavailable, SpotifyFailure.Timeout -> RecoveryAction.OpenSpotify
    SpotifyFailure.AccountRestriction -> RecoveryAction.CheckAccount
    SpotifyFailure.SdkNotBundled -> RecoveryAction.None
    SpotifyFailure.Unknown -> RecoveryAction.Retry
}

class SpotifyRemoteException(val failure: SpotifyFailure, message: String? = null, cause: Throwable? = null) : Exception(message ?: failure.name, cause)

data class RemotePlayerState(
    val isPaused: Boolean,
    val trackUri: String?,
    val contextUri: String?,
    val positionMs: Long,
)

/** Thin seam over Spotify App Remote (a fake in unit tests; the real adapter needs the SDK AAR, see SPOTIFY.md). */
interface SpotifyRemote {
    /** Connects; [interactive] lets the SDK show its authorization view (only from a visible screen). Throws [SpotifyRemoteException]. */
    suspend fun connect(interactive: Boolean)
    /** Asks Spotify to play [uri]. Returning does NOT mean audio is playing. Throws [SpotifyRemoteException]. */
    suspend fun play(uri: String)
    /** Current player state, fetched fresh. Throws [SpotifyRemoteException] when disconnected. */
    suspend fun playerState(): RemotePlayerState
    suspend fun pause()
    fun disconnect()
}

/** What the alarm screen shows about the streaming source of the ringing alarm. */
sealed interface AlarmMusicState {
    data object Idle : AlarmMusicState
    data object Connecting : AlarmMusicState
    /** Playback was confirmed from player state; the local tone is silent. */
    data object Playing : AlarmMusicState
    /** The local tone is ringing instead. [failure] is why; [stoppedAfterPlaying] = it had started and then stopped. */
    data class FellBack(val failure: SpotifyFailure, val recovery: RecoveryAction, val stoppedAfterPlaying: Boolean = false) : AlarmMusicState
}

/** Connection state of the Spotify alarm source as Settings shows it. */
enum class SpotifyConnection {
    /** This build cannot use Spotify (no SDK AAR or no client id). */
    Unavailable,
    /** Usable build, but the Spotify app is not installed. */
    NotInstalled,
    /** Ready; nothing is connected now (App Remote connects only while an alarm rings or on "Try again"). */
    Idle,
    Connecting,
    Playing,
    /** The last attempt fell back to the local tone ([SpotifyAvailability.lastFailure] says why). */
    FellBack,
}

/**
 * APP_API §11 `facade.spotify`: whether the Spotify alarm source exists in this build ([available]), can be used
 * on this phone ([enabled]: available and installed), and its connection state.
 */
data class SpotifyAvailability(
    val sdkBundled: Boolean,
    val clientIdConfigured: Boolean,
    val installed: Boolean,
    val connection: SpotifyConnection,
    val lastFailure: SpotifyFailure? = null,
) {
    val available: Boolean get() = sdkBundled && clientIdConfigured
    val enabled: Boolean get() = available && installed

    companion object {
        /** Pure (JVM-tested). */
        fun of(sdkBundled: Boolean, clientIdConfigured: Boolean, installed: Boolean, music: AlarmMusicState): SpotifyAvailability {
            val connection = when {
                !sdkBundled || !clientIdConfigured -> SpotifyConnection.Unavailable
                !installed -> SpotifyConnection.NotInstalled
                else -> when (music) {
                    AlarmMusicState.Idle -> SpotifyConnection.Idle
                    AlarmMusicState.Connecting -> SpotifyConnection.Connecting
                    AlarmMusicState.Playing -> SpotifyConnection.Playing
                    is AlarmMusicState.FellBack -> SpotifyConnection.FellBack
                }
            }
            return SpotifyAvailability(sdkBundled, clientIdConfigured, installed, connection, (music as? AlarmMusicState.FellBack)?.failure)
        }
    }
}

class AlarmMusicStatus {
    private val s = MutableStateFlow<AlarmMusicState>(AlarmMusicState.Idle)
    val state: StateFlow<AlarmMusicState> = s.asStateFlow()
    fun set(v: AlarmMusicState) { s.value = v }
}
