package app.daycue.ui.today

import androidx.annotation.StringRes
import app.daycue.R
import app.daycue.domain.config.Activity
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Environment
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.Language
import app.daycue.domain.config.PostureModeKind
import app.daycue.domain.config.SessionKind
import app.daycue.domain.context.ContextSource
import app.daycue.domain.context.EnvOverride
import app.daycue.domain.context.PlaceKind
import app.daycue.domain.context.Session
import app.daycue.domain.context.SessionStatus
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.PosturePhase
import app.daycue.domain.engine.RunStatus
import app.daycue.domain.engine.SlotRef
import app.daycue.domain.query.DoseStatus
import app.daycue.domain.query.TodayView
import app.daycue.domain.query.UpcomingItem
import app.daycue.domain.query.WaitingReason
import app.daycue.ui.components.ContextLineKind
import app.daycue.ui.field.FieldActive
import app.daycue.ui.field.FieldContext
import app.daycue.ui.marks.CueType as MarkCue
import app.daycue.ui.marks.PostureMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import app.daycue.domain.config.CueType as DomainCue

/**
 * UI model of the Today screen, derived purely from `Queries.todayView` + config + engine state (no Android, no
 * business rules: every decision comes from the domain). Texts are resolved by the composables from these
 * structured values, so this mapper is unit-testable on the JVM.
 */

/** A display name that is either a bundled default (localized by resource) or the owner's own text. */
data class ItemName(@StringRes val res: Int?, val raw: String)

data class PlaceInfo(val kind: PlaceKind, val name: ItemName?)

data class ContextInfo(
    val place: PlaceInfo,
    val placeSource: ContextSource,
    val placeSince: Instant?,
    val environment: Environment,
    val environmentSource: ContextSource,
    val environmentSince: Instant?,
    val activity: Activity,
    val activitySource: ContextSource,
    val activitySince: Instant?,
    val session: Session?,
    val envOverride: EnvOverride?,
    val placeOverride: app.daycue.domain.context.PlaceOverride?,
    val detectionPaused: Boolean,
    val detectionPausedUntil: Instant?,
    val lineKind: ContextLineKind,
)

sealed interface DueItem {
    val key: String
    val priority: Int
    val mark: MarkCue
}

data class HabitDue(
    override val key: String,
    val habitId: String,
    override val mark: MarkCue,
    override val priority: Int,
    val name: ItemName,
    val lastAck: Instant?,
    val snoozeMin: Int,
    /** Water bottle style: "Got it" / "Not needed" instead of ack + snooze. */
    val bottle: Boolean,
    val canPauseUntilConditionEnds: Boolean,
    val generic: Boolean,
    val rule: String,
) : DueItem

data class DoseDue(
    override val key: String,
    val slot: SlotRef,
    val label: String,
    val dueAt: Instant,
    val notConfirmed: Boolean,
) : DueItem {
    override val priority: Int get() = 2
    override val mark: MarkCue get() = MarkCue.Medication
}

data class PostureDue(val nextName: String?, val snoozeMin: Int) : DueItem {
    override val key: String get() = "posture"
    override val priority: Int get() = 7
    override val mark: MarkCue get() = MarkCue.Posture
}

data class RoutinePromptDue(val routineId: String, val name: ItemName) : DueItem {
    override val key: String get() = "routine-prompt:$routineId"
    override val priority: Int get() = 3
    override val mark: MarkCue get() = MarkCue.Routine
}

data class CalendarDue(override val key: String, val cueId: String, val title: String) : DueItem {
    override val priority: Int get() = 4
    override val mark: MarkCue get() = MarkCue.Calendar
}

data class SessionPromptDue(override val key: String, val placeId: String, val place: ItemName?, val cueId: String?) : DueItem {
    override val priority: Int get() = 9
    override val mark: MarkCue get() = MarkCue.Routine
}

sealed interface RunningItem {
    val key: String
}

data class SessionRunning(val kind: SessionKind, val since: Instant, val status: SessionStatus) : RunningItem {
    override val key: String get() = "session"
}

data class RoutineRunning(
    val routineId: String,
    val name: ItemName,
    val stepName: String,
    val stepNumber: Int,
    val stepCount: Int,
    val endsAt: Instant?,
    val paused: Boolean,
    val test: Boolean,
) : RunningItem {
    override val key: String get() = "routine:$routineId"
}

data class PostureRunning(
    val modeName: String,
    val mark: PostureMode?,
    val endsAt: Instant?,
    val nextName: String?,
    val nextMinutes: Int?,
    val paused: Boolean,
    val frozenBySession: Boolean,
    val keptMinutes: Int?,
) : RunningItem {
    override val key: String get() = "posture"
}

data class OverrideRunning(val environment: Environment, val expiresAt: Instant, val untilTransition: Boolean) : RunningItem {
    override val key: String get() = "override"
}

/** What a Next row says in place of a clock time. */
enum class ConditionText { Outdoors, Indoors, Working, Studying, SessionRunning, Generic }

data class NextItem(
    val key: String,
    val mark: MarkCue,
    val name: ItemName,
    val at: Instant?,
    val waiting: WaitingReason?,
    val waitingUntil: Instant?,
    val condition: ConditionText?,
    val rule: String,
    val lastAck: Instant?,
)

data class FieldModel(
    val context: FieldContext,
    val active: FieldActive,
    val nextMark: MarkCue,
    val remainingMinutes: Int,
    val horizonMinutes: Int,
    val overdue: Boolean,
    val nextName: ItemName?,
)

data class TodayModel(
    val now: Instant,
    val zone: ZoneId,
    val language: Language,
    val context: ContextInfo,
    val due: List<DueItem>,
    val running: List<RunningItem>,
    val next: List<NextItem>,
    val field: FieldModel?,
    val nothingEnabled: Boolean,
    val bottleEnabled: Boolean,
    val alarmEnabled: Boolean,
    /** Today's medication doses, for the (calm) nothing-else-today decision. */
    val doseCount: Int,
    /** Saved places the owner can pick in the context sheet (id, display name). */
    val places: List<Pair<String, ItemName>> = emptyList(),
)

/** Bundled default names that must follow the app language (user-renamed items keep their own text). */
fun defaultName(id: String, raw: String): ItemName {
    val res = when (id) {
        "sunscreen" -> R.string.cue_sunscreen to "Sunscreen"
        "hydration" -> R.string.cue_hydration to "Hydration"
        "water-bottle" -> R.string.cue_bottle to "Water bottle"
        "morning-routine" -> R.string.app_name_morning_routine to "Morning routine"
        "morning-alarm" -> R.string.app_name_morning_alarm to "Morning alarm"
        "home" -> R.string.app_place_home to "Home"
        "office" -> R.string.app_place_office to "Office"
        "gym" -> R.string.app_place_gym to "Gym"
        else -> null
    }
    return if (res != null && raw == res.second) ItemName(res.first, raw) else ItemName(null, raw)
}

object TodayMapper {

    fun map(view: TodayView, config: DayCueConfig, state: EngineState, zone: ZoneId): TodayModel {
        val now = view.now
        val lang = config.settings.language
        val ctx = view.context
        val placeName = (ctx.place.value.placeId)?.let { id -> config.place(id)?.let { defaultName(it.id, it.name) } }
        val pausedUntil = state.context.detectionPause?.until
        val lineKind = when {
            ctx.detectionPaused -> ContextLineKind.Paused
            ctx.place.value.kind == PlaceKind.Unknown -> ContextLineKind.Uncertain
            ctx.environment.source == ContextSource.Manual || state.context.envOverride != null || state.context.placeOverride != null -> ContextLineKind.Override
            else -> ContextLineKind.Normal
        }
        val context = ContextInfo(
            place = PlaceInfo(ctx.place.value.kind, placeName),
            placeSource = ctx.place.source,
            placeSince = ctx.place.since,
            environment = ctx.environment.value,
            environmentSource = ctx.environment.source,
            environmentSince = ctx.environment.since,
            activity = ctx.activity.value,
            activitySource = ctx.activity.source,
            activitySince = ctx.activity.since,
            session = state.context.session,
            envOverride = state.context.envOverride,
            placeOverride = state.context.placeOverride,
            detectionPaused = ctx.detectionPaused,
            detectionPausedUntil = pausedUntil,
            lineKind = lineKind,
        )

        val due = dueItems(view, config, state, lang)
        val running = runningItems(config, state, now, lang)
        val dueKeys = due.map { it.key }.toSet()
        val next = nextItems(view, config, dueKeys)

        val bottle = config.transitionHabits.any { it.enabled }
        val enabled = config.habits.count { it.enabled } + (if (config.postureCycle.enabled) 1 else 0) +
            config.routines.count { it.enabled } + config.alarms.count { it.enabled } + config.medications.size
        return TodayModel(
            now = now, zone = zone, language = lang, context = context, due = due, running = running, next = next,
            field = field(context, due, running, next, config, state, now),
            nothingEnabled = enabled == 0,
            bottleEnabled = bottle,
            alarmEnabled = config.alarms.any { it.enabled },
            doseCount = view.doses.size,
            places = config.places.map { it.id to defaultName(it.id, it.name) },
        )
    }

    private fun mark(type: DomainCue): MarkCue = when (type) {
        DomainCue.Alarm -> MarkCue.Alarm
        DomainCue.Medication -> MarkCue.Medication
        DomainCue.RoutineStep -> MarkCue.Routine
        DomainCue.Calendar -> MarkCue.Calendar
        DomainCue.WaterBottle -> MarkCue.Bottle
        DomainCue.Sunscreen -> MarkCue.Sunscreen
        DomainCue.Posture -> MarkCue.Posture
        DomainCue.Hydration, DomainCue.Habit, DomainCue.Notice -> MarkCue.Hydration
    }

    private fun dueItems(view: TodayView, config: DayCueConfig, state: EngineState, lang: Language): List<DueItem> {
        val out = mutableListOf<DueItem>()
        val upcomingByKey = view.upcoming.associateBy { it.itemKey }
        for (a in view.active) {
            if (a.kind == "posture_switch_pending") {
                val next = a.detail["next"]?.let { id -> config.postureCycle.modes.firstOrNull { it.id == id } }
                out += PostureDue(next?.name?.get(lang), config.postureCycle.snoozeMin)
                continue
            }
            if (a.kind != "cue") continue
            val key = a.itemKey
            when {
                key.startsWith("habit:") -> {
                    val habit = config.habit(key.removePrefix("habit:")) ?: continue
                    val interval = habit as? IntervalHabit
                    val lastAck = upcomingByKey[key]?.facts?.get("lastAck")?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val domainType = interval?.cueType ?: DomainCue.WaterBottle
                    out += HabitDue(
                        key = key, habitId = habit.id, mark = mark(domainType), priority = domainType.priority,
                        name = defaultName(habit.id, habit.name), lastAck = lastAck,
                        snoozeMin = interval?.snoozeMin ?: 15, bottle = interval == null,
                        canPauseUntilConditionEnds = interval != null && interval.kind == IntervalKind.Sunscreen,
                        generic = interval != null && interval.kind == IntervalKind.Generic,
                        rule = upcomingByKey[key]?.rule ?: "GEN-4",
                    )
                }
                key.startsWith("routine-prompt:") -> {
                    val id = key.removePrefix("routine-prompt:")
                    val r = config.routine(id)
                    out += RoutinePromptDue(id, defaultName(id, r?.name ?: id))
                }
                key.startsWith("cal:") -> {
                    val title = state.calendar.events.firstOrNull { it.key == key.removePrefix("cal:") }?.title.orEmpty()
                    out += CalendarDue(key, a.detail["cueId"].orEmpty(), title)
                }
                key.startsWith("session:") -> {
                    val pid = key.removePrefix("session:")
                    out += SessionPromptDue(key, pid, config.place(pid)?.let { defaultName(it.id, it.name) }, a.detail["cueId"])
                }
            }
        }
        view.doses.filter { it.status == DoseStatus.Due || it.status == DoseStatus.NotConfirmed }.forEach {
            out += DoseDue("med:${it.slot.key}", it.slot, it.label, it.dueAt, it.status == DoseStatus.NotConfirmed)
        }
        return out.sortedBy { it.priority }
    }

    private fun runningItems(config: DayCueConfig, state: EngineState, now: Instant, lang: Language): List<RunningItem> {
        val out = mutableListOf<RunningItem>()
        state.routine.run?.let { r ->
            val steps = r.routine.steps
            val step = steps.getOrNull(r.stepIndex)
            out += RoutineRunning(
                routineId = r.routineId, name = defaultName(r.routineId, r.routine.name), stepName = step?.name.orEmpty(),
                stepNumber = r.stepIndex + 1, stepCount = steps.size, endsAt = r.stepEndsAt,
                paused = r.status != RunStatus.Running, test = r.test != null,
            )
        }
        val cycle = config.postureCycle
        val ps = state.posture
        if (cycle.enabled && (ps.phase == PosturePhase.Running || ps.phase == PosturePhase.Paused || ps.phase == PosturePhase.Frozen)) {
            val modes = cycle.enabledModes
            val cur = cycle.modes.firstOrNull { it.id == ps.modeId }
            val idx = modes.indexOfFirst { it.id == ps.modeId }
            val next = if (modes.size > 1 && idx >= 0) modes[(idx + 1) % modes.size] else null
            val kept = ps.remainingMs?.let { (it / 60_000L).toInt() }
            out += PostureRunning(
                modeName = cur?.name?.get(lang).orEmpty(),
                mark = cur?.kind?.let(::postureMark),
                endsAt = if (ps.phase == PosturePhase.Running) ps.snoozedUntil ?: ps.modeEndsAt else null,
                nextName = next?.name?.get(lang),
                nextMinutes = next?.durationMin,
                paused = ps.phase != PosturePhase.Running,
                frozenBySession = ps.phase == PosturePhase.Frozen,
                keptMinutes = kept,
            )
        }
        state.context.session?.let { out += SessionRunning(it.kind, it.startedAt, it.status) }
        state.context.envOverride?.let { if (now.isBefore(it.expiresAt)) out += OverrideRunning(it.value, it.expiresAt, it.untilTransition) }
        return out
    }

    fun postureMark(kind: PostureModeKind): PostureMode? = when (kind) {
        PostureModeKind.Sitting -> PostureMode.Sit
        PostureModeKind.Standing -> PostureMode.Stand
        PostureModeKind.Walking -> PostureMode.Walk
        PostureModeKind.Custom -> null
    }

    private fun conditionOf(config: DayCueConfig, item: UpcomingItem): ConditionText {
        if (item.itemKey == "posture") return ConditionText.SessionRunning
        val habit = config.habit(item.itemKey.removePrefix("habit:")) as? IntervalHabit ?: return ConditionText.Generic
        val c = habit.condition
        return when {
            c.environments == setOf(Environment.Outdoor) -> ConditionText.Outdoors
            c.environments == setOf(Environment.Indoor) -> ConditionText.Indoors
            c.activities == setOf(Activity.Working) -> ConditionText.Working
            c.activities == setOf(Activity.Studying) -> ConditionText.Studying
            else -> ConditionText.Generic
        }
    }

    private fun nextItems(view: TodayView, config: DayCueConfig, dueKeys: Set<String>): List<NextItem> =
        view.upcoming.mapNotNull { u ->
            if (u.itemKey in dueKeys) return@mapNotNull null
            if (u.waiting == WaitingReason.Pending) return@mapNotNull null
            // Posture that is running, paused or frozen is a Running row, not a Next row.
            if (u.itemKey == "posture" && u.waiting != WaitingReason.WhenConditionHolds) return@mapNotNull null
            val name = when {
                u.itemKey == "posture" -> ItemName(R.string.cue_posture, "Posture")
                u.itemKey.startsWith("habit:") -> defaultName(u.itemKey.removePrefix("habit:"), u.name)
                u.itemKey.startsWith("alarm:") -> defaultName(u.itemKey.removePrefix("alarm:"), u.name)
                else -> ItemName(null, u.name)
            }
            NextItem(
                key = u.itemKey, mark = mark(u.type), name = name, at = u.at, waiting = u.waiting, waitingUntil = u.waitingUntil,
                condition = if (u.waiting == WaitingReason.WhenConditionHolds) conditionOf(config, u) else null,
                rule = u.rule,
                lastAck = u.facts["lastAck"]?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }

    fun fieldContext(ctx: ContextInfo): FieldContext = when {
        ctx.place.kind == PlaceKind.Unknown && ctx.environment == Environment.Unknown -> FieldContext.Unknown
        ctx.environment == Environment.Outdoor -> FieldContext.Outdoors
        ctx.activity == Activity.Working || ctx.activity == Activity.Studying || ctx.session != null -> FieldContext.Work
        ctx.environment == Environment.Indoor && ctx.envOverride != null -> FieldContext.Home
        ctx.place.kind == PlaceKind.Saved -> FieldContext.Home
        ctx.place.kind == PlaceKind.Elsewhere -> FieldContext.Transit
        else -> FieldContext.Unknown
    }

    private fun field(
        ctx: ContextInfo, due: List<DueItem>, running: List<RunningItem>, next: List<NextItem>,
        config: DayCueConfig, state: EngineState, now: Instant,
    ): FieldModel? {
        val posture = running.filterIsInstance<PostureRunning>().firstOrNull()
        val routine = running.filterIsInstance<RoutineRunning>().firstOrNull()
        val active: FieldActive = when {
            posture != null && posture.mark != null -> {
                val mode = config.postureCycle.modes.firstOrNull { it.name.get(config.settings.language) == posture.modeName }
                val total = (mode?.durationMin ?: 30) * 60_000L
                val remaining = posture.endsAt?.let { Duration.between(now, it).toMillis() } ?: posture.keptMinutes?.times(60_000L) ?: total
                val fill = (1f - remaining.toFloat() / total).coerceIn(0f, 1f)
                FieldActive.Posture(posture.mark, fill, nextPostureMark(config, state))
            }
            routine != null -> {
                val run = state.routine.run
                val total = run?.routine?.steps?.getOrNull(run.stepIndex)?.durationSec?.times(1000L) ?: 0L
                val remaining = routine.endsAt?.let { Duration.between(now, it).toMillis() } ?: 0L
                FieldActive.Routine(if (total > 0 && routine.endsAt != null) (1f - remaining.toFloat() / total).coerceIn(0f, 1f) else 0f)
            }
            else -> FieldActive.None
        }
        val upcoming = next.firstOrNull { (it.at != null && it.waiting == null) || (it.waiting == WaitingReason.CoveredUntil && (it.waitingUntil ?: it.at) != null) }
        val firstDue = due.firstOrNull()
        if (upcoming == null && firstDue == null && active == FieldActive.None) return null
        val horizon = upcoming?.let { u ->
            val interval = (config.habit(u.key.removePrefix("habit:")) as? IntervalHabit)?.intervalMin ?: 120
            minOf(120, interval)
        } ?: 120
        return FieldModel(
            context = fieldContext(ctx),
            active = active,
            nextMark = firstDue?.mark ?: upcoming?.mark ?: MarkCue.Hydration,
            remainingMinutes = (if (upcoming?.waiting == WaitingReason.CoveredUntil) upcoming.waitingUntil ?: upcoming.at else upcoming?.at)?.let { Duration.between(now, it).toMinutes().toInt().coerceAtLeast(0) } ?: horizon,
            horizonMinutes = horizon,
            overdue = firstDue != null,
            nextName = upcoming?.name,
        )
    }

    private fun nextPostureMark(config: DayCueConfig, state: EngineState): PostureMode? {
        val modes = config.postureCycle.enabledModes
        val idx = modes.indexOfFirst { it.id == state.posture.modeId }
        if (modes.size < 2 || idx < 0) return null
        return postureMark(modes[(idx + 1) % modes.size].kind)
    }
}
