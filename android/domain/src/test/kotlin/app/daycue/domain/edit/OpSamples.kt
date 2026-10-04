package app.daycue.domain.edit

import app.daycue.domain.config.CalendarPreference
import app.daycue.domain.config.CalendarPreferenceMode
import app.daycue.domain.config.CollisionSettings
import app.daycue.domain.config.ContextRules
import app.daycue.domain.config.CueProfile
import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.EventDecisionOverride
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.Language
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MorningAlarm
import app.daycue.domain.config.OverrideScope
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.config.Place
import app.daycue.domain.config.QuietHours
import app.daycue.domain.config.RoutineStep
import app.daycue.domain.config.SessionRules
import app.daycue.domain.config.SpeechSettings
import app.daycue.domain.config.TravelPolicy
import app.daycue.domain.edit.Sensitivity.destructive
import app.daycue.domain.edit.Sensitivity.ordinary
import app.daycue.domain.edit.Sensitivity.sensitive
import app.daycue.domain.engine.PauseTarget
import app.daycue.domain.time.TimeWindow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Synthetic fixture with distinctive (fake) coordinates and a fake medication, plus at least one sample of
 * every [ConfigOp] variant with its expected [ConfigSensitivity] class. Nothing here is real data.
 */
object OpSamples {
    val HOME_C = GeoPoint(1.234567, 7.654321)
    val OFFICE_C = GeoPoint(2.468024, 8.642086)
    val NEW_C = GeoPoint(3.141592, 6.283185)

    /** Fragments that must never appear in any remote output. */
    val COORD_FRAGMENTS = listOf(HOME_C, OFFICE_C, NEW_C).flatMap { listOf(it.lat.toString(), it.lng.toString()) } +
        listOf("234567", "654321", "468024", "642086", "141592", "283185")

    const val MED_LABEL = "Zyxlabel"
    const val NEW_MED_LABEL = "Qwvlabel"
    const val MED_PHRASE = "Zyxphrase"
    /** Medication content that must be withheld without `allowMedication`. */
    val MED_FRAGMENTS = listOf(MED_LABEL, NEW_MED_LABEL, MED_PHRASE, "05:17", "23:41", "06:43", "2031-03-17", "2032-01-09")

    private val at = Instant.parse("2026-10-05T10:00:00Z")

    val base: DayCueConfig = Defaults.config().let { d ->
        d.copy(
            places = d.places.map {
                when (it.id) { Defaults.HOME -> it.copy(center = HOME_C); Defaults.OFFICE -> it.copy(center = OFFICE_C); else -> it }
            },
            medications = listOf(Medication("med-a", MED_LABEL, listOf(LocalTime.of(5, 17), LocalTime.of(23, 41)), startDate = LocalDate.of(2032, 1, 9))),
            cueProfiles = d.cueProfiles.map { if (it.type == CueType.Medication) it.copy(phrase = LocalizedText(MED_PHRASE, MED_PHRASE)) else it },
            calendarRules = d.calendarRules.copy(calendars = listOf(CalendarPreference("cal-0"))),
        )
    }

    data class Sample(val op: ConfigOp, val expected: Sensitivity)

    val samples: List<Sample> by lazy {
        val c = base
        val home = c.place(Defaults.HOME)!!
        val hyd = c.habit(Defaults.HYDRATION) as IntervalHabit
        val routine = c.routine(Defaults.MORNING_ROUTINE)!!
        val alarm = c.alarm(Defaults.MORNING_ALARM)!!
        val medProfile = c.cueProfiles.first { it.type == CueType.Medication }
        val hydProfile = c.cueProfiles.first { it.type == CueType.Hydration }
        listOf(
            Sample(ConfigOp.UpsertHabit(hyd.copy(intervalMin = 45)), ordinary),
            Sample(ConfigOp.DeleteHabit(Defaults.HYDRATION), destructive),
            Sample(ConfigOp.SetHabitEnabled(Defaults.SUNSCREEN, true), ordinary),
            Sample(ConfigOp.SetHabitEnabled(Defaults.SUNSCREEN, false), ordinary),
            Sample(ConfigOp.SetHabitInterval(Defaults.SUNSCREEN, 90), ordinary),
            Sample(ConfigOp.SetHabitActiveHours(Defaults.HYDRATION, TimeWindow(LocalTime.of(8, 0), LocalTime.of(20, 0))), ordinary),
            Sample(ConfigOp.SetPause(PauseTarget.Habit(Defaults.HYDRATION), PauseSpec.Until(at, at.plusSeconds(3600))), ordinary),
            Sample(ConfigOp.SetPause(PauseTarget.Posture, PauseSpec.Indefinite(at)), ordinary),
            Sample(ConfigOp.SetPause(PauseTarget.All, PauseSpec.Indefinite(at)), sensitive),
            Sample(ConfigOp.SetPause(PauseTarget.All, PauseSpec.Until(at, at.plusSeconds(3600))), sensitive),
            Sample(ConfigOp.SetPause(PauseTarget.All, null), ordinary),
            Sample(ConfigOp.SetPostureCycle(c.postureCycle.copy(snoozeMin = 10)), ordinary),
            Sample(ConfigOp.SetPostureModes(c.postureCycle.modes.map { it.copy(durationMin = 25) }), ordinary),
            Sample(ConfigOp.SetPostureEnabled(true), ordinary),
            Sample(ConfigOp.UpsertMedication(Medication("med-b", NEW_MED_LABEL, listOf(LocalTime.of(6, 43)))), sensitive),
            Sample(ConfigOp.UpsertMedication(c.medication("med-a")!!.copy(times = listOf(LocalTime.of(6, 43)))), sensitive),
            Sample(ConfigOp.DeleteMedication("med-a"), destructive),
            Sample(ConfigOp.SetMedicationTimes("med-a", listOf(LocalTime.of(6, 43))), sensitive),
            Sample(ConfigOp.SetMedicationTravelPolicy("med-a", TravelPolicy.KeepHomeTimezone(ZoneId.of("UTC"))), sensitive),
            Sample(ConfigOp.SetMedicationEndDate("med-a", LocalDate.of(2031, 3, 17)), sensitive),
            Sample(ConfigOp.UpsertRoutine(routine.copy(name = "Mornings")), ordinary),
            Sample(ConfigOp.DeleteRoutine(Defaults.MORNING_ROUTINE), destructive),
            Sample(ConfigOp.DuplicateRoutine(Defaults.MORNING_ROUTINE, "copy", "Copy"), ordinary),
            Sample(ConfigOp.UpsertRoutineStep(Defaults.MORNING_ROUTINE, RoutineStep("stretch", "Stretch")), ordinary),
            Sample(ConfigOp.UpsertRoutineStep(Defaults.MORNING_ROUTINE, routine.steps.first().copy(durationSec = 240)), ordinary),
            Sample(ConfigOp.DeleteRoutineStep(Defaults.MORNING_ROUTINE, "shower"), destructive),
            Sample(ConfigOp.ReorderRoutineSteps(Defaults.MORNING_ROUTINE, routine.steps.map { it.id }.reversed()), ordinary),
            Sample(ConfigOp.UpsertAlarm(alarm.copy(time = LocalTime.of(9, 0))), sensitive),
            Sample(ConfigOp.UpsertAlarm(alarm.copy(enabled = false, name = "Renamed")), sensitive),
            Sample(ConfigOp.UpsertAlarm(MorningAlarm("second-alarm", time = LocalTime.of(6, 30))), ordinary),
            Sample(ConfigOp.DeleteAlarm(Defaults.MORNING_ALARM), destructive),
            Sample(ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, false), sensitive),
            Sample(ConfigOp.SetAlarmEnabled(Defaults.MORNING_ALARM, true), ordinary),
            Sample(ConfigOp.SkipNextAlarm(Defaults.MORNING_ALARM, LocalDate.of(2026, 10, 6)), sensitive),
            Sample(ConfigOp.SkipNextAlarm(Defaults.MORNING_ALARM, null), ordinary),
            Sample(ConfigOp.UpsertPlace(home.copy(center = NEW_C)), sensitive),
            Sample(ConfigOp.UpsertPlace(home.copy(radiusM = 400)), sensitive),
            Sample(ConfigOp.UpsertPlace(home.copy(center = null)), sensitive),
            Sample(ConfigOp.UpsertPlace(home.copy(name = "House")), ordinary),
            Sample(ConfigOp.UpsertPlace(Place("park", "Park", center = NEW_C)), ordinary),
            Sample(ConfigOp.DeletePlace(Defaults.HOME), destructive),
            Sample(ConfigOp.DeletePlace(Defaults.GYM), destructive),
            Sample(ConfigOp.SetPlaceLocation(Defaults.HOME, NEW_C), sensitive),
            Sample(ConfigOp.SetPlaceLocation(Defaults.HOME, null), sensitive),
            Sample(ConfigOp.SetPlaceLocation(Defaults.GYM, NEW_C, 300), sensitive),
            Sample(ConfigOp.SetPlaceLocation(Defaults.OFFICE, OFFICE_C, 300), sensitive),
            Sample(ConfigOp.UpsertCueProfile(medProfile.copy(phrase = LocalizedText("x", "x"))), sensitive),
            Sample(ConfigOp.UpsertCueProfile(c.cueProfiles.first { it.type == CueType.Alarm }.copy(phrase = LocalizedText("Wake up", "Wake up"))), sensitive),
            Sample(ConfigOp.UpsertCueProfile(hydProfile.copy(phrase = LocalizedText("Drink", "Drink"))), ordinary),
            Sample(ConfigOp.UpsertCueProfile(hydProfile.copy(soundEnabled = false)), sensitive),
            Sample(ConfigOp.UpsertCueProfile(hydProfile.copy(soundId = "none")), sensitive),
            Sample(ConfigOp.UpsertCueProfile(CueProfile("extra", CueType.Habit, "droplet", "single-short")), ordinary),
            Sample(ConfigOp.UpsertCueProfile(CueProfile("extra-med", CueType.Medication, "droplet", "single-short", phrase = LocalizedText(MED_PHRASE, ""))), sensitive),
            Sample(ConfigOp.DeleteCueProfile(medProfile.id), destructive),
            Sample(ConfigOp.DeleteCueProfile(hydProfile.id), destructive),
            Sample(ConfigOp.SetCalendarConfig(c.calendarRules.copy(speakTitles = true)), sensitive),
            Sample(ConfigOp.UpsertCalendarRule(c.calendarRules.rules.first().copy(leadsMin = listOf(5))), ordinary),
            Sample(ConfigOp.DeleteCalendarRule("free-time"), destructive),
            Sample(ConfigOp.ReorderCalendarRules(c.calendarRules.rules.map { it.id }.reversed()), ordinary),
            Sample(ConfigOp.SetCalendarPreference(CalendarPreference("cal-1", CalendarPreferenceMode.Never)), ordinary),
            Sample(ConfigOp.RemoveCalendarPreference("cal-0"), destructive),
            Sample(ConfigOp.SetEventOverride("evt-1", OverrideScope.Instance, EventDecisionOverride.Never), ordinary),
            Sample(ConfigOp.SetEventOverride("evt-1", OverrideScope.Instance, null), ordinary),
            Sample(ConfigOp.SetContextRules(ContextRules(onFootHoldMin = 30)), sensitive),
            Sample(ConfigOp.SetSessionRules(SessionRules(idleToPauseMin = 20)), sensitive),
            Sample(ConfigOp.SetQuietHours(QuietHours(enabled = false)), sensitive),
            Sample(ConfigOp.SetSpeechSettings(SpeechSettings(enabled = false)), sensitive),
            Sample(ConfigOp.SetCollisionSettings(CollisionSettings(minAudibleGapSec = 120)), sensitive),
            Sample(ConfigOp.SetLanguage(Language.he), ordinary),
            Sample(ConfigOp.SetGlobalSettings(c.settings.copy(dayStartsAt = LocalTime.of(5, 0))), sensitive),
        )
    }


    /** Ops that restore [base]'s places and medications (what an undo of a place/medication change sends). */
    val restoreOps: List<ConfigOp> get() = base.places.map { ConfigOp.UpsertPlace(it) } + base.medications.map { ConfigOp.UpsertMedication(it) }

    fun serialName(op: ConfigOp): String =
        DayCueJson.encodeToJsonElement(ConfigOp.serializer(), op).jsonObject["type"]!!.jsonPrimitive.content
}
