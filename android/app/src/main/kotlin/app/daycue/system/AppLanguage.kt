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
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Region for work days (VALIDATION D10c, DOMAIN.md "Work days"): the DEVICE region decides (IL -> Sunday-Thursday,
     * most others Monday-Friday), independent of the UI language; the per-app tag's region only when the device has
     * none; null when neither has one (then [app.daycue.domain.config.Defaults.workDaysFor] falls back to the language).
     */
    fun region(perAppTag: String?, deviceTag: String?): String? =
        listOfNotNull(deviceTag, perAppTag).map { Locale.forLanguageTag(it).country }.firstOrNull { it.isNotBlank() }

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

    private val sync = LanguageSync(
        perAppTag = ::perAppTag, setPerApp = ::setPerApp, deviceTag = ::deviceTag,
        config = { host().ensureLoaded().config },
        apply = { ops, base, source -> host().applyOps(ops, base, source) },
        log = log,
    )

    /**
     * Sets the app language everywhere: `settings.language` (notifications and speech) through the normal edit path,
     * then the per-app locale (UI, recreates visible Activities). [tag] `he` / `en`, or null = follow the phone (the
     * config then takes the phone's language when supported, else English). Runs in the app scope, so leaving the
     * screen (which cancels the caller's scope) can never stop it half-way (VALIDATION 7a, see [LanguageSync]).
     * [fromOnboarding]: also re-localizes first-run names the user has not edited (VALIDATION D10b).
     */
    suspend fun set(tag: String?, source: String = "ui", fromOnboarding: Boolean = false): ApplyOutcome? =
        scope.async {
            sync.set(tag, source) { cfg, target ->
                if (!fromOnboarding) emptyList() else {
                    val t = text()
                    Defaults.relocalizeOps(
                        cfg, Defaults.Names { key -> t.textOrNull(key, target) },
                        known = { key -> Language.entries.mapNotNull { t.textOrNull(key, it) }.toSet() },
                        from = cfg.settings.language, to = target, region = LanguagePolicy.region(null, deviceTag()),
                    )
                }
            }
        }.await()

    /** Process start / system "App languages" change: a language picked in system settings reaches the config. */
    suspend fun reconcileOnStart() = sync.reconcile()

    /** Keeps the UI on the config language when it changes from elsewhere (MCP, import, undo). Also feeds `use24Hour`. */
    fun followConfig() {
        scope.launch {
            host().snapshot.filterNotNull().map { it.config.settings }.distinctUntilChanged().collect { s ->
                text().use24Hour = s.use24Hour
                sync.followConfig()
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

/**
 * The language write path, Android-free (JVM-tested in `LanguageSyncTest`). QA once saw the UI in English with the
 * config still Hebrew (VALIDATION row 7a). Races found in the old code, all fixed here:
 * 1. `set` switched the per-app locale FIRST and wrote the config after a suspension, in the caller's scope. Leaving the
 *    screen (cancelling the scope) between the two left UI = en, config = he, and nothing repaired it while the process
 *    lived. Now the config is written first (in the app scope), then the UI follows.
 * 2. The per-app change makes the system call `Application.onConfigurationChanged`, whose reconcile ran concurrently with
 *    `set` from the same config version: one of the two got a `Conflict`, which was only logged; with a third writer
 *    (an engine `ApplyConfigOps`, an MCP op) both could lose. Now every write is serialized by one mutex and retried on
 *    a conflict against the fresh version.
 * 3. `followConfig` acted on the emitted (possibly stale) settings and could move the UI back to the previous language;
 *    it now re-reads the current config under the same mutex.
 */
class LanguageSync(
    private val perAppTag: () -> String?,
    private val setPerApp: (String?) -> Unit,
    private val deviceTag: () -> String?,
    private val config: suspend () -> DayCueConfig,
    private val apply: suspend (List<ConfigOp>, Long, String) -> ApplyOutcome,
    private val log: HostLog,
) {
    private val mutex = Mutex()

    suspend fun set(tag: String?, source: String, extra: (DayCueConfig, Language) -> List<ConfigOp> = { _, _ -> emptyList() }): ApplyOutcome? =
        mutex.withLock {
            val language = LanguagePolicy.languageOf(tag)
            val target = language ?: LanguagePolicy.uiLanguage(null, deviceTag())
            val r = applyWithRetry(source) { cfg ->
                (if (cfg.settings.language != target) listOf(ConfigOp.SetLanguage(target)) else emptyList()) + extra(cfg, target)
            }
            setPerApp(language?.let(LanguagePolicy::tagOf))
            r
        }

    suspend fun reconcile() = mutex.withLock {
        val r = applyWithRetry("system") { cfg ->
            val fix = LanguagePolicy.configFix(perAppTag(), deviceTag(), cfg.settings.language)
            if (fix != null) log.info("app language: config ${cfg.settings.language} -> $fix (follows the per-app / device locale)")
            listOfNotNull(fix?.let { ConfigOp.SetLanguage(it) })
        }
        if (r != null && r !is ApplyOutcome.Applied) log.warn("app language: config not updated: $r")
    }

    suspend fun followConfig() = mutex.withLock {
        val fix = LanguagePolicy.uiFix(perAppTag(), deviceTag(), config().settings.language) ?: return@withLock
        log.info("app language: UI -> $fix (config language changed)")
        setPerApp(fix)
    }

    /** Applies the ops built from the CURRENT config; on a version conflict rebuilds and retries (3 attempts). Null = nothing to do. */
    private suspend fun applyWithRetry(source: String, build: (DayCueConfig) -> List<ConfigOp>): ApplyOutcome? {
        var last: ApplyOutcome? = null
        repeat(3) {
            val cfg = config()
            val ops = build(cfg)
            if (ops.isEmpty()) return last.takeIf { it is ApplyOutcome.Applied }
            val r = apply(ops, cfg.version, source)
            if (r !is ApplyOutcome.Conflict) return r
            last = r
        }
        return last
    }
}
