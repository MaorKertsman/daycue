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

/**
 * One entry of `status.recentChanges`. [summary] must come from `RemoteRedaction.Strict`
 * (`ConfigEditor.previewForRemote`): the entry is readable by every grant, including ones without the
 * medication scope. [medication] tags entries that mention medication so the relay can withhold them
 * (RELAY.md 3.1 "Per-client isolation").
 */
data class RecentChange(val version: Long?, val atMs: Long, val source: String, val summary: String, val medication: Boolean = false)

/**
 * Builds the redacted snapshot of RELAY.md 4.1. Redaction happens here, on the phone:
 * no coordinates (places keep names, radius and `hasLocation`), no calendar event contents (the event cache is
 * never read) and no calendar event ids (override keys are hashed), no tokens, no history, no medication
 * unless [includeMedication].
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
                put(k, when (k) {
                    "places" -> redactPlaces(v)
                    "calendarRules" -> hashOverrideKeys(v)
                    // Medication cue profiles are medication content: out of the shared config unless allowed.
                    "cueProfiles" -> if (includeMedication) v else dropMedicationProfiles(v)
                    else -> v
                })
            }
        }
        return buildJsonObject {
            put("version", config.version)
            put("schemaVersion", config.schemaVersion)
            put("publishedAt", nowMs)
            put("config", cfg)
            if (includeMedication) put("medication", buildJsonObject { put("medications", full["medications"] ?: JsonArray(emptyList())) })
            put("status", status(config, state, recent, includeMedication))
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

    private fun dropMedicationProfiles(profiles: JsonElement): JsonElement =
        JsonArray((profiles as? JsonArray ?: JsonArray(emptyList())).filter { (it as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content } != "Medication" })

    /** Event / series ids from the calendar provider can embed account domains (review I-7): publish a short hash. */
    internal fun hashOverrideKeys(calendar: JsonElement): JsonElement {
        val o = calendar as? JsonObject ?: return calendar
        val overrides = o["overrides"] as? JsonArray ?: return calendar
        val hashed = JsonArray(overrides.map { ov ->
            val oo = ov as? JsonObject ?: return@map ov
            val key = (oo["key"] as? JsonPrimitive)?.content ?: return@map ov
            JsonObject(oo + ("key" to JsonPrimitive("h:" + CanonicalJson.sha256Hex(key).take(12))))
        })
        return JsonObject(o + ("overrides" to hashed))
    }

    private val COARSE_KINDS = setOf("habit", "alarm", "routine", "posture", "calendar", "bottle", "session", "hydration", "sunscreen")

    /** Coarse kind of the next wake, plus whether it concerns medication (tagged `"medication": true`). */
    internal fun coarseKind(reason: String?): Pair<String, Boolean> {
        val r = reason?.lowercase() ?: return "other" to false
        if (r.startsWith("medication") || r.contains("med:")) return "medication" to true
        val first = r.substringBefore(':').substringBefore(' ')
        return (if (first in COARSE_KINDS) first else "other") to false
    }

    private fun status(config: DayCueConfig, state: EngineState, recent: List<RecentChange>, includeMedication: Boolean): JsonObject = buildJsonObject {
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
                val (kind, med) = coarseKind(state.nextWakeReason)
                if (med) {
                    put("medication", true)
                    // Without the owner's allowance not even the time of a dose is published.
                    if (includeMedication) { put("at", at.toEpochMilli()); put("kind", kind) }
                } else { put("at", at.toEpochMilli()); put("kind", kind) }
            })
        })
        put("recentChanges", buildJsonArray {
            for (r in recent.take(10)) add(buildJsonObject {
                if (r.medication) {
                    put("medication", true)
                    put("at", r.atMs)
                    put("source", r.source)
                    if (includeMedication) { if (r.version != null) put("version", r.version); put("summary", r.summary) }
                } else {
                    if (r.version != null) put("version", r.version)
                    put("at", r.atMs)
                    put("source", r.source)
                    put("summary", r.summary)
                }
            })
        })
    }
}

/** Audit rows that other grants can read are built from `RemoteRedaction.Strict` text (never raw preview text). */
object AuditText {
    private const val GENERIC = "(sensitive change, details on the phone)"

    /** [sensitivity] is the stored `Sensitivity.name`; [strictSummary] was produced by `previewForRemote(.., Strict)`. */
    fun forRecentChanges(sensitivity: String, strictSummary: String): Pair<String, Boolean> {
        val med = strictSummary.contains("medications")
        val text = if (sensitivity != "ordinary") GENERIC else strictSummary.take(200)
        return text to med
    }
}
