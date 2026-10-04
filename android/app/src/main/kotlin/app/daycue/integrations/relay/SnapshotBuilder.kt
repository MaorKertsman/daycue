package app.daycue.integrations.relay

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.engine.EngineState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One entry of `status.recentChanges` (already free of sensitive content). */
data class RecentChange(val version: Long?, val atMs: Long, val source: String, val summary: String)

/**
 * Builds the redacted snapshot of RELAY.md 4.1. Redaction happens here, on the phone:
 * no coordinates (places keep names, radius and `hasLocation`), no calendar event contents (the event cache is
 * never read), no tokens, no history, no medication unless [includeMedication].
 */
object SnapshotBuilder {
    const val MAX_BYTES = 256 * 1024
    private val SECTIONS = listOf("habits", "routines", "cueProfiles", "calendarRules", "places", "settings", "alarms", "postureCycle", "contextRules")

    fun build(
        config: DayCueConfig,
        state: EngineState,
        nowMs: Long,
        includeMedication: Boolean,
        recent: List<RecentChange>,
    ): JsonObject {
        val full = DayCueJson.encodeToJsonElement(DayCueConfig.serializer(), config).jsonObject
        val cfg = buildJsonObject {
            for (k in SECTIONS) {
                val v = full[k] ?: continue
                put(k, if (k == "places") redactPlaces(v) else v)
            }
        }
        return buildJsonObject {
            put("version", config.version)
            put("schemaVersion", config.schemaVersion)
            put("publishedAt", nowMs)
            put("config", cfg)
            if (includeMedication) put("medication", buildJsonObject { put("medications", full["medications"] ?: JsonArray(emptyList())) })
            put("status", status(config, state, recent))
        }
    }

    /** Coordinates never leave the phone. */
    internal fun redactPlaces(places: JsonElement): JsonElement = JsonArray((places as? JsonArray ?: JsonArray(emptyList())).map { p ->
        val o = p.jsonObject
        buildJsonObject {
            for ((k, v) in o) if (k != "center") put(k, v)
            put("hasLocation", o["center"].let { it != null && it !is kotlinx.serialization.json.JsonNull })
        }
    })

    private val COARSE_KINDS = setOf("habit", "alarm", "routine", "posture", "calendar", "bottle", "session", "hydration", "sunscreen")

    private fun status(config: DayCueConfig, state: EngineState, recent: List<RecentChange>): JsonObject = buildJsonObject {
        put("activeSessions", buildJsonArray {
            state.context.session?.let { s ->
                add(buildJsonObject {
                    put("kind", s.kind.name)
                    put("status", s.status.name)
                    put("manual", s.manual)
                    put("startedAt", s.startedAt.toEpochMilli())
                    s.placeId?.let { id -> config.place(id)?.name?.let { put("place", it) } }
                })
            }
        })
        put("nextCues", buildJsonArray {
            val at = state.nextWakeAt
            if (at != null) add(buildJsonObject {
                put("at", at.toEpochMilli())
                val kind = state.nextWakeReason?.substringBefore(':')?.lowercase()
                put("kind", if (kind != null && kind in COARSE_KINDS) kind else "other")
            })
        })
        put("recentChanges", buildJsonArray {
            for (r in recent.take(10)) add(buildJsonObject {
                if (r.version != null) put("version", r.version)
                put("at", r.atMs)
                put("source", r.source)
                put("summary", r.summary)
            })
        })
    }
}

/** Removes content that must not leave the phone from diff text (coordinates, medication content). */
object Redaction {
    private val COORD = Regex("""(^|[.\[])(center|lat|lng)\b""")

    /** [diffLines] are `DiffLine.text` strings (`path: before -> after`). */
    fun summary(diffLines: List<String>, maxChars: Int = 300): String {
        val kept = diffLines.filter { l ->
            val path = l.substringBefore(':')
            !COORD.containsMatchIn(path) && !path.startsWith("medications")
        }
        val dropped = diffLines.size - kept.size
        val text = kept.joinToString("; ").ifBlank { if (dropped > 0) "(details withheld)" else "" }
        val t = if (dropped > 0 && kept.isNotEmpty()) "$text; (+$dropped withheld)" else text
        return if (t.length > maxChars) t.take(maxChars - 1) + "…" else t
    }

    /** Audit rows are summaries of `Preview.text`; sensitive rows are published generically. */
    fun auditSummary(sensitivity: String, text: String): String =
        if (sensitivity != "ordinary") "(sensitive change, details on the phone)" else summary(text.split('\n'), 200)
}
