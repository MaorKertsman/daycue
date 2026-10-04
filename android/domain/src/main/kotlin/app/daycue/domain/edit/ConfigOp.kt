@file:UseSerializers(LocalTimeSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class)

package app.daycue.domain.edit

import app.daycue.domain.config.CalendarConfig
import app.daycue.domain.config.CalendarPreference
import app.daycue.domain.config.CalendarRule
import app.daycue.domain.config.CollisionSettings
import app.daycue.domain.config.ContextRules
import app.daycue.domain.config.CueProfile
import app.daycue.domain.config.EventDecisionOverride
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.GlobalSettings
import app.daycue.domain.config.Habit
import app.daycue.domain.config.Language
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MorningAlarm
import app.daycue.domain.config.OverrideScope
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.Place
import app.daycue.domain.config.PostureCycleConfig
import app.daycue.domain.config.PostureMode
import app.daycue.domain.config.QuietHours
import app.daycue.domain.config.Routine
import app.daycue.domain.config.RoutineStep
import app.daycue.domain.config.SessionRules
import app.daycue.domain.config.SpeechSettings
import app.daycue.domain.config.TravelPolicy
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.time.DayOfWeekSerializer
import app.daycue.domain.time.LocalDateSerializer
import app.daycue.domain.time.LocalTimeSerializer
import app.daycue.domain.time.TimeWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Every edit to [app.daycue.domain.config.DayCueConfig] — from the UI, MCP commands or import — is a list
 * of these, applied by [ConfigEditor.applyOps] (ARCHITECTURE §3.2, CLAUDE.md "one edit path").
 */
@Serializable
sealed interface ConfigOp {
    // Habits (interval + transition)
    @Serializable @SerialName("upsertHabit") data class UpsertHabit(val habit: Habit) : ConfigOp
    @Serializable @SerialName("deleteHabit") data class DeleteHabit(val id: String) : ConfigOp
    @Serializable @SerialName("setHabitEnabled") data class SetHabitEnabled(val id: String, val enabled: Boolean) : ConfigOp
    @Serializable @SerialName("setHabitInterval") data class SetHabitInterval(val id: String, val minutes: Int) : ConfigOp
    @Serializable @SerialName("setHabitActiveHours") data class SetHabitActiveHours(val id: String, val window: TimeWindow?, val days: Set<DayOfWeek>? = null) : ConfigOp

    /** Pause-until for a habit, the posture cycle or everything non-exempt. `pause = null` resumes. */
    @Serializable @SerialName("setPause") data class SetPause(val target: PauseTarget, val pause: PauseSpec?) : ConfigOp

    // Posture
    @Serializable @SerialName("setPostureCycle") data class SetPostureCycle(val cycle: PostureCycleConfig) : ConfigOp
    @Serializable @SerialName("setPostureModes") data class SetPostureModes(val modes: List<PostureMode>) : ConfigOp
    @Serializable @SerialName("setPostureEnabled") data class SetPostureEnabled(val enabled: Boolean) : ConfigOp

    // Medication (sensitive / destructive, MED-9)
    @Serializable @SerialName("upsertMedication") data class UpsertMedication(val medication: Medication) : ConfigOp
    @Serializable @SerialName("deleteMedication") data class DeleteMedication(val id: String) : ConfigOp
    @Serializable @SerialName("setMedicationTimes") data class SetMedicationTimes(val id: String, val times: List<LocalTime>, val days: Set<DayOfWeek>? = null) : ConfigOp
    @Serializable @SerialName("setMedicationTravelPolicy") data class SetMedicationTravelPolicy(val id: String, val policy: TravelPolicy) : ConfigOp
    /** MED-9: "pausing" a medication is an end-date edit. */
    @Serializable @SerialName("setMedicationEndDate") data class SetMedicationEndDate(val id: String, val endDate: LocalDate?) : ConfigOp

    // Routines
    @Serializable @SerialName("upsertRoutine") data class UpsertRoutine(val routine: Routine) : ConfigOp
    @Serializable @SerialName("deleteRoutine") data class DeleteRoutine(val id: String) : ConfigOp
    @Serializable @SerialName("duplicateRoutine") data class DuplicateRoutine(val sourceId: String, val newId: String, val newName: String) : ConfigOp
    @Serializable @SerialName("upsertRoutineStep") data class UpsertRoutineStep(val routineId: String, val step: RoutineStep, val index: Int? = null) : ConfigOp
    @Serializable @SerialName("deleteRoutineStep") data class DeleteRoutineStep(val routineId: String, val stepId: String) : ConfigOp
    @Serializable @SerialName("reorderRoutineSteps") data class ReorderRoutineSteps(val routineId: String, val stepIds: List<String>) : ConfigOp

    // Alarms
    @Serializable @SerialName("upsertAlarm") data class UpsertAlarm(val alarm: MorningAlarm) : ConfigOp
    @Serializable @SerialName("deleteAlarm") data class DeleteAlarm(val id: String) : ConfigOp
    @Serializable @SerialName("setAlarmEnabled") data class SetAlarmEnabled(val id: String, val enabled: Boolean) : ConfigOp
    /** ALM-4. `date = null` clears the skip. */
    @Serializable @SerialName("skipNextAlarm") data class SkipNextAlarm(val id: String, val date: LocalDate?) : ConfigOp

    // Places
    @Serializable @SerialName("upsertPlace") data class UpsertPlace(val place: Place) : ConfigOp
    @Serializable @SerialName("deletePlace") data class DeletePlace(val id: String) : ConfigOp
    @Serializable @SerialName("setPlaceLocation") data class SetPlaceLocation(val id: String, val center: GeoPoint?, val radiusM: Int? = null) : ConfigOp

    // Cue profiles
    @Serializable @SerialName("upsertCueProfile") data class UpsertCueProfile(val profile: CueProfile) : ConfigOp
    @Serializable @SerialName("deleteCueProfile") data class DeleteCueProfile(val id: String) : ConfigOp

    // Calendar
    @Serializable @SerialName("setCalendarConfig") data class SetCalendarConfig(val calendar: CalendarConfig) : ConfigOp
    @Serializable @SerialName("upsertCalendarRule") data class UpsertCalendarRule(val rule: CalendarRule, val index: Int? = null) : ConfigOp
    @Serializable @SerialName("deleteCalendarRule") data class DeleteCalendarRule(val id: String) : ConfigOp
    @Serializable @SerialName("reorderCalendarRules") data class ReorderCalendarRules(val ruleIds: List<String>) : ConfigOp
    @Serializable @SerialName("setCalendarPreference") data class SetCalendarPreference(val preference: CalendarPreference) : ConfigOp
    @Serializable @SerialName("removeCalendarPreference") data class RemoveCalendarPreference(val calendarId: String) : ConfigOp
    /** CAL-1 one-tap corrections; per instance or per series. `decision = null` removes the override. */
    @Serializable @SerialName("setEventOverride") data class SetEventOverride(val key: String, val scope: OverrideScope, val decision: EventDecisionOverride?) : ConfigOp

    // Context / sessions
    @Serializable @SerialName("setContextRules") data class SetContextRules(val rules: ContextRules) : ConfigOp
    @Serializable @SerialName("setSessionRules") data class SetSessionRules(val rules: SessionRules) : ConfigOp

    // Global settings
    @Serializable @SerialName("setQuietHours") data class SetQuietHours(val quietHours: QuietHours) : ConfigOp
    @Serializable @SerialName("setSpeechSettings") data class SetSpeechSettings(val speech: SpeechSettings) : ConfigOp
    @Serializable @SerialName("setCollisionSettings") data class SetCollisionSettings(val collision: CollisionSettings) : ConfigOp
    @Serializable @SerialName("setLanguage") data class SetLanguage(val language: Language) : ConfigOp
    @Serializable @SerialName("setGlobalSettings") data class SetGlobalSettings(val settings: GlobalSettings) : ConfigOp
}
