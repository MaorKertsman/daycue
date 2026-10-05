package app.daycue.domain.config

import app.daycue.domain.time.TimeWindow
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * What the device says on first run (the app fills it; JVM tests use the defaults).
 *
 * @property language first-run language (from the per-app locale, else the device locale).
 * @property region ISO 3166 region of that locale (e.g. `IL`); picks `settings.workDays` (PRODUCT §0 "Workdays").
 * @property use24Hour the device's 24-hour clock preference.
 * @property text resolves a template text key (e.g. `template.place.home`) in [language]; null = English built-in.
 */
data class FirstRunSeed(
    val language: Language = Language.en,
    val region: String? = null,
    val use24Hour: Boolean = true,
    val text: (String) -> String? = { null },
)

/**
 * First-run templates (PRODUCT §12). Entirely synthetic: no locations, no medication, no calendar data.
 * Everything is shipped disabled except quiet hours and the calendar starter rules (which do nothing
 * until a calendar is selected). Display names are resolved once, at creation, from `template.*` text keys
 * in the first-run language (they are user-editable text afterwards; a later language change does not rename them).
 */
object Defaults {
    const val SUNSCREEN = "sunscreen"
    const val HYDRATION = "hydration"
    const val WATER_BOTTLE = "water-bottle"
    const val HOME = "home"
    const val OFFICE = "office"
    const val GYM = "gym"
    const val MORNING_ROUTINE = "morning-routine"
    const val MORNING_ALARM = "morning-alarm"

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private val SUN_THU = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
    private val MON_FRI = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val SAT_WED = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)

    /** Regions with a Friday-Saturday weekend (CLDR weekData), so Sunday-Thursday is the working week. */
    private val FRI_SAT_WEEKEND = setOf("IL", "SA", "EG", "KW", "QA", "BH", "OM", "JO", "DZ", "IQ", "LY", "SY", "YE", "SD", "BD", "MV")
    /** Friday-only weekend: Saturday-Wednesday (Thursday is often half a day; not counted). */
    private val FRI_WEEKEND = setOf("IR", "AF")

    fun workDaysFor(language: Language): Set<DayOfWeek> = when (language) {
        Language.he -> SUN_THU
        Language.en -> MON_FRI
    }

    /** PRODUCT §0 Workdays "default from locale weekend": by region when known, else by language. */
    fun workDaysFor(region: String?, language: Language): Set<DayOfWeek> = when (region?.uppercase()) {
        null, "" -> workDaysFor(language)
        in FRI_SAT_WEEKEND -> SUN_THU
        in FRI_WEEKEND -> SAT_WED
        else -> MON_FRI
    }

    /** Built-in English template texts, keyed like the app's `dc_template_*` strings (key `template.x.y`). */
    val TEMPLATE_TEXT: Map<String, String> = mapOf(
        "template.place.home" to "Home", "template.place.office" to "Office", "template.place.gym" to "Gym",
        "template.habit.sunscreen" to "Sunscreen", "template.habit.hydration" to "Hydration", "template.habit.water_bottle" to "Water bottle",
        "template.routine.morning" to "Morning routine",
        "template.step.shower" to "Shower", "template.step.face_cleanser" to "Face cleanser",
        "template.step.brush_teeth" to "Brush teeth", "template.step.get_dressed" to "Get dressed",
        "template.alarm.morning" to "Morning alarm",
        "template.calendar.meetings" to "Meetings", "template.calendar.appointments" to "Appointments",
        "template.calendar.workouts" to "Workouts", "template.calendar.important" to "Important", "template.calendar.free_time" to "Free time",
    )

    /** Template display name: the seed's localized text for [key], else the built-in English one. */
    class Names(private val text: (String) -> String? = { null }) {
        operator fun invoke(key: String): String = text(key)?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: TEMPLATE_TEXT.getValue(key)
    }
    private val EN = Names()

    fun sunscreen(n: Names = EN) = IntervalHabit(
        id = SUNSCREEN, kind = IntervalKind.Sunscreen, name = n("template.habit.sunscreen"), enabled = false,
        intervalMin = 120, activeHours = TimeWindow(t(7), t(19)),
        condition = ContextCondition(environments = setOf(Environment.Outdoor), minConfidence = Confidence.Medium),
        anchor = IntervalAnchor.FromAck, firstReminder = FirstReminderPolicy.OnConditionStart,
        reentry = ReentryPolicy.RemindOnReentry(0), onLeaveCondition = LeaveConditionPolicy.RetractAndHold,
        repeat = RepeatPolicy(20, 1), unanswered = UnansweredPolicy.RollForward, snoozeMin = 15,
        duringMeeting = DuringMeeting.DeliverSilently,
        phrase = LocalizedText("Time for sunscreen", "הגיע הזמן למרוח קרם הגנה"),
    )

    fun hydration(n: Names = EN) = IntervalHabit(
        id = HYDRATION, kind = IntervalKind.Hydration, name = n("template.habit.hydration"), enabled = false,
        intervalMin = 60, activeHours = TimeWindow(t(9), t(21)),
        condition = ContextCondition.ANY, anchor = IntervalAnchor.FromAck, dayStart = DayStartPolicy.IntervalAfterStart,
        repeat = RepeatPolicy(20, 0), unanswered = UnansweredPolicy.RollForward, snoozeMin = 15,
        duringMeeting = DuringMeeting.Defer,
        phrase = LocalizedText("Time for some water", "הגיע הזמן לשתות מים"),
    )

    fun waterBottle(n: Names = EN) = TransitionHabit(
        id = WATER_BOTTLE, name = n("template.habit.water_bottle"), enabled = false,
        triggers = setOf(BottleTrigger.LeavingNow, BottleTrigger.GeofenceExit),
        cooldownPerPlaceMin = 60, dedupWindowMin = 30, repeat = RepeatPolicy(10, 0),
        phrase = LocalizedText("Take your water bottle", "לא לשכוח בקבוק מים"),
    )

    fun postureCycle() = PostureCycleConfig(
        enabled = false,
        modes = listOf(
            PostureMode("sitting", PostureModeKind.Sitting, LocalizedText("Sitting", "ישיבה"), 30, true, LocalizedText("Time to sit", "הגיע הזמן לשבת")),
            PostureMode("standing", PostureModeKind.Standing, LocalizedText("Standing", "עמידה"), 30, true, LocalizedText("Time to stand", "הגיע הזמן לעמוד")),
            PostureMode("walking", PostureModeKind.Walking, LocalizedText("Walking (treadmill)", "הליכה (הליכון)"), 30, true, LocalizedText("Time to walk", "הגיע הזמן ללכת")),
        ),
    )

    fun places(n: Names = EN) = listOf(
        Place(HOME, n("template.place.home"), typicalEnvironment = TypicalEnvironment.Indoor, allowedActivities = setOf(SessionKind.Working, SessionKind.Studying),
            sessionStart = SessionStart.Suggest, bottleReminderOnLeave = true, allowedRoutines = setOf(MORNING_ROUTINE)),
        Place(OFFICE, n("template.place.office"), typicalEnvironment = TypicalEnvironment.Indoor, allowedActivities = setOf(SessionKind.Working),
            sessionStart = SessionStart.AutoStart, bottleReminderOnLeave = true),
        Place(GYM, n("template.place.gym"), typicalEnvironment = TypicalEnvironment.Indoor, sessionStart = SessionStart.Off, bottleReminderOnLeave = true),
    )

    fun morningRoutine(n: Names = EN) = Routine(
        id = MORNING_ROUTINE, name = n("template.routine.morning"), enabled = false, trigger = RoutineTrigger.Manual,
        steps = listOf(
            RoutineStep("shower", n("template.step.shower"), phrase = n("template.step.shower"), durationSec = 300, completion = StepCompletion.Timed),
            RoutineStep("face-cleanser", n("template.step.face_cleanser"), durationSec = 120, completion = StepCompletion.Timed),
            RoutineStep("brush-teeth", n("template.step.brush_teeth"), durationSec = 120, completion = StepCompletion.Timed),
            RoutineStep("get-dressed", n("template.step.get_dressed"), durationSec = 120, completion = StepCompletion.Explicit),
        ),
    )

    fun morningAlarm(n: Names = EN) = MorningAlarm(id = MORNING_ALARM, name = n("template.alarm.morning"), enabled = false, time = t(7), days = null)

    fun calendar(n: Names = EN) = CalendarConfig(
        rules = listOf(
            CalendarRule("meetings", n("template.calendar.meetings"), anyOf = listOf(CalendarCondition.Attendees(1), CalendarCondition.ConferencingLink),
                leadsMin = listOf(10), kind = LocalizedText("meeting", "פגישה"), isMeeting = true),
            CalendarRule("appointments", n("template.calendar.appointments"), anyOf = listOf(CalendarCondition.Keywords(listOf("appointment", "תור"))),
                leadsMin = listOf(60, 15), kind = LocalizedText("appointment", "תור")),
            CalendarRule("workouts", n("template.calendar.workouts"), anyOf = listOf(CalendarCondition.Keywords(listOf("workout", "gym", "training", "אימון"))),
                leadsMin = listOf(30), kind = LocalizedText("workout", "אימון")),
            CalendarRule("important", n("template.calendar.important"), anyOf = listOf(CalendarCondition.Keywords(listOf("important", "חשוב"))),
                leadsMin = listOf(30, 10), kind = LocalizedText("important event", "אירוע חשוב")),
            CalendarRule("free-time", n("template.calendar.free_time"), anyOf = listOf(CalendarCondition.Availability(EventAvailability.Free)), noCue = true),
        ),
    )

    fun cueProfiles() = listOf(
        CueProfile("profile-alarm", CueType.Alarm, "alarm-source", "continuous", phrase = LocalizedText("Good morning", "בוקר טוב")),
        CueProfile("profile-medication", CueType.Medication, "soft-bell", "long-short-long", phrase = LocalizedText("Medication reminder", "תזכורת לתרופה")),
        CueProfile("profile-routine", CueType.RoutineStep, "wood-tap", "single-short", phrase = LocalizedText("Next step", "השלב הבא")),
        CueProfile("profile-calendar", CueType.Calendar, "two-note-rise", "double-short", phrase = LocalizedText("Upcoming event", "אירוע קרוב")),
        CueProfile("profile-bottle", CueType.WaterBottle, "pop", "single-long", phrase = LocalizedText("Take your water bottle", "לא לשכוח בקבוק מים")),
        CueProfile("profile-sunscreen", CueType.Sunscreen, "bright-chime", "triple-short", phrase = LocalizedText("Time for sunscreen", "הגיע הזמן למרוח קרם הגנה")),
        CueProfile("profile-posture", CueType.Posture, "low-marimba", "two-long", phrase = LocalizedText("Time to switch", "הגיע הזמן להחליף תנוחה")),
        CueProfile("profile-hydration", CueType.Hydration, "droplet", "single-short-soft", phrase = LocalizedText("Time for some water", "הגיע הזמן לשתות מים")),
        CueProfile("profile-habit", CueType.Habit, "droplet", "single-short-soft", phrase = LocalizedText("Reminder", "תזכורת")),
        CueProfile("profile-notice", CueType.Notice, "none", "none", soundEnabled = false, vibrationEnabled = false, speechEnabled = false),
    )

    /** The first-run document. Medication list is empty by design. */
    fun config(language: Language = Language.en): DayCueConfig = config(FirstRunSeed(language))

    /** The first-run document seeded from the device (language, region work days, 24-hour clock, localized template names). */
    fun config(seed: FirstRunSeed): DayCueConfig {
        val n = Names(seed.text)
        return DayCueConfig(
            version = 0,
            habits = listOf(sunscreen(n), hydration(n), waterBottle(n)),
            postureCycle = postureCycle(),
            medications = emptyList(),
            routines = listOf(morningRoutine(n)),
            alarms = listOf(morningAlarm(n)),
            places = places(n),
            cueProfiles = cueProfiles(),
            calendarRules = calendar(n),
            contextRules = ContextRules(),
            settings = GlobalSettings(language = seed.language, workDays = workDaysFor(seed.region, seed.language), use24Hour = seed.use24Hour),
        )
    }
}
