package app.daycue.data.repo

import androidx.room.withTransaction
import app.daycue.data.db.AuditLogEntity
import app.daycue.data.db.ConfigCurrentEntity
import app.daycue.data.db.ConfigHistoryEntity
import app.daycue.data.db.DayCueDatabase
import app.daycue.data.db.EngineStateEntity
import app.daycue.data.db.SignalEntity
import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.HistoryEntry
import app.daycue.domain.engine.WakePrecision
import app.daycue.domain.signal.CompanionActivity
import app.daycue.domain.signal.GeofenceSnapshot
import app.daycue.domain.signal.GeofenceTransition
import app.daycue.domain.signal.LocationAvailability
import app.daycue.domain.signal.MotionActivity
import app.daycue.domain.signal.MotionAvailability
import app.daycue.domain.signal.Signal
import app.daycue.engine.ConfigCommit
import app.daycue.engine.EngineStore
import app.daycue.engine.PreviousConfig
import java.time.Instant
import java.time.ZoneId

/** Room implementation of [EngineStore]: every commit is one SQLite transaction. */
class RoomEngineStore(private val db: DayCueDatabase) : EngineStore {

    override suspend fun loadConfig(): DayCueConfig? = db.configDao().current()?.let { ConfigCodec.decode(it.json) }

    override suspend fun loadState(): EngineState? = db.engineStateDao().current()?.let { EngineState.decode(it.json) }

    override suspend fun seedConfig(config: DayCueConfig, at: Instant) {
        db.withTransaction {
            if (db.configDao().current() == null) db.configDao().put(currentRow(config, at))
        }
    }

    override suspend fun commitReduction(state: EngineState, history: List<HistoryEntry>, zone: ZoneId, signal: Signal?, at: Instant) {
        db.withTransaction {
            db.engineStateDao().put(
                EngineStateEntity(
                    json = EngineState.encode(state),
                    configVersion = state.configVersion,
                    nextWakeAtMs = state.nextWakeAt?.toEpochMilli(),
                    nextWakePrecision = state.nextWakePrecision?.let(::precisionColumn),
                    updatedAtMs = at.toEpochMilli(),
                ),
            )
            if (history.isNotEmpty()) db.historyDao().insertAll(history.map { HistoryMapping.toEntity(it, zone) })
            if (signal != null) db.signalDao().put(
                SignalEntity(
                    source = signalSource(signal),
                    valueJson = DayCueJson.encodeToString(Signal.serializer(), signal),
                    observedAtMs = signal.observedAt.toEpochMilli(),
                    expiresAtMs = signal.expiresAt?.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun commitConfig(commit: ConfigCommit, at: Instant) {
        db.withTransaction {
            val dao = db.configDao()
            if (commit.pushPrevious) {
                dao.deleteHistory(commit.previous.version) // a version is only ever replaced once; defensive
                dao.insertHistory(
                    ConfigHistoryEntity(
                        version = commit.previous.version,
                        schemaVersion = commit.previous.schemaVersion,
                        json = ConfigCodec.encode(commit.previous),
                        replacedAtMs = at.toEpochMilli(),
                        source = commit.source,
                        commandId = commit.commandId,
                    ),
                )
                dao.trimHistory(ConfigHistoryEntity.MAX_ROWS)
            }
            commit.consumedHistoryVersion?.let { dao.deleteHistory(it) }
            dao.put(currentRow(commit.next, at))
            db.auditDao().insert(
                AuditLogEntity(
                    atMs = at.toEpochMilli(),
                    actor = commit.audit.actor,
                    action = commit.audit.action,
                    sensitivity = commit.audit.sensitivity.name,
                    summary = commit.audit.summary,
                    versionBefore = commit.previous.version,
                    versionAfter = commit.next.version,
                    commandId = commit.commandId,
                ),
            )
        }
    }

    override suspend fun latestPrevious(): PreviousConfig? =
        db.configDao().latestHistory()?.let { PreviousConfig(it.version, ConfigCodec.decode(it.json)) }

    private fun currentRow(c: DayCueConfig, at: Instant) =
        ConfigCurrentEntity(schemaVersion = c.schemaVersion, version = c.version, json = ConfigCodec.encode(c), updatedAtMs = at.toEpochMilli())

    companion object {
        fun precisionColumn(p: WakePrecision): String = when (p) {
            WakePrecision.AlarmClock -> "alarm_clock"
            WakePrecision.Exact -> "exact"
            WakePrecision.Inexact -> "inexact"
        }

        fun precisionFromColumn(s: String?): WakePrecision? = when (s) {
            "alarm_clock" -> WakePrecision.AlarmClock
            "exact" -> WakePrecision.Exact
            "inexact" -> WakePrecision.Inexact
            else -> null
        }

        fun signalSource(s: Signal): String = when (s) {
            is GeofenceTransition, is GeofenceSnapshot, is LocationAvailability -> "geofence"
            is MotionActivity, is MotionAvailability -> "motion"
            is CompanionActivity -> "companion"
            else -> "other"
        }
    }
}
