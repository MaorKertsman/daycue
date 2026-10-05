package app.daycue.ui.setup

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.delivery.CueMedia
import app.daycue.delivery.VoiceAvailability
import app.daycue.domain.config.CueProfile
import app.daycue.domain.config.CueType
import app.daycue.domain.config.Language
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.QuietWindow
import app.daycue.domain.config.SpeechInMeetingPolicy
import app.daycue.domain.config.SpeechOverMediaPolicy
import app.daycue.domain.time.TimeWindow
import app.daycue.ui.components.AdvancedSection
import app.daycue.ui.components.ChoiceRow
import app.daycue.ui.components.CuePreviewButton
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.components.TimeStepperPicker
import app.daycue.ui.components.TimeWindowField
import app.daycue.ui.marks.CueMark
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationText
import app.daycue.ui.util.isolatedRange
import app.daycue.ui.util.formatTimeRaw
import app.daycue.ui.util.ltr
import app.daycue.ui.marks.CueType as MarkType
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

// ---- helpers -----------------------------------------------------------------------------------------------

private fun CueType.mark(): MarkType? = when (this) {
    CueType.Alarm -> MarkType.Alarm
    CueType.Medication -> MarkType.Medication
    CueType.RoutineStep -> MarkType.Routine
    CueType.Calendar -> MarkType.Calendar
    CueType.WaterBottle -> MarkType.Bottle
    CueType.Sunscreen -> MarkType.Sunscreen
    CueType.Posture -> MarkType.Posture
    CueType.Hydration, CueType.Habit -> MarkType.Hydration
    CueType.Notice -> null
}

private fun CueType.nameRes(): Int = when (this) {
    CueType.Alarm -> R.string.cue_alarm
    CueType.Medication -> R.string.cue_medication
    CueType.RoutineStep -> R.string.cue_routine
    CueType.Calendar -> R.string.cue_calendar
    CueType.WaterBottle -> R.string.cue_bottle
    CueType.Sunscreen -> R.string.cue_sunscreen
    CueType.Posture -> R.string.cue_posture
    CueType.Hydration -> R.string.cue_hydration
    CueType.Habit -> R.string.su_cue_habit
    CueType.Notice -> R.string.su_cue_notice
}

@Composable
private fun soundName(id: String): String = stringResource(
    when (id) {
        "soft-bell" -> R.string.su_sound_soft_bell
        "wood-tap" -> R.string.su_sound_wood_tap
        "two-note-rise" -> R.string.su_sound_two_note_rise
        "pop" -> R.string.su_sound_pop
        "bright-chime" -> R.string.su_sound_bright_chime
        "low-marimba" -> R.string.su_sound_low_marimba
        "droplet" -> R.string.su_sound_droplet
        "morning" -> R.string.su_sound_morning
        "alarm-source" -> R.string.su_sound_alarm_source
        else -> R.string.su_sound_none
    },
)

@Composable
private fun vibrationName(id: String): String = stringResource(
    when (id) {
        "continuous" -> R.string.su_vib_continuous
        "long-short-long" -> R.string.su_vib_long_short_long
        "single-short" -> R.string.su_vib_single_short
        "double-short" -> R.string.su_vib_double_short
        "single-long" -> R.string.su_vib_single_long
        "triple-short" -> R.string.su_vib_triple_short
        "two-long" -> R.string.su_vib_two_long
        "single-short-soft" -> R.string.su_vib_soft
        else -> R.string.su_vib_none
    },
)

@Composable
private fun profileSummary(p: CueProfile): String {
    val sound = if (p.type.isAlarm()) stringResource(R.string.su_sound_alarm_source)
    else if (p.soundEnabled) soundName(p.soundId) else stringResource(R.string.su_sound_off)
    val vib = if (p.vibrationEnabled) vibrationName(p.vibrationId) else stringResource(R.string.su_vib_off)
    val speak = stringResource(if (p.speechEnabled) R.string.su_speaks else R.string.su_silent_speech)
    return listOf(sound, vib, speak).joinToString(" · ")
}

/** Plays a bundled tone or a vibration pattern as the owner browses (an audition; nothing is saved or recorded). */
private class Auditioner(private val context: android.content.Context) {
    private var player: MediaPlayer? = null
    fun playSound(id: String) {
        val res = CueMedia.sounds[id] ?: return
        release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                val afd = context.resources.openRawResourceFd(res)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        }.getOrNull()
    }
    fun vibrate(id: String) {
        val pattern = CueMedia.vibrations[id] ?: return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
    fun release() { runCatching { player?.release() }; player = null }
}

@Composable
internal fun TimePickerSheet(titleRes: Int, initialMinutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(initialMinutes) }
    DayCueBottomSheet(onDismiss, stringResource(titleRes), primaryLabel = stringResource(R.string.su_done), onPrimary = { onPick(minutes); onDismiss() }) {
        TimeStepperPicker(minutes, { minutes = it })
    }
}

// ---- Sounds & speech home --------------------------------------------------------------------------------------

@Composable
fun SoundsScreen(onBack: () -> Unit, push: (String) -> Unit) {
    val vm: SoundsViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val c = DayCueTheme.colors
    SetupFrame(stringResource(R.string.su_sounds_title), onBack) {
        val config = cfg
        if (config == null) { repeat(4) { SkeletonRow(mark = true) }; return@SetupFrame }
        SectionHeader(stringResource(R.string.su_profiles_header), topPadding = 8.dp)
        config.cueProfiles.sortedBy { it.type.priority }.forEach { p ->
            DayCueRow(
                primary = stringResource(p.type.nameRes()),
                secondary = profileSummary(p),
                leading = { p.type.mark()?.let { CueMark(it) } },
                trailing = { GlyphIcon(Glyph.Chevron, c.ink2) },
                onClick = { push(SetupRoutes.PROFILE + p.id) },
            )
        }
        SectionHeader(stringResource(R.string.su_sounds_more_header))
        SettingRow(stringResource(R.string.su_speech_title), stringResource(if (config.settings.speech.enabled) R.string.su_speech_on else R.string.su_speech_off), { push(SetupRoutes.SPEECH) })
        SettingRow(stringResource(R.string.su_quiet_title), quietSummary(config.settings.quietHours), { push(SetupRoutes.QUIET) })
    }
}

@Composable
private fun quietSummary(q: app.daycue.domain.config.QuietHours): String {
    if (!q.enabled || q.windows.isEmpty()) return stringResource(R.string.su_quiet_off)
    val w = q.windows.first().window
    val range = isolatedRange(formatTimeRaw(w.start.hour, w.start.minute), formatTimeRaw(w.end.hour, w.end.minute))
    return if (q.windows.size > 1) stringResource(R.string.su_quiet_more, range, q.windows.size - 1) else range
}

// ---- One profile ----------------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(profileId: String, onBack: () -> Unit) {
    val vm: SoundsViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auditioner = remember { Auditioner(context) }
    DisposableEffect(Unit) { onDispose { auditioner.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshVoices() }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var langSheet by remember { mutableStateOf(false) }
    val c = DayCueTheme.colors

    val config = cfg
    if (config == null) { SetupFrame(stringResource(R.string.su_profile_title), onBack) { repeat(4) { SkeletonRow() } }; return }
    val profile = config.cueProfiles.firstOrNull { it.id == profileId }
    if (profile == null) {
        SetupFrame(stringResource(R.string.su_profile_title), onBack) {
            StateBlock(StateBlockKind.Error, stringResource(R.string.su_profile_missing), body = stringResource(R.string.su_profile_missing_body), actionLabel = stringResource(R.string.su_back), onAction = onBack)
        }
        return
    }
    fun save(change: (CueProfile) -> CueProfile) { scope.launch { vm.saveProfile(change(profile)) } }
    var phraseEn by rememberSaveable(profile.id) { mutableStateOf(profile.phrase.en) }
    var phraseHe by rememberSaveable(profile.id) { mutableStateOf(profile.phrase.he) }
    // Phrases save a moment after typing stops.
    androidx.compose.runtime.LaunchedEffect(phraseEn, phraseHe) {
        if (phraseEn != profile.phrase.en || phraseHe != profile.phrase.he) {
            kotlinx.coroutines.delay(800)
            if (phraseEn.length <= 200 && phraseHe.length <= 200) vm.saveProfile(profile.copy(phrase = LocalizedText(phraseEn, phraseHe)))
        }
    }
    val markType = profile.type.mark()
    val effectiveLang = profile.language ?: config.settings.language
    val voiceMissing = when (effectiveLang) {
        Language.he -> voices.he == VoiceAvailability.MissingData || voices.he == VoiceAvailability.NotSupported
        Language.en -> voices.en == VoiceAvailability.MissingData || voices.en == VoiceAvailability.NotSupported
    }

    SetupFrame(stringResource(profile.type.nameRes()), onBack) {
        // Preview plays what a real cue would: sound, vibration and phrase (GEN-10).
        DayCueTextButton(stringResource(R.string.su_preview), { vm.preview(profile.id) })

        SectionHeader(stringResource(R.string.su_sound_header))
        if (profile.type.isAlarm()) {
            Para(stringResource(R.string.su_alarm_sound_note))
        } else {
            SwitchRow(stringResource(R.string.su_sound_switch), profile.soundEnabled, { on -> save { it.copy(soundEnabled = on) } })
            if (profile.soundEnabled) {
                (SOUND_IDS).forEach { id ->
                    SoundChoice(soundName(id), profile.soundId == id, onPlay = { auditioner.playSound(id) }, onSelect = { save { it.copy(soundId = id) } })
                }
            }
        }

        SectionHeader(stringResource(R.string.su_vibration_header))
        SwitchRow(stringResource(R.string.su_vibration_switch), profile.vibrationEnabled, { on -> save { it.copy(vibrationEnabled = on) } })
        if (profile.vibrationEnabled) {
            VIBRATION_IDS.forEach { id ->
                SoundChoice(vibrationName(id), profile.vibrationId == id, onPlay = { auditioner.vibrate(id) }, onSelect = { save { it.copy(vibrationId = id) } })
            }
        }

        SectionHeader(stringResource(R.string.su_phrase_header))
        SwitchRow(stringResource(R.string.su_speak_switch), profile.speechEnabled, { on -> save { it.copy(speechEnabled = on) } }, secondary = stringResource(R.string.su_speak_switch_hint))
        if (profile.speechEnabled) {
            DayCueTextField(phraseEn, { phraseEn = it }, stringResource(R.string.su_phrase_en), error = if (phraseEn.length > 200) stringResource(R.string.su_err_length) else null)
            Gap(8)
            DayCueTextField(phraseHe, { phraseHe = it }, stringResource(R.string.su_phrase_he), error = if (phraseHe.length > 200) stringResource(R.string.su_err_length) else null)
            SettingRow(stringResource(R.string.su_profile_language), langWord(profile.language), { langSheet = true })
            if (voiceMissing) {
                StateBlock(
                    StateBlockKind.Uncertain,
                    stringResource(if (effectiveLang == Language.he) R.string.su_voice_missing_he else R.string.su_voice_missing_en),
                    body = stringResource(R.string.su_voice_missing_cue),
                    actionLabel = stringResource(R.string.su_install_voice),
                    onAction = { context.startActivitySafely(vm.installVoiceIntent()) },
                )
            }
        }

        Gap(8)
        DayCueTextButton(stringResource(R.string.su_channel_open), { context.startActivitySafely(vm.channelSettingsIntent(profile)) })
        LearnMore(stringResource(R.string.su_channel_explain))
        DayCueTextButton(stringResource(R.string.su_profile_reset_action), { vm.resetProfile(profile.id); phraseEn = ""; phraseHe = "" })
    }

    if (langSheet) {
        val opts = listOf<Language?>(null, Language.en, Language.he)
        ChoiceSheet(
            stringResource(R.string.su_profile_language),
            opts.map { PolicyOption(langWord(it), stringResource(langHint(it))) },
            opts.indexOf(profile.language),
            { i -> save { it.copy(language = opts[i]) } },
            { langSheet = false },
        )
    }
    @Suppress("UNUSED_EXPRESSION") c
}

@Composable
private fun langWord(l: Language?) = stringResource(
    when (l) {
        null -> R.string.su_lang_app
        Language.en -> R.string.su_lang_en
        Language.he -> R.string.su_lang_he
    },
)

private fun langHint(l: Language?): Int = when (l) {
    null -> R.string.su_lang_app_hint
    Language.en -> R.string.su_lang_en_hint
    Language.he -> R.string.su_lang_he_hint
}

/** A radio row with its own play button (a separate 48dp target). */
@Composable
private fun SoundChoice(label: String, selected: Boolean, onPlay: () -> Unit, onSelect: () -> Unit) {
    val c = DayCueTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ChoiceRow(label, "", selected, onSelect)
        }
        DayCueTextButton(stringResource(R.string.su_play), onPlay)
    }
}

// ---- Speech --------------------------------------------------------------------------------------------------------

@Composable
fun SpeechScreen(onBack: () -> Unit) {
    val vm: SoundsViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val rate by vm.rate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshVoices() }
    var mediaSheet by remember { mutableStateOf(false) }
    var meetingSheet by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf<Language?>(null) }

    SetupFrame(stringResource(R.string.su_speech_title), onBack) {
        val config = cfg
        if (config == null) { repeat(4) { SkeletonRow() }; return@SetupFrame }
        val s = config.settings.speech
        fun save(change: (app.daycue.domain.config.SpeechSettings) -> app.daycue.domain.config.SpeechSettings) { scope.launch { vm.saveSpeech(change(s)) } }
        SwitchRow(stringResource(R.string.su_speech_switch), s.enabled, { on -> save { it.copy(enabled = on) } }, secondary = stringResource(if (s.enabled) R.string.su_speech_state_on else R.string.su_speech_state_off))

        SectionHeader(stringResource(R.string.su_voices_header))
        voiceBlock(Language.en, voices.en, voices.voicesEn.size, vm, onChoose = { voiceSheet = Language.en }, onInstall = { context.startActivitySafely(vm.installVoiceIntent()) })
        voiceBlock(Language.he, voices.he, voices.voicesHe.size, vm, onChoose = { voiceSheet = Language.he }, onInstall = { context.startActivitySafely(vm.installVoiceIntent()) })
        if (voices.he == VoiceAvailability.MissingData) {
            LearnMore(stringResource(R.string.su_voice_install_steps), label = stringResource(R.string.su_how_to_install))
        }
        // The speech engine is shown by its name ("Google Speech Services"), never its package id (REVIEW-2 C3).
        val engineLabel = remember(voices.engine) { engineLabel(context, voices.engine) }
        if (engineLabel != null) Hint(stringResource(R.string.su_voice_engine, engineLabel))

        StepperRow(
            label = stringResource(R.string.su_rate),
            valueText = String.format(Locale.ROOT, "%.1fx", rate).ltr(),
            valueDescription = stringResource(R.string.su_rate_desc, String.format(Locale.ROOT, "%.1f", rate)),
            onDecrease = { vm.setRate(Math.round((rate - 0.1f) * 10) / 10f) },
            onIncrease = { vm.setRate(Math.round((rate + 0.1f) * 10) / 10f) },
            canDecrease = rate > 0.5f, canIncrease = rate < 2.0f,
            hint = stringResource(R.string.su_rate_hint),
        )

        SectionHeader(stringResource(R.string.su_when_header))
        SettingRow(stringResource(R.string.su_over_media), overMediaWord(s.overMedia), { mediaSheet = true })
        SettingRow(stringResource(R.string.su_in_meeting), inMeetingWord(s.inMeeting), { meetingSheet = true })
        SwitchRow(
            stringResource(R.string.su_headphones_only), s.output == app.daycue.domain.config.SpeechOutput.HeadphonesOnly,
            { on -> save { it.copy(output = if (on) app.daycue.domain.config.SpeechOutput.HeadphonesOnly else app.daycue.domain.config.SpeechOutput.AnyRoute) } },
            secondary = stringResource(R.string.su_headphones_only_hint),
        )

        SectionHeader(stringResource(R.string.su_collision_header))
        val col = config.settings.collision
        fun saveCol(change: (app.daycue.domain.config.CollisionSettings) -> app.daycue.domain.config.CollisionSettings) { scope.launch { vm.saveCollision(change(col)) } }
        MinutesStepper(R.string.su_col_merge, col.mergeWindowMin, 0, 10, R.string.su_col_merge_hint) { v -> saveCol { it.copy(mergeWindowMin = v) } }
        StepperRow(
            stringResource(R.string.su_col_gap), stringResource(R.string.su_seconds, col.minAudibleGapSec), stringResource(R.string.su_seconds, col.minAudibleGapSec),
            { saveCol { it.copy(minAudibleGapSec = (it.minAudibleGapSec - 15).coerceAtLeast(0)) } },
            { saveCol { it.copy(minAudibleGapSec = (it.minAudibleGapSec + 15).coerceAtMost(300)) } },
            canDecrease = col.minAudibleGapSec > 0, canIncrease = col.minAudibleGapSec < 300, hint = stringResource(R.string.su_col_gap_hint),
        )
        StepperRow(
            stringResource(R.string.su_col_items), col.maxSpokenItems.toString().ltr(), col.maxSpokenItems.toString(),
            { saveCol { it.copy(maxSpokenItems = (it.maxSpokenItems - 1).coerceAtLeast(1)) } },
            { saveCol { it.copy(maxSpokenItems = (it.maxSpokenItems + 1).coerceAtMost(5)) } },
            canDecrease = col.maxSpokenItems > 1, canIncrease = col.maxSpokenItems < 5, hint = stringResource(R.string.su_col_items_hint),
        )
        StepperRow(
            stringResource(R.string.su_col_age), stringResource(R.string.su_seconds, col.speechMaxAgeSec), stringResource(R.string.su_seconds, col.speechMaxAgeSec),
            { saveCol { it.copy(speechMaxAgeSec = (it.speechMaxAgeSec - 30).coerceAtLeast(30)) } },
            { saveCol { it.copy(speechMaxAgeSec = (it.speechMaxAgeSec + 30).coerceAtMost(600)) } },
            canDecrease = col.speechMaxAgeSec > 30, canIncrease = col.speechMaxAgeSec < 600, hint = stringResource(R.string.su_col_age_hint),
        )
    }

    cfg?.let { config ->
        val s = config.settings.speech
        if (mediaSheet) {
            val opts = listOf(SpeechOverMediaPolicy.DuckAndSpeak, SpeechOverMediaPolicy.PauseAndSpeak, SpeechOverMediaPolicy.NotificationOnly)
            ChoiceSheet(
                stringResource(R.string.su_over_media),
                opts.map { PolicyOption(overMediaWord(it), stringResource(overMediaHint(it))) },
                opts.indexOf(s.overMedia),
                { i -> scope.launch { vm.saveSpeech(s.copy(overMedia = opts[i])) } },
                { mediaSheet = false },
            )
        }
        if (meetingSheet) {
            val opts = listOf(SpeechInMeetingPolicy.NotificationOnly, SpeechInMeetingPolicy.VibrateOnly, SpeechInMeetingPolicy.SpeakAnyway)
            ChoiceSheet(
                stringResource(R.string.su_in_meeting),
                opts.map { PolicyOption(inMeetingWord(it), stringResource(inMeetingHint(it))) },
                opts.indexOf(s.inMeeting),
                { i -> scope.launch { vm.saveSpeech(s.copy(inMeeting = opts[i])) } },
                { meetingSheet = false },
            )
        }
        voiceSheet?.let { lang ->
            val names = if (lang == Language.he) voices.voicesHe else voices.voicesEn
            val current = vm.voice(lang)
            DayCueBottomSheet({ voiceSheet = null }, stringResource(R.string.su_voice_pick, stringResource(if (lang == Language.he) R.string.su_lang_he else R.string.su_lang_en))) {
                ChoiceRow(stringResource(R.string.su_voice_default), stringResource(R.string.su_voice_default_hint), current == null, { vm.setVoice(lang, null); voiceSheet = null })
                names.forEach { n -> ChoiceRow(n, "", current == n, { vm.setVoice(lang, n); voiceSheet = null }) }
                if (names.isEmpty()) Hint(stringResource(R.string.su_voice_none_listed))
            }
        }
    }
}

@Composable
private fun voiceBlock(lang: Language, state: VoiceAvailability, count: Int, vm: SoundsViewModel, onChoose: () -> Unit, onInstall: () -> Unit) {
    val name = stringResource(if (lang == Language.he) R.string.su_lang_he else R.string.su_lang_en)
    val statusRes = when (state) {
        VoiceAvailability.Available -> R.string.su_voice_ready
        VoiceAvailability.MissingData -> R.string.su_voice_missing
        VoiceAvailability.NotSupported -> R.string.su_voice_unsupported
        VoiceAvailability.EngineUnavailable -> R.string.su_voice_no_engine
        VoiceAvailability.Unknown -> R.string.su_voice_checking
    }
    val problem = state == VoiceAvailability.MissingData || state == VoiceAvailability.NotSupported || state == VoiceAvailability.EngineUnavailable
    val c = DayCueTheme.colors
    val chosen = vm.voice(lang)
    DayCueRow(
        primary = name,
        secondary = stringResource(statusRes) + (if (chosen != null) " · $chosen" else ""),
        secondaryColor = if (problem) c.error.ink else c.ink2,
        leading = { if (problem) app.daycue.ui.components.StatusNotch() },
        trailing = {
            if (state == VoiceAvailability.MissingData) DayCueTextButton(stringResource(R.string.su_install_voice), onInstall)
            else if (state == VoiceAvailability.Available && count > 0) DayCueTextButton(stringResource(R.string.su_choose_voice), onChoose)
        },
        extra = if (state == VoiceAvailability.MissingData) ({ Text(stringResource(R.string.su_voice_missing_body, name), style = DayCueTheme.type.bodySmall, color = c.ink2) }) else null,
    )
}

@Composable
private fun overMediaWord(p: SpeechOverMediaPolicy) = stringResource(
    when (p) {
        SpeechOverMediaPolicy.DuckAndSpeak -> R.string.su_media_duck
        SpeechOverMediaPolicy.PauseAndSpeak -> R.string.su_media_pause
        SpeechOverMediaPolicy.NotificationOnly -> R.string.su_media_none
    },
)

private fun overMediaHint(p: SpeechOverMediaPolicy): Int = when (p) {
    SpeechOverMediaPolicy.DuckAndSpeak -> R.string.su_media_duck_hint
    SpeechOverMediaPolicy.PauseAndSpeak -> R.string.su_media_pause_hint
    SpeechOverMediaPolicy.NotificationOnly -> R.string.su_media_none_hint
}

@Composable
private fun inMeetingWord(p: SpeechInMeetingPolicy) = stringResource(
    when (p) {
        SpeechInMeetingPolicy.NotificationOnly -> R.string.su_meet_silent
        SpeechInMeetingPolicy.VibrateOnly -> R.string.su_meet_vibrate
        SpeechInMeetingPolicy.SpeakAnyway -> R.string.su_meet_speak
    },
)

private fun inMeetingHint(p: SpeechInMeetingPolicy): Int = when (p) {
    SpeechInMeetingPolicy.NotificationOnly -> R.string.su_meet_silent_hint
    SpeechInMeetingPolicy.VibrateOnly -> R.string.su_meet_vibrate_hint
    SpeechInMeetingPolicy.SpeakAnyway -> R.string.su_meet_speak_hint
}

// ---- Quiet hours -----------------------------------------------------------------------------------------------------

@Composable
fun QuietScreen(onBack: () -> Unit) {
    val vm: SoundsViewModel = viewModel()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Triple<Int, Boolean, Int>?>(null) } // window index, editing start?, initial minutes
    val c = DayCueTheme.colors

    SetupFrame(stringResource(R.string.su_quiet_title), onBack) {
        val config = cfg
        if (config == null) { repeat(3) { SkeletonRow() }; return@SetupFrame }
        val q = config.settings.quietHours
        fun save(change: (app.daycue.domain.config.QuietHours) -> app.daycue.domain.config.QuietHours) { scope.launch { vm.saveQuiet(change(q)) } }
        SwitchRow(stringResource(R.string.su_quiet_switch), q.enabled, { on -> save { it.copy(enabled = on) } })
        if (q.enabled) {
            q.windows.forEachIndexed { index, qw ->
                // "Window 1" only appears once there is more than one window.
                if (q.windows.size > 1) SectionHeader(stringResource(R.string.su_quiet_window, index + 1))
                TimeWindowField(
                    qw.window.start.hour * 60 + qw.window.start.minute, qw.window.end.hour * 60 + qw.window.end.minute,
                    { picker = Triple(index, true, qw.window.start.hour * 60 + qw.window.start.minute) },
                    { picker = Triple(index, false, qw.window.end.hour * 60 + qw.window.end.minute) },
                )
                Gap(4)
                Text(stringResource(R.string.su_quiet_days), style = DayCueTheme.type.label, color = c.ink2)
                DayChips(qw.days, { day ->
                    val next = if (day in qw.days) qw.days - day else qw.days + day
                    if (next.isNotEmpty()) save { it.copy(windows = it.windows.mapIndexed { i, w -> if (i == index) w.copy(days = next) else w }) }
                })
                if (q.windows.size > 1) {
                    DayCueTextButton(stringResource(R.string.su_quiet_remove), { save { it.copy(windows = it.windows.filterIndexed { i, _ -> i != index }) } })
                }
            }
            if (q.windows.size < 4) {
                Gap(8)
                DayCueTextButton(stringResource(R.string.su_quiet_add), {
                    save { it.copy(windows = it.windows + QuietWindow(TimeWindow(LocalTime.of(13, 0), LocalTime.of(14, 0)))) }
                })
            }
        }
        SwitchRow(stringResource(R.string.su_quiet_dnd), q.respectSystemDnd, { on -> save { it.copy(respectSystemDnd = on) } }, secondary = stringResource(R.string.su_quiet_dnd_hint))

        SectionHeader(stringResource(R.string.su_quiet_what_header))
        Para(stringResource(R.string.su_quiet_always))
        Para(stringResource(R.string.su_quiet_silent))
        Para(stringResource(R.string.su_quiet_wait))
    }

    picker?.let { (index, start, initial) ->
        val q = cfg?.settings?.quietHours
        if (q != null) {
            TimePickerSheet(if (start) R.string.su_ctx_hours_from else R.string.su_ctx_hours_to, initial, { minutes ->
                val t = LocalTime.of(minutes / 60, minutes % 60)
                scope.launch {
                    vm.saveQuiet(q.copy(windows = q.windows.mapIndexed { i, w ->
                        if (i != index) w else w.copy(window = if (start) TimeWindow(t, w.window.end) else TimeWindow(w.window.start, t))
                    }))
                }
            }, { picker = null })
        }
    }
}

/** The speech engine's own label from its package (null if it cannot be resolved or would just repeat the id). */
internal fun engineLabel(context: android.content.Context, pkg: String?): String? {
    if (pkg.isNullOrBlank()) return null
    val pm = context.packageManager
    return runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != pkg && !it.contains('.') }
}
