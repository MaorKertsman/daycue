package app.daycue.delivery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.res.stringResource
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.domain.engine.AlarmAction
import app.daycue.domain.engine.Event
import kotlinx.coroutines.launch

/**
 * Full-screen alarm UI target (ANDROID.md §6.3). PLACEHOLDER: functional but unstyled; the UI engineer
 * owns the real ringing screen (UX §3.11) and can replace [AlarmScreen] — the contract is
 * [AlarmRingingService.ringing] (what rings) plus [stop] / [snooze]. Back does nothing (UX §1.3);
 * the activity finishes when ringing ends. Direct-boot aware so it can show before first unlock.
 */
class AlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })
        setContent {
            val ringing by AlarmRingingService.ringing.collectAsState()
            LaunchedEffect(ringing) { if (ringing == null) finish() }
            ringing?.let { AlarmScreen(it, onStop = { stop(it) }, onSnooze = { snooze(it) }) }
        }
    }

    private fun stop(a: RingingAlarm) {
        if (a.locked) { startService(Intent(this, AlarmRingingService::class.java).setAction(AlarmRingingService.ACTION_LOCKED_STOP)); return }
        dispatch(Event.AlarmControl(a.alarmId, AlarmAction.Stop))
    }

    private fun snooze(a: RingingAlarm) {
        if (a.locked) { startService(Intent(this, AlarmRingingService::class.java).setAction(AlarmRingingService.ACTION_LOCKED_SNOOZE)); return }
        dispatch(Event.AlarmControl(a.alarmId, AlarmAction.Snooze))
    }

    private fun dispatch(e: Event) {
        val app = (application as DayCueApplication).container
        lifecycleScope.launch { app.host.dispatch(e) }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

@Composable
private fun AlarmScreen(a: RingingAlarm, onStop: () -> Unit, onSnooze: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(a.time, style = MaterialTheme.typography.displayLarge)
                Text(a.title, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (a.canSnooze) OutlinedButton(onSnooze, Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.dc_action_snooze_alt)) }
                    Button(onStop, Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.dc_action_stop)) }
                }
            }
        }
    }
}
