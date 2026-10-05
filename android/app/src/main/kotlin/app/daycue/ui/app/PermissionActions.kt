package app.daycue.ui.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.daycue.DayCueApplication
import app.daycue.facade.DayCueFacade
import app.daycue.system.ReadinessId

/** The facade of the running app (APP_API section 1). */
@Composable
fun rememberFacade(): DayCueFacade {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as DayCueApplication).container.facade }
}

/**
 * System permission and settings actions shared by onboarding and Reminder readiness. Runtime prompts are used
 * where Android has one (notifications on API 33+, calendar, location steps); everything else opens the exact
 * system screen from `facade.fixIntent`. [onChanged] runs after any result so the caller refreshes its rows.
 */
class PermissionActions(
    val requestNotifications: () -> Unit,
    val requestCalendar: () -> Unit,
    val requestRuntime: (List<String>) -> Unit,
    val openFix: (ReadinessId) -> Unit,
    val openIntent: (Intent?) -> Unit,
)

@Composable
fun rememberPermissionActions(facade: DayCueFacade, onChanged: () -> Unit): PermissionActions {
    val context = LocalContext.current
    val changed = rememberUpdatedState(onChanged)
    val prefs = remember(context) { UiPrefs(context) }

    fun open(intent: Intent?) {
        if (intent == null) return
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The device has no screen for this setting; the row stays as it is.
        } catch (_: SecurityException) {
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { changed.value() }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        facade.calendar.onPermissionChanged()
        changed.value()
    }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        changed.value()
    }

    return remember(context, facade) {
        PermissionActions(
            requestNotifications = {
                val granted = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!granted && !prefs.notificationsAsked) {
                    prefs.notificationsAsked = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    open(facade.fixIntent(ReadinessId.Notifications))
                }
            },
            requestCalendar = { calendar.launch(Manifest.permission.READ_CALENDAR) },
            requestRuntime = { perms -> if (perms.isNotEmpty()) multiple.launch(perms.toTypedArray()) },
            openFix = { id -> open(facade.fixIntent(id)) },
            openIntent = ::open,
        )
    }
}
