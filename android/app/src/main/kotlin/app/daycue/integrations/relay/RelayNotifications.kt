package app.daycue.integrations.relay

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.delivery.ChannelRegistry
import app.daycue.delivery.DeepLinks
import kotlinx.coroutines.launch

/**
 * Prompts for things that need the owner on the phone: remote changes, new connections (grants) and "this
 * phone was unpaired from the relay". Tapping opens the **non-exported** [RemoteConfirmActivity] through an
 * explicit, immutable PendingIntent (security review L-13); the notification itself can only *decline* a
 * remote change. Approving a change or a connection is possible only from that in-app screen, after a
 * deliberate gesture. Dismissal is never a decision.
 */
class AndroidRemoteNotifier(private val context: Context) : RemoteNotifier {
    private val nm = context.getSystemService(NotificationManager::class.java)

    private fun allowed() = android.os.Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun id(key: String) = 0x5200 + (key.hashCode() and 0xFFF)

    private fun openConfirm(key: String, extra: String, value: String): PendingIntent = PendingIntent.getActivity(
        context, id(key), RemoteConfirmActivity.intent(context, extra, value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun confirmationNeeded(p: PendingRemote) {
        if (!allowed()) return
        val open = openConfirm(p.commandId, RemoteConfirmActivity.EXTRA_COMMAND, p.commandId)
        val decline = PendingIntent.getBroadcast(
            context, id(p.commandId), Intent(context, RemoteActionReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_ID, p.commandId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(if (p.kind == PendingKind.RoutineStart) R.string.dc_remote_confirm_title_routine else R.string.dc_remote_confirm_title)
        val n = NotificationCompat.Builder(context, ChannelRegistry.SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.dc_remote_confirm_body, p.clientLabel.take(40)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.dc_remote_confirm_body, p.clientLabel.take(40))))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.dc_remote_decline), decline)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((p.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000))
            .build()
        nm.notify(TAG, id(p.commandId), n)
    }

    override fun cancel(commandId: String) = nm.cancel(TAG, id(commandId))

    /** A user-visible result also keeps high-priority FCM wakes from being downgraded (RELAY.md section 6). Text is `Strict`-redacted. */
    override fun appliedNotice(clientLabel: String, summary: String) {
        if (!allowed()) return
        val open = PendingIntent.getActivity(context, 0, DeepLinks.intent(context, "today"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, ChannelRegistry.SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(context.getString(R.string.dc_remote_applied_title, clientLabel.take(40)))
            .setContentText(summary.take(120))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setTimeoutAfter(6 * 3600_000L)
            .build()
        nm.notify(TAG, APPLIED_ID, n)
    }

    override fun grantAwaitingApproval(g: RemoteGrant) {
        if (!allowed()) return
        val body = context.getString(R.string.dc_remote_grant_notif_body, g.label.take(40))
        val n = NotificationCompat.Builder(context, ChannelRegistry.SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(context.getString(R.string.dc_remote_grant_notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openConfirm("grant:" + g.id, RemoteConfirmActivity.EXTRA_GRANT, g.id))
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(TAG, id("grant:" + g.id), n)
    }

    override fun cancelGrant(grantId: String) = nm.cancel(TAG, id("grant:$grantId"))

    override fun phoneRevoked() {
        if (!allowed()) return
        val open = PendingIntent.getActivity(context, 1, DeepLinks.intent(context, "today"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, ChannelRegistry.SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(context.getString(R.string.dc_remote_revoked_title))
            .setContentText(context.getString(R.string.dc_remote_revoked_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.dc_remote_revoked_body)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(TAG, REVOKED_ID, n)
    }

    companion object {
        const val TAG = "daycue.remote"
        const val APPLIED_ID = 0x5100
        const val REVOKED_ID = 0x5101
        const val ACTION_DECLINE = "app.daycue.relay.DECLINE"
        const val EXTRA_ID = "commandId"
    }
}

/** Notification "Decline" button (non-exported, explicit, immutable). It can only decline; there is no approve action. */
class RemoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidRemoteNotifier.ACTION_DECLINE) return
        val id = intent.getStringExtra(AndroidRemoteNotifier.EXTRA_ID) ?: return
        val container = (context.applicationContext as DayCueApplication).container
        val pr = goAsync()
        container.scope.launch { try { container.relay.client.decide(id, accept = false) } finally { pr.finish() } }
    }
}
