package app.daycue.delivery

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.daycue.domain.config.CueProfile
import app.daycue.domain.config.Language
import app.daycue.domain.config.SpeechOutput
import app.daycue.domain.config.SpeechOverMediaPolicy
import java.time.Instant

/**
 * Plays a cue sound / vibration directly (not through a notification channel). Used for cue previews
 * from the UI (GEN-10: nothing is posted, nothing is recorded) and inside the routine foreground service,
 * where the app plays step cues itself (ANDROID.md §6.2 mechanism 2).
 */
class CuePlayer(private val context: Context, private val speech: SpeechQueue) {

    private var player: MediaPlayer? = null

    fun playSound(soundId: String?, usage: Int = AudioAttributes.USAGE_NOTIFICATION) {
        val res = soundId?.let { CueMedia.sounds[it] } ?: return
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                val afd = context.resources.openRawResourceFd(res)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        }.onFailure { Log.w("DayCue", "cue sound $soundId failed", it) }
    }

    fun vibrate(vibrationId: String?) {
        val pattern = vibrationId?.let { CueMedia.vibrations[it] } ?: return
        vibrator(context).vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** UI "Preview" for a cue profile: sound, then vibration, then the phrase (speech never reported to the engine). */
    fun preview(profile: CueProfile, language: Language) {
        if (profile.soundEnabled) playSound(profile.soundId)
        if (profile.vibrationEnabled) vibrate(profile.vibrationId)
        val lang = profile.language ?: language
        val phrase = profile.phrase.get(lang)
        if (profile.speechEnabled && phrase.isNotBlank()) {
            val now = Instant.now()
            speech.enqueue(SpeechQueue.Item("preview:${profile.id}", 0, now, now.plusSeconds(60), phrase, lang,
                SpeechOverMediaPolicy.DuckAndSpeak, SpeechOutput.AnyRoute, SpeechUsage.Assistant, System.currentTimeMillis() + 900, report = false))
        }
    }

    companion object {
        fun vibrator(context: Context): Vibrator =
            if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }
}
