package app.daycue.delivery

import android.content.Context
import android.content.Intent
import android.util.Log
import app.daycue.domain.config.AlarmSource
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.engine.Cue
import app.daycue.domain.engine.Effect
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.SpeechRequest
import app.daycue.domain.engine.Text
import app.daycue.engine.EffectSink
import java.time.ZoneId

/**
 * Executes delivery effects on Android. No policy here: everything is decided by the domain and carried
 * in the effect. Called by `EngineHost` under its lock, after state is persisted; must not block.
 */
class AndroidEffectSink(
    private val context: Context,
    private val notifications: NotificationDelivery,
    private val speech: SpeechQueue,
    private val text: TextResolver,
) : EffectSink {

    override fun execute(effect: Effect, config: DayCueConfig, state: EngineState) {
        val lang = config.settings.language
        when (effect) {
            is Effect.Deliver -> deliver(effect.cue, config)
            is Effect.DismissCue -> {
                val playback = RoutinePlaybackService.instance
                if (playback == null || playback.routineKey != effect.notificationKey) notifications.dismiss(effect.notificationKey)
            }
            is Effect.Speak -> enqueueSpeech("speak:${effect.speech.createdAt.toEpochMilli()}", 1, effect.speech, delayMs = 0)
            is Effect.StartAlarm -> startAlarm(effect, config)
            is Effect.StopAlarm -> AlarmRingingService.stop(context)
            is Effect.ScheduleWake, Effect.CancelWake, is Effect.RecordHistory, is Effect.ApplyConfigOps -> Unit // EngineHost
        }
        if (effect is Effect.Deliver) Log.i(TAG, "delivered ${effect.cue.id} ${effect.cue.itemKey} ch=${effect.cue.channelId} silent=${effect.cue.silent} test=${effect.cue.isTest} lang=$lang")
    }

    private fun deliver(cue: Cue, config: DayCueConfig) {
        val lang = config.settings.language
        val playback = RoutinePlaybackService.instance
        if (playback != null && playback.routineKey == cue.itemKey) {
            playback.show(cue, notifications.build(cue, lang))
        } else {
            notifications.deliver(cue, lang)
        }
        // Speech after the notification sound (SPK-1 ordering inside the queue; SPK-3 fallback = the notification).
        cue.speech?.let { enqueueSpeech(cue.id, cue.priority, it, delayMs = if (cue.silent) 0 else 1_200) }
    }

    private fun enqueueSpeech(cueId: String, priority: Int, req: SpeechRequest, delayMs: Long) {
        val zone = ZoneId.systemDefault()
        speech.enqueue(
            SpeechQueue.Item(
                cueId = cueId, priority = priority, createdAt = req.createdAt, dropAfter = req.dropAfter,
                text = text.speech(req, zone), language = req.language, overMedia = req.overMedia, output = req.output,
                usage = SpeechUsage.Assistant, notBeforeMs = System.currentTimeMillis() + delayMs,
            ),
        )
    }

    private fun startAlarm(e: Effect.StartAlarm, config: DayCueConfig) {
        val lang = config.settings.language
        val alarm = config.alarm(e.alarmId)
        val time = alarm?.time?.toString() ?: ""
        val name = alarm?.name ?: ""
        val title = text.app(if (e.isTest) "alarm.test.title" else "alarm.ringing.title", lang, mapOf("time" to time, "name" to name))
        val (tone, uri) = when (val s = e.source) {
            is AlarmSource.LocalTone -> s.toneId to null
            is AlarmSource.SpotifyItem -> s.fallbackToneId to s.uri
        }
        val intent = Intent()
            .putExtra(AlarmRingingService.EXTRA_ALARM_ID, e.alarmId)
            .putExtra(AlarmRingingService.EXTRA_TITLE, title)
            .putExtra(AlarmRingingService.EXTRA_TIME, Templates.formatArg("time", time, ZoneId.systemDefault(), rtl = false, hour24 = text.use24Hour, locale = java.util.Locale.forLanguageTag(lang.name)))
            .putExtra(AlarmRingingService.EXTRA_NAME, name)
            .putExtra(AlarmRingingService.EXTRA_TONE, tone)
            .putExtra(AlarmRingingService.EXTRA_SOURCE_URI, uri)
            .putExtra(AlarmRingingService.EXTRA_RAMP_SEC, e.volumeRampSec)
            .putExtra(AlarmRingingService.EXTRA_VIBRATE, e.vibrate)
            .putExtra(AlarmRingingService.EXTRA_SNOOZE_COUNT, e.snoozeCount)
            .putExtra(AlarmRingingService.EXTRA_MAX_SNOOZES, e.maxSnoozes)
            .putExtra(AlarmRingingService.EXTRA_SNOOZE_MIN, alarm?.snoozeMin ?: 9)
            .putExtra(AlarmRingingService.EXTRA_TEST, e.isTest)
            .putExtra(AlarmRingingService.EXTRA_SNOOZE_LABEL, text.resolve(Text("action.snooze", mapOf("minutes" to (alarm?.snoozeMin ?: 9).toString())), lang))
            .putExtra(AlarmRingingService.EXTRA_STOP_LABEL, text.resolve(Text("action.stop"), lang))
        AlarmRingingService.start(context, intent)
    }

    companion object { private const val TAG = "DayCue" }
}
