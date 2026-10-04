package app.daycue.domain.engine

import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.PauseSpec
import app.daycue.domain.context.InferredContext
import app.daycue.domain.time.TimeMath
import app.daycue.domain.time.plusMin
import java.time.Instant
import java.time.ZoneId

internal class Wake(val at: Instant, val precision: WakePrecision, val reason: String)

/** What an item wants delivered now. Committed only if the delivery policy actually sends it. */
internal class Proposal(
    val itemKey: String,
    val notificationKey: String,
    val type: CueType,
    val dueAt: Instant,
    /** Existing cue id for re-alerts; null = new delivery cycle (id assigned at delivery). */
    val cueId: String?,
    val title: Text,
    val body: Text,
    val actions: List<CueAction>,
    val why: WhyNow,
    val repeatIndex: Int = 0,
    val lockScreen: LockScreenVisibility = LockScreenVisibility.Public,
    val publicTitle: Text? = null,
    /** Lead speech for this item (null = never speaks). */
    val speech: Text? = null,
    /** Short name used in "Also: ..." (COL-1). */
    val shortName: Text = Text("short.${type.name.lowercase()}"),
    /** Forced silent by its own policy (quiet hours for calendar, DeliverSilently, notices...). */
    val silent: Boolean = false,
    val profileId: String? = null,
    val ongoing: Boolean = false,
    val fullScreen: Boolean = false,
    val isTest: Boolean = false,
    /** May be pulled forward into a group up to `mergeWindow` early (COL-1). */
    val pullable: Boolean = false,
    val commit: (EngineState, String) -> EngineState,
)

internal data class PauseInfo(val paused: Boolean, val until: Instant?)

internal sealed interface Gate {
    data class Open(val silent: Boolean) : Gate
    data class Held(val until: Instant?, val reason: String) : Gate
}

/** Mutable working context for one reduce. Never escapes [Engine.reduce]. */
internal class Run(
    val config: DayCueConfig,
    var st: EngineState,
    val now: Instant,
    val zone: ZoneId,
    val elapsedMs: Long,
    val event: Event,
) {
    val effects = mutableListOf<Effect>()
    val proposals = mutableListOf<Proposal>()
    val wakes = mutableListOf<Wake>()
    lateinit var ctx: InferredContext
    val ctxReady: Boolean get() = this::ctx.isInitialized
    /** One-shot guards within a single reduce (e.g. recovery cues). */
    val once = mutableSetOf<String>()

    val prevEvaluatedAt: Instant? = st.lastEvaluatedAt
    /** A routine run was already running before this event (for RTN-5/6 interruption detection). */
    val routineRunningAtStart: Boolean = st.routine.run?.status == RunStatus.Running
    val isReboot: Boolean = event is Event.BootCompleted && st.lastElapsedRealtimeMs != null && elapsedMs < st.lastElapsedRealtimeMs!!
    val zoneChanged: Boolean = st.lastZone != null && st.lastZone != zone
    val timeRecovery: Boolean = event is Event.TimeChanged || event is Event.TimezoneChanged || zoneChanged
    val recovery: Boolean = isReboot || timeRecovery
    val horizon: Instant = now.plusMin(config.settings.collision.mergeWindowMin)

    fun wake(at: Instant?, precision: WakePrecision, reason: String) {
        if (at != null && at.isAfter(now)) wakes += Wake(at, precision, reason)
    }

    fun history(itemKey: String, kind: HistoryKind, rule: String, cueId: String? = null, detail: Map<String, String> = emptyMap(), test: Boolean = false, at: Instant = now) {
        effects += Effect.RecordHistory(HistoryEntry(at, itemKey, kind, rule, cueId, detail, test))
    }

    fun dismiss(notificationKey: String, cueId: String?, reason: String) {
        effects += Effect.DismissCue(notificationKey, cueId, reason)
        st = st.copy(delivery = st.delivery.copy(visible = st.delivery.visible - notificationKey))
    }

    fun newCueId(itemKey: String): String {
        val n = st.delivery.seq + 1
        st = st.copy(delivery = st.delivery.copy(seq = n))
        return "$itemKey#$n"
    }

    fun propose(p: Proposal) { proposals += p }

    // ---- gates ----

    /** End of the current quiet-hours window, or null if not in quiet hours (§8.2). */
    fun quietUntil(at: Instant = now): Instant? {
        val q = config.settings.quietHours
        if (!q.enabled) return null
        return q.windows.mapNotNull { TimeMath.windowContaining(at, zone, it.window, it.days)?.second }.maxOrNull()
    }

    fun pauseInfo(spec: PauseSpec?, conditionHolds: Boolean = true, endedFor: Instant? = null): PauseInfo = when (spec) {
        null -> PauseInfo(false, null)
        is PauseSpec.Until -> if (now.isBefore(spec.until)) PauseInfo(true, spec.until) else PauseInfo(false, null)
        is PauseSpec.Indefinite -> PauseInfo(true, null)
        is PauseSpec.UntilConditionEnds ->
            if (endedFor == spec.setAt || !now.isBefore(spec.cap) || !conditionHolds && now.isAfter(spec.setAt)) PauseInfo(false, null) else PauseInfo(true, spec.cap)
    }

    fun globalPause(): PauseInfo = pauseInfo(config.settings.pauseAll)

    /** RTN-10: a run that is active, not paused, and not a test. */
    val routineActive: Boolean get() = st.routine.run?.let { it.status == RunStatus.Running && it.test == null } == true

    /** Common gate for P6–P8 style items (QH-1, RTN-10, pause). */
    fun softGate(pause: PauseInfo, meetingDefer: Boolean, meetingSilent: Boolean): Gate {
        val g = globalPause()
        if (g.paused) return Gate.Held(g.until, "paused_all")
        if (pause.paused) return Gate.Held(pause.until, "paused")
        quietUntil()?.let { return Gate.Held(it, "quiet_hours") }
        if (routineActive) return Gate.Held(null, "routine_running")
        if (ctx.inMeeting) {
            if (meetingDefer) return Gate.Held(ctx.meetingEndsAt, "meeting")
            if (meetingSilent) return Gate.Open(silent = true)
        }
        return Gate.Open(silent = false)
    }
}
