package app.daycue.engine

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp

/**
 * Expresses "turn document [from] into document [to]" as targeted [ConfigOp]s, so whole-document
 * changes (import, undo) still pass the single `applyOps` validation path (ARCHITECTURE §3.2).
 *
 * Only items that differ are touched (an unchanged medication is not re-upserted, so it doesn't make
 * the change "sensitive"). Deletes go first, then upserts, then whole-section setters, then a second
 * pass corrects any side effects of deletes (e.g. `DeletePlace` editing habits). List order of items
 * that already existed is kept from [from]; new items are appended (documented limitation).
 */
object ConfigDiff {

    fun ops(from: DayCueConfig, to: DayCueConfig): List<ConfigOp> {
        val first = pass(from, to)
        val after = (ConfigEditor.applyOps(from, first, from.version) as? ApplyResult.Applied)?.config ?: return first
        val second = pass(after, to)
        return first + second
    }

    private fun pass(from: DayCueConfig, to: DayCueConfig): List<ConfigOp> {
        val ops = mutableListOf<ConfigOp>()
        fun <T> section(a: List<T>, b: List<T>, id: (T) -> String, del: (String) -> ConfigOp, up: (T) -> ConfigOp, deletes: MutableList<ConfigOp>, upserts: MutableList<ConfigOp>) {
            val bIds = b.map(id).toSet()
            a.filter { id(it) !in bIds }.forEach { deletes += del(id(it)) }
            val aById = a.associateBy(id)
            b.filter { aById[id(it)] != it }.forEach { upserts += up(it) }
        }
        val deletes = mutableListOf<ConfigOp>()
        val upserts = mutableListOf<ConfigOp>()
        section(from.cueProfiles, to.cueProfiles, { it.id }, { ConfigOp.DeleteCueProfile(it) }, { ConfigOp.UpsertCueProfile(it) }, deletes, upserts)
        section(from.places, to.places, { it.id }, { ConfigOp.DeletePlace(it) }, { ConfigOp.UpsertPlace(it) }, deletes, upserts)
        section(from.habits, to.habits, { it.id }, { ConfigOp.DeleteHabit(it) }, { ConfigOp.UpsertHabit(it) }, deletes, upserts)
        section(from.medications, to.medications, { it.id }, { ConfigOp.DeleteMedication(it) }, { ConfigOp.UpsertMedication(it) }, deletes, upserts)
        section(from.routines, to.routines, { it.id }, { ConfigOp.DeleteRoutine(it) }, { ConfigOp.UpsertRoutine(it) }, deletes, upserts)
        section(from.alarms, to.alarms, { it.id }, { ConfigOp.DeleteAlarm(it) }, { ConfigOp.UpsertAlarm(it) }, deletes, upserts)
        ops += deletes
        ops += upserts
        if (from.postureCycle != to.postureCycle) ops += ConfigOp.SetPostureCycle(to.postureCycle)
        if (from.calendarRules != to.calendarRules) ops += ConfigOp.SetCalendarConfig(to.calendarRules)
        if (from.contextRules != to.contextRules) ops += ConfigOp.SetContextRules(to.contextRules)
        if (from.settings != to.settings) ops += ConfigOp.SetGlobalSettings(to.settings)
        return ops
    }

    /** True when [a] and [b] are the same document apart from `version` (and list order). */
    fun sameContent(a: DayCueConfig, b: DayCueConfig): Boolean = pass(a, b).isEmpty()
}
