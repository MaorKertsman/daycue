package app.daycue.debug.cues

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.daycue.ui.cues.CuesRoot
import app.daycue.ui.theme.DayCueTheme

/**
 * Debug-only host for the Cues tab without the app shell:
 * `adb shell am start -n app.daycue/app.daycue.debug.cues.CuesDebugActivity -e item habit:sunscreen`
 * `item` takes the same values as `CuesRoot(startItem)`, or `route:meds,med/history` for a literal route stack.
 */
class CuesDebugActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val item = intent.getStringExtra("item")
        val reduce = intent.getStringExtra("reduce_motion") == "true"
        setContent {
            DayCueTheme(reduceMotionSetting = reduce) { CuesRoot(startItem = item) }
        }
    }
}
