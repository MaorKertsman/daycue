package app.daycue.ui.util

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.daycue.R
import java.text.DateFormatSymbols
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val LRI = "\u2066"
internal const val PDI = "\u2069"
internal const val NBSP = "\u00A0"

/** Wraps a clock time, range or number run in an LTR isolate so it never reorders inside Hebrew strings. */
fun String.ltr(): String = "$LRI$this$PDI"

/**
 * A "from–to" clock range as ONE LTR isolate (REVIEW-1 #30): inside Hebrew text it reads "22:30–07:00" left to
 * right, so an overnight window never looks like a daytime one. The inner times must not be isolated again.
 */
fun isolatedRange(from: String, to: String): String = "$from–$to".ltr()

/**
 * Plain (not isolated) clock text. 24-hour: "07:30". 12-hour: "7:30 AM" with the locale's AM/PM marker,
 * Western digits.
 */
fun clockText(hour: Int, minute: Int, is24: Boolean, locale: Locale): String {
    val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    return if (is24) {
        "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)
    } else {
        time.format(
            DateTimeFormatter.ofPattern(
                "h:mm a",
                Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build(),
            ),
        )
    }
}

/** 12-hour clock face value of a 24-hour [hour]: 0 and 12 show as 12. */
fun hour12(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }

/** AM / PM markers of [locale], index 0 = AM. */
fun amPmMarkers(locale: Locale): Array<String> = DateFormatSymbols.getInstance(locale).amPmStrings

/**
 * Duration units are language text, not an isolate (REVIEW-1 #29): a Hebrew "2 שע׳ 15 דק׳" must run right to
 * left. Only clock-format durations such as "1:20" are LTR runs.
 */
fun clockDuration(minutes: Int, seconds: Int): String = "$minutes:${"%02d".format(Locale.ROOT, seconds)}".ltr()

@Composable
fun currentLocale(): Locale = LocalLocale.current.platformLocale

fun is24Hour(context: Context): Boolean = DateFormat.is24HourFormat(context)

/** Formats a clock time with the device 24h/12h setting, Western digits, as an LTR isolate. */
@Composable
fun formatTime(hour: Int, minute: Int): String = formatTimeRaw(hour, minute).ltr()

/** Same as [formatTime] but without the isolate, for building a range with [isolatedRange]. */
@Composable
fun formatTimeRaw(hour: Int, minute: Int): String =
    clockText(hour, minute, is24Hour(LocalContext.current), currentLocale())

/** Short visual duration, e.g. "2 h 15 min" / "2 שע׳ 15 דק׳". Not isolated. */
@Composable
fun durationText(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> stringResource(R.string.duration_min, m)
        m == 0 -> stringResource(R.string.duration_h, h)
        else -> stringResource(R.string.duration_h_min, h, m)
    }
}

@Composable
fun durationSecondsText(totalSeconds: Int): String =
    stringResource(R.string.duration_sec, totalSeconds)

/** Full-word duration for TalkBack, e.g. "2 hours 15 minutes". */
@Composable
fun durationDescription(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    val hours = pluralStringResource(R.plurals.desc_hours, h, h)
    val minutes = pluralStringResource(R.plurals.desc_minutes, m, m)
    return when {
        h == 0 -> minutes
        m == 0 -> hours
        else -> "$hours $minutes"
    }
}
