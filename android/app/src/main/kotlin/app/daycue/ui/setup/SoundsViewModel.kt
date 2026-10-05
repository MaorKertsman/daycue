package app.daycue.ui.setup

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import app.daycue.R
import app.daycue.delivery.VoiceStatus
import app.daycue.delivery.CueMedia
import app.daycue.domain.config.CollisionSettings
import app.daycue.domain.config.CueProfile
import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Language
import app.daycue.domain.config.QuietHours
import app.daycue.domain.config.SpeechSettings
import app.daycue.domain.edit.ConfigOp
import app.daycue.system.ReadinessId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cue profiles, speech, quiet hours and collision settings. Previews go through the facade (GEN-10). */
class SoundsViewModel(app: Application) : SetupViewModel(app) {
    val config: StateFlow<DayCueConfig?> = facade.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val voices: StateFlow<VoiceStatus> = facade.voices
    val rate = MutableStateFlow(facade.speechRate())

    // Voice status needs the speech engine to start; do it after the first frame so the screen is never blocked by it.
    init { viewModelScope.launch { kotlinx.coroutines.delay(1_200); facade.refreshReadiness() } }

    fun refreshVoices() { viewModelScope.launch { facade.refreshReadiness() } }

    suspend fun saveProfile(profile: CueProfile): EditResult = edit(listOf(ConfigOp.UpsertCueProfile(profile)))

    fun resetProfile(id: String) {
        val default = Defaults.cueProfiles().firstOrNull { it.id == id } ?: return
        viewModelScope.launch { edit(listOf(ConfigOp.UpsertCueProfile(default)), R.string.su_profile_reset) }
    }

    /** Plays exactly what a real cue of this profile would (sound, vibration, phrase). Nothing is posted or recorded. */
    fun preview(profileId: String) { viewModelScope.launch { facade.previewCueProfile(profileId) } }

    suspend fun saveSpeech(speech: SpeechSettings): EditResult = edit(listOf(ConfigOp.SetSpeechSettings(speech)))
    suspend fun saveCollision(c: CollisionSettings): EditResult = edit(listOf(ConfigOp.SetCollisionSettings(c)))
    suspend fun saveQuiet(q: QuietHours): EditResult = edit(listOf(ConfigOp.SetQuietHours(q)))

    fun setRate(value: Float) { val v = value.coerceIn(0.5f, 2.0f); facade.setSpeechRate(v); rate.value = v }
    fun voice(language: Language): String? = facade.voice(language)
    fun setVoice(language: Language, name: String?) { facade.setVoice(language, name) }
    fun installVoiceIntent(): Intent? = facade.fixIntent(ReadinessId.Voices)

    /**
     * The notification channel DayCue posts this profile on: the type's base channel when sound and vibration are the
     * type's defaults, else the variant channel for this sound and vibration (APP_API 6). Android owns a channel's
     * sound and vibration after creation, so the system screen is where those are changed afterwards.
     */
    fun channelId(profile: CueProfile): String {
        val (defSound, defVib) = CueMedia.defaults[profile.type] ?: (CueMedia.NONE to CueMedia.NONE)
        val sound = CueMedia.knownSound(profile.soundId)
        val vib = CueMedia.knownVibration(profile.vibrationId)
        return if (sound == defSound && vib == defVib) profile.type.channelId else "${profile.type.channelId}.s.$sound.v.$vib"
    }

    fun channelSettingsIntent(profile: CueProfile): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getApplication<Application>().packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId(profile))

    fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getApplication<Application>().packageName)
}

/** Selectable sounds and vibrations in the order shown. `morning` is the alarm tone and is offered only for alarms. */
internal val SOUND_IDS: List<String> = CueMedia.sounds.keys.filter { it != "morning" }
internal val VIBRATION_IDS: List<String> = CueMedia.vibrations.keys.toList()

internal fun CueType.isAlarm() = this == CueType.Alarm
