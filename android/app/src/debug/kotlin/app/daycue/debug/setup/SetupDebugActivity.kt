package app.daycue.debug.setup

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.daycue.ui.setup.SetupRoot
import app.daycue.ui.setup.UiPrefs
import app.daycue.ui.theme.DayCueTheme

/**
 * Debug-only host for the Setup tab until the app shell exists.
 * adb shell am start -n app.daycue/app.daycue.debug.setup.SetupDebugActivity -e start places
 * Extra "start": a SetupRoot startItem (place id, calendar, remote, cueprofile:profile-hydration, ...).
 */
class SetupDebugActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("start")
        UiPrefs.load(this)
        setContent {
            val reduce by UiPrefs.reduceMotion.collectAsState()
            DayCueTheme(reduceMotionSetting = reduce) { SetupRoot(startItem = start) }
        }
    }
}
