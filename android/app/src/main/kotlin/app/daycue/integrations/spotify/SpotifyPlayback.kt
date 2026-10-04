package app.daycue.integrations.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts and watches Spotify playback. Success is decided only from player state, never from a call
 * returning or an app launching: the state must show the requested URI (as track or context), not paused,
 * with the position advancing between two samples. Anything else within the timeout is a typed failure.
 */
class SpotifyPlayback(
    private val remote: SpotifyRemote,
    private val verifyWindowMs: Long = 1_200,
    private val minAdvanceMs: Long = 500,
    private val pollMs: Long = 250,
) {
    sealed interface Attempt {
        data object Confirmed : Attempt
        data class Failed(val failure: SpotifyFailure, val detail: String? = null) : Attempt
    }

    suspend fun attempt(uri: String, timeoutMs: Long, interactive: Boolean = false): Attempt {
        var lastError: SpotifyRemoteException? = null
        val r = withTimeoutOrNull(timeoutMs) {
            try {
                remote.connect(interactive)
                remote.play(uri)
                awaitConfirmed(uri)
            } catch (e: SpotifyRemoteException) {
                lastError = e
                Attempt.Failed(e.failure, e.message)
            }
        }
        return r ?: Attempt.Failed(SpotifyFailure.Timeout, "no confirmed playback within ${timeoutMs} ms")
    }

    private suspend fun awaitConfirmed(uri: String): Attempt {
        while (true) {
            val first = remote.playerState()
            if (matches(first, uri) && !first.isPaused) {
                delay(verifyWindowMs)
                val second = remote.playerState()
                if (matches(second, uri) && !second.isPaused && (second.positionMs - first.positionMs >= minAdvanceMs || second.trackUri != first.trackUri)) return Attempt.Confirmed
            } else delay(pollMs)
        }
    }

    /**
     * Returns when playback has stopped for [graceMs] (paused, a different source, or the connection lost) so the
     * caller can bring the local tone back. Cancel the calling coroutine to stop watching.
     */
    suspend fun watchUntilStopped(uri: String, pollEveryMs: Long = 1_000, graceMs: Long = 3_000): SpotifyFailure? {
        var bad = 0L
        while (true) {
            delay(pollEveryMs)
            val ok = try { remote.playerState().let { matches(it, uri) && !it.isPaused } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            bad = if (ok) 0 else bad + pollEveryMs
            if (bad >= graceMs) return SpotifyFailure.RemoteUnavailable
        }
    }

    companion object {
        fun matches(s: RemotePlayerState, uri: String) = s.trackUri == uri || s.contextUri == uri
    }
}
