package app.daycue.system

import android.app.Application
import android.app.LocaleManager
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.daycue.delivery.TextResolver
import app.daycue.domain.config.FirstRunSeed
import app.daycue.domain.config.Language
import app.daycue.domain.edit.ConfigOp
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.EngineHost
import app.daycue.engine.HostLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pure language rules (JVM-tested, no Android). Two places hold a language: the **per-app locale** (UI resources;
 * Android 13+ system "App languages" setting, AppCompat before) and `config.settings.language` (notifications and
 * speech, set by the UI, MCP or an import). They must agree (VALIDATION D8):
 * - the UI is the truth at process start (a change in system settings reaches the config on the next start),
 * - a config change from elsewhere (MCP, import, undo) moves the UI along at once.
 */
object LanguagePolicy {
    fun languageOf(tag: String?): Language? = when (tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase()) {
        "he", "iw" -> Language.he
        "en" -> Language.en
        else -> null
    }

    fun tagOf(language: Language): String = when (language) { Language.he -> "he"; Language.en -> "en" }

    /** The language the UI shows: the per-app locale when it is a supported one, else the device's (unsupported: English). */
    fun uiLanguage(perAppTag: String?, deviceTag: String?): Language = languageOf(perAppTag) ?: languageOf(deviceTag) ?: Language.en

    /** At start: the config language to store so it matches the UI, or null when they already agree. */
    fun configFix(perAppTag: String?, deviceTag: String?, config: Language): Language? =
        uiLanguage(perAppTag, deviceTag).takeIf { it != config }

    /** After a config change from MCP / import / undo: the per-app tag to set so the UI follows, or null. */
    fun uiFix(perAppTag: String?, deviceTag: String?, config: Language): String? =
        if (uiLanguage(perAppTag, deviceTag) != config) tagOf(config) else null

    /** Region for work days: the per-app locale's region when it has one, else the device's. */
    fun region(perAppTag: String?, deviceTag: String?): String? =
        listOfNotNull(perAppTag, deviceTag).map { Locale.forLanguageTag(it).country }.firstOrNull { it.isNotBlank() }

    fun seed(perAppTag: String?, deviceTag: String?, use24Hour: Boolean, text: (String, Language) -> String?): FirstRunSeed {
        val lang = uiLanguage(perAppTag, deviceTag)
        return FirstRunSeed(language = lang, region = region(perAppTag, deviceTag), use24Hour = use24Hour, text = { key -> text(key, lang) })
    }
}

/**
 * The one place that sets the app language (VALIDATION D8). [set] changes the per-app locale **and** the config
 * language in one call; [reconcileOnStart] copies a system-settings change into the config; [followConfig] moves the
 * UI when the config language changes from MCP or an import; [firstRunSeed] seeds the first document from the device.
 */
class AppLanguage(
    private val app: Application,
    private val host: () -> EngineHost,
    private val text: () -> TextResolver,
    private val scope: CoroutineScope,
    private val log: HostLog,
) {
    private val main = Handler(Looper.getMainLooper())

    /** The explicit per-app locale tag, or null when the app follows the phone. */
    fun perAppTag(): String? {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            LocaleListCompat.wrap(app.getSystemService(LocaleManager::class.java).applicationLocales)
        } else AppCompatDelegate.getApplicationLocales()
        return if (list.isEmpty) null else list[0]?.toLanguageTag()
    }

    /** The phone's own language (not the per-app one). */
    fun deviceTag(): String? = Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()

    /** What the UI currently shows: `he` or `en`, or null when it follows the phone. */
    fun currentTag(): String? = perAppTag()?.let { LanguagePolicy.languageOf(it) }?.let(LanguagePolicy::tagOf)

    /**
     * Sets the app language everywhere: the per-app locale (UI, recreates visible Activities) and
     * `settings.language` (notifications and speech) through the normal edit path. [tag] `he` / `en`,
     * or null = follow the phone (the config then takes the phone's language when supported, else English).
     */
    suspend fun set(tag: String?, source: String = "ui"): ApplyOutcome? {
        val language = LanguagePolicy.languageOf(tag)
        setPerApp(language?.let(LanguagePolicy::tagOf))
        val target = language ?: LanguagePolicy.uiLanguage(null, deviceTag())
        val h = host()
        val cfg = h.ensureLoaded().config
        if (cfg.settings.language == target) return null
        return h.applyOps(listOf(ConfigOp.SetLanguage(target)), cfg.version, source)
    }

    /** Process start: a language picked in system settings ("App languages") reaches the config. */
    suspend fun reconcileOnStart() {
        val h = host()
        val cfg = h.ensureLoaded().config
        val fix = LanguagePolicy.configFix(perAppTag(), deviceTag(), cfg.settings.language) ?: return
        log.info("app language: config ${cfg.settings.language} -> $fix (follows the per-app / device locale)")
        val r = h.applyOps(listOf(ConfigOp.SetLanguage(fix)), cfg.version, "system")
        if (r !is ApplyOutcome.Applied) log.warn("app language: config not updated: $r")
    }

    /** Keeps the UI on the config language when it changes from elsewhere (MCP, import, undo). Also feeds `use24Hour`. */
    fun followConfig() {
        scope.launch {
            host().snapshot.filterNotNull().map { it.config.settings }.distinctUntilChanged().collect { s ->
                text().use24Hour = s.use24Hour
                val fix = LanguagePolicy.uiFix(perAppTag(), deviceTag(), s.language) ?: return@collect
                log.info("app language: UI -> $fix (config language changed)")
                setPerApp(fix)
            }
        }
    }

    /** First run: language from the per-app / device locale, work days from its region, the device clock style, localized template names. */
    fun firstRunSeed(): FirstRunSeed = LanguagePolicy.seed(perAppTag(), deviceTag(), DateFormat.is24HourFormat(app)) { key, lang -> text().textOrNull(key, lang) }

    private fun setPerApp(tag: String?) {
        val list = if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        val apply = { AppCompatDelegate.setApplicationLocales(list) }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post(apply)
    }
}
