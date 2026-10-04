package app.daycue.integrations.spotify

import app.daycue.delivery.AlarmMusicPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val URI_PLAYLIST = "spotify:playlist:synthetic"
private const val URI_TRACK = "spotify:track:synthetic"

/** Scripted remote: the state is a function of virtual time so position can advance (or not). */
private class FakeRemote(private val now: () -> Long) : SpotifyRemote {
    var connectError: SpotifyFailure? = null
    var playError: SpotifyFailure? = null
    var stateError: SpotifyFailure? = null
    var behaviour: (Long) -> RemotePlayerState = { RemotePlayerState(true, null, null, 0) }
    var interactiveSeen: Boolean? = null
    var paused = false
    var disconnected = false
    override suspend fun connect(interactive: Boolean) { interactiveSeen = interactive; connectError?.let { throw SpotifyRemoteException(it) } }
    override suspend fun play(uri: String) { playError?.let { throw SpotifyRemoteException(it) } }
    override suspend fun playerState(): RemotePlayerState { stateError?.let { throw SpotifyRemoteException(it) }; return behaviour(now()) }
    override suspend fun pause() { paused = true }
    override fun disconnect() { disconnected = true }
}

private class Cb : AlarmMusicPlayer.Callbacks {
    val events = mutableListOf<String>()
    override fun onConfirmed() { events += "confirmed" }
    override fun onLost() { events += "lost" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyPlaybackTest {

    private fun playingFrom(startAt: Long, uri: String = URI_PLAYLIST, asContext: Boolean = true): (Long) -> RemotePlayerState = { t ->
        if (t < startAt) RemotePlayerState(true, null, null, 0)
        else RemotePlayerState(false, if (asContext) "spotify:track:t1" else uri, if (asContext) uri else null, t - startAt)
    }

    @Test fun confirmsOnlyFromAdvancingPlayerState() = runTest {
        val r = FakeRemote { currentTime }.apply { behaviour = playingFrom(500) }
        assertEquals(SpotifyPlayback.Attempt.Confirmed, SpotifyPlayback(r).attempt(URI_PLAYLIST, 10_000))
        val t = FakeRemote { currentTime }.apply { behaviour = playingFrom(500, URI_TRACK, asContext = false) }
        assertEquals("a track URI is matched on the track", SpotifyPlayback.Attempt.Confirmed, SpotifyPlayback(t).attempt(URI_TRACK, 10_000))
    }

    @Test fun launchingWithoutPlaybackIsNeverSuccess() = runTest {
        val r = FakeRemote { currentTime } // play() returns fine, state stays paused
        val a = SpotifyPlayback(r).attempt(URI_PLAYLIST, 10_000)
        assertEquals(SpotifyFailure.Timeout, (a as SpotifyPlayback.Attempt.Failed).failure)
        assertTrue("the timeout was honoured", currentTime in 10_000..10_500)
    }

    @Test fun otherAudioOrFrozenPositionIsNotConfirmation() = runTest {
        val other = FakeRemote { currentTime }.apply { behaviour = { RemotePlayerState(false, "spotify:track:other", "spotify:album:other", it) } }
        assertEquals(SpotifyFailure.Timeout, (SpotifyPlayback(other).attempt(URI_PLAYLIST, 5_000) as SpotifyPlayback.Attempt.Failed).failure)
        val frozen = FakeRemote { currentTime }.apply { behaviour = { RemotePlayerState(false, "spotify:track:t1", URI_PLAYLIST, 1234) } }
        assertEquals(SpotifyFailure.Timeout, (SpotifyPlayback(frozen).attempt(URI_PLAYLIST, 5_000) as SpotifyPlayback.Attempt.Failed).failure)
    }

    @Test fun everyConnectFailureReasonIsTypedAndHasARecovery() = runTest {
        for ((f, rec) in mapOf(
            SpotifyFailure.NotInstalled to RecoveryAction.InstallSpotify,
            SpotifyFailure.NotAuthorized to RecoveryAction.AuthorizeSpotify,
            SpotifyFailure.NoNetwork to RecoveryAction.CheckNetwork,
            SpotifyFailure.RemoteUnavailable to RecoveryAction.OpenSpotify,
            SpotifyFailure.AccountRestriction to RecoveryAction.CheckAccount,
            SpotifyFailure.SdkNotBundled to RecoveryAction.None,
        )) {
            val r = FakeRemote { currentTime }.apply { connectError = f }
            assertEquals(f, (SpotifyPlayback(r).attempt(URI_PLAYLIST, 10_000) as SpotifyPlayback.Attempt.Failed).failure)
            assertEquals(rec, f.recovery())
        }
        assertEquals(RecoveryAction.OpenSpotify, SpotifyFailure.Timeout.recovery())
        val p = FakeRemote { currentTime }.apply { playError = SpotifyFailure.AccountRestriction }
        assertEquals(SpotifyFailure.AccountRestriction, (SpotifyPlayback(p).attempt(URI_PLAYLIST, 10_000) as SpotifyPlayback.Attempt.Failed).failure)
    }

    @Test fun interactiveFlagReachesTheRemote() = runTest {
        val r = FakeRemote { currentTime }.apply { behaviour = playingFrom(0) }
        SpotifyPlayback(r).attempt(URI_PLAYLIST, 10_000, interactive = true)
        assertEquals(true, r.interactiveSeen)
    }

    @Test fun watchReturnsAfterGraceWhenPlaybackStops() = runTest {
        val r = FakeRemote { currentTime }.apply { behaviour = { t -> RemotePlayerState(t >= 4_000, "spotify:track:t1", URI_PLAYLIST, t) } }
        val f = SpotifyPlayback(r).watchUntilStopped(URI_PLAYLIST)
        assertEquals(SpotifyFailure.RemoteUnavailable, f)
        assertTrue("stops at about 4 s + 3 s grace", currentTime in 6_000..8_000)
    }

    @Test fun briefGlitchDoesNotCountAsStopped() = runTest {
        val r = FakeRemote { currentTime }.apply { behaviour = { t -> if (t in 3_000..4_500) RemotePlayerState(true, null, null, 0) else RemotePlayerState(false, "spotify:track:t1", URI_PLAYLIST, t) } }
        var done = false
        val job = launch { SpotifyPlayback(r).watchUntilStopped(URI_PLAYLIST); done = true }
        advanceTimeBy(20_000)
        assertFalse(done)
        job.cancel()
    }

    // ---- the alarm player: callbacks + status (the service keeps the tone until onConfirmed) ----------------

    private fun kotlinx.coroutines.test.TestScope.player(
        remote: FakeRemote?, installed: Boolean = true, online: Boolean = true, status: AlarmMusicStatus = AlarmMusicStatus(),
    ) = SpotifyAlarmMusicPlayer(backgroundScope, status, { remote }, { installed }, { online }, { 10_000 }) to status

    @Test fun notInstalledNoNetworkAndNoSdkFallBackWithReasonAndCallback() = runTest {
        for ((exp, args) in listOf(
            SpotifyFailure.NotInstalled to Triple<FakeRemote?, Boolean, Boolean>(FakeRemote { currentTime }, false, true),
            SpotifyFailure.NoNetwork to Triple<FakeRemote?, Boolean, Boolean>(FakeRemote { currentTime }, true, false),
            SpotifyFailure.SdkNotBundled to Triple<FakeRemote?, Boolean, Boolean>(null, true, true),
        )) {
            val (p, status) = player(args.first, args.second, args.third)
            val cb = Cb()
            assertTrue(p.begin("a1", URI_PLAYLIST, cb))
            advanceTimeBy(15_000)
            assertEquals(listOf("lost"), cb.events)
            val s = status.state.value as AlarmMusicState.FellBack
            assertEquals(exp, s.failure); assertEquals(exp.recovery(), s.recovery); assertFalse(s.stoppedAfterPlaying)
        }
    }

    @Test fun confirmedThenStoppedBringsTheToneBack() = runTest {
        val r = FakeRemote { currentTime }.apply { behaviour = { t -> if (t < 600) RemotePlayerState(true, null, null, 0) else if (t < 30_000) RemotePlayerState(false, "spotify:track:t1", URI_PLAYLIST, t) else RemotePlayerState(true, "spotify:track:t1", URI_PLAYLIST, t) } }
        val (p, status) = player(r)
        val cb = Cb()
        p.begin("a1", URI_PLAYLIST, cb)
        advanceTimeBy(5_000)
        assertEquals(listOf("confirmed"), cb.events)
        assertEquals(AlarmMusicState.Playing, status.state.value)
        advanceTimeBy(40_000)
        assertEquals(listOf("confirmed", "lost"), cb.events)
        val s = status.state.value as AlarmMusicState.FellBack
        assertTrue(s.stoppedAfterPlaying)
    }

    @Test fun timeoutKeepsToneAndReportsTimeout() = runTest {
        val r = FakeRemote { currentTime } // never plays
        val (p, status) = player(r)
        val cb = Cb()
        p.begin("a1", URI_PLAYLIST, cb)
        advanceTimeBy(15_000)
        assertEquals(listOf("lost"), cb.events)
        assertEquals(SpotifyFailure.Timeout, (status.state.value as AlarmMusicState.FellBack).failure)
    }

    @Test fun stopPausesSpotifyAndClearsState() = runTest {
        val r = FakeRemote { currentTime }.apply { behaviour = playingFrom(0) }
        val (p, status) = player(r)
        p.begin("a1", URI_PLAYLIST, Cb())
        advanceTimeBy(3_000)
        p.stop(); advanceTimeBy(15_000)
        assertTrue(r.paused); assertTrue(r.disconnected)
        assertEquals(AlarmMusicState.Idle, status.state.value)
    }

    @Test fun retryReconnectsInteractively() = runTest {
        val r = FakeRemote { currentTime }.apply { connectError = SpotifyFailure.NotAuthorized }
        val (p, status) = player(r)
        val cb = Cb()
        p.begin("a1", URI_PLAYLIST, cb); advanceTimeBy(15_000)
        assertEquals(SpotifyFailure.NotAuthorized, (status.state.value as AlarmMusicState.FellBack).failure)
        r.connectError = null; r.behaviour = playingFrom(0)
        p.retry(interactive = true); advanceTimeBy(5_000)
        assertEquals(true, r.interactiveSeen)
        assertEquals(AlarmMusicState.Playing, status.state.value)
        assertEquals(listOf("lost", "confirmed"), cb.events)
    }
}
