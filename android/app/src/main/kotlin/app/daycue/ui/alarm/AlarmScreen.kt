package app.daycue.ui.alarm

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import app.daycue.ui.field.Field
import app.daycue.ui.field.FieldActive
import app.daycue.ui.field.FieldContext
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueShapes
import app.daycue.ui.util.durationText
import app.daycue.ui.cues.alarmName
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
import app.daycue.ui.util.clockIsolate
import androidx.compose.ui.layout.SubcomposeLayout
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
    val fontScale = LocalDensity.current.fontScale
    val name = alarmName(alarm.alarmId, alarm.name).ifBlank { alarm.title }
    val snoozeLabel = stringResource(R.string.app_alarm_snooze_for, durationText(alarm.snoozeMin))
    val fieldDescription = stringResource(R.string.app_alarm_field_desc, name)
    Box(Modifier.fillMaxSize().background(c.paper), contentAlignment = Alignment.TopCenter) {
        // VALIDATION D12. Three groups: time + name at the top, the Field in the space left between them and the controls,
        // the controls anchored to the bottom. The Field takes what is left within [AlarmLayout] bounds and is dropped
        // entirely when too little is left (never squashed into a strip); the upper part scrolls if even the text does not
        // fit, so Stop is always on screen.
        Column(
            Modifier.widthIn(max = DayCueSpacing.contentMaxWidth).fillMaxSize()
                .statusBarsPadding().navigationBarsPadding().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val avail = maxHeight
                val scroll = rememberScrollState()
                SubcomposeLayout(Modifier.fillMaxWidth().verticalScroll(scroll)) { cs ->
                    val loose = cs.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
                    val top = subcompose("top") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = DayCueSpacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(if (fontScale >= 1.5f) 8.dp else 24.dp))
                            Text(
                                alarmTimeText(alarm.time),
                                style = DayCueTheme.type.display.copy(fontSize = if (fontScale >= 1.5f) 56.sp else 88.sp, lineHeight = if (fontScale >= 1.5f) 64.sp else 96.sp),
                                color = c.ink, textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(name, style = DayCueTheme.type.title.copy(fontSize = 26.sp, lineHeight = 34.sp), color = c.ink, textAlign = TextAlign.Center)
                            if (alarm.isTest) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.app_alarm_test), style = DayCueTheme.type.label, color = c.ink2)
                            }
                            MusicNote(music, onRecover)
                        }
                    }.single().measure(loose)
                    val availPx = avail.roundToPx()
                    val gap = AlarmLayout.GAP_DP.dp.roundToPx()
                    val roomDp = (availPx - top.height - 2 * gap).toDp().value
                    val fieldDp = AlarmLayout.fieldHeightDp(roomDp)
                    val field = fieldDp?.let { h ->
                        subcompose("field") {
                            // The plane with the alarm disc arrived on it (due, with the seed dot), edge to edge.
                            Field(
                                context = FieldContext.Home, active = FieldActive.None, nextCue = CueType.Alarm,
                                remainingMinutes = 0, horizonMinutes = 1, description = fieldDescription, overdue = true,
                                heightDpOverride = h,
                            )
                        }.single().measure(loose)
                    }
                    val height = maxOf(availPx, top.height + (field?.let { it.height + 2 * gap } ?: 0))
                    layout(cs.maxWidth, height) {
                        top.place((cs.maxWidth - top.width) / 2, 0)
                        // Sits just above the controls (one gap), any spare space goes between the name and the plane.
                        field?.place(0, height - field.height - gap)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = DayCueSpacing.gutter).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (alarm.canSnooze) {
                    // Snooze: a pill, outlined. Stop: a slab, filled. Different shapes, both well over 72dp.
                    AlarmButton(snoozeLabel, onSnooze, RoundedCornerShape(50), Color.Transparent, c.ink, c.outlineStrong)
                }
                AlarmButton(stringResource(R.string.dc_action_stop), onStop, DayCueShapes.plane, c.ink, c.paper, null)
            }
        }
    }
}

/** The big clock: digits at display size, a 12-hour day-period marker ("AM", "אחה״צ") smaller on the same line. */
private fun alarmTimeText(time: String): androidx.compose.ui.text.AnnotatedString {
    val isolated = time.clockIsolate()
    val sp = isolated.lastIndexOf(' ')
    if (sp < 0) return androidx.compose.ui.text.AnnotatedString(isolated)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(isolated)
        addStyle(androidx.compose.ui.text.SpanStyle(fontSize = androidx.compose.ui.unit.TextUnit(0.4f, androidx.compose.ui.unit.TextUnitType.Em)), sp, isolated.length - 1)
    }
}

/** Alarm screen sizing (VALIDATION D12), pure so it is unit-tested. */
object AlarmLayout {
    /** Below this the illustration is dropped, not squashed. */
    const val FIELD_MIN_DP = 120f
    const val FIELD_MAX_DP = 300f
    const val GAP_DP = 16f

    /** The Field height for [roomDp] of free space, or null = leave it out. */
    fun fieldHeightDp(roomDp: Float): Float? = if (roomDp < FIELD_MIN_DP) null else roomDp.coerceAtMost(FIELD_MAX_DP)
}

@Composable
private fun AlarmButton(text: String, onClick: () -> Unit, shape: Shape, container: Color, content: Color, border: Color?) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(shape).background(container)
            .then(if (border != null) Modifier.border(BorderStroke(2.dp, border), shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DayCueTheme.type.title.copy(fontSize = 22.sp, lineHeight = 30.sp), color = content, textAlign = TextAlign.Center)
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
