package app.daycue.delivery

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.daycue.domain.config.Language
import app.daycue.domain.config.SpeechOutput
import app.daycue.domain.config.SpeechOverMediaPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.PriorityQueue

/** Per-language voice availability as reported by the installed TTS engine. */
enum class VoiceAvailability { Unknown, Available, MissingData, NotSupported, EngineUnavailable }

data class VoiceStatus(
    val engine: String? = null,
    val initialized: Boolean = false,
    val en: VoiceAvailability = VoiceAvailability.Unknown,
    val he: VoiceAvailability = VoiceAvailability.Unknown,
    /** True when the selected/default voice for that language needs the network (fails in airplane mode). */
    val enNeedsNetwork: Boolean = false,
    val heNeedsNetwork: Boolean = false,
    val voicesEn: List<String> = emptyList(),
    val voicesHe: List<String> = emptyList(),
)

/** Which audio usage an utterance plays with. `Alarm` only inside the ringing-alarm service (USAGE_ALARM). */
enum class SpeechUsage { Assistant, Alarm }

/**
 * SPK-1 speech queue over Android [TextToSpeech]: one utterance at a time, ordered by cue priority then
 * creation time; utterances past `dropAfter` are dropped (SPK-2); failures are reported, never retried
 * (SPK-3: the notification already carries the cue). Policy executed here from the fields the domain
 * provides: over-media (`DuckAndSpeak` -> transient may-duck focus, `PauseAndSpeak` -> transient focus,
 * `NotificationOnly` -> skip while music plays), `HeadphonesOnly`, never during a phone call, and system
 * DND when the config respects it. Meetings are already handled by the domain (no speech emitted).
 *
 * All TTS work happens on one HandlerThread. Results go to [onOutcome] (EngineHost -> `SpeechFinished` /
 * `SpeechFailed`); policy skips are logged only (they are not failures of the speech system).
 */
class SpeechQueue(
    private val context: Context,
    private val prefs: SpeechPrefs,
    private val respectDnd: () -> Boolean,
    private val onOutcome: (cueId: String, ok: Boolean, reason: String?) -> Unit,
) {
    data class Item(
        val cueId: String,
        val priority: Int,
        val createdAt: Instant,
        val dropAfter: Instant,
        val text: String,
        val language: Language,
        val overMedia: SpeechOverMediaPolicy,
        val output: SpeechOutput,
        val usage: SpeechUsage,
        /** Let the notification sound finish first. */
        val notBeforeMs: Long,
        val report: Boolean = true,
    )

    private val thread = HandlerThread("daycue-tts").apply { start() }
    private val handler = Handler(thread.looper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = PriorityQueue<Item>(compareBy<Item>({ it.priority }, { it.createdAt }))
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initFailed = false
    private var speaking: Item? = null
    private var focus: AudioFocusRequest? = null
    private var seq = 0
    private val idleWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private val _voices = MutableStateFlow(VoiceStatus())
    val voices: StateFlow<VoiceStatus> = _voices.asStateFlow()

    /** Last observed outcome, for the debug dump / readiness. */
    @Volatile var lastResult: String? = null
        private set

    fun enqueue(item: Item) {
        handler.post {
            queue += item
            ensureTts()
            pump()
        }
    }

    /** Initializes the engine (if needed) and refreshes [voices]. */
    fun checkVoices() { handler.post { ensureTts(); if (ready) { refreshVoices(); pump() } } }

    suspend fun awaitIdle(timeoutMs: Long) {
        val d = CompletableDeferred<Unit>()
        handler.post { if (isIdle()) d.complete(Unit) else idleWaiters += d }
        withTimeoutOrNull(timeoutMs) { d.await() }
    }

    fun stopAll() {
        handler.post {
            queue.clear()
            tts?.stop()
            speaking?.let { finish(it, ok = false, reason = "stopped") }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun isIdle() = queue.isEmpty() && speaking == null

    private fun ensureTts() {
        if (tts != null) return
        initFailed = false
        Log.i(TAG, "TTS init")
        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(listener)
                    refreshVoices()
                    Log.i(TAG, "TTS ready (engine=${tts?.defaultEngine})")
                    pump()
                } else {
                    Log.w(TAG, "TTS init failed: $status")
                    initFailed = true
                    _voices.value = VoiceStatus(initialized = false, en = VoiceAvailability.EngineUnavailable, he = VoiceAvailability.EngineUnavailable)
                    while (queue.isNotEmpty()) report(queue.poll()!!, false, "tts_init_failed")
                    tts?.shutdown(); tts = null; ready = false
                    notifyIdle()
                }
            }
        }
    }

    private fun refreshVoices() {
        val t = tts ?: return
        fun avail(lang: Language): VoiceAvailability = when (runCatching { t.isLanguageAvailable(TextResolver.localeOf(lang)) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)) {
            TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> VoiceAvailability.Available
            TextToSpeech.LANG_MISSING_DATA -> VoiceAvailability.MissingData
            else -> VoiceAvailability.NotSupported
        }
        val all = runCatching { t.voices?.toList() }.getOrNull().orEmpty()
        fun voicesFor(lang: Language) = all.filter { v -> langMatches(v.locale.language, lang) }
        fun needsNet(lang: Language): Boolean {
            val vs = voicesFor(lang)
            if (vs.isEmpty()) return false
            val preferred = prefs.voice(lang)?.let { n -> vs.firstOrNull { it.name == n } }
            return (preferred ?: vs.firstOrNull { !it.isNetworkConnectionRequired } ?: vs.first()).isNetworkConnectionRequired
        }
        // Google TTS reports a language as available even when its offline voice data was never downloaded
        // (observed on the API 37 emulator for Hebrew: the first utterance starts a download and fails offline).
        // A language counts as Available only if some voice is installed and works offline.
        fun offline(lang: Language): VoiceAvailability {
            val base = avail(lang)
            if (base != VoiceAvailability.Available) return base
            val vs = voicesFor(lang)
            if (vs.isEmpty()) return base
            val usable = vs.any { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            return if (usable) VoiceAvailability.Available else VoiceAvailability.MissingData
        }
        _voices.value = VoiceStatus(
            engine = t.defaultEngine, initialized = true,
            en = offline(Language.en), he = offline(Language.he),
            enNeedsNetwork = needsNet(Language.en), heNeedsNetwork = needsNet(Language.he),
            voicesEn = voicesFor(Language.en).map { it.name }.sorted(), voicesHe = voicesFor(Language.he).map { it.name }.sorted(),
        )
    }

    private fun langMatches(code: String, lang: Language) = when (lang) {
        Language.he -> code == "he" || code == "iw" || code == "heb"
        Language.en -> code == "en" || code == "eng"
    }

    private fun pump() {
        if (speaking != null || !ready) { if (initFailed) notifyIdle(); return }
        val next = queue.peek() ?: run { notifyIdle(); scheduleShutdown(); return }
        val wait = next.notBeforeMs - System.currentTimeMillis()
        if (wait > 0) { handler.postDelayed({ pump() }, wait); return }
        queue.poll()
        val now = Instant.now()
        val skip = when {
            !now.isBefore(next.dropAfter) -> "dropped_stale" // SPK-2
            inCall() -> "in_call"
            next.usage != SpeechUsage.Alarm && respectDnd() && dndActive() -> "dnd"
            next.output == SpeechOutput.HeadphonesOnly && !headphones() -> "no_headphones"
            next.overMedia == SpeechOverMediaPolicy.NotificationOnly && audio.isMusicActive -> "media_playing"
            else -> null
        }
        if (skip != null) {
            Log.i(TAG, "speech skipped for ${next.cueId}: $skip")
            lastResult = "skipped:$skip"
            pump(); return
        }
        speak(next)
    }

    private fun speak(item: Item) {
        val t = tts ?: return
        val locale = TextResolver.localeOf(item.language)
        val langResult = t.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            report(item, false, "voice_missing:${item.language}")
            pump(); return
        }
        prefs.voice(item.language)?.let { name -> t.voices?.firstOrNull { it.name == name }?.let { t.voice = it } }
        t.setSpeechRate(prefs.rate())
        val attrs = AudioAttributes.Builder()
            .setUsage(if (item.usage == SpeechUsage.Alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        t.setAudioAttributes(attrs)
        val gain = if (item.overMedia == SpeechOverMediaPolicy.PauseAndSpeak) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(gain).setAudioAttributes(attrs).setOnAudioFocusChangeListener({ }, handler).build()
        val granted = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = req
        if (!granted) Log.w(TAG, "audio focus not granted for ${item.cueId} (speaking anyway)")
        val uid = "${item.cueId}#${seq++}"
        speaking = item
        val r = t.speak(item.text, TextToSpeech.QUEUE_FLUSH, Bundle(), uid)
        Log.i(TAG, "speak ${item.cueId} lang=${item.language} usage=${item.usage} focus=$granted result=$r")
        if (r != TextToSpeech.SUCCESS) { finish(item, false, "speak_error:$r"); return }
        handler.postDelayed({ if (speaking === item) { t.stop(); finish(item, false, "timeout") } }, 30_000)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { Log.i(TAG, "utterance start $utteranceId") }
        override fun onDone(utteranceId: String?) { handler.post { speaking?.let { finish(it, true, null) } } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { handler.post { speaking?.let { finish(it, false, "engine_error") } } }
        override fun onError(utteranceId: String?, errorCode: Int) { handler.post { speaking?.let { finish(it, false, "engine_error:$errorCode") } } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.post { speaking?.let { finish(it, false, "stopped") } } }
    }

    private fun finish(item: Item, ok: Boolean, reason: String?) {
        if (speaking !== item) return
        speaking = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        report(item, ok, reason)
        pump()
    }

    private fun report(item: Item, ok: Boolean, reason: String?) {
        lastResult = if (ok) "spoken:${item.cueId}" else "failed:${item.cueId}:$reason"
        Log.i(TAG, "speech ${if (ok) "finished" else "failed ($reason)"} for ${item.cueId}")
        if (item.report && !item.cueId.startsWith("preview")) runCatching { onOutcome(item.cueId, ok, reason) }
    }

    private fun notifyIdle() {
        if (!isIdle()) return
        idleWaiters.forEach { it.complete(Unit) }
        idleWaiters.clear()
    }

    private val shutdown = Runnable {
        if (isIdle()) { tts?.shutdown(); tts = null; ready = false; Log.i(TAG, "TTS released (idle)") }
    }

    private fun scheduleShutdown() {
        handler.removeCallbacks(shutdown)
        handler.postDelayed(shutdown, 60_000)
    }

    private fun inCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION || audio.mode == AudioManager.MODE_RINGTONE

    private fun dndActive(): Boolean {
        val f = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun headphones(): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    companion object { private const val TAG = "DayCue" }
}

/** App-local speech preferences (not part of the synced config): rate and preferred voice per language. */
class SpeechPrefs(context: Context) {
    private val sp = context.getSharedPreferences("speech_prefs", Context.MODE_PRIVATE)
    fun rate(): Float = sp.getFloat("rate", 1.0f).coerceIn(0.5f, 2.0f)
    fun setRate(r: Float) = sp.edit().putFloat("rate", r.coerceIn(0.5f, 2.0f)).apply()
    fun voice(lang: Language): String? = sp.getString("voice_${lang.name}", null)
    fun setVoice(lang: Language, name: String?) = sp.edit().putString("voice_${lang.name}", name).apply()
}
