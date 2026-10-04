package app.daycue.domain.edit

import app.daycue.domain.config.CueType
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.Place
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a remote caller may see (security review H-1, M-8). Coordinates are never included, whatever the
 * policy. Medication content (labels, times, days, dates, policies, medication cue profiles) is included only
 * when [allowMedication] — the grant has the `medication` scope **and** the owner allows remote medication.
 */
data class RemoteRedaction(val allowMedication: Boolean = false) {
    companion object {
        /** No medication content, no coordinates. Use this for anything shared across grants (audit, lists). */
        val Strict = RemoteRedaction(allowMedication = false)
    }
}

/**
 * A preview that is safe to send off the phone ([ConfigEditor.previewForRemote]). It is a separate type from
 * [Preview] on purpose, so on-phone (raw) lines cannot be passed where redacted lines are expected.
 */
@Serializable
data class RemotePreview(
    val lines: List<DiffLine>,
    val sensitivity: Sensitivity,
    val errors: List<ValidationError>,
) {
    val valid: Boolean get() = errors.isEmpty()
    val text: String get() = lines.joinToString("\n") { it.text }

    /**
     * One-line summary for confirmation, applied and undo results (`"<prefix><line>; <line>…"`), cut to
     * [maxChars]. Built only from already-redacted lines, so truncation can never expose a hidden value.
     */
    fun summary(maxChars: Int = 300, prefix: String = ""): String {
        val body = lines.joinToString("; ") { it.text }.ifBlank { if (valid) "no changes" else "invalid change" }
        val t = prefix + body
        return if (t.length > maxChars) t.take((maxChars - 1).coerceAtLeast(0)) + "…" else t
    }
}

/** Value-level redaction used by [ConfigEditor.previewForRemote]. */
internal object RemoteRedactor {
    const val LOCATION_SET = "set"
    const val LOCATION_NONE = "none"
    const val LOCATION_MOVED = "moved"
    const val MEDICATION_WITHHELD = "details withheld"
    private val COORD_KEYS = setOf("center", "lat", "lng", "latitude", "longitude")
    private val WHOLE_PLACE = Regex("""^places\[(.+)]$""")

    fun lines(before: DayCueConfig, after: DayCueConfig, r: RemoteRedaction): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        ConfigEditor.diff("", doc(before, r), doc(after, r), out)
        // A moved place keeps the "set" marker on both sides; say it moved, never where.
        for (p in after.places) {
            val old = before.place(p.id) ?: continue
            if (old.center != null && p.center != null && old.center != p.center) out += DiffLine("places[${p.id}].location", LOCATION_SET, LOCATION_MOVED)
        }
        if (!r.allowMedication && medicationPart(before) != medicationPart(after)) {
            out += DiffLine("medications", MEDICATION_WITHHELD, "changed ($MEDICATION_WITHHELD)")
        }
        return out.filter { it.path != "version" }.map { l ->
            val whole = WHOLE_PLACE.matchEntire(l.path)?.groupValues?.get(1)
            if (whole != null) {
                DiffLine(l.path, before.place(whole)?.let { describe(it, removed = after.place(whole) == null) },
                    after.place(whole)?.let { describe(it, removed = false) })
            } else DiffLine(l.path, l.before?.let(::scrub), l.after?.let(::scrub))
        }
    }

    fun errors(errors: List<ValidationError>, r: RemoteRedaction): List<ValidationError> = errors.map { e ->
        when {
            !r.allowMedication && e.path.startsWith("medications") -> ValidationError("medications", e.code, "invalid medication change ($MEDICATION_WITHHELD)")
            COORD_KEYS.any { e.path.endsWith(".$it") } -> e.copy(message = "invalid location")
            else -> e.copy(message = scrub(e.message))
        }
    }

    /** "Home (location set)" / "Home (location removed)" / "Home (no location)". */
    private fun describe(p: Place, removed: Boolean): String = when {
        p.center == null -> "${p.name} (no location)"
        removed -> "${p.name} (location removed)"
        else -> "${p.name} (location set)"
    }

    private fun medicationPart(c: DayCueConfig) = c.medications to c.cueProfiles.filter { it.type == CueType.Medication }

    /** The config document as a remote caller may diff it. */
    private fun doc(c: DayCueConfig, r: RemoteRedaction): JsonElement {
        val o = DayCueJson.encodeToJsonElement(DayCueConfig.serializer(), c) as JsonObject
        val m = o.toMutableMap()
        m["places"] = JsonArray((o["places"] as? JsonArray ?: JsonArray(emptyList())).map { p ->
            val po = p as JsonObject
            val has = po["center"].let { it != null && it !is kotlinx.serialization.json.JsonNull }
            JsonObject(po.filterKeys { it !in COORD_KEYS } + ("location" to JsonPrimitive(if (has) LOCATION_SET else LOCATION_NONE)))
        })
        if (!r.allowMedication) {
            m.remove("medications")
            m["cueProfiles"] = JsonArray((o["cueProfiles"] as? JsonArray ?: JsonArray(emptyList())).filter { p ->
                (p as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull != CueType.Medication.name
            })
        }
        return strip(JsonObject(m))
    }

    /** Recursively drops coordinate keys (belt and braces: places are already handled in [doc]). */
    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it !in COORD_KEYS }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private val parser = Json

    /** A diff value that is a JSON object/array is re-parsed and stripped of coordinate keys. */
    private fun scrub(v: String): String {
        val t = v.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return v
        return runCatching { strip(parser.parseToJsonElement(v)).toString() }.getOrDefault(v)
    }
}
