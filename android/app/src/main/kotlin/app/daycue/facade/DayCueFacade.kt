package app.daycue.facade

import android.content.Context
import android.content.Intent
import app.daycue.AppContainer
import app.daycue.data.db.AuditLogEntity
import app.daycue.data.db.HistoryEventEntity
import app.daycue.delivery.RoutinePlaybackService
import app.daycue.delivery.VoiceStatus
import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Environment
import app.daycue.domain.config.Language
import app.daycue.domain.config.SessionKind
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Preview
import app.daycue.domain.engine.AlarmAction
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.engine.RoutineAction
import app.daycue.domain.engine.RoutineTestMode
import app.daycue.domain.query.Queries
import app.daycue.domain.query.TodayView
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.DispatchResult
import app.daycue.engine.HostSnapshot
import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of [DayCueFacade.exportConfig]. UX §3.14: warn when [containsMedication]. */
data class ExportResult(val json: String, val containsMedication: Boolean, val includesHistory: Boolean)

/**
 * The UI's only entry point into the engine (documented in docs/architecture/APP_API.md).
 * Every write goes through [app.daycue.engine.EngineHost] (serialized, persisted, re-armed); every
 * config edit goes through `applyOps`. All functions are main-safe.
 */
class DayCueFacade(private val c: AppContainer) {

    init { c.scope.launch { c.host.ensureLoaded() } }

    /** Remote access / MCP relay: pairing, kill switch, local scopes, on-phone confirmations (APP_API.md section 10). */
    val remote: RelayFacade get() = c.remote

    /**
     * Spotify alarm source for the ringing alarm: `Connecting`, `Playing` (confirmed from player state, tone silent) or
     * `FellBack(failure, recovery)` (tone ringing; show the reason and a button for `recovery`). APP_API.md section 11.
     */
    val alarmMusic: StateFlow<app.daycue.integrations.spotify.AlarmMusicState> get() = c.spotify.alarmMusic

    /** The system intent for a recovery action (install Spotify, open it, network settings), or null. */
    fun alarmMusicRecoveryIntent(action: app.daycue.integrations.spotify.RecoveryAction): Intent? =
        app.daycue.integrations.spotify.SpotifyIntegration.recoveryIntent(c.app, action)

    /** "Try again" / "Authorize" from the alarm screen (visible, so the Spotify auth view may be shown when [interactive]). */
    fun retryAlarmMusic(interactive: Boolean = false) = c.spotify.player.retry(interactive)

    // ---- Observation -------------------------------------------------------------------------

    /** Current config + engine state (null until the first load finishes, normally a few ms). */
    val snapshot: StateFlow<HostSnapshot?> get() = c.host.snapshot

    val config: Flow<DayCueConfig> get() = c.host.snapshot.filterNotNull().map { it.config }.distinctUntilChanged()

    val engineState: Flow<EngineState> get() = c.host.snapshot.filterNotNull().map { it.state }.distinctUntilChanged()

    /** `Queries.todayView`, recomputed on every state/config change and every [refreshMs] (timers, expiries). */
    fun today(refreshMs: Long = 30_000): Flow<TodayView> =
        c.host.snapshot.filterNotNull().combine(ticker(refreshMs)) { s, _ -> s }
            .map { Queries.todayView(it.config, it.state, c.clock) }
            .flowOn(Dispatchers.Default)

    /** Newest first. Rows are GEN-8 history; `isTest` rows never count (GEN-10). */
    fun history(limit: Int = 200): Flow<List<HistoryEventEntity>> = c.db.historyDao().observeRecent(limit)

    /** History of one item by its engine item key (`habit:<id>`, `med:<id>|...`, `routine:<id>`, ...). */
    fun historyFor(subjectType: String, subjectId: String, limit: Int = 100): Flow<List<HistoryEventEntity>> =
        c.db.historyDao().observeFor(subjectType, subjectId, limit)

    fun audit(limit: Int = 100): Flow<List<AuditLogEntity>> = c.db.auditDao().observeRecent(limit)

    /** True when [undo] has something to restore. */
    val canUndo: Flow<Boolean> get() = c.db.configDao().observeLatestHistory().map { it != null }

    // ---- Events ------------------------------------------------------------------------------

    suspend fun dispatch(event: Event): DispatchResult = c.host.dispatch(event)

    /** In-app ack / snooze etc.: pass `cueId = null` in events (never stale). */
    fun dispatchAsync(event: Event) { c.scope.launch { c.host.dispatch(event) } }

    // ---- Config edits -------------------------------------------------------------------------

    /** The single edit path. [baseVersion] defaults to the version the UI last saw (conflicts are reported, not merged). */
    suspend fun apply(ops: List<ConfigOp>, baseVersion: Long? = null, source: String = "ui"): ApplyOutcome {
        val base = baseVersion ?: c.host.ensureLoaded().config.version
        return c.host.applyOps(ops, base, source)
    }

    /** Diff + sensitivity + validation errors without applying (MED-9 review, import review). */
    suspend fun preview(ops: List<ConfigOp>): Preview { c.host.ensureLoaded(); return c.host.preview(ops)!! }

    suspend fun undo(): ApplyOutcome = c.host.undo()

    // ---- Context controls (PRODUCT §1.4) --------------------------------------------------------

    suspend fun setEnvironment(value: Environment, duration: OverrideDuration = OverrideDuration.UntilTransition) = dispatch(Event.OverrideEnvironment(value, duration))
    suspend fun clearEnvironmentOverride() = dispatch(Event.ClearEnvironmentOverride)
    suspend fun startSession(kind: SessionKind, duration: OverrideDuration = OverrideDuration.UntilChanged) = dispatch(Event.StartSession(kind, duration))
    suspend fun endSession() = dispatch(Event.EndSession)
    suspend fun pauseAutoDetection(duration: OverrideDuration = OverrideDuration.For(120)) = dispatch(Event.PauseAutoDetection(duration))
    suspend fun resumeAutoDetection() = dispatch(Event.ResumeAutoDetection)
    /** BTL-1 "Leaving now" (tile, widget, Today chip). */
    suspend fun leavingNow() = dispatch(Event.LeavingNow)

    // ---- Routines and alarms ------------------------------------------------------------------

    /**
     * Starts a routine from a user action. Must be called while an Activity of ours is visible: it starts
     * the `mediaPlayback` foreground service that keeps speech audible (Android 17).
     */
    fun startRoutine(context: Context, routineId: String, replaceCurrent: Boolean = false) =
        RoutinePlaybackService.start(context, routineId, replaceCurrent)

    /** RTN-8 test run (x1 or fast) — still through the playback service. */
    fun testRoutine(context: Context, routineId: String, mode: RoutineTestMode) =
        RoutinePlaybackService.start(context, routineId, replaceCurrent = true, test = mode)

    /** ALM-5: ring now with the real config. */
    suspend fun testAlarm(alarmId: String) = dispatch(Event.AlarmControl(alarmId, AlarmAction.Test))

    // ---- Tests and previews (GEN-10) ----------------------------------------------------------

    /** "Send a test reminder": a real notification labeled Test (with sound/vibration/speech of the type's profile). */
    suspend fun sendTestReminder(type: CueType = CueType.Habit) = dispatch(Event.PreviewCue(type.name))

    /** Cue profile "Preview": sound, vibration and phrase, without posting anything or recording history. */
    suspend fun previewCueProfile(profileId: String) {
        val cfg = c.host.ensureLoaded().config
        val profile = cfg.cueProfiles.firstOrNull { it.id == profileId } ?: return
        withContext(Dispatchers.Main) { c.cuePlayer.preview(profile, cfg.settings.language) }
    }

    // ---- Export / import ----------------------------------------------------------------------

    suspend fun exportConfig(includeHistory: Boolean = false): ExportResult = withContext(Dispatchers.IO) {
        val cfg = c.host.ensureLoaded().config
        val history = if (includeHistory) c.db.historyDao().all() else null
        ExportResult(ConfigTransfer.export(cfg, history, c.clock.now()), cfg.medications.isNotEmpty(), includeHistory)
    }

    /** Parse + plan (diff, sensitivity, validation). Nothing is applied. */
    suspend fun planImport(text: String): Pair<ImportParse, ImportPlan?> {
        val parsed = ConfigTransfer.parse(text)
        val plan = (parsed as? ImportParse.Ok)?.let { ConfigTransfer.plan(c.host.ensureLoaded().config, it.config) }
        return parsed to plan
    }

    /** Applies a reviewed import through `applyOps` (fails with Conflict if the config changed since planning). */
    suspend fun applyImport(plan: ImportPlan): ApplyOutcome = c.host.applyOps(plan.ops, plan.baseVersion, "import")

    // ---- Readiness and speech -----------------------------------------------------------------

    private val _readiness = MutableStateFlow<ReadinessReport?>(null)
    val readiness: StateFlow<ReadinessReport?> = _readiness.asStateFlow()

    val voices: StateFlow<VoiceStatus> get() = c.speech.voices

    /** Re-check every row (call from onResume). Also initializes TTS once to learn voice availability. */
    suspend fun refreshReadiness(): ReadinessReport {
        c.speech.checkVoices()
        val snap = c.host.ensureLoaded()
        val speechWanted = snap.config.settings.speech.enabled
        return c.readiness.check(c.speech.voices.value, snap.state, c.host.lastArm, speechWanted).also { _readiness.value = it }
    }

    fun fixIntent(id: ReadinessId): Intent? = c.readiness.fixIntent(id)

    fun setSpeechRate(rate: Float) = c.speechPrefs.setRate(rate)
    fun speechRate(): Float = c.speechPrefs.rate()
    fun setVoice(language: Language, voiceName: String?) = c.speechPrefs.setVoice(language, voiceName)
    fun voice(language: Language): String? = c.speechPrefs.voice(language)

    // ---- Context producers: places, location/motion permissions, calendar (APP_API.md §10-§12) ----

    /** Saved places CRUD, "Use current location", progressive location permission flow. */
    val places: PlacesFacade by lazy { PlacesFacade(c, this) }

    /** Device calendars (Calendar Provider, read-only): selection, preview with "why matched", one-tap overrides. */
    val calendar: CalendarFacade by lazy { CalendarFacade(c, this) }

    private val _contextReadiness = MutableStateFlow<ContextReadinessReport?>(null)
    val contextReadiness: StateFlow<ContextReadinessReport?> = _contextReadiness.asStateFlow()

    /** Rows for location / background location / activity recognition / calendar (call from onResume with [refreshReadiness]). */
    suspend fun refreshContextReadiness(): ContextReadinessReport = ContextReadinessCheck.check(c).also { _contextReadiness.value = it }

    private fun ticker(ms: Long): Flow<Unit> = flow { while (true) { emit(Unit); delay(ms) } }
}
