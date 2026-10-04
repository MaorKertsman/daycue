package app.daycue.ui.marks

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.daycue.R
import app.daycue.ui.theme.DayCueColors
import app.daycue.ui.theme.Tone

/** Cue identity: one mark and one hue per type (VISUAL.md 2.2, 4.2). Pure UI model, no domain types. */
enum class CueType(@StringRes val nameRes: Int) {
    Sunscreen(R.string.cue_sunscreen),
    Hydration(R.string.cue_hydration),
    Bottle(R.string.cue_bottle),
    Posture(R.string.cue_posture),
    Medication(R.string.cue_medication),
    Calendar(R.string.cue_calendar),
    Routine(R.string.cue_routine),
    Alarm(R.string.cue_alarm),
}

/** Visual treatment of a mark (VISUAL.md 2.3). `null` state draws the plain mark. */
enum class CueState(@StringRes val wordRes: Int) {
    Due(R.string.state_due),
    Scheduled(R.string.state_scheduled),
    Snoozed(R.string.state_snoozed),
    Paused(R.string.state_paused),
    Unknown(R.string.state_unknown),
    Error(R.string.state_error),
}

enum class PostureMode { Sit, Stand, Walk }

fun DayCueColors.tone(cue: CueType): Tone = when (cue) {
    CueType.Sunscreen -> sunscreen
    CueType.Hydration, CueType.Bottle -> hydration
    CueType.Posture -> posture
    CueType.Medication -> medication
    CueType.Calendar -> calendar
    CueType.Routine -> routine
    CueType.Alarm -> alarm
}

fun DayCueColors.tone(state: CueState): Tone? = when (state) {
    CueState.Snoozed -> snoozed
    CueState.Paused -> paused
    CueState.Unknown -> unknown
    CueState.Error -> error
    CueState.Due, CueState.Scheduled -> null
}

/** Text color for a state word: cue ink when due, `ink2` when scheduled, the state ink otherwise. */
fun DayCueColors.stateInk(cue: CueType, state: CueState): Color = when (state) {
    CueState.Due -> tone(cue).ink
    CueState.Scheduled -> ink2
    CueState.Snoozed -> snoozed.ink
    CueState.Paused -> paused.ink
    CueState.Unknown -> unknown.ink
    CueState.Error -> error.ink
}
