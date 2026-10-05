package app.daycue.ui.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.daycue.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns the domain's raw preview / diff lines (`settings.workDays: ["MONDAY",...] -> [...]`,
 * `habits[hydration].intervalMin: 60 -> 90`, `places[home]: Home (location removed)`) into short, localized,
 * friendly sentences (REVIEW-2 C1, C2). Used by the import review and every remote-access change list, so the
 * three surfaces cannot drift. Raw paths, JSON, quotes and enum names never reach the user.
 *
 * The core is pure: it takes a string lookup ([DiffLex]) and a small environment ([DiffEnv]), so it is unit
 * tested on the JVM. [friendlyDiffLines] is the Compose entry point.
 */
@Composable
fun friendlyDiffLines(lines: List<String>): List<String> {
    val context = LocalContext.current
    val locale = currentLocale()
    val resources = context.resources
    val is24 = is24Hour(context)
    return remember(lines, locale, is24, resources) {
        DiffText.lines(lines, DiffLex { id, args -> resources.getString(id, *args) }, DiffEnv(locale, is24))
    }
}

/** A one-line title for a pending change: "Change quiet hours to 23:00-06:30", or a count when there are several. */
@Composable
fun friendlyDiffTitle(lines: List<String>, fallback: String): String {
    val context = LocalContext.current
    val locale = currentLocale()
    val resources = context.resources
    val is24 = is24Hour(context)
    return remember(lines, locale, is24, fallback, resources) {
        DiffText.title(lines, DiffLex { id, args -> resources.getString(id, *args) }, DiffEnv(locale, is24)) ?: fallback
    }
}

/** Resolves a string resource id plus format args to text; the unit tests supply a resource-file backed one. */
fun interface DiffLex {
    fun s(@StringRes id: Int, args: Array<out Any>): String
}

class DiffEnv(
    val locale: Locale,
    val is24: Boolean,
    /** Optional id to display-name lookup (habit "hydration" -> "Hydration"). */
    val nameOf: (String) -> String? = { null },
) {
    val hebrew: Boolean get() = locale.language == "iw" || locale.language == "he"
}

/** One parsed change, before it is turned into a sentence. */
data class DiffEntry(val label: String, val before: String?, val after: String?, val sentence: String)

object DiffText {

    private val json = Json

    fun lines(raw: List<String>, lex: DiffLex, env: DiffEnv): List<String> = entries(raw, lex, env).map { it.sentence }

    /** "Change quiet hours to 23:00-06:30" for one simple change, "N changes" otherwise; null if unknown. */
    fun title(raw: List<String>, lex: DiffLex, env: DiffEnv): String? {
        val e = entries(raw, lex, env).singleOrNull() ?: return null
        val after = e.after ?: return null
        if (e.label.isBlank()) return null
        return lex.s(R.string.diff_title_change, arrayOf(e.label.lowercaseFirst(env), after))
    }

    /** Splits an audit/applied summary ("line; line; line") back into diff lines. */
    fun splitSummary(summary: String): List<String> = summary.split(SUMMARY_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun entries(raw: List<String>, lex: DiffLex, env: DiffEnv): List<DiffEntry> =
        raw.map { one(it, lex, env) }

    // ---------------------------------------------------------------------------------------------

    private class Parsed(val path: String, val before: String?, val after: String?)

    private fun parse(line: String): Parsed {
        val i = line.indexOf(": ")
        if (i < 0) return Parsed(line, null, null)
        val path = line.substring(0, i)
        val rest = line.substring(i + 2)
        return when {
            rest.startsWith("added ") -> Parsed(path, null, rest.removePrefix("added "))
            rest.startsWith("removed ") -> Parsed(path, rest.removePrefix("removed "), null)
            else -> {
                val j = rest.indexOf(" -> ")
                if (j < 0) Parsed(path, rest, null) else Parsed(path, rest.substring(0, j), rest.substring(j + 4))
            }
        }
    }

    private val ITEM = Regex("""^(\w+)\[([^\]]*)](.*)$""")
    private val PATH = Regex("""^[A-Za-z]\w*(\[[^\]]*])?(\.\w+(\[[^\]]*])?)*$""")
    private val SUMMARY_SPLIT = Regex(""";\s(?=[A-Za-z]\w*(\[[^\]]*])?(\.\w+(\[[^\]]*])?)*: )""")
    private val UUIDISH = Regex("""^[0-9a-fA-F-]{16,}$|^.*\d{6,}.*$""")

    private fun one(line: String, lex: DiffLex, env: DiffEnv): DiffEntry {
        val p = parse(line)
        fun s(id: Int, vararg a: Any) = lex.s(id, a)
        // Not a diff line at all ("no changes", "(sensitive change, details on the phone)"): leave it as written.
        if (!PATH.matches(p.path)) return DiffEntry("", null, null, line)
        // Medication withheld marker (remote, no medication permission).
        if (p.path == "medications" && (p.before == "details withheld" || p.after?.contains("details withheld") == true)) {
            val t = s(R.string.diff_med_withheld)
            return DiffEntry(t, null, null, t)
        }
        val m = ITEM.matchEntire(p.path)
        if (m == null) return simple(p, lex, env)
        val section = m.groupValues[1]
        val id = m.groupValues[2]
        val tail = m.groupValues[3].removePrefix(".")
        val known = itemName(section, id, p, lex, env)
        val kind = kindLabel(section, lex)
        val item = known ?: kind.replaceFirstChar { it.uppercase() }
        return when {
            tail.isEmpty() -> wholeItem(section, kind, item, p, lex, env)
            tail == "order" -> {
                val t = s(R.string.diff_order, sectionName(section, lex))
                DiffEntry(t, null, null, t)
            }
            tail == "location" -> location(item, p, lex)
            // The on-phone preview carries the raw coordinates object: say only that a location was saved or removed.
            tail == "center" -> location(item, Parsed(p.path, p.before, if (p.after == null || p.after == "none") "none" else "set"), lex)
            else -> field("$section[].$tail", section, known, p, lex, env, kindFallback = item)
        }
    }

    // ---- whole item added / removed ----

    private fun wholeItem(section: String, kind: String, name: String, p: Parsed, lex: DiffLex, env: DiffEnv): DiffEntry {
        fun s(id: Int, vararg a: Any) = lex.s(id, a)
        val place = if (section == "places") placeNote(p.after ?: p.before, lex) else null
        val shown = if (place != null) "${place.first} ${place.second}" else name
        val t = when {
            p.before == null -> s(R.string.diff_item_added, kind, shown)
            p.after == null -> s(R.string.diff_item_removed, kind, shown)
            else -> s(R.string.diff_change, kind, name, name)
        }
        return DiffEntry(kind, null, shown, t)
    }

    /** "Home (location removed)" -> ("Home", "(location removed)" localized). Null when it is not that shape. */
    private fun placeNote(v: String?, lex: DiffLex): Pair<String, String>? {
        if (v == null || v.startsWith("{")) return null
        fun s(id: Int) = lex.s(id, emptyArray())
        return when {
            v.endsWith(" (location removed)") -> v.removeSuffix(" (location removed)") to s(R.string.diff_loc_note_removed)
            v.endsWith(" (location set)") -> v.removeSuffix(" (location set)") to s(R.string.diff_loc_note_set)
            v.endsWith(" (no location)") -> v.removeSuffix(" (no location)") to s(R.string.diff_loc_note_none)
            else -> null
        }
    }

    private fun location(name: String, p: Parsed, lex: DiffLex): DiffEntry {
        val t = when {
            p.after == "moved" -> lex.s(R.string.diff_place_moved, arrayOf(name))
            p.after == "set" -> lex.s(R.string.diff_loc_saved, arrayOf(name))
            p.after == "none" || p.after == null -> lex.s(R.string.diff_loc_removed, arrayOf(name))
            else -> lex.s(R.string.diff_loc_saved, arrayOf(name))
        }
        return DiffEntry(name, null, null, t)
    }

    /** A display name for the item, or null when all we have is an opaque id. */
    private fun itemName(section: String, id: String, p: Parsed, lex: DiffLex, env: DiffEnv): String? {
        for (v in listOf(p.after, p.before)) {
            val j = v?.let(::parseJson) as? JsonObject ?: continue
            val n = (j["name"] ?: j["label"]).prim() ?: continue
            if (n.isNotBlank()) return n
        }
        if (section == "places") placeNote(p.after ?: p.before, lex)?.let { return it.first }
        env.nameOf(id)?.let { return it }
        return when (id) {
            "hydration" -> lex.s(R.string.diff_n_hydration, emptyArray())
            "sunscreen" -> lex.s(R.string.diff_n_sunscreen, emptyArray())
            "bottle" -> lex.s(R.string.diff_n_bottle, emptyArray())
            else -> if (id.isBlank() || id.any { it.isDigit() } || id.length > 24) null else id.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun kindLabel(section: String, lex: DiffLex): String = lex.s(
        when (section) {
            "habits" -> R.string.diff_k_habit
            "places" -> R.string.diff_k_place
            "routines" -> R.string.diff_k_routine
            "alarms" -> R.string.diff_k_alarm
            "medications" -> R.string.diff_k_medication
            "cueProfiles" -> R.string.diff_k_profile
            else -> R.string.diff_k_item
        }, emptyArray(),
    )

    private fun sectionName(section: String, lex: DiffLex): String = lex.s(
        when (section) {
            "settings" -> R.string.diff_sec_settings
            "habits" -> R.string.diff_sec_habits
            "places" -> R.string.diff_sec_places
            "routines" -> R.string.diff_sec_routines
            "alarms" -> R.string.diff_sec_alarms
            "medications" -> R.string.diff_sec_medications
            "cueProfiles" -> R.string.diff_sec_profiles
            "calendarRules" -> R.string.diff_sec_calendar
            "contextRules" -> R.string.diff_sec_context
            "postureCycle" -> R.string.diff_sec_posture
            else -> R.string.diff_sec_settings
        }, emptyArray(),
    )

    // ---- fields ----

    private enum class K { Min, Sec, Meters, Days, Time, Window, Windows, Bool, Enum, Text, Int, Times, IntList, Phrase, DaysN }

    private class Spec(@StringRes val label: Int?, val kind: K, val itemLabel: Boolean = false, val every: Boolean = false)

    private val SPECS: Map<String, Spec> = mapOf(
        "settings.workDays" to Spec(R.string.diff_l_work_days, K.Days),
        "settings.dayStartsAt" to Spec(R.string.diff_l_day_start, K.Time),
        "settings.language" to Spec(R.string.diff_l_language, K.Enum),
        "settings.quietHours.enabled" to Spec(R.string.diff_l_quiet, K.Bool),
        "settings.quietHours.windows" to Spec(R.string.diff_l_quiet, K.Windows),
        "settings.quietHours.respectSystemDnd" to Spec(R.string.diff_l_quiet_dnd, K.Bool),
        "settings.speech.enabled" to Spec(R.string.diff_l_speech, K.Bool),
        "settings.speech.overMedia" to Spec(R.string.diff_l_speech_media, K.Enum),
        "settings.speech.inMeeting" to Spec(R.string.diff_l_speech_meeting, K.Enum),
        "settings.speech.output" to Spec(R.string.diff_l_speech_output, K.Enum),
        "settings.collision.mergeWindowMin" to Spec(R.string.diff_l_merge, K.Min),
        "habits[].enabled" to Spec(null, K.Bool, itemLabel = true),
        "habits[].intervalMin" to Spec(null, K.Min, itemLabel = true, every = true),
        "habits[].name" to Spec(R.string.diff_l_name, K.Text),
        "habits[].activeHours" to Spec(R.string.diff_l_hours, K.Window),
        "habits[].days" to Spec(R.string.diff_l_days, K.Days),
        "habits[].snoozeMin" to Spec(R.string.diff_l_snooze, K.Min),
        "habits[].anchor" to Spec(R.string.diff_l_anchor, K.Enum),
        "habits[].phrase" to Spec(R.string.diff_l_phrase, K.Phrase),
        "habits[].cooldownPerPlaceMin" to Spec(R.string.diff_l_cooldown, K.Min),
        "habits[].repeat.everyMin" to Spec(R.string.diff_l_repeat_every, K.Min),
        "places[].name" to Spec(R.string.diff_l_name, K.Text),
        "places[].radiusM" to Spec(R.string.diff_l_radius, K.Meters),
        "places[].typicalEnvironment" to Spec(R.string.diff_l_usually, K.Enum),
        "places[].bottleReminderOnLeave" to Spec(R.string.diff_l_bottle_leave, K.Bool),
        "places[].sessionStart" to Spec(R.string.diff_l_session_start, K.Enum),
        "alarms[].enabled" to Spec(null, K.Bool, itemLabel = true),
        "alarms[].name" to Spec(R.string.diff_l_name, K.Text),
        "alarms[].time" to Spec(R.string.diff_l_time, K.Time),
        "alarms[].days" to Spec(R.string.diff_l_days, K.Days),
        "alarms[].snoozeMin" to Spec(R.string.diff_l_snooze, K.Min),
        "alarms[].vibrate" to Spec(R.string.diff_l_vibration, K.Bool),
        "routines[].enabled" to Spec(null, K.Bool, itemLabel = true),
        "routines[].name" to Spec(R.string.diff_l_name, K.Text),
        "medications[].label" to Spec(R.string.diff_l_name, K.Text),
        "medications[].times" to Spec(R.string.diff_l_times, K.Times),
        "medications[].days" to Spec(R.string.diff_l_days, K.Days),
        "medications[].snoozeMin" to Spec(R.string.diff_l_snooze, K.Min),
        "cueProfiles[].soundEnabled" to Spec(R.string.diff_l_sound, K.Bool),
        "cueProfiles[].vibrationEnabled" to Spec(R.string.diff_l_vibration, K.Bool),
        "cueProfiles[].speechEnabled" to Spec(R.string.diff_l_speech, K.Bool),
        "cueProfiles[].phrase" to Spec(R.string.diff_l_phrase, K.Phrase),
        "calendarRules.syncHorizonDays" to Spec(R.string.diff_l_sync_range, K.DaysN),
        "calendarRules.speakTitles" to Spec(R.string.diff_l_speak_titles, K.Bool),
        "calendarRules.showTitlesOnLockScreen" to Spec(R.string.diff_l_lock_titles, K.Bool),
        "calendarRules.snoozeMin" to Spec(R.string.diff_l_snooze, K.Min),
        "calendarRules.tentative" to Spec(R.string.diff_l_tentative, K.Enum),
        "calendarRules.supplement" to Spec(R.string.diff_l_supplement, K.Enum),
        "postureCycle.enabled" to Spec(R.string.diff_l_posture, K.Bool),
        "postureCycle.snoozeMin" to Spec(R.string.diff_l_snooze, K.Min),
        "postureCycle.extendOptionsMin" to Spec(R.string.diff_l_extend, K.IntList),
    )

    private val ENUMS: Map<String, Int> = mapOf(
        "Indoor" to R.string.diff_e_indoor, "Outdoor" to R.string.diff_e_outdoor, "Mixed" to R.string.diff_e_mixed,
        "en" to R.string.diff_e_en, "he" to R.string.diff_e_he,
        "AutoStart" to R.string.diff_e_autostart, "Suggest" to R.string.diff_e_suggest, "Off" to R.string.diff_off,
        "DuckAndSpeak" to R.string.diff_e_duck, "PauseAndSpeak" to R.string.diff_e_pause, "NotificationOnly" to R.string.diff_e_notif_only,
        "VibrateOnly" to R.string.diff_e_vibrate_only, "SpeakAnyway" to R.string.diff_e_speak_anyway,
        "AnyRoute" to R.string.diff_e_any_route, "HeadphonesOnly" to R.string.diff_e_headphones,
        "Accepted" to R.string.diff_e_accepted, "Exclude" to R.string.diff_e_exclude,
        "SupplementOnMatch" to R.string.diff_e_supplement, "OnlyWhenNoReminder" to R.string.diff_e_only_none, "Ignore" to R.string.diff_e_ignore,
        "FromAck" to R.string.diff_e_from_ack, "FromDue" to R.string.diff_e_from_due,
        "Working" to R.string.diff_e_working, "Studying" to R.string.diff_e_studying,
    )

    private fun simple(p: Parsed, lex: DiffLex, env: DiffEnv): DiffEntry {
        val root = p.path.substringBefore('.').substringBefore('[')
        return field(p.path, root, null, p, lex, env)
    }

    private fun field(key: String, section: String, item: String?, p: Parsed, lex: DiffLex, env: DiffEnv, kindFallback: String? = null): DiffEntry {
        val spec = SPECS[key]
        fun s(id: Int, vararg a: Any) = lex.s(id, a)
        if (spec == null) return generic(key, section, item, p, lex, env)
        val label = when {
            spec.itemLabel && (item ?: kindFallback) != null -> item ?: kindFallback!!
            spec.label != null && item != null && key.contains("[]") -> s(R.string.diff_of, s(spec.label), item)
            spec.label != null -> s(spec.label)
            else -> sectionName(section, lex)
        }
        val b = p.before?.let { value(spec, it, lex, env) }
        val a = p.after?.let { value(spec, it, lex, env) }
        val bb = if (b != null && spec.every) s(R.string.diff_every, b) else b
        val aa = if (a != null && spec.every) s(R.string.diff_every, a) else a
        // A value we cannot put in words (nested JSON): say the setting changed, never "removed".
        if ((p.before != null && bb == null) || (p.after != null && aa == null)) return DiffEntry(label, null, null, lex.s(R.string.diff_generic, arrayOf(label)))
        return DiffEntry(label, bb, aa, compose(label, bb, aa, lex))
    }

    private fun compose(label: String, b: String?, a: String?, lex: DiffLex): String = when {
        b == null && a != null -> lex.s(R.string.diff_now, arrayOf(label, a))
        a == null && b != null -> lex.s(R.string.diff_removed_value, arrayOf(label, b))
        b != null && a != null -> lex.s(R.string.diff_change, arrayOf(label, b, a))
        else -> label
    }

    private fun generic(key: String, section: String, item: String?, p: Parsed, lex: DiffLex, env: DiffEnv): DiffEntry {
        // The leaf name is an English code identifier: shown (humanized) in English only, never inside Hebrew.
        val leaf = key.substringAfterLast('.').substringAfterLast(']')
        val what = when {
            env.hebrew -> item ?: sectionName(section, lex)
            else -> humanize(leaf).let { h -> if (item != null) lex.s(R.string.diff_of, arrayOf(h, item)) else h }
        }
        val label = lex.s(R.string.diff_generic, arrayOf(what))
        val b = p.before?.let { plainValue(it, lex, env) }
        val a = p.after?.let { plainValue(it, lex, env) }
        if ((p.before != null && b == null) || (p.after != null && a == null)) return DiffEntry(label, null, null, label)
        return DiffEntry(label, b, a, compose(label, b, a, lex))
    }

    /** Short plain values only (booleans, numbers, English words); JSON and long text are dropped. */
    private fun plainValue(v: String, lex: DiffLex, env: DiffEnv): String? {
        val t = v.trim()
        if (t.startsWith("{") || t.startsWith("[")) return null
        if (t == "none") return lex.s(R.string.diff_none, emptyArray())
        if (t == "true") return lex.s(R.string.diff_on, emptyArray())
        if (t == "false") return lex.s(R.string.diff_off, emptyArray())
        ENUMS[t]?.let { return lex.s(it, emptyArray()) }
        if (t.toLongOrNull() != null || t.toDoubleOrNull() != null) return t.ltr()
        timeOf(t)?.let { return timeText(it, env).ltr() }
        if (env.hebrew || t.length > 30) return null
        return humanize(t)
    }

    private fun value(spec: Spec, raw: String, lex: DiffLex, env: DiffEnv): String? {
        fun s(id: Int, vararg a: Any) = lex.s(id, a)
        val t = raw.trim()
        if (t == "none" && spec.kind != K.Text) return s(R.string.diff_none)
        return when (spec.kind) {
            K.Bool -> if (t == "true") s(R.string.diff_on) else s(R.string.diff_off)
            K.Min -> t.toIntOrNull()?.let { duration(it, lex) }
            K.Sec -> t.toIntOrNull()?.let { s(R.string.diff_sec, it) }
            K.Meters -> t.toIntOrNull()?.let { s(R.string.diff_meters, it) }
            K.DaysN -> t.toIntOrNull()?.let { s(R.string.diff_days_n, it) }
            K.Int -> t.toLongOrNull()?.let { it.toString().ltr() }
            K.Text -> t
            K.Time -> timeOf(t)?.let { timeText(it, env).ltr() }
            K.Window -> windowText(parseJson(t), env)
            K.Windows -> windows(parseJson(t), lex, env)
            K.Days -> days(parseJson(t), lex, env)
            K.Times -> list(parseJson(t), lex) { timeOf(it)?.let { x -> timeText(x, env) } }?.ltr()
            K.IntList -> {
                val j = parseJson(t) as? JsonArray
                j?.mapNotNull { it.prim()?.toIntOrNull() }?.let { l -> s(R.string.diff_dur_min_list, l.joinToString(", ")) }?.ltrIfNumbers()
            }
            K.Enum -> ENUMS[t]?.let { s(it) } ?: (if (env.hebrew) null else humanize(t))
            K.Phrase -> (parseJson(t) as? JsonObject)?.let { o ->
                val primary = if (env.hebrew) "he" else "en"
                val other = if (env.hebrew) "en" else "he"
                (o[primary].prim()?.takeIf { it.isNotBlank() } ?: o[other].prim())?.takeIf { it.isNotBlank() }
            }
        }
    }

    // ---- value helpers ----

    private fun String.ltrIfNumbers(): String = this

    fun duration(min: Int, lex: DiffLex): String {
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> lex.s(R.string.diff_dur_min, arrayOf(m))
            m == 0 -> lex.s(R.string.diff_dur_h, arrayOf(h))
            else -> lex.s(R.string.diff_dur_h_min, arrayOf(h, m))
        }
    }

    private fun timeOf(t: String): java.time.LocalTime? =
        runCatching { java.time.LocalTime.parse(t) }.getOrNull()

    private fun timeText(t: java.time.LocalTime, env: DiffEnv): String = clockText(t.hour, t.minute, env.is24, env.locale)

    private fun windowText(j: JsonElement?, env: DiffEnv): String? {
        val o = j as? JsonObject ?: return null
        val s = o["start"].prim()?.let(::timeOf) ?: return null
        val e = o["end"].prim()?.let(::timeOf) ?: return null
        return isolatedRange(timeText(s, env), timeText(e, env))
    }

    private fun windows(j: JsonElement?, lex: DiffLex, env: DiffEnv): String? {
        val arr = j as? JsonArray ?: return null
        if (arr.isEmpty()) return lex.s(R.string.diff_no_windows, emptyArray())
        val parts = arr.mapNotNull { w ->
            val o = w as? JsonObject ?: return@mapNotNull null
            val range = windowText(o["window"], env) ?: return@mapNotNull null
            val d = days(o["days"], lex, env) ?: return@mapNotNull range
            lex.s(R.string.diff_window_days, arrayOf(range, d))
        }
        if (parts.isEmpty()) return null
        return truncate(parts, 2, lex, "; ")
    }

    private fun truncate(parts: List<String>, max: Int, lex: DiffLex, sep: String = ", "): String =
        if (parts.size <= max) parts.joinToString(sep)
        else parts.take(max).joinToString(sep) + sep + lex.s(R.string.diff_more, arrayOf(parts.size - max))

    private fun list(j: JsonElement?, lex: DiffLex, f: (String) -> String?): String? {
        val arr = j as? JsonArray ?: return null
        val items = arr.mapNotNull { it.prim()?.let(f) }
        return truncate(items, 4, lex)
    }

    /** Weekday sets as the shortest honest phrase: "Every day", "Mon–Fri", "Sun–Thu", "Mon, Wed". */
    fun days(j: JsonElement?, lex: DiffLex, env: DiffEnv): String? {
        val arr = j as? JsonArray ?: return null
        val set = arr.mapNotNull { runCatching { DayOfWeek.valueOf(it.prim() ?: "") }.getOrNull() }.toSet()
        return daysText(set, lex, env.locale)
    }

    fun daysText(set: Set<DayOfWeek>, lex: DiffLex, locale: Locale): String {
        if (set.size == 7) return lex.s(R.string.diff_every_day, emptyArray())
        if (set.isEmpty()) return lex.s(R.string.diff_no_days, emptyArray())
        fun name(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, locale).removePrefix("יום ").trim().let { if (it == "שבת") "ש׳" else it }
        // Circular runs in calendar order, starting after a day that is not in the set.
        val order = DayOfWeek.values().toList() // Mon..Sun
        val start = order.first { it in set && order[(order.indexOf(it) + 6) % 7] !in set }
        val seq = (0 until 7).map { order[(order.indexOf(start) + it) % 7] }
        val runs = mutableListOf<List<DayOfWeek>>()
        var cur = mutableListOf<DayOfWeek>()
        for (d in seq) {
            if (d in set) cur += d else if (cur.isNotEmpty()) { runs += cur; cur = mutableListOf() }
        }
        if (cur.isNotEmpty()) runs += cur
        // Week reads Sunday-first for Hebrew, Monday-first otherwise: sort runs by that position.
        val weekStart = if (locale.language == "iw" || locale.language == "he") DayOfWeek.SUNDAY else DayOfWeek.MONDAY
        fun pos(d: DayOfWeek) = (d.value - weekStart.value + 7) % 7
        return runs.sortedBy { pos(it.first()) }.joinToString(", ") { r ->
            if (r.size >= 3) "${name(r.first())}–${name(r.last())}" else r.joinToString(", ") { name(it) }
        }
    }

    private fun humanize(s: String): String {
        val spaced = s.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').lowercase(Locale.ROOT)
        return spaced.replaceFirstChar { it.uppercase() }
    }

    private fun String.lowercaseFirst(env: DiffEnv): String = if (env.hebrew) this else replaceFirstChar { it.lowercase() }

    private fun parseJson(v: String): JsonElement? =
        if (v.trimStart().let { it.startsWith("{") || it.startsWith("[") }) runCatching { json.parseToJsonElement(v) }.getOrNull() else null

    private fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.contentOrNull
}

/** Friendly text for an audit summary ("path: a -> b; path: c -> d") joined with "; ". */
@Composable
fun friendlyDiffSummary(summary: String): String {
    val lines = remember(summary) { DiffText.splitSummary(summary) }
    return friendlyDiffLines(lines).joinToString("; ")
}

/** Convenience for non-composable callers (view models): friendly lines from a [Context]. */
fun friendlyDiffLines(context: Context, lines: List<String>, locale: Locale = Locale.getDefault()): List<String> =
    DiffText.lines(lines, DiffLex { id, args -> context.getString(id, *args) }, DiffEnv(locale, is24Hour(context)))
