package app.daycue

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.daycue.delivery.DeepLinks
import app.daycue.ui.app.AppRoot
import app.daycue.ui.app.AppRoute
import app.daycue.ui.theme.DayCueTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.daycue.ui.setup.UiPrefs as SetupPrefs

// AppCompatActivity (not ComponentActivity) so AppCompatDelegate.setApplicationLocales
// applies the per-app language on API 26-32.
class MainActivity : AppCompatActivity() {

    // A deep link (notification body tap) is routed in onCreate and onNewIntent (APP_API section 4).
    private val route = mutableStateOf<AppRoute?>(null)
    private val routeNonce = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        SetupPrefs.load(this)
        super.onCreate(savedInstanceState)
        // A recreated Activity (rotation, language change) must not replay the link it was started with.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val reduceMotion by SetupPrefs.reduceMotion.collectAsState()
            DayCueTheme(reduceMotionSetting = reduceMotion) {
                AppRoot(route.value, routeNonce.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        if (data.scheme != DeepLinks.SCHEME || data.host != "open") return
        val target = data.pathSegments.firstOrNull()
        route.value = AppRoute.from(target, data.getQueryParameter("item"))
        routeNonce.intValue += 1
    }
}
