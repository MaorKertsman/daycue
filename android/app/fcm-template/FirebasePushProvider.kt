package app.daycue.integrations.relay

import android.content.Context
import app.daycue.DayCueApplication
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * OPTIONAL FCM wake (docs/setup/MCP.md, "Phone push wake"). This file lives in `fcm-template/` so the default
 * build has no Firebase dependency; the owner copies it into `src/main/kotlin/app/daycue/integrations/relay/`.
 * `RelayService` finds this class by name (`Class.forName`), so nothing else changes.
 */
class FirebasePushProvider(private val context: Context) : PushProvider {
    override val available: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    override suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null) }
    }
}

/** Data-only high-priority message `{type: "sync"}` (no config content): run a sync through WorkManager. */
class DayCueMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as DayCueApplication).container.relay.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "sync") (application as DayCueApplication).container.relay.onPushWake()
    }
}
