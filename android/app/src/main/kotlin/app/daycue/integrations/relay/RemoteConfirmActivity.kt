package app.daycue.integrations.relay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.daycue.DayCueApplication
import app.daycue.ui.app.RemoteConfirmRoot
import app.daycue.ui.theme.DayCueTheme

/**
 * The only place a remote change or a new connection can be **approved**.
 *
 * - `android:exported="false"`: other apps cannot start it. Notifications reach it through explicit, immutable
 *   PendingIntents (security review L-13).
 * - Intent extras only choose which item is shown first; they can never approve anything. The decision
 *   happens on screen: **press and hold** the approve button (a tap does nothing), so one stray or injected
 *   touch cannot approve. The window rejects touches while another window covers it
 *   (`filterTouchesWhenObscured`) and asks the system to hide overlays (API 31+).
 * - It reads what is pending from the facade (`remote.pending`, `remote.grants`), never from the intent.
 *
 * The content is `ui.app.RemoteConfirmRoot` (design system). The manifest entry, the intent
 * contract ([intent]), the hold gesture and the window flags set here are the security-relevant parts.
 */
class RemoteConfirmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(true) }
        val remote = (application as DayCueApplication).container.remote
        val focusCommand = intent.getStringExtra(EXTRA_COMMAND)
        val focusGrant = intent.getStringExtra(EXTRA_GRANT)
        setContent {
            DayCueTheme {
                RemoteConfirmRoot(remote, this, focusCommand, focusGrant) { finish() }
            }
        }
    }

    companion object {
        const val EXTRA_COMMAND = "app.daycue.extra.REMOTE_COMMAND"
        const val EXTRA_GRANT = "app.daycue.extra.REMOTE_GRANT"

        /** Explicit intent for a PendingIntent or an in-app launch. [extra] only selects the item to show first. */
        fun intent(context: Context, extra: String? = null, value: String? = null): Intent =
            Intent(context, RemoteConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (extra != null && value != null) putExtra(extra, value) }
    }
}
