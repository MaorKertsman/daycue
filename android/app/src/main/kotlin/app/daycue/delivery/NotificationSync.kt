package app.daycue.delivery

import android.app.NotificationManager
import android.content.Context
import app.daycue.domain.engine.Event

/**
 * Reports to the engine which cue notifications the system really shows (VALIDATION D1). Notifications do not
 * survive a reboot or a force stop, and the system can drop them; the engine re-posts quietly whatever it still
 * believes visible ([Event.NotificationsObserved]). Sent on every process start and after `BOOT_COMPLETED`.
 */
object NotificationSync {

    /**
     * Pure part (JVM-tested): the cue keys among the active notifications. Cue notifications use tag = notification key
     * and id [NotificationDelivery.NOTIFICATION_ID]; group summaries (`group:*`) are not cues. The routine playback
     * foreground notification shows [playbackKey]'s cue under its own id, so that key counts as shown.
     */
    fun shownKeys(active: List<Pair<String?, Int>>, playbackKey: String?): Set<String> =
        active.mapNotNull { (tag, id) -> tag?.takeIf { id == NotificationDelivery.NOTIFICATION_ID && !it.startsWith("group:") } }.toSet() +
            listOfNotNull(playbackKey)

    /** Null when the list can't be read or posting is not allowed (re-posting would show nothing, and must not churn history). */
    fun observe(context: Context, canPost: Boolean): Event.NotificationsObserved? {
        if (!canPost) return null
        val active = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.tag to it.id }
        }.getOrNull() ?: return null
        return Event.NotificationsObserved(shownKeys(active, RoutinePlaybackService.instance?.routineKey))
    }
}
