package app.daycue.ui.alarm

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.delivery.RingingAlarm
import app.daycue.integrations.spotify.AlarmMusicState
import app.daycue.integrations.spotify.RecoveryAction
import app.daycue.integrations.spotify.SpotifyFailure
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.ltr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Words for a Spotify failure on the ringing screen. The local tone is already ringing in every case (ALM-2). */
@StringRes
fun spotifyFailureText(failure: SpotifyFailure, stoppedAfterPlaying: Boolean): Int = when {
    stoppedAfterPlaying -> R.string.dc_spotify_stopped
    else -> when (failure) {
        SpotifyFailure.NotInstalled -> R.string.dc_spotify_fail_not_installed
        SpotifyFailure.NotAuthorized -> R.string.dc_spotify_fail_not_authorized
        SpotifyFailure.NoNetwork -> R.string.dc_spotify_fail_no_network
        SpotifyFailure.RemoteUnavailable -> R.string.dc_spotify_fail_no_device
        SpotifyFailure.AccountRestriction -> R.string.dc_spotify_fail_account
        SpotifyFailure.Timeout -> R.string.dc_spotify_fail_timeout
        SpotifyFailure.SdkNotBundled -> R.string.dc_spotify_fail_sdk_missing
        SpotifyFailure.Unknown -> R.string.dc_spotify_fail_unknown
    }
}

@StringRes
fun recoveryLabel(action: RecoveryAction): Int? = when (action) {
    RecoveryAction.InstallSpotify -> R.string.app_alarm_recover_install
    RecoveryAction.AuthorizeSpotify -> R.string.app_alarm_recover_authorize
    RecoveryAction.CheckNetwork -> R.string.app_alarm_recover_network
    RecoveryAction.OpenSpotify -> R.string.app_alarm_recover_open
    RecoveryAction.CheckAccount -> R.string.app_alarm_recover_account
    RecoveryAction.Retry -> R.string.app_alarm_recover_retry
    RecoveryAction.None -> null
}

/**
 * The ringing alarm (UX 3.11): the time, the name, why the tone is playing instead of Spotify (when that is the
 * case) with one recovery action, then Snooze and Stop as two clearly different, 72dp-tall buttons with no swipe.
 * Back is disabled by the Activity. Stop stays reachable at any font scale because the page scrolls.
 *
 * Direct-boot safe: before the first unlock ([RingingAlarm.locked]) the app container is not touched here.
 */
@Composable
fun AlarmRingingScreen(alarm: RingingAlarm, onStop: () -> Unit, onSnooze: () -> Unit) {
    val context = LocalContext.current
    val facade = remember(alarm.locked) {
        if (alarm.locked) null else runCatching { (context.applicationContext as DayCueApplication).container.facade }.getOrNull()
    }
    val idle: StateFlow<AlarmMusicState> = remember { MutableStateFlow(AlarmMusicState.Idle) }
    val music by (facade?.alarmMusic ?: idle).collectAsState()
    DayCueTheme {
        AlarmContent(
            alarm = alarm, music = music, onStop = onStop, onSnooze = onSnooze,
            onRecover = { action ->
                when (action) {
                    RecoveryAction.AuthorizeSpotify -> facade?.retryAlarmMusic(interactive = true)
                    RecoveryAction.Retry -> facade?.retryAlarmMusic(interactive = false)
                    else -> facade?.alarmMusicRecoveryIntent(action)?.let {
                        try { context.startActivity(it) } catch (_: ActivityNotFoundException) { }
                    }
                }
            },
        )
    }
}

@Composable
fun AlarmContent(alarm: RingingAlarm, music: AlarmMusicState, onStop: () -> Unit, onSnooze: () -> Unit, onRecover: (RecoveryAction) -> Unit) {
    val c = DayCueTheme.colors
    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = DayCueSpacing.contentMaxWidth).fillMaxWidth().padding(horizontal = DayCueSpacing.gutter).padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    alarm.time.ltr(),
                    style = DayCueTheme.type.display.copy(fontSize = 72.sp, lineHeight = 84.sp),
                    color = c.ink, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(alarm.title, style = DayCueTheme.type.headline, color = c.ink, textAlign = TextAlign.Center)
                if (alarm.isTest) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.app_alarm_test), style = DayCueTheme.type.label, color = c.ink2)
                }
                MusicNote(music, onRecover)
                Spacer(Modifier.height(40.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (alarm.canSnooze) {
                        SecondaryButton(stringResource(R.string.app_alarm_snooze), onSnooze, Modifier.fillMaxWidth().heightIn(min = 72.dp))
                    }
                    PrimaryButton(stringResource(R.string.dc_action_stop), onStop, Modifier.fillMaxWidth().heightIn(min = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun MusicNote(music: AlarmMusicState, onRecover: (RecoveryAction) -> Unit) {
    val c = DayCueTheme.colors
    when (music) {
        AlarmMusicState.Idle, AlarmMusicState.Playing -> Unit
        AlarmMusicState.Connecting -> {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_alarm_connecting), style = DayCueTheme.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        }
        is AlarmMusicState.FellBack -> {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(spotifyFailureText(music.failure, music.stoppedAfterPlaying)),
                    style = DayCueTheme.type.body, color = c.ink, textAlign = TextAlign.Center,
                )
                recoveryLabel(music.recovery)?.let { label ->
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(stringResource(label), { onRecover(music.recovery) }, compact = true)
                }
            }
        }
    }
}
