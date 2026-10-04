package app.daycue.delivery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.daycue.MainActivity

/**
 * Notification body taps (UX §1.4). The body tap is never an ack: it opens `MainActivity` with a
 * `daycue://open/<target>?item=<itemKey>&cue=<cueId>` data URI that the UI routes on (`onCreate` and
 * `onNewIntent`). Targets: `item` (habit detail, Why now expanded), `dose` (medication slot),
 * `medication` (merged cue -> Today medication section), `routine` (playback), `posture` (live control),
 * `calendar` (preview at that event), `context` (Today + context sheet), `alarm` (ringing screen),
 * `readiness`, `today`.
 */
object DeepLinks {
    const val SCHEME = "daycue"

    fun targetFor(itemKey: String): String = when {
        itemKey == "med:merged" || itemKey == "med:policy" -> "medication"
        itemKey.startsWith("med:") -> "dose"
        itemKey.startsWith("habit:") -> "item"
        itemKey.startsWith("routine:") || itemKey.startsWith("routine-prompt:") -> "routine"
        itemKey == "posture" -> "posture"
        itemKey.startsWith("cal:") -> "calendar"
        itemKey.startsWith("session:") -> "context"
        itemKey.startsWith("alarm:") -> "alarm"
        else -> "today"
    }

    fun uri(target: String, itemKey: String? = null, cueId: String? = null): Uri =
        Uri.Builder().scheme(SCHEME).authority("open").appendPath(target).apply {
            itemKey?.let { appendQueryParameter("item", it) }
            cueId?.let { appendQueryParameter("cue", it) }
        }.build()

    fun intent(context: Context, target: String, itemKey: String? = null, cueId: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, uri(target, itemKey, cueId), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, itemKey: String, cueId: String?): PendingIntent =
        PendingIntent.getActivity(context, 0, intent(context, targetFor(itemKey), itemKey, cueId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
