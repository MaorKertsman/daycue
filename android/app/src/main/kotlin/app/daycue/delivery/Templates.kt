package app.daycue.delivery

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure template filling for domain [app.daycue.domain.engine.Text] (no Android imports; JVM-tested).
 *
 * Resource strings use named placeholders `{name}`; argument values come from the domain. User text
 * (habit names, routine steps, calendar titles) is inserted literally and never re-expanded, except
 * the calendar `template` argument, which is the user's own phrase template and is expanded once with
 * the same arguments (PRODUCT §9 `{kind}`, `{minutes}`, `{title}`).
 */
object Templates {
    private val placeholder = Regex("\\{([a-zA-Z0-9_]+)\\}")
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val hmma: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)

    /** Keys whose resource name is derived from the domain key. */
    fun resourceName(key: String): String = "dc_" + key.replace('.', '_').replace('-', '_')

    fun placeholders(template: String): Set<String> = placeholder.findAll(template).map { it.groupValues[1] }.toSet()

    /** True when [template] references an argument that is missing or blank (use the `_alt` resource). */
    fun needsAlt(template: String, args: Map<String, String>): Boolean =
        placeholders(template).any { it != "inner" && args[it].isNullOrBlank() }

    fun fill(template: String, args: Map<String, String>, zone: ZoneId, rtl: Boolean, hour24: Boolean = true): String {
        val formatted = args.mapValues { (k, v) -> formatArg(k, v, zone, rtl, hour24) }
        val expanded = formatted["template"]?.let { t -> mapOf("template" to substitute(t, formatted - "template")) } ?: emptyMap()
        return substitute(template, formatted + expanded).trim()
    }

    private val kindKeyShape = Regex("[a-z0-9_.]+")

    /**
     * The domain passes `kindKey` (e.g. `calendar.kind.event`) instead of a `kind` when a calendar cue has no
     * user-defined kind (CalendarRules.UNMATCHED_KIND_KEY). Resolves it to the localized word via [lookup] (resource
     * name -> text) and supplies it as `kind`, so templates that reference `{kind}` (the user's own phrase) read
     * "event" / "אירוע" instead of an empty word. An explicit non-blank `kind` always wins.
     */
    fun withResolvedKind(args: Map<String, String>, lookup: (String) -> String?): Map<String, String> {
        val key = args["kindKey"]?.takeIf { args["kind"].isNullOrBlank() && kindKeyShape.matches(it) } ?: return args
        val text = lookup(resourceName(key))?.takeIf { it.isNotBlank() } ?: return args
        return args + ("kind" to text)
    }

    private fun substitute(t: String, args: Map<String, String>): String =
        placeholder.replace(t) { m -> args[m.groupValues[1]] ?: "" }

    /**
     * Instants -> local `HH:mm` (or `h:mm AM` when ![hour24], `settings.use24Hour`); `HH:mm[:ss]` times kept;
     * times are LTR-isolated inside RTL text.
     */
    fun formatArg(key: String, value: String, zone: ZoneId, rtl: Boolean, hour24: Boolean = true): String {
        val fmt = if (hour24) hhmm else hmma
        val time = when {
            key == "template" -> null
            value.length >= 16 && value[10] == 'T' && value.endsWith("Z") ->
                runCatching { fmt.format(Instant.parse(value).atZone(zone)) }.getOrNull()
            value.length in 5..8 && value[2] == ':' -> runCatching { fmt.format(LocalTime.parse(value)) }.getOrNull()
            else -> null
        } ?: return value
        return if (rtl) "\u2066$time\u2069" else time
    }

    /** "Also: a, b." / "And 2 more." joiner for COL-1 utterances. */
    fun joinSpoken(lead: String, also: List<String>, alsoTemplate: String, moreCount: Int, moreTemplate: String, separator: String): String {
        val parts = mutableListOf(lead.trim().trimEnd('.') + ".")
        if (also.isNotEmpty()) parts += alsoTemplate.replace("{items}", also.joinToString(separator))
        if (moreCount > 0) parts += moreTemplate.replace("{count}", moreCount.toString())
        return parts.joinToString(" ")
    }
}
