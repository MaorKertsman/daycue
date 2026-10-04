package app.daycue.delivery

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.daycue.R
import app.daycue.domain.config.CueType

/**
 * Bundled original cue sounds (`res/raw/cue_*.wav`, synthesized by `CueSoundSynth` in the unit tests;
 * no third-party audio) and vibration patterns. Ids are the cue-profile ids from `Defaults.cueProfiles()`.
 */
object CueMedia {
    const val NONE = "none"
    const val ALARM_SOURCE = "alarm-source"

    /** soundId -> raw resource. */
    val sounds: Map<String, Int> = mapOf(
        "soft-bell" to R.raw.cue_soft_bell,
        "wood-tap" to R.raw.cue_wood_tap,
        "two-note-rise" to R.raw.cue_two_note_rise,
        "pop" to R.raw.cue_pop,
        "bright-chime" to R.raw.cue_bright_chime,
        "low-marimba" to R.raw.cue_low_marimba,
        "droplet" to R.raw.cue_droplet,
        "morning" to R.raw.cue_alarm_morning,
    )

    /** Alarm tones by `AlarmSource.LocalTone.toneId`; unknown ids fall back to "morning". */
    fun alarmTone(toneId: String?): Int = sounds[toneId] ?: R.raw.cue_alarm_morning

    /** Vibration patterns: off/on millisecond pairs starting with an initial delay (Android `VibrationEffect` waveform). */
    val vibrations: Map<String, LongArray> = mapOf(
        "continuous" to longArrayOf(0, 800, 400, 800, 400),
        "long-short-long" to longArrayOf(0, 500, 150, 150, 150, 500),
        "single-short" to longArrayOf(0, 120),
        "double-short" to longArrayOf(0, 120, 120, 120),
        "single-long" to longArrayOf(0, 450),
        "triple-short" to longArrayOf(0, 100, 100, 100, 100, 100),
        "two-long" to longArrayOf(0, 400, 200, 400),
        "single-short-soft" to longArrayOf(0, 70),
    )

    /** Default identity per channel type (PRODUCT §8.1), used for the base channel of each type. */
    val defaults: Map<CueType, Pair<String, String>> = mapOf(
        CueType.Alarm to (NONE to NONE), // silent channel: AlarmRingingService plays audio itself
        CueType.Medication to ("soft-bell" to "long-short-long"),
        CueType.RoutineStep to ("wood-tap" to "single-short"),
        CueType.Calendar to ("two-note-rise" to "double-short"),
        CueType.WaterBottle to ("pop" to "single-long"),
        CueType.Sunscreen to ("bright-chime" to "triple-short"),
        CueType.Posture to ("low-marimba" to "two-long"),
        CueType.Hydration to ("droplet" to "single-short-soft"),
        CueType.Habit to ("droplet" to "single-short-soft"),
        CueType.Notice to (NONE to NONE),
    )

    fun soundUri(context: Context, soundId: String?): Uri? {
        val res = soundId?.let { sounds[it] } ?: return null
        // By name, not numeric id: channels persist this URI across app updates, ids may change.
        return Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority(context.packageName)
            .appendPath("raw").appendPath(context.resources.getResourceEntryName(res)).build()
    }

    fun knownSound(id: String?): String = if (id != null && id in sounds) id else NONE
    fun knownVibration(id: String?): String = if (id != null && id in vibrations) id else NONE
}
