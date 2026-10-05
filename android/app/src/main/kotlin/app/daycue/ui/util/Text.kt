package app.daycue.ui.util

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.daycue.R
import java.text.DateFormatSymbols
import java.util.Locale

internal const val LRI = "\u2066"
internal const val PDI = "\u2069"
internal const val NBSP = "\u00A0"

internal const val RLI = "\u2067"

/** Wraps a clock time, range or number run in an LTR isolate so it never reorders inside Hebrew strings. */
fun String.ltr(): String = "$LRI$this$PDI"

private fun hasHebrew(s: String) = s.any { it in '֐'..'׿' }

/**
 * Bidi isolate for clock text: an LTR isolate for digits-only times ("07:30", "7:30 AM"), an RTL isolate when the text
 * carries a Hebrew day-period marker, so "12:01 אחה״צ" reads time first in Hebrew (VALIDATION D10).
 */
fun String.clockIsolate(): String = if (hasHebrew(this)) "$RLI$this$PDI" else ltr()

/**
 * A "from–to" clock range as ONE isolate (REVIEW-1 #30): with 24-hour or Latin times it reads "22:30–07:00" left to
 * right, so an overnight window never looks like a daytime one; with Hebrew 12-hour times it is one RTL run, from on
 * the right. The inner times must not be isolated again.
 */
fun isolatedRange(from: String, to: String): String = "$from–$to".clockIsolate()

/** CLDR `he` abbreviated day periods: the Hebrew AM / PM markers when the platform returns none (VALIDATION D10). */
private val HEBREW_DAY_PERIODS = arrayOf("לפנה״צ", "אחה״צ")

private fun isHebrew(locale: Locale) = locale.language == "he" || locale.language == "iw"

/**
 * THE clock formatter. Plain (not isolated) clock text. 24-hour: "07:30". 12-hour: "7:30 AM" / "7:30 לפנה״צ" with the
 * day-period marker of [locale] (the app / UI language, never the JVM default), Western digits. Every UI, notification,
 * speech, boot-snapshot and alarm-screen time goes through here (a source scan in `ClockFormatTest` enforces it).
 */
fun clockText(hour: Int, minute: Int, is24: Boolean, locale: Locale): String {
    val h = hour.coerceIn(0, 23)
    val m = minute.coerceIn(0, 59)
    return if (is24) {
        "%02d:%02d".format(Locale.ROOT, h, m)
    } else {
        "${hour12(h)}:${"%02d".format(Locale.ROOT, m)} ${amPmMarkers(locale)[if (h < 12) 0 else 1]}"
    }
}

/** 12-hour clock face value of a 24-hour [hour]: 0 and 12 show as 12. */
fun hour12(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }

/**
 * AM / PM markers of [locale], index 0 = AM. The platform's own markers; for Hebrew, the CLDR Hebrew markers whenever
 * the platform answers with Latin ones (the cause of VALIDATION D10: "12:01 PM" under a Hebrew UI).
 */
fun amPmMarkers(locale: Locale): Array<String> {
    val platform = runCatching { DateFormatSymbols.getInstance(locale).amPmStrings }.getOrNull()?.takeIf { it.size >= 2 && it.all(String::isNotBlank) }
    if (isHebrew(locale) && (platform == null || platform.any { !hasHebrew(it) })) return HEBREW_DAY_PERIODS.copyOf()
    return platform ?: arrayOf("AM", "PM")
}

/** The locale used to format clock times and plurals for an app language (UI, notifications and speech alike). */
fun localeOf(lang: app.daycue.domain.config.Language): Locale = when (lang) {
    app.daycue.domain.config.Language.he -> Locale.forLanguageTag("he-IL")
    app.daycue.domain.config.Language.en -> Locale.forLanguageTag("en")
}

/**
 * Duration units are language text, not an isolate (REVIEW-1 #29): a Hebrew "2 שע׳ 15 דק׳" must run right to
 * left. Only clock-format durations such as "1:20" are LTR runs.
 */
fun clockDuration(minutes: Int, seconds: Int): String = "$minutes:${"%02d".format(Locale.ROOT, seconds)}".ltr()

/**
 * The UI locale: the Activity's resource configuration, which carries the per-app language (so it matches the strings
 * on screen), with the Compose locale as a fallback.
 */
@Composable
fun currentLocale(): Locale =
    androidx.compose.ui.platform.LocalConfiguration.current.locales.takeIf { !it.isEmpty }?.get(0) ?: LocalLocale.current.platformLocale

/**
 * The 12/24-hour choice of the owner (`config.settings.use24Hour`), mirrored here so every screen formats times the
 * same way as notifications and speech. Null until the config has loaded: then the device setting applies.
 */
object ClockPrefs {
    var use24Hour: Boolean? by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
}

fun is24Hour(context: Context): Boolean = ClockPrefs.use24Hour ?: DateFormat.is24HourFormat(context)

/** Keeps [ClockPrefs] in step with the config (call once per Activity, inside the theme). */
@Composable
fun SyncClockFromConfig(appContext: Context) {
    val facade = (appContext as? app.daycue.DayCueApplication)?.container?.facade ?: return
    androidx.compose.runtime.LaunchedEffect(facade) {
        facade.config.collect { ClockPrefs.use24Hour = it.settings.use24Hour }
    }
}

/** Formats a clock time with the device 24h/12h setting, Western digits, as an LTR isolate. */
@Composable
fun formatTime(hour: Int, minute: Int): String = clockTextIsolated(hour, minute, is24Hour(LocalContext.current), currentLocale())

/** Same as [formatTime] but without the isolate, for building a range with [isolatedRange]. */
@Composable
fun formatTimeRaw(hour: Int, minute: Int): String =
    clockText(hour, minute, is24Hour(LocalContext.current), currentLocale())

/** Short visual duration, e.g. "2 h 15 min" / "2 שע׳ ו־15 דק׳" (one hour is the word "שעה": "כל שעה ו־15 דק׳"). Not isolated. */
@Composable
fun durationText(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> stringResource(R.string.duration_min, m)
        h == 1 && m == 0 -> stringResource(R.string.duration_1h)
        m == 0 -> stringResource(R.string.duration_h, h)
        h == 1 -> stringResource(R.string.duration_1h_min, m)
        else -> stringResource(R.string.duration_h_min, h, m)
    }
}

@Composable
fun durationSecondsText(totalSeconds: Int): String =
    stringResource(R.string.duration_sec, totalSeconds)

/**
 * The words of one language for full-word durations (pure, so it is JVM-tested against the real plural resources in
 * `DurationWordsTest`). [hours] / [minutes] are the plural lookups (Android picks the `iw` one / two / many / other form);
 * [joinWord] joins hours and a minutes phrase that starts with a word (Hebrew "19 שעות ודקה"), [joinNumber] one that
 * starts with a number (Hebrew "19 שעות ו־5 דקות"); [now] replaces "in 0 minutes" (VALIDATION D13).
 */
class DurationLex(
    val hours: (Int) -> String,
    val minutes: (Int) -> String,
    val joinWord: (String, String) -> String,
    val joinNumber: (String, String) -> String,
    val inDuration: (String) -> String,
    val now: String,
)

object DurationWords {
    /** "2 hours 15 minutes" / "שעתיים ו־15 דקות"; 0 stays "0 minutes" (a setting value, not a moment). */
    fun of(totalMinutes: Int, lex: DurationLex): String {
        val t = totalMinutes.coerceAtLeast(0)
        val h = t / 60
        val m = t % 60
        if (h == 0) return lex.minutes(m)
        if (m == 0) return lex.hours(h)
        val mm = lex.minutes(m)
        return if (mm.firstOrNull()?.isDigit() == true) lex.joinNumber(lex.hours(h), mm) else lex.joinWord(lex.hours(h), mm)
    }

    /** "in 19 hours 1 minute" / "בעוד 19 שעות ודקה"; under one minute: "now" / "עכשיו", never "in 0 minutes". */
    fun relative(totalMinutes: Int, lex: DurationLex): String = if (totalMinutes < 1) lex.now else lex.inDuration(of(totalMinutes, lex))
}

@Composable
fun durationLex(): DurationLex {
    val r = LocalContext.current.resources
    androidx.compose.ui.platform.LocalConfiguration.current // recompose on a locale change
    return DurationLex(
        hours = { r.getQuantityString(R.plurals.desc_hours, it, it) },
        minutes = { r.getQuantityString(R.plurals.desc_minutes, it, it) },
        joinWord = { a, b -> r.getString(R.string.desc_join_word, a, b) },
        joinNumber = { a, b -> r.getString(R.string.desc_join_number, a, b) },
        inDuration = { r.getString(R.string.app_in_duration, it) },
        now = r.getString(R.string.app_in_now),
    )
}

/** Full-word duration for TalkBack and summaries, e.g. "2 hours 15 minutes" / "19 שעות ודקה". */
@Composable
fun durationDescription(totalMinutes: Int): String = DurationWords.of(totalMinutes, durationLex())

/** Full-word relative time: "in 2 hours" / "בעוד שעתיים", "now" / "עכשיו" under a minute. */
@Composable
fun relativeDescription(totalMinutes: Int): String = DurationWords.relative(totalMinutes, durationLex())

/** [clockText] as one bidi isolate ([clockIsolate]): LTR for digits and Latin markers, RTL with a Hebrew marker. */
fun clockTextIsolated(hour: Int, minute: Int, is24: Boolean, locale: Locale): String = clockText(hour, minute, is24, locale).clockIsolate()
