package app.daycue.ui.setup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewModelScope
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.GlobalSettings
import app.daycue.domain.config.Language
import app.daycue.domain.edit.ConfigOp
import app.daycue.facade.ExportResult
import app.daycue.facade.ImportParse
import app.daycue.facade.ImportPlan
import app.daycue.engine.ApplyOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * Small UI-only preferences (not part of the synced config). The app shell reads [reduceMotion] to feed
 * `DayCueTheme(reduceMotionSetting = ...)`: the value is process wide and persists in `daycue_ui` shared preferences.
 */
object UiPrefs {
    private const val FILE = "daycue_ui"
    private const val KEY_REDUCE = "reduce_motion"
    private val _reduceMotion = MutableStateFlow(false)
    private var loaded = false

    /** Observe from the shell. Call [load] once with any context before reading. */
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _reduceMotion.value = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_REDUCE, false)
    }

    fun setReduceMotion(context: Context, on: Boolean) {
        load(context)
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_REDUCE, on).apply()
        _reduceMotion.value = on
    }
}

/** Where the Import screen is. Held in the view model so choosing the file and reviewing it are separate screens. */
sealed interface ImportUi {
    data object None : ImportUi
    data object Reading : ImportUi
    data class NotDayCue(val reason: String) : ImportUi
    data class TooNew(val schemaVersion: Int) : ImportUi
    data object Unreadable : ImportUi
    data class Review(val plan: ImportPlan, val hadHistory: Boolean) : ImportUi
    data object Applied : ImportUi
    data class Failed(val conflict: Boolean) : ImportUi
}

class SettingsViewModel(app: Application) : SetupViewModel(app) {
    val config: StateFlow<DayCueConfig?> = facade.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), facade.snapshot.value?.config)
    val reduceMotion: StateFlow<Boolean> = UiPrefs.reduceMotion

    init { UiPrefs.load(app) }

    val importState = MutableStateFlow<ImportUi>(ImportUi.None)
    private var pendingExport: ExportResult? = null
    val exporting = MutableStateFlow(false)

    fun setReduceMotion(on: Boolean) = UiPrefs.setReduceMotion(getApplication(), on)

    /** null = follow the phone; else a language tag. Applies at once (per-app locale) and sets the cue language. */
    fun setLanguage(tag: String?) {
        viewModelScope.launch { facade.setAppLanguage(tag) }
    }

    fun currentLanguageTag(): String? = facade.appLanguageTag()

    fun setDayStart(t: LocalTime) = saveGlobal { it.copy(dayStartsAt = t) }
    fun setWorkDays(days: Set<java.time.DayOfWeek>) { if (days.isNotEmpty()) saveGlobal { it.copy(workDays = days) } }

    private fun saveGlobal(change: (GlobalSettings) -> GlobalSettings) {
        val cfg = config.value ?: return
        viewModelScope.launch { edit(listOf(ConfigOp.SetGlobalSettings(change(cfg.settings)))) }
    }

    // ---- export -------------------------------------------------------------------------------------

    fun suggestedFileName(): String = "daycue-setup-${LocalDate.now()}.daycue-backup.json"

    /** Builds the file content, then calls [launch] so the system file picker can ask where to save it. */
    fun prepareExport(includeHistory: Boolean, launch: () -> Unit) {
        viewModelScope.launch {
            exporting.value = true
            pendingExport = facade.exportConfig(includeHistory)
            exporting.value = false
            launch()
        }
    }

    fun writeExport(uri: Uri?) {
        val export = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export.json.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            post(if (ok) R.string.su_export_saved else R.string.su_export_failed)
        }
    }

    // ---- import ---------------------------------------------------------------------------------------

    fun readImport(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            importState.value = ImportUi.Reading
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var total = 0
                        var tooBig = false
                        while (true) {
                            val n = s.read(chunk)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMPORT_BYTES) { tooBig = true; break }
                            out.write(chunk, 0, n)
                        }
                        if (tooBig) null else String(out.toByteArray(), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) { importState.value = ImportUi.Unreadable; return@launch }
            val (parsed, plan) = facade.planImport(text)
            importState.value = when (parsed) {
                is ImportParse.NotDayCue -> ImportUi.NotDayCue(parsed.reason)
                is ImportParse.UnsupportedSchema -> ImportUi.TooNew(parsed.schemaVersion)
                is ImportParse.Ok -> plan?.let { ImportUi.Review(it, parsed.hadHistory) } ?: ImportUi.Unreadable
            }
        }
    }

    fun resetImport() { importState.value = ImportUi.None }

    fun applyImport(plan: ImportPlan) {
        viewModelScope.launch {
            importState.value = when (val r = facade.applyImport(plan)) {
                is ApplyOutcome.Applied -> { SetupBus.post(Snack(R.string.su_import_applied, undo = true)); ImportUi.Applied }
                is ApplyOutcome.Conflict -> ImportUi.Failed(conflict = true)
                else -> ImportUi.Failed(conflict = false)
            }
        }
    }

    companion object { const val MAX_IMPORT_BYTES = 5 * 1024 * 1024 }
}
