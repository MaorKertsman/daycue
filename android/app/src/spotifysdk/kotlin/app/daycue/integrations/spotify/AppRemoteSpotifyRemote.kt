package app.daycue.integrations.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.SpotifyRemoteServiceException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real [SpotifyRemote] over Spotify App Remote SDK 0.8.0. Compiled only when the AAR is in `app/libs/`
 * (docs/setup/SPOTIFY.md). **Unverified against a live Spotify account** (no credentials in this repo).
 * `PlayerState` has no context URI, so the playing context comes from `subscribeToPlayerContext`.
 */
class AppRemoteSpotifyRemote(private val context: Context, private val clientId: String, private val redirectUri: String) : SpotifyRemote {
    private var remote: SpotifyAppRemote? = null
    @Volatile private var contextUri: String? = null

    override suspend fun connect(interactive: Boolean) {
        val params = ConnectionParams.Builder(clientId).setRedirectUri(redirectUri).showAuthView(interactive).build()
        val r = suspendCancellableCoroutine<SpotifyAppRemote> { cont ->
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) { if (cont.isActive) cont.resume(r) else SpotifyAppRemote.disconnect(r) }
                override fun onFailure(t: Throwable) { if (cont.isActive) cont.resumeWithException(SpotifyRemoteException(map(t), t.message, t)) }
            })
        }
        remote = r
        r.playerApi.subscribeToPlayerContext().setEventCallback { contextUri = it.uri }
    }

    override suspend fun play(uri: String) {
        awaitCall(api().play(uri))
    }

    override suspend fun playerState(): RemotePlayerState {
        val s: PlayerState = awaitCall(api().playerState)
        return RemotePlayerState(s.isPaused, s.track?.uri, contextUri, s.playbackPosition)
    }

    override suspend fun pause() { runCatching { awaitCall(api().pause()) } }

    override fun disconnect() { remote?.let { SpotifyAppRemote.disconnect(it) }; remote = null }

    private fun api() = (remote ?: throw SpotifyRemoteException(SpotifyFailure.RemoteUnavailable, "not connected")).playerApi

    private suspend fun <T> awaitCall(call: CallResult<T>): T = suspendCancellableCoroutine { cont ->
        call.setResultCallback { data -> if (cont.isActive) cont.resume(data) }
        call.setErrorCallback { t -> if (cont.isActive) cont.resumeWithException(SpotifyRemoteException(map(t), t.message, t)) }
        cont.invokeOnCancellation { call.cancel() }
    }

    companion object {
        /** Error classes from the SDK's ERRORS.md. */
        fun map(t: Throwable): SpotifyFailure = when (t) {
            is CouldNotFindSpotifyApp -> SpotifyFailure.NotInstalled
            is NotLoggedInException, is UserNotAuthorizedException, is AuthenticationFailedException -> SpotifyFailure.NotAuthorized
            is OfflineModeException -> SpotifyFailure.NoNetwork
            is SpotifyDisconnectedException, is SpotifyConnectionTerminatedException, is SpotifyRemoteServiceException -> SpotifyFailure.RemoteUnavailable
            is UnsupportedFeatureVersionException -> SpotifyFailure.Unknown
            else -> {
                val m = t.message?.lowercase().orEmpty()
                if ("premium" in m || "restrict" in m || "not allowed" in m) SpotifyFailure.AccountRestriction else SpotifyFailure.Unknown
            }
        }
    }
}
