package app.daycue.integrations.relay

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.EngineHost
import app.daycue.engine.EngineStore
import app.daycue.engine.PreviousConfig

/** [EngineGateway] over the real [EngineHost]: every remote change goes through `applyOps` (single edit path). */
class HostEngineGateway(private val host: EngineHost, private val store: EngineStore) : EngineGateway {
    override suspend fun config(): DayCueConfig = host.ensureLoaded().config
    override suspend fun state(): EngineState = host.ensureLoaded().state
    override suspend fun applyOps(ops: List<ConfigOp>, baseVersion: Long, commandId: String) = host.applyOps(ops, baseVersion, "mcp", commandId)
    override suspend fun undo(): ApplyOutcome = host.undo()
    override suspend fun previous(): PreviousConfig? = store.latestPrevious()
    override suspend fun dispatch(event: Event) { host.dispatch(event) }
}
