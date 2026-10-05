package app.daycue.ui.cues

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.daycue.R
import app.daycue.data.db.HistoryEventEntity
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.ltr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** History kinds shown to the user, as plain words (never "missed", never a score). Null = not shown. */
@Composable
internal fun historyKindText(kind: String): String? = when (kind) {
    "Delivered" -> stringResource(R.string.cues_hist_reminder)
    "Repeated" -> stringResource(R.string.cues_hist_repeated)
    "Acked" -> stringResource(R.string.cues_hist_done)
    "Taken" -> stringResource(R.string.status_taken)
    "Snoozed" -> stringResource(R.string.state_snoozed)
    "Paused" -> stringResource(R.string.state_paused)
    "Resumed" -> stringResource(R.string.cues_hist_resumed)
    "Dismissed" -> stringResource(R.string.cues_hist_dismissed)
    "Unanswered" -> stringResource(R.string.cues_hist_unanswered)
    "Skipped" -> stringResource(R.string.status_skipped)
    "NotConfirmed" -> stringResource(R.string.status_not_confirmed)
    "RoutineStarted" -> stringResource(R.string.cues_hist_started)
    "RoutineCompleted" -> stringResource(R.string.cues_hist_finished)
    "RoutineCanceled" -> stringResource(R.string.cues_hist_canceled)
    "AlarmRang" -> stringResource(R.string.cues_hist_alarm_rang)
    "AlarmSnoozed" -> stringResource(R.string.state_snoozed)
    "AlarmStopped" -> stringResource(R.string.cues_hist_alarm_stopped)
    else -> null
}

@Composable
internal fun historyWhen(ms: Long, zone: ZoneId = zoneNow): String {
    val locale = currentLocale()
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val time = at.toLocalTime().let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) }
    val today = LocalDate.now(zone)
    return if (at.toLocalDate() == today) stringResource(R.string.cues_hist_today_at, time.ltr())
    else "${at.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))} ${time}".ltr()
}

/** Compact recent-activity list for an item (UX 3.2 history excerpt). Hidden when there is nothing yet. */
@Composable
internal fun RecentActivity(vm: CuesViewModel, subjectType: String, subjectId: String, limit: Int = 5) {
    val flow = remember(subjectType, subjectId) { vm.history(subjectType, subjectId, 30) }
    val rows: List<HistoryEventEntity> by flow.collectAsState(emptyList())
    val shown = rows.filter { !it.isTest }
    val words = shown.map { it to historyKindText(it.kind) }.filter { it.second != null }.take(limit)
    if (words.isEmpty()) return
    SectionHeader(stringResource(R.string.cues_recent_activity))
    words.forEachIndexed { i, (row, word) ->
        DayCueRow(primary = word!!, secondary = historyWhen(row.occurredAtMs), divider = i < words.lastIndex)
    }
}
