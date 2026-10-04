package app.daycue.integrations.spotify

import android.content.Context
import app.daycue.delivery.AlarmMusicPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Spotify as an alarm source behind [AlarmMusicPlayer] (ALM-2). Contract with the ringing service:
 * the local tone is already ringing when [startAsync] returns; we call [AlarmMusicPlayer.Callbacks.onConfirmed]
 * only after [SpotifyPlayback] confirmed playback from player state, and [AlarmMusicPlayer.Callbacks.onLost]
 * on any failure or when playback stops later. The reason and the recovery action go to [status].
 *
 * Honest limit: Spotify's own docs say App Remote needs your app or Spotify in the foreground; an alarm that
 * fires from the background may be unable to connect. That ends as [SpotifyFailure.RemoteUnavailable] or
 * [SpotifyFailure.Timeout] with the tone ringing, never as silence.
 */
class SpotifyAlarmMusicPlayer(
    private val scope: CoroutineScope,
    private val status: AlarmMusicStatus,
    private val remoteFactory: () -> SpotifyRemote?,
    private val installed: () -> Boolean,
    private val online: () -> Boolean,
    private val timeoutMs: (alarmId: String) -> Long,
) : AlarmMusicPlayer {

    private var job: Job? = null
    private var remote: SpotifyRemote? = null
    private var last: Triple<String, String, AlarmMusicPlayer.Callbacks>? = null

    override fun start(context: Context, sourceUri: String?) = false
    override fun stop() { teardown(pause = true); status.set(AlarmMusicState.Idle) }

    override fun startAsync(context: Context, alarmId: String, sourceUri: String, callbacks: AlarmMusicPlayer.Callbacks): Boolean = begin(alarmId, sourceUri, callbacks)

    internal fun begin(alarmId: String, sourceUri: String, callbacks: AlarmMusicPlayer.Callbacks): Boolean {
        last = Triple(alarmId, sourceUri, callbacks)
        launch(alarmId, sourceUri, callbacks, interactive = false)
        return true
    }

    /** Alarm screen "Try again" / "Authorize": from a visible screen, so the SDK may show its auth view. */
    fun retry(interactive: Boolean) {
        val (alarmId, uri, cb) = last ?: return
        teardown(pause = false)
        launch(alarmId, uri, cb, interactive)
    }

    private fun launch(alarmId: String, uri: String, cb: AlarmMusicPlayer.Callbacks, interactive: Boolean) {
        job?.cancel()
        job = scope.launch { run(alarmId, uri, cb, interactive) }
    }

    private suspend fun run(alarmId: String, uri: String, cb: AlarmMusicPlayer.Callbacks, interactive: Boolean) {
        status.set(AlarmMusicState.Connecting)
        fun fail(f: SpotifyFailure, stopped: Boolean = false) {
            status.set(AlarmMusicState.FellBack(f, f.recovery(), stoppedAfterPlaying = stopped))
            cb.onLost()
        }
        if (!installed()) return fail(SpotifyFailure.NotInstalled)
        if (!online()) return fail(SpotifyFailure.NoNetwork)
        val r = remoteFactory() ?: return fail(SpotifyFailure.SdkNotBundled)
        remote = r
        try {
            val playback = SpotifyPlayback(r)
            when (val a = playback.attempt(uri, timeoutMs(alarmId), interactive)) {
                is SpotifyPlayback.Attempt.Failed -> return fail(a.failure)
                SpotifyPlayback.Attempt.Confirmed -> {
                    status.set(AlarmMusicState.Playing)
                    cb.onConfirmed()
                }
            }
            val ended = playback.watchUntilStopped(uri) // returns only when playback stopped
            fail(ended ?: SpotifyFailure.RemoteUnavailable, stopped = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(SpotifyFailure.Unknown)
        }
    }

    private fun teardown(pause: Boolean) {
        job?.cancel(); job = null
        val r = remote; remote = null
        if (r != null) scope.launch {
            if (pause) runCatching { r.pause() }
            runCatching { r.disconnect() }
        }
    }
}
