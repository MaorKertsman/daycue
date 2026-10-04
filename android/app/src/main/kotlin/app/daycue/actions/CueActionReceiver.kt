package app.daycue.actions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.daycue.delivery.RoutinePlaybackService
import app.daycue.domain.engine.ActionKind
import app.daycue.system.runAsync

/**
 * Notification action buttons and the notification delete intent (ANDROID.md §5.3 `CueActionReceiver` +
 * `CueDismissedReceiver`, merged: one explicit, non-exported receiver). Maps the tap with [ActionMapper]
 * and dispatches it through the engine host. Dismissal is logged only, never an ack.
 */
class CueActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemKey = intent.getStringExtra(EXTRA_ITEM_KEY) ?: return
        val cueId = intent.getStringExtra(EXTRA_CUE_ID)
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, -1).takeIf { it >= 0 }
        val tap = if (kindName == KIND_DISMISSED) ActionMapper.Tap.Dismissed
        else ActionMapper.Tap.Action(runCatching { ActionKind.valueOf(kindName) }.getOrNull() ?: return, minutes)
        val event = ActionMapper.map(itemKey, tap, cueId)
        Log.i("DayCue", "notification ${if (tap is ActionMapper.Tap.Dismissed) "dismissed" else kindName} on $itemKey -> ${event?.let { it::class.simpleName }}")
        if (event == null) return
        runAsync(context, "action $kindName") { app ->
            app.host.dispatch(event)
            app.speech.awaitIdle(timeoutMs = 5_000)
        }
    }

    companion object {
        const val ACTION_CUE = "app.daycue.action.CUE"
        const val EXTRA_ITEM_KEY = "app.daycue.extra.ITEM_KEY"
        const val EXTRA_CUE_ID = "app.daycue.extra.CUE_ID"
        const val EXTRA_KIND = "app.daycue.extra.KIND"
        const val EXTRA_MINUTES = "app.daycue.extra.MINUTES"
        const val KIND_DISMISSED = "dismissed"

        private fun data(itemKey: String, kind: String, minutes: Int?): Uri =
            Uri.Builder().scheme("daycue-action").authority(kind).appendPath((minutes ?: -1).toString()).appendPath(itemKey).build()

        fun intent(context: Context, itemKey: String, cueId: String?, kind: String, minutes: Int?): Intent =
            Intent(context, CueActionReceiver::class.java).setAction(ACTION_CUE).setData(data(itemKey, kind, minutes))
                .putExtra(EXTRA_ITEM_KEY, itemKey).putExtra(EXTRA_CUE_ID, cueId).putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_MINUTES, minutes ?: -1)

        /** PendingIntent for a notification button. The routine "Start" goes straight to the playback FGS (user action). */
        fun pending(context: Context, itemKey: String, cueId: String?, kind: ActionKind, minutes: Int?): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (ActionMapper.startsRoutinePlayback(itemKey, kind)) {
                val svc = RoutinePlaybackService.cueIntent(context, itemKey, cueId, kind.name, minutes).setData(data(itemKey, kind.name, minutes))
                PendingIntent.getForegroundService(context, 0, svc, flags)
            } else PendingIntent.getBroadcast(context, 0, intent(context, itemKey, cueId, kind.name, minutes), flags)
        }

        fun dismissed(context: Context, itemKey: String, cueId: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0, intent(context, itemKey, cueId, KIND_DISMISSED, null),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
