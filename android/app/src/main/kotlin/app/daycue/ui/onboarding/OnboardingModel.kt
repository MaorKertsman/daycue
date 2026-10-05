package app.daycue.ui.onboarding

import androidx.annotation.StringRes
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.edit.ConfigOp
import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessReport
import app.daycue.system.ReadinessStatus

/** What a first-run owner can start with (UX 3.1). Medication is never a template: the list ships empty. */
enum class Template(val id: String, @StringRes val title: Int, @StringRes val summary: Int) {
    Sunscreen(Defaults.SUNSCREEN, R.string.cue_sunscreen, R.string.app_tpl_sunscreen),
    Hydration(Defaults.HYDRATION, R.string.cue_hydration, R.string.app_tpl_hydration),
    Bottle(Defaults.WATER_BOTTLE, R.string.cue_bottle, R.string.app_tpl_bottle),
    Posture("posture", R.string.cue_posture, R.string.app_tpl_posture),
    Routine(Defaults.MORNING_ROUTINE, R.string.app_name_morning_routine, R.string.app_tpl_routine),
    Alarm(Defaults.MORNING_ALARM, R.string.app_name_morning_alarm, R.string.app_tpl_alarm),
    Calendar("calendar", R.string.cue_calendar, R.string.app_tpl_calendar);

    companion object {
        fun fromIds(ids: Set<String>): Set<Template> = entries.filter { it.id in ids }.toSet()
    }
}

/** The system permissions and settings onboarding may ask for, each in context with a benefit and a skip. */
enum class PermissionKind(val id: String) {
    Notifications("notifications"), ExactAlarms("exact"), FullScreen("fullscreen"), Calendar("calendar"), Battery("battery"), Hibernation("hibernation"),
}

object OnboardingPlan {

    /**
     * The config ops that make the chosen templates enabled (and the rest disabled), expressed against the current
     * document so nothing that is already right is re-sent. First run starts with everything disabled and no
     * medication; the ops never add personal data. `calendar` has no switch: its starter rules already exist and do
     * nothing until the owner selects a calendar.
     */
    fun ops(config: DayCueConfig, chosen: Set<Template>): List<ConfigOp> = buildList {
        for (t in listOf(Template.Sunscreen, Template.Hydration, Template.Bottle)) {
            val habit = config.habit(t.id) ?: continue
            val want = t in chosen
            if (habit.enabled != want) add(ConfigOp.SetHabitEnabled(t.id, want))
        }
        if (config.postureCycle.enabled != (Template.Posture in chosen)) add(ConfigOp.SetPostureEnabled(Template.Posture in chosen))
        config.routine(Template.Routine.id)?.let { r ->
            val want = Template.Routine in chosen
            if (r.enabled != want) add(ConfigOp.UpsertRoutine(r.copy(enabled = want)))
        }
        config.alarm(Template.Alarm.id)?.let { a ->
            val want = Template.Alarm in chosen
            if (a.enabled != want) add(ConfigOp.SetAlarmEnabled(a.id, want))
        }
    }

    /** Templates that depend on the clock (exact alarms matter); the bottle and calendar cues are event driven. */
    private fun timeBased(chosen: Set<Template>) = chosen.any { it != Template.Bottle && it != Template.Calendar }

    /**
     * The permission cards to show, in the UX order: Notifications, exact alarms, full-screen alarm (only when an
     * alarm was chosen), calendar (only when calendar cues were chosen), then battery and hibernation. A card
     * appears only while its row is not already Ready and the owner did not say "Not now".
     */
    fun cards(chosen: Set<Template>, report: ReadinessReport?, calendarGranted: Boolean, skipped: Set<String>): List<PermissionKind> {
        if (report == null) return emptyList()
        fun status(id: ReadinessId) = report.items.firstOrNull { it.id == id }?.status
        fun missing(id: ReadinessId) = status(id).let { it == ReadinessStatus.Off || it == ReadinessStatus.Limited }
        val any = chosen.isNotEmpty()
        return buildList {
            if (missing(ReadinessId.Notifications)) add(PermissionKind.Notifications)
            if (any && timeBased(chosen) && missing(ReadinessId.ExactAlarms)) add(PermissionKind.ExactAlarms)
            if (Template.Alarm in chosen && missing(ReadinessId.FullScreenIntent)) add(PermissionKind.FullScreen)
            if (Template.Calendar in chosen && !calendarGranted) add(PermissionKind.Calendar)
            if (any && missing(ReadinessId.BatteryOptimization)) add(PermissionKind.Battery)
            if (any && missing(ReadinessId.Hibernation)) add(PermissionKind.Hibernation)
        }.filter { it.id !in skipped }
    }
}
