package app.daycue.domain.edit

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.Habit
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.RoutineTrigger
import app.daycue.domain.config.TransitionHabit
import app.daycue.domain.engine.PauseTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Result of [ConfigEditor.applyOps]. */
sealed interface ApplyResult {
    data class Applied(val config: DayCueConfig, val previous: DayCueConfig) : ApplyResult
    data class Invalid(val errors: List<ValidationError>) : ApplyResult
    /** The caller edited an older version (MCP `baseVersion`, concurrent UI edit). */
    data class Conflict(val currentVersion: Long, val baseVersion: Long) : ApplyResult
}

@Serializable enum class Sensitivity { ordinary, sensitive, destructive }

@Serializable
data class DiffLine(val path: String, val before: String?, val after: String?) {
    val text: String get() = when {
        before == null -> "$path: added $after"
        after == null -> "$path: removed $before"
        else -> "$path: $before -> $after"
    }
}

@Serializable
data class Preview(
    val lines: List<DiffLine>,
    val sensitivity: Sensitivity,
    val errors: List<ValidationError>,
) {
    val valid: Boolean get() = errors.isEmpty()
    val text: String get() = lines.joinToString("\n") { it.text }
}

private class OpError(val error: ValidationError) : Exception(error.message)

/** Pure config edit path shared by UI, MCP and import (ARCHITECTURE §3.2). */
object ConfigEditor {

    fun applyOps(config: DayCueConfig, ops: List<ConfigOp>, baseVersion: Long): ApplyResult {
        if (baseVersion != config.version) return ApplyResult.Conflict(config.version, baseVersion)
        val next = try {
            ops.fold(config) { c, op -> applyOne(c, op) }
        } catch (e: OpError) {
            return ApplyResult.Invalid(listOf(e.error))
        }
        val errors = ConfigValidator.validate(next)
        if (errors.isNotEmpty()) return ApplyResult.Invalid(errors)
        return ApplyResult.Applied(next.copy(version = config.version + 1), config)
    }

    /**
     * Human-readable diff plus sensitivity (ARCHITECTURE §3.2, MED-9): deletions are `destructive`;
     * any medication change is `sensitive`. With [redactMedicationLabels] labels are replaced by "***"
     * (for remote callers without the `medication` scope).
     */
    fun preview(config: DayCueConfig, ops: List<ConfigOp>, redactMedicationLabels: Boolean = false): Preview {
        val next = try {
            ops.fold(config) { c, op -> applyOne(c, op) }
        } catch (e: OpError) {
            return Preview(emptyList(), sensitivityOf(ops, emptyList()), listOf(e.error))
        }
        val before = DayCueJson.encodeToJsonElement(DayCueConfig.serializer(), config)
        val after = DayCueJson.encodeToJsonElement(DayCueConfig.serializer(), next)
        val lines = mutableListOf<DiffLine>()
        diff("", before, after, lines)
        val shown = lines.filter { it.path != "version" }.map { l ->
            if (redactMedicationLabels && l.path.startsWith("medications")) {
                l.copy(before = l.before?.let { redact(l.path, it) }, after = l.after?.let { redact(l.path, it) })
            } else l
        }
        return Preview(shown, sensitivityOf(ops, shown), ConfigValidator.validate(next))
    }

    private fun redact(path: String, v: String): String = if (path.endsWith(".label") || !path.contains('.')  || path.endsWith("]")) "***" else v

    private fun sensitivityOf(ops: List<ConfigOp>, lines: List<DiffLine>): Sensitivity {
        val destructive = ops.any {
            it is ConfigOp.DeleteHabit || it is ConfigOp.DeleteMedication || it is ConfigOp.DeleteRoutine || it is ConfigOp.DeleteAlarm ||
                it is ConfigOp.DeletePlace || it is ConfigOp.DeleteCueProfile || it is ConfigOp.DeleteCalendarRule || it is ConfigOp.DeleteRoutineStep ||
                it is ConfigOp.RemoveCalendarPreference
        } || lines.any { it.after == null && it.path.endsWith("]") }
        if (destructive) return Sensitivity.destructive
        val sensitive = ops.any {
            it is ConfigOp.UpsertMedication || it is ConfigOp.SetMedicationTimes || it is ConfigOp.SetMedicationTravelPolicy || it is ConfigOp.SetMedicationEndDate
        } || lines.any { it.path.startsWith("medications") }
        return if (sensitive) Sensitivity.sensitive else Sensitivity.ordinary
    }

    private fun diff(path: String, a: JsonElement?, b: JsonElement?, out: MutableList<DiffLine>) {
        if (a == b) return
        when {
            a is JsonObject && b is JsonObject -> {
                for (k in (a.keys + b.keys)) diff(if (path.isEmpty()) k else "$path.$k", a[k], b[k], out)
            }
            a is JsonArray && b is JsonArray && (a + b).all { it is JsonObject && it["id"] != null } -> {
                fun idOf(e: JsonElement) = (e as JsonObject)["id"]!!.jsonPrimitive.content
                val am = a.associateBy(::idOf); val bm = b.associateBy(::idOf)
                for (id in (am.keys + bm.keys)) diff("$path[$id]", am[id], bm[id], out)
                if (am.keys.intersect(bm.keys).let { common -> a.map(::idOf).filter { it in common } != b.map(::idOf).filter { it in common } }) {
                    out += DiffLine("$path.order", a.map(::idOf).joinToString(","), b.map(::idOf).joinToString(","))
                }
            }
            else -> out += DiffLine(path, a?.let(::show), b?.let(::show))
        }
    }

    private fun show(e: JsonElement): String = when (e) {
        is JsonNull -> "none"
        is JsonPrimitive -> e.contentOrNull ?: "none"
        else -> e.toString()
    }

    // ---------------------------------------------------------------------------------------------

    private fun err(path: String, msg: String, code: String = "not_found"): Nothing = throw OpError(ValidationError(path, code, msg))

    private inline fun <T> List<T>.replaceBy(path: String, id: String, idOf: (T) -> String, f: (T) -> T): List<T> {
        if (none { idOf(it) == id }) err(path, "no item with id '$id'")
        return map { if (idOf(it) == id) f(it) else it }
    }

    private fun <T> List<T>.upsert(item: T, idOf: (T) -> String, index: Int? = null): List<T> {
        val id = idOf(item)
        return if (any { idOf(it) == id }) map { if (idOf(it) == id) item else it }
        else if (index != null) toMutableList().apply { add(index.coerceIn(0, size), item) } else this + item
    }

    private fun <T> List<T>.removeBy(path: String, id: String, idOf: (T) -> String): List<T> {
        if (none { idOf(it) == id }) err(path, "no item with id '$id'")
        return filterNot { idOf(it) == id }
    }

    private fun applyOne(c: DayCueConfig, op: ConfigOp): DayCueConfig = when (op) {
        is ConfigOp.UpsertHabit -> c.copy(habits = c.habits.upsert(op.habit, Habit::id))
        is ConfigOp.DeleteHabit -> c.copy(habits = c.habits.removeBy("habits", op.id, Habit::id),
            places = c.places.map { it.copy(allowedRoutines = it.allowedRoutines - op.id) })
        is ConfigOp.SetHabitEnabled -> c.copy(habits = c.habits.replaceBy("habits", op.id, Habit::id) {
            when (it) { is IntervalHabit -> it.copy(enabled = op.enabled); is TransitionHabit -> it.copy(enabled = op.enabled) }
        })
        is ConfigOp.SetHabitInterval -> c.copy(habits = c.habits.replaceBy("habits", op.id, Habit::id) {
            (it as? IntervalHabit)?.copy(intervalMin = op.minutes) ?: err("habits[${op.id}]", "not an interval habit", "wrong_type")
        })
        is ConfigOp.SetHabitActiveHours -> c.copy(habits = c.habits.replaceBy("habits", op.id, Habit::id) {
            (it as? IntervalHabit)?.copy(activeHours = op.window, days = op.days ?: it.days) ?: err("habits[${op.id}]", "not an interval habit", "wrong_type")
        })
        is ConfigOp.SetPause -> when (val t = op.target) {
            is PauseTarget.Habit -> c.copy(habits = c.habits.replaceBy("habits", t.habitId, Habit::id) {
                when (it) { is IntervalHabit -> it.copy(pause = op.pause); is TransitionHabit -> it.copy(pause = op.pause) }
            })
            PauseTarget.Posture -> c.copy(postureCycle = c.postureCycle.copy(pause = op.pause))
            PauseTarget.All -> c.copy(settings = c.settings.copy(pauseAll = op.pause))
        }
        is ConfigOp.SetPostureCycle -> c.copy(postureCycle = op.cycle)
        is ConfigOp.SetPostureModes -> c.copy(postureCycle = c.postureCycle.copy(modes = op.modes))
        is ConfigOp.SetPostureEnabled -> c.copy(postureCycle = c.postureCycle.copy(enabled = op.enabled))
        is ConfigOp.UpsertMedication -> c.copy(medications = c.medications.upsert(op.medication, { it.id }))
        is ConfigOp.DeleteMedication -> c.copy(medications = c.medications.removeBy("medications", op.id) { it.id })
        is ConfigOp.SetMedicationTimes -> c.copy(medications = c.medications.replaceBy("medications", op.id, { it.id }) { it.copy(times = op.times, days = op.days ?: it.days) })
        is ConfigOp.SetMedicationTravelPolicy -> c.copy(medications = c.medications.replaceBy("medications", op.id, { it.id }) { it.copy(travelPolicy = op.policy) })
        is ConfigOp.SetMedicationEndDate -> c.copy(medications = c.medications.replaceBy("medications", op.id, { it.id }) { it.copy(endDate = op.endDate) })
        is ConfigOp.UpsertRoutine -> c.copy(routines = c.routines.upsert(op.routine, { it.id }))
        is ConfigOp.DeleteRoutine -> c.copy(
            routines = c.routines.removeBy("routines", op.id) { it.id },
            alarms = c.alarms.map { if (it.followOnRoutineId == op.id) it.copy(followOnRoutineId = null) else it },
            places = c.places.map { it.copy(allowedRoutines = it.allowedRoutines - op.id) },
        )
        is ConfigOp.DuplicateRoutine -> {
            val src = c.routine(op.sourceId) ?: err("routines", "no item with id '${op.sourceId}'")
            if (c.routine(op.newId) != null) err("routines[${op.newId}]", "id already exists", "duplicate_id")
            c.copy(routines = c.routines + src.copy(id = op.newId, name = op.newName, enabled = false, trigger = RoutineTrigger.Manual))
        }
        is ConfigOp.UpsertRoutineStep -> c.copy(routines = c.routines.replaceBy("routines", op.routineId, { it.id }) { r -> r.copy(steps = r.steps.upsert(op.step, { it.id }, op.index)) })
        is ConfigOp.DeleteRoutineStep -> c.copy(routines = c.routines.replaceBy("routines", op.routineId, { it.id }) { r -> r.copy(steps = r.steps.removeBy("routines[${r.id}].steps", op.stepId) { it.id }) })
        is ConfigOp.ReorderRoutineSteps -> c.copy(routines = c.routines.replaceBy("routines", op.routineId, { it.id }) { r ->
            if (op.stepIds.sorted() != r.steps.map { it.id }.sorted()) err("routines[${r.id}].steps", "reorder must list every step exactly once", "bad_reorder")
            r.copy(steps = op.stepIds.map { id -> r.steps.first { it.id == id } })
        })
        is ConfigOp.UpsertAlarm -> c.copy(alarms = c.alarms.upsert(op.alarm, { it.id }))
        is ConfigOp.DeleteAlarm -> c.copy(
            alarms = c.alarms.removeBy("alarms", op.id) { it.id },
            routines = c.routines.map { r -> if ((r.trigger as? RoutineTrigger.AfterAlarm)?.alarmId == op.id) r.copy(trigger = RoutineTrigger.Manual) else r },
        )
        is ConfigOp.SetAlarmEnabled -> c.copy(alarms = c.alarms.replaceBy("alarms", op.id, { it.id }) { it.copy(enabled = op.enabled) })
        is ConfigOp.SkipNextAlarm -> c.copy(alarms = c.alarms.replaceBy("alarms", op.id, { it.id }) { it.copy(skipDate = op.date) })
        is ConfigOp.UpsertPlace -> c.copy(places = c.places.upsert(op.place, { it.id }))
        is ConfigOp.DeletePlace -> c.copy(
            places = c.places.removeBy("places", op.id) { it.id },
            habits = c.habits.map { h ->
                when (h) {
                    is TransitionHabit -> h.copy(placeIds = h.placeIds - op.id, scheduledDepartures = h.scheduledDepartures.filterNot { it.placeId == op.id })
                    is IntervalHabit -> h.copy(
                        condition = h.condition.copy(places = h.condition.places?.minus(op.id)),
                        contextIntervals = h.contextIntervals.map { it.copy(condition = it.condition.copy(places = it.condition.places?.minus(op.id))) },
                    )
                }
            },
        )
        is ConfigOp.SetPlaceLocation -> c.copy(places = c.places.replaceBy("places", op.id, { it.id }) { it.copy(center = op.center, radiusM = op.radiusM ?: it.radiusM) })
        is ConfigOp.UpsertCueProfile -> c.copy(cueProfiles = c.cueProfiles.upsert(op.profile, { it.id }))
        is ConfigOp.DeleteCueProfile -> {
            val id = op.id
            fun f(x: String?) = if (x == id) null else x
            c.copy(
                cueProfiles = c.cueProfiles.removeBy("cueProfiles", id) { it.id },
                habits = c.habits.map { h -> when (h) { is IntervalHabit -> h.copy(cueProfileId = f(h.cueProfileId)); is TransitionHabit -> h.copy(cueProfileId = f(h.cueProfileId)) } },
                medications = c.medications.map { it.copy(cueProfileId = f(it.cueProfileId)) },
                routines = c.routines.map { r -> r.copy(steps = r.steps.map { it.copy(cueProfileId = f(it.cueProfileId)) }) },
                postureCycle = c.postureCycle.copy(cueProfileId = f(c.postureCycle.cueProfileId)),
                calendarRules = c.calendarRules.copy(cueProfileId = f(c.calendarRules.cueProfileId)),
            )
        }
        is ConfigOp.SetCalendarConfig -> c.copy(calendarRules = op.calendar)
        is ConfigOp.UpsertCalendarRule -> c.copy(calendarRules = c.calendarRules.copy(rules = c.calendarRules.rules.upsert(op.rule, { it.id }, op.index)))
        is ConfigOp.DeleteCalendarRule -> c.copy(calendarRules = c.calendarRules.copy(rules = c.calendarRules.rules.removeBy("calendarRules.rules", op.id) { it.id }))
        is ConfigOp.ReorderCalendarRules -> {
            val rules = c.calendarRules.rules
            if (op.ruleIds.sorted() != rules.map { it.id }.sorted()) err("calendarRules.rules", "reorder must list every rule exactly once", "bad_reorder")
            c.copy(calendarRules = c.calendarRules.copy(rules = op.ruleIds.map { id -> rules.first { it.id == id } }))
        }
        is ConfigOp.SetCalendarPreference -> c.copy(calendarRules = c.calendarRules.copy(calendars = c.calendarRules.calendars.upsert(op.preference, { it.calendarId })))
        is ConfigOp.RemoveCalendarPreference -> c.copy(calendarRules = c.calendarRules.copy(calendars = c.calendarRules.calendars.removeBy("calendarRules.calendars", op.calendarId) { it.calendarId }))
        is ConfigOp.SetEventOverride -> {
            val rest = c.calendarRules.overrides.filterNot { it.key == op.key && it.scope == op.scope }
            val added = op.decision?.let { listOf(app.daycue.domain.config.EventOverride(op.key, op.scope, it)) } ?: emptyList()
            c.copy(calendarRules = c.calendarRules.copy(overrides = rest + added))
        }
        is ConfigOp.SetContextRules -> c.copy(contextRules = op.rules)
        is ConfigOp.SetSessionRules -> c.copy(contextRules = c.contextRules.copy(sessions = op.rules))
        is ConfigOp.SetQuietHours -> c.copy(settings = c.settings.copy(quietHours = op.quietHours))
        is ConfigOp.SetSpeechSettings -> c.copy(settings = c.settings.copy(speech = op.speech))
        is ConfigOp.SetCollisionSettings -> c.copy(settings = c.settings.copy(collision = op.collision))
        is ConfigOp.SetLanguage -> c.copy(settings = c.settings.copy(language = op.language))
        is ConfigOp.SetGlobalSettings -> c.copy(settings = op.settings)
    }
}

/** Bounded undo/audit list of prior config documents (ARCHITECTURE §3.2 `config_history`). */
@Serializable
data class ConfigHistory(val maxSize: Int = 20, val entries: List<DayCueConfig> = emptyList()) {
    fun push(previous: DayCueConfig): ConfigHistory = copy(entries = (entries + previous).takeLast(maxSize))

    /**
     * Undo: restore the most recent prior document as a **new** version (versions stay monotonic so MCP
     * `baseVersion` checks keep working). Returns null when there is nothing to undo.
     */
    fun undo(current: DayCueConfig): Pair<DayCueConfig, ConfigHistory>? {
        val prev = entries.lastOrNull() ?: return null
        return prev.copy(version = current.version + 1) to copy(entries = entries.dropLast(1))
    }
}
