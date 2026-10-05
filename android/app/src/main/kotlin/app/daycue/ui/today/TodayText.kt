package app.daycue.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.daycue.R
import app.daycue.domain.config.Activity
import app.daycue.domain.config.Environment
import app.daycue.domain.context.ContextSource
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.query.WaitingReason
import app.daycue.ui.util.durationDescription
import app.daycue.ui.util.durationText
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.formatTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Text resolvers for the Today models. Every clock time and number run goes through the bidi helpers. */

@Composable
fun ItemName.text(): String = res?.let { stringResource(it) } ?: raw

/** Clock time of [at] in [zone] as an LTR isolate. */
@Composable
fun clockOf(at: Instant, zone: ZoneId): String {
    val t = at.atZone(zone)
    return formatTime(t.hour, t.minute)
}

/** "Sun 4 Oct · 12:20" (the date part follows the locale; the clock is an LTR isolate). */
@Composable
fun dateLine(now: Instant, zone: ZoneId): String {
    val date = now.atZone(zone).format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(currentLocale(), "EEEMMMd"), currentLocale()))
    return "$date · ${clockOf(now, zone)}"
}

@Composable
fun minutesUntil(at: Instant, now: Instant): String {
    val m = Duration.between(now, at).toMinutes().toInt().coerceAtLeast(0)
    return if (m < 1) stringResource(R.string.app_now_soon) else stringResource(R.string.app_in_duration, durationText(m))
}

/** "13:00", or "Tomorrow 13:00" when [at] is not on today's date. */
@Composable
fun dayAwareClock(at: Instant, now: Instant, zone: ZoneId): String {
    val clock = clockOf(at, zone)
    val d = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        d == today -> clock
        d == today.plusDays(1) -> stringResource(R.string.app_tomorrow_at, clock)
        else -> stringResource(R.string.app_date_at, d.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(currentLocale(), "EEEMMMd"), currentLocale())), clock)
    }
}

@Composable
fun placeText(p: PlaceInfo): String = when (p.kind) {
    PlaceKind.Saved -> p.name?.text() ?: stringResource(R.string.app_place_elsewhere)
    PlaceKind.Elsewhere -> stringResource(R.string.app_place_elsewhere)
    PlaceKind.Unknown -> stringResource(R.string.app_place_unknown)
}

@Composable
fun environmentText(e: Environment): String = stringResource(
    when (e) {
        Environment.Indoor -> R.string.app_env_indoor
        Environment.Outdoor -> R.string.app_env_outdoor
        Environment.Unknown -> R.string.app_env_unknown
    },
)

@Composable
fun activityText(a: Activity, hasSession: Boolean): String? = when {
    a == Activity.Working -> stringResource(R.string.app_act_working)
    a == Activity.Studying -> stringResource(R.string.app_act_studying)
    a == Activity.Meeting -> stringResource(R.string.app_act_meeting)
    a == Activity.RoutineRunning -> stringResource(R.string.app_act_routine)
    hasSession -> stringResource(R.string.app_act_working)
    else -> null
}

/** `Place · Environment · Activity`: Activity is left out when Inactive / Unknown and no session exists. */
@Composable
fun contextLine(c: ContextInfo): String {
    val parts = mutableListOf(placeText(c.place), environmentText(c.environment))
    activityText(c.activity, c.session != null)?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
fun contextSpoken(c: ContextInfo): String = contextLine(c).replace(" · ", ", ")

@Composable
fun sourceText(s: ContextSource): String? = when (s) {
    ContextSource.Manual -> R.string.app_src_manual
    ContextSource.Geofence -> R.string.app_src_location
    ContextSource.PlaceTypical -> R.string.app_src_place
    ContextSource.Motion -> R.string.app_src_motion
    ContextSource.AssumedAway -> R.string.app_src_assumed
    ContextSource.Companion -> R.string.app_src_computer
    ContextSource.Calendar -> R.string.app_src_calendar
    ContextSource.Routine -> R.string.app_src_routine
    ContextSource.Session -> R.string.app_src_session
    ContextSource.DetectionPaused -> R.string.app_src_paused
    ContextSource.None -> null
}?.let { stringResource(it) }

/** "from location · since 10:20" for the context sheet and the Why now? sources. */
@Composable
fun sourceDetail(source: ContextSource, since: Instant?, zone: ZoneId): String? {
    val word = sourceText(source) ?: return null
    return if (since != null) stringResource(R.string.app_src_since, word, clockOf(since, zone)) else word
}

@Composable
fun waitingText(item: NextItem, now: Instant, zone: ZoneId): String {
    val until = item.waitingUntil
    return when (item.waiting) {
        WaitingReason.WhenConditionHolds -> stringResource(
            when (item.condition ?: ConditionText.Generic) {
                ConditionText.Outdoors -> R.string.app_wait_outdoors
                ConditionText.Indoors -> R.string.app_wait_indoors
                ConditionText.Working -> R.string.app_wait_working
                ConditionText.Studying -> R.string.app_wait_studying
                ConditionText.SessionRunning -> R.string.app_wait_session
                ConditionText.Generic -> R.string.app_wait_generic
            },
        )
        WaitingReason.PausedUntil -> if (until != null) stringResource(R.string.app_wait_paused_until, dayAwareClock(until, now, zone)) else stringResource(R.string.app_wait_paused)
        WaitingReason.AfterQuietHours -> stringResource(R.string.app_wait_quiet, clockOf(until ?: item.at ?: now, zone))
        WaitingReason.AfterMeeting -> stringResource(R.string.app_wait_meeting)
        WaitingReason.AfterRoutine -> stringResource(R.string.app_wait_routine)
        WaitingReason.OutsideActiveHours -> stringResource(R.string.app_wait_from, dayAwareClock(until ?: item.at ?: now, now, zone))
        WaitingReason.CoveredUntil -> (until ?: item.at ?: now).let { "${dayAwareClock(it, now, zone)} · ${minutesUntil(it, now)}" }
        WaitingReason.AfterFirstAck -> stringResource(if (item.mark == app.daycue.ui.marks.CueType.Hydration) R.string.app_wait_first_drank else R.string.app_wait_first)
        WaitingReason.Frozen -> stringResource(R.string.app_wait_frozen)
        WaitingReason.Pending -> stringResource(R.string.app_now_soon)
        null -> item.at?.let { "${dayAwareClock(it, now, zone)} · ${minutesUntil(it, now)}" } ?: ""
    }
}

@Composable
fun minutesDescription(m: Int): String = durationDescription(m)

/** Rule id to one plain sentence for "Why now?" (GEN-9). Unknown ids fall back to a generic line. */
@Composable
fun ruleText(rule: String): String = stringResource(
    when (rule) {
        "SUN-2", "HYD-3" -> R.string.app_rule_interval
        "SUN-3" -> R.string.app_rule_covered
        "SUN-4" -> R.string.app_rule_condition
        "SUN-8" -> R.string.app_rule_first
        "SUN-10" -> R.string.app_rule_paused
        "GEN-4" -> R.string.app_rule_pending
        "GEN-6" -> R.string.app_rule_hours
        "QH-1" -> R.string.app_rule_quiet
        "DuringMeeting" -> R.string.app_rule_meeting
        "RTN-10" -> R.string.app_rule_routine
        "MED-1" -> R.string.app_rule_dose
        "ALM-1" -> R.string.app_rule_alarm
        "CAL-1" -> R.string.app_rule_calendar
        "POS-2", "POS-3" -> R.string.app_rule_posture
        else -> R.string.app_rule_generic
    },
)

/** "from location, 2 min ago" for the context sheet: the source and how old that reading is. */
@Composable
fun sourceAgo(source: ContextSource, since: Instant?, now: Instant): String? {
    val word = sourceText(source) ?: return null
    if (since == null) return word
    val m = Duration.between(since, now).toMinutes().toInt()
    val ago = if (m < 1) stringResource(R.string.app_just_now) else stringResource(R.string.app_ago, durationText(m))
    return stringResource(R.string.app_src_ago, word, ago)
}

/**
 * The specific "Why now?" explanation for an item, e.g. "Every 2 h outdoors · last applied 08:10", built from the
 * domain's reason data ([WhyInfo]). The generic rule sentence ([ruleText]) is added only where it says something the
 * specific line does not (snoozed, repeating, waiting for a condition, ...), and is the whole answer for item types the
 * domain explains only with a rule id.
 */
@Composable
fun whyLines(info: WhyInfo?, rule: String, now: Instant, zone: ZoneId): List<String> {
    if (info == null) return listOf(ruleText(rule))
    return when (info.kind) {
        WhyKind.Interval -> {
            val every = info.intervalMin?.let { m ->
                val d = durationText(m)
                stringResource(
                    when (info.condition) {
                        null -> R.string.app_why_every
                        ConditionText.Outdoors -> R.string.app_why_every_outdoors
                        ConditionText.Indoors -> R.string.app_why_every_indoors
                        ConditionText.Working -> R.string.app_why_every_working
                        ConditionText.Studying -> R.string.app_why_every_studying
                        ConditionText.SessionRunning -> R.string.app_why_every_session
                        ConditionText.Generic -> R.string.app_why_every_generic
                    },
                    d,
                )
            }
            val last = info.lastAck?.let {
                stringResource(
                    when (info.habitKind) {
                        app.daycue.domain.config.IntervalKind.Sunscreen -> R.string.app_why_last_applied
                        app.daycue.domain.config.IntervalKind.Hydration -> R.string.app_why_last_drank
                        else -> R.string.app_why_last_done
                    },
                    dayAwareClock(it, now, zone),
                )
            }
            val specific = listOfNotNull(every, last).joinToString(" · ")
            val extra = when {
                info.due && (info.repeat || info.rule == "GEN-4") -> ruleText("GEN-4")
                info.due && info.rule == "GEN-3" -> stringResource(R.string.app_why_snoozed)
                info.due && info.rule == "SUN-8" -> stringResource(R.string.app_why_stretch)
                info.due -> null
                info.rule == "SUN-2" || info.rule == "HYD-3" -> null
                else -> ruleText(info.rule)
            }
            listOfNotNull(specific.ifBlank { null }, extra).ifEmpty { listOf(ruleText(info.rule)) }
        }
        WhyKind.Dose -> listOf(
            when {
                info.at == null -> ruleText("MED-1")
                info.notConfirmed -> stringResource(R.string.app_why_dose_open, dayAwareClock(info.at, now, zone))
                else -> stringResource(R.string.app_why_dose, dayAwareClock(info.at, now, zone))
            },
        )
        WhyKind.Alarm -> listOf(
            if (info.at != null && info.days != null) stringResource(R.string.app_why_alarm, clockOf(info.at, zone), app.daycue.ui.cues.daysSummary(info.days))
            else ruleText("ALM-1"),
        )
        WhyKind.Other -> listOf(ruleText(info.rule))
    }
}
