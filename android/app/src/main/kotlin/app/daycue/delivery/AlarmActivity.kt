package app.daycue.delivery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.daycue.DayCueApplication
import app.daycue.domain.engine.AlarmAction
import app.daycue.domain.engine.Event
import app.daycue.ui.alarm.AlarmRingingScreen
import kotlinx.coroutines.launch

/**
 * Full-screen alarm UI target (ANDROID.md §6.3). The screen itself is `ui.alarm.AlarmRingingScreen` (UX §3.11); this
 * Activity owns only the window flags and the contract: [AlarmRingingService.ringing] (what rings) plus [stop] /
 * [snooze]. Back does nothing (UX §1.3); the activity finishes when ringing ends. Direct-boot aware so it can show
 * before first unlock.
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
            ringing?.let { AlarmRingingScreen(it, onStop = { stop(it) }, onSnooze = { snooze(it) }) }
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
