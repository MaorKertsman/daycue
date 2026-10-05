package app.daycue.delivery

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import app.daycue.domain.config.Language
import app.daycue.domain.engine.SpeechRequest
import app.daycue.domain.engine.Text
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves domain [Text] keys against `strings_engine.xml` (values + values-iw) in an explicit language (the
 * config's language for notifications, the request's language for speech), independent of the UI
 * locale. Resource name = `dc_` + key with `.`/`-` -> `_` (see [Templates.resourceName]).
 *
 * Fallbacks: `<name>_<kind>` when a `kind` argument selects a variant (session suggestion), `<name>_alt`
 * when a referenced argument is blank, and finally the key itself (logged) so a missing string is
 * visible but never crashes delivery.
 */
class TextResolver(private val context: Context) {
    private val contexts = ConcurrentHashMap<Language, Context>()
    private val ids = ConcurrentHashMap<String, Int>()

    /** `settings.use24Hour` (kept current by `AppContainer` from the config). */
    @Volatile var use24Hour: Boolean = true

    fun localized(lang: Language): Context = contexts.getOrPut(lang) {
        val cfg = Configuration(context.resources.configuration)
        cfg.setLocale(localeOf(lang))
        context.createConfigurationContext(cfg)
    }

    fun resolve(text: Text, lang: Language, zone: ZoneId = ZoneId.systemDefault()): String {
        val inner = text.args["inner"]
        if (inner != null) {
            val innerText = resolve(Text(inner, text.args - "inner"), lang, zone)
            return fill(text.key, mapOf("inner" to innerText), lang, zone)
        }
        return fill(text.key, text.args, lang, zone)
    }

    /** App-owned string by key (same naming scheme), with `{name}` arguments. */
    fun app(key: String, lang: Language, args: Map<String, String> = emptyMap(), zone: ZoneId = ZoneId.systemDefault()): String =
        fill(key, args, lang, zone)

    /** The plain string for a text key in [lang] (no arguments), or null when the key has no resource. */
    fun textOrNull(key: String, lang: Language): String? = raw(Templates.resourceName(key), lang)

    fun speech(req: SpeechRequest, zone: ZoneId = ZoneId.systemDefault()): String {
        val lang = req.language
        val lead = resolve(req.lead, lang, zone)
        val also = req.also.map { resolve(it, lang, zone) }.filter { it.isNotBlank() }
        return Templates.joinSpoken(lead, also, raw("dc_speech_also", lang) ?: "Also: {items}.", req.moreCount,
            raw("dc_speech_more", lang) ?: "And {count} more.", raw("dc_speech_separator", lang) ?: ", ")
    }

    private fun fill(key: String, args: Map<String, String>, lang: Language, zone: ZoneId): String {
        val base = Templates.resourceName(key)
        // Variant: `_<kind>` (session suggestion, calendar event), then the quantity form of `{minutes}` (D13: "now", one, two).
        var template = Templates.candidateNames(base, args).firstNotNullOfOrNull { raw(it, lang) }
        if (template == null) {
            Log.w("DayCue", "missing string for text key '$key'")
            return args["phrase"] ?: args["text"] ?: args["name"] ?: key
        }
        // `kindKey` -> localized `kind` (after variant selection, which uses only a real `kind`).
        val filled = Templates.withResolvedKind(args) { raw(it, lang) }
        if (Templates.needsAlt(template, filled)) raw("${base}_alt", lang)?.let { template = it }
        return Templates.fill(template!!, filled, zone, rtl = lang == Language.he, hour24 = use24Hour, locale = localeOf(lang))
    }

    @SuppressLint("DiscouragedApi") // keys are data from the domain; names are kept from shrinking by res/raw/keep_engine.xml
    private fun raw(name: String, lang: Language): String? {
        val id = ids.getOrPut(name) { context.resources.getIdentifier(name, "string", context.packageName) }
        if (id == 0) return null
        return localized(lang).getString(id)
    }

    companion object {
        /** The one app-language -> locale mapping ([app.daycue.ui.util.localeOf]). */
        fun localeOf(lang: Language): Locale = app.daycue.ui.util.localeOf(lang)
    }
}
