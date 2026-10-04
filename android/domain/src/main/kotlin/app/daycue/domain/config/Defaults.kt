package app.daycue.domain.config

import app.daycue.domain.time.TimeWindow
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * First-run templates (PRODUCT §12). Entirely synthetic: no locations, no medication, no calendar data.
 * Everything is shipped disabled except quiet hours and the calendar starter rules (which do nothing
 * until a calendar is selected).
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

    fun workDaysFor(language: Language): Set<DayOfWeek> = when (language) {
        Language.he -> setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        Language.en -> setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }

    fun sunscreen() = IntervalHabit(
        id = SUNSCREEN, kind = IntervalKind.Sunscreen, name = "Sunscreen", enabled = false,
        intervalMin = 120, activeHours = TimeWindow(t(7), t(19)),
        condition = ContextCondition(environments = setOf(Environment.Outdoor), minConfidence = Confidence.Medium),
        anchor = IntervalAnchor.FromAck, firstReminder = FirstReminderPolicy.OnConditionStart,
        reentry = ReentryPolicy.RemindOnReentry(0), onLeaveCondition = LeaveConditionPolicy.RetractAndHold,
        repeat = RepeatPolicy(20, 1), unanswered = UnansweredPolicy.RollForward, snoozeMin = 15,
        duringMeeting = DuringMeeting.DeliverSilently,
        phrase = LocalizedText("Time for sunscreen", "הגיע הזמן למרוח קרם הגנה"),
    )

    fun hydration() = IntervalHabit(
        id = HYDRATION, kind = IntervalKind.Hydration, name = "Hydration", enabled = false,
        intervalMin = 60, activeHours = TimeWindow(t(9), t(21)),
        condition = ContextCondition.ANY, anchor = IntervalAnchor.FromAck, dayStart = DayStartPolicy.IntervalAfterStart,
        repeat = RepeatPolicy(20, 0), unanswered = UnansweredPolicy.RollForward, snoozeMin = 15,
        duringMeeting = DuringMeeting.Defer,
        phrase = LocalizedText("Time for some water", "הגיע הזמן לשתות מים"),
    )

    fun waterBottle() = TransitionHabit(
        id = WATER_BOTTLE, name = "Water bottle", enabled = false,
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

    fun places() = listOf(
        Place(HOME, "Home", typicalEnvironment = TypicalEnvironment.Indoor, allowedActivities = setOf(SessionKind.Working, SessionKind.Studying),
            sessionStart = SessionStart.Suggest, bottleReminderOnLeave = true, allowedRoutines = setOf(MORNING_ROUTINE)),
        Place(OFFICE, "Office", typicalEnvironment = TypicalEnvironment.Indoor, allowedActivities = setOf(SessionKind.Working),
            sessionStart = SessionStart.AutoStart, bottleReminderOnLeave = true),
        Place(GYM, "Gym", typicalEnvironment = TypicalEnvironment.Indoor, sessionStart = SessionStart.Off, bottleReminderOnLeave = true),
    )

    fun morningRoutine() = Routine(
        id = MORNING_ROUTINE, name = "Morning routine", enabled = false, trigger = RoutineTrigger.Manual,
        steps = listOf(
            RoutineStep("shower", "Shower", phrase = "Shower", durationSec = 300, completion = StepCompletion.Timed),
            RoutineStep("face-cleanser", "Face cleanser", durationSec = 120, completion = StepCompletion.Timed),
            RoutineStep("brush-teeth", "Brush teeth", durationSec = 120, completion = StepCompletion.Timed),
            RoutineStep("get-dressed", "Get dressed", durationSec = 120, completion = StepCompletion.Explicit),
        ),
    )

    fun morningAlarm() = MorningAlarm(id = MORNING_ALARM, name = "Morning alarm", enabled = false, time = t(7), days = null)

    fun calendar() = CalendarConfig(
        rules = listOf(
            CalendarRule("meetings", "Meetings", anyOf = listOf(CalendarCondition.Attendees(1), CalendarCondition.ConferencingLink),
                leadsMin = listOf(10), kind = LocalizedText("meeting", "פגישה"), isMeeting = true),
            CalendarRule("appointments", "Appointments", anyOf = listOf(CalendarCondition.Keywords(listOf("appointment", "תור"))),
                leadsMin = listOf(60, 15), kind = LocalizedText("appointment", "תור")),
            CalendarRule("workouts", "Workouts", anyOf = listOf(CalendarCondition.Keywords(listOf("workout", "gym", "training", "אימון"))),
                leadsMin = listOf(30), kind = LocalizedText("workout", "אימון")),
            CalendarRule("important", "Important", anyOf = listOf(CalendarCondition.Keywords(listOf("important", "חשוב"))),
                leadsMin = listOf(30, 10), kind = LocalizedText("important event", "אירוע חשוב")),
            CalendarRule("free-time", "Free time", anyOf = listOf(CalendarCondition.Availability(EventAvailability.Free)), noCue = true),
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
    fun config(language: Language = Language.en): DayCueConfig = DayCueConfig(
        version = 0,
        habits = listOf(sunscreen(), hydration(), waterBottle()),
        postureCycle = postureCycle(),
        medications = emptyList(),
        routines = listOf(morningRoutine()),
        alarms = listOf(morningAlarm()),
        places = places(),
        cueProfiles = cueProfiles(),
        calendarRules = calendar(),
        contextRules = ContextRules(),
        settings = GlobalSettings(language = language, workDays = workDaysFor(language)),
    )
}
