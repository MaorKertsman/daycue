package app.daycue.domain.edit

import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.engine.PauseTarget

/**
 * Per-op sensitivity (DOMAIN.md §5, security review H-1/M-5, MED-9). `sensitive` and `destructive` changes
 * need on-phone confirmation when they come from a remote caller.
 *
 * Every [ConfigOp] variant is classified explicitly: the `when` below has no `else`, so adding a variant
 * fails compilation until it is classified. [config] is the document the op is applied to (some classes
 * depend on whether the op edits an existing item).
 */
object ConfigSensitivity {

    fun of(config: DayCueConfig, op: ConfigOp): Sensitivity = when (op) {
        // Habits: interval / hours / a single habit's pause or enable state are ordinary.
        is ConfigOp.UpsertHabit -> Sensitivity.ordinary
        is ConfigOp.DeleteHabit -> Sensitivity.destructive
        is ConfigOp.SetHabitEnabled -> Sensitivity.ordinary
        is ConfigOp.SetHabitInterval -> Sensitivity.ordinary
        is ConfigOp.SetHabitActiveHours -> Sensitivity.ordinary
        // Pausing everything silences cues; resuming (pause = null) only restores them.
        is ConfigOp.SetPause -> when (op.target) {
            PauseTarget.All -> if (op.pause != null) Sensitivity.sensitive else Sensitivity.ordinary
            PauseTarget.Posture, is PauseTarget.Habit -> Sensitivity.ordinary
        }
        // Posture durations / modes / on-off.
        is ConfigOp.SetPostureCycle -> Sensitivity.ordinary
        is ConfigOp.SetPostureModes -> Sensitivity.ordinary
        is ConfigOp.SetPostureEnabled -> Sensitivity.ordinary
        // Medication (MED-9).
        is ConfigOp.UpsertMedication -> Sensitivity.sensitive
        is ConfigOp.DeleteMedication -> Sensitivity.destructive
        is ConfigOp.SetMedicationTimes -> Sensitivity.sensitive
        is ConfigOp.SetMedicationTravelPolicy -> Sensitivity.sensitive
        is ConfigOp.SetMedicationEndDate -> Sensitivity.sensitive
        // Routines: step edits are ordinary.
        is ConfigOp.UpsertRoutine -> Sensitivity.ordinary
        is ConfigOp.DeleteRoutine -> Sensitivity.destructive
        is ConfigOp.DuplicateRoutine -> Sensitivity.ordinary
        is ConfigOp.UpsertRoutineStep -> Sensitivity.ordinary
        is ConfigOp.DeleteRoutineStep -> Sensitivity.destructive
        is ConfigOp.ReorderRoutineSteps -> Sensitivity.ordinary
        // Alarms: anything that can disable, skip or move an existing alarm. Adding a new alarm or
        // enabling one only adds alerting.
        is ConfigOp.UpsertAlarm -> if (config.alarm(op.alarm.id) != null) Sensitivity.sensitive else Sensitivity.ordinary
        is ConfigOp.DeleteAlarm -> Sensitivity.destructive
        is ConfigOp.SetAlarmEnabled -> if (op.enabled) Sensitivity.ordinary else Sensitivity.sensitive
        is ConfigOp.SkipNextAlarm -> if (op.date != null) Sensitivity.sensitive else Sensitivity.ordinary
        // Places: relocating (center or radius of an existing place) moves geofences.
        is ConfigOp.UpsertPlace -> config.place(op.place.id)
            ?.let { if (it.center != op.place.center || it.radiusM != op.place.radiusM) Sensitivity.sensitive else Sensitivity.ordinary }
            ?: Sensitivity.ordinary
        is ConfigOp.DeletePlace -> Sensitivity.destructive
        is ConfigOp.SetPlaceLocation -> Sensitivity.sensitive
        // Cue profiles: medication/alarm profiles always; others when the edit can make cues quieter.
        is ConfigOp.UpsertCueProfile -> cueProfile(config, op)
        is ConfigOp.DeleteCueProfile -> Sensitivity.destructive
        // Calendar: the whole calendar document (default policy, lock-screen titles, spoken titles) is
        // sensitive; rule / lead-time / per-calendar / per-event corrections are ordinary.
        is ConfigOp.SetCalendarConfig -> Sensitivity.sensitive
        is ConfigOp.UpsertCalendarRule -> Sensitivity.ordinary
        is ConfigOp.DeleteCalendarRule -> Sensitivity.destructive
        is ConfigOp.ReorderCalendarRules -> Sensitivity.ordinary
        is ConfigOp.SetCalendarPreference -> Sensitivity.ordinary
        is ConfigOp.RemoveCalendarPreference -> Sensitivity.destructive
        is ConfigOp.SetEventOverride -> Sensitivity.ordinary
        // Context detection.
        is ConfigOp.SetContextRules -> Sensitivity.sensitive
        is ConfigOp.SetSessionRules -> Sensitivity.sensitive
        // Global settings that can silence or delay cues.
        is ConfigOp.SetQuietHours -> Sensitivity.sensitive
        is ConfigOp.SetSpeechSettings -> Sensitivity.sensitive
        is ConfigOp.SetCollisionSettings -> Sensitivity.sensitive
        is ConfigOp.SetLanguage -> Sensitivity.ordinary
        is ConfigOp.SetGlobalSettings -> Sensitivity.sensitive
    }

    private fun cueProfile(config: DayCueConfig, op: ConfigOp.UpsertCueProfile): Sensitivity {
        val new = op.profile
        val old = config.cueProfiles.firstOrNull { it.id == new.id }
        val critical = setOf(CueType.Medication, CueType.Alarm)
        if (new.type in critical || old?.type in critical) return Sensitivity.sensitive
        if (old == null) return Sensitivity.ordinary
        val quieter = (old.soundEnabled && !new.soundEnabled) || (old.vibrationEnabled && !new.vibrationEnabled) ||
            (old.speechEnabled && !new.speechEnabled) || old.soundId != new.soundId || old.vibrationId != new.vibrationId || old.type != new.type
        return if (quieter) Sensitivity.sensitive else Sensitivity.ordinary
    }

    /** Highest class over a sequence of ops, each classified against the document it is applied to. */
    internal fun max(a: Sensitivity, b: Sensitivity): Sensitivity = if (a.ordinal >= b.ordinal) a else b
}
