package app.daycue.delivery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.daycue.R
import app.daycue.actions.ActionMapper
import app.daycue.actions.CueActionReceiver
import app.daycue.domain.config.CueType
import app.daycue.domain.config.Language
import app.daycue.domain.engine.Cue
import app.daycue.domain.engine.LockScreenVisibility
import app.daycue.domain.engine.Text
import java.time.ZoneId

/**
 * Posts domain `Deliver` effects as notifications (UX §4). Makes no policy decisions: priority, sound,
 * silence, grouping, lock-screen visibility and actions all come from the [Cue].
 *
 * - One notification per `notificationKey` (tag = key, id = [NOTIFICATION_ID]): re-alerts update it (GEN-4).
 * - Channel from [ChannelRegistry.channelFor] (sound/vibration variants); `silent` -> `setSilent(true)`.
 * - At most 3 actions ([ActionMapper.pickThree]); body tap = deep link, never an ack; delete intent ->
 *   `CueDismissed` (logged only).
 * - `Private` visibility posts a public version with `publicTitle` only (medication default: no label).
 */
class NotificationDelivery(
    private val context: Context,
    private val channels: ChannelRegistry,
    private val text: TextResolver,
) {
    private val nm = NotificationManagerCompat.from(context)

    fun canPost(): Boolean =
        nm.areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun build(cue: Cue, lang: Language, zone: ZoneId = ZoneId.systemDefault()): Notification {
        val title = text.resolve(cue.title, lang, zone)
        val body = text.resolve(cue.body, lang, zone)
        val why = text.app("why.prefix", lang, mapOf("reason" to text.resolve(Text(cue.why.reason), lang, zone)))
        val channel = channels.channelFor(cue)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (body.isBlank()) why else "$body\n$why"))
            .setCategory(categoryOf(cue.type))
            .setPriority(if (cue.priority <= 2) NotificationCompat.PRIORITY_HIGH else if (cue.type == CueType.Notice) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(cue.deliveredAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false) // repeats re-alert (GEN-4)
            .setContentIntent(DeepLinks.pending(context, cue.itemKey, cue.id))
            .setDeleteIntent(CueActionReceiver.dismissed(context, cue.itemKey, cue.id))
            .setVisibility(visibilityOf(cue.lockScreen))
        if (cue.silent || !cue.groupLead) b.setSilent(true)
        // COL-1: only the group lead alerts; the summary (postSummary) never does.
        cue.groupKey?.let { b.setGroup(it) }
        if (cue.lockScreen == LockScreenVisibility.Private) {
            val publicTitle = cue.publicTitle?.let { text.resolve(it, lang, zone) } ?: text.app("cue.${cue.type.name.lowercase()}.title", lang)
            b.setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_daycue)
                    .setContentTitle(publicTitle).setCategory(categoryOf(cue.type)).build(),
            )
        }
        ActionMapper.pickThree(cue.itemKey, cue.actions, { it.kind }, { it.minutes }).forEach { a ->
            b.addAction(0, text.resolve(a.label, lang, zone), CueActionReceiver.pending(context, cue.itemKey, cue.id, a.kind, a.minutes))
        }
        if (cue.fullScreen) b.setFullScreenIntent(DeepLinks.pending(context, cue.itemKey, cue.id), true)
        return b.build()
    }

    @SuppressLint("MissingPermission") // checked in canPost()
    fun deliver(cue: Cue, lang: Language) {
        if (!canPost()) { Log.w(TAG, "notifications not allowed; cue ${cue.id} (${cue.itemKey}) not shown"); return }
        nm.notify(cue.notificationKey, NOTIFICATION_ID, build(cue, lang))
        if (cue.groupLead) cue.groupKey?.let { postSummary(it, cue, lang) }
    }

    @SuppressLint("MissingPermission")
    private fun postSummary(groupKey: String, cue: Cue, lang: Language) {
        // COL-2: members keep their own notifications; the summary is silent and only groups them.
        val n = NotificationCompat.Builder(context, cue.channelId)
            .setSmallIcon(R.drawable.ic_stat_daycue)
            .setContentTitle(text.resolve(cue.title, lang))
            .setGroup(groupKey).setGroupSummary(true).setSilent(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        nm.notify("group:$groupKey", NOTIFICATION_ID, n)
    }

    fun dismiss(notificationKey: String) = nm.cancel(notificationKey, NOTIFICATION_ID)

    companion object {
        private const val TAG = "DayCue"
        const val NOTIFICATION_ID = 1

        fun visibilityOf(v: LockScreenVisibility) = when (v) {
            LockScreenVisibility.Public -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenVisibility.Private -> NotificationCompat.VISIBILITY_PRIVATE
            LockScreenVisibility.Secret -> NotificationCompat.VISIBILITY_SECRET
        }

        fun categoryOf(t: CueType) = when (t) {
            CueType.Alarm -> NotificationCompat.CATEGORY_ALARM
            CueType.Calendar -> NotificationCompat.CATEGORY_EVENT
            CueType.Notice -> NotificationCompat.CATEGORY_STATUS
            else -> NotificationCompat.CATEGORY_REMINDER
        }
    }
}
