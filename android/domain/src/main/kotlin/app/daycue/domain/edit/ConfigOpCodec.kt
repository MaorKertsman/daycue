package app.daycue.domain.edit

import app.daycue.domain.config.DayCueJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Forward-compatible decoding of op lists from outside the process (MCP commands, import, dev tools).
 *
 * `DayCueJson.decodeFromJsonElement(ListSerializer(ConfigOp.serializer()), …)` throws on an op type (or a
 * nested polymorphic subtype such as a future habit, pause or trigger kind) that this build does not know.
 * [decodeList] never throws: each element is decoded on its own and failures become typed
 * [ValidationError]s (`unsupported_op`, `unsupported_type`, `bad_op`, `bad_ops`, `too_many`) with path
 * `ops[i]`. Callers must reject the whole list when [Decoded.errors] is non-empty — applying the known
 * subset of a change set would apply a different change than the one requested.
 */
object ConfigOpCodec {

    data class Decoded(val ops: List<ConfigOp>, val errors: List<ValidationError>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /** Serial names (`"type"` values) of every [ConfigOp] variant this build understands. */
    val knownTypes: Set<String> by lazy {
        val d = ConfigOp.serializer().descriptor.getElementDescriptor(1)
        (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
    }

    fun decodeList(json: String, maxOps: Int = 50): Decoded =
        try { decodeList(DayCueJson.parseToJsonElement(json), maxOps) }
        catch (e: SerializationException) { Decoded(emptyList(), listOf(ValidationError("ops", "bad_ops", "ops is not valid JSON"))) }
        catch (e: IllegalArgumentException) { Decoded(emptyList(), listOf(ValidationError("ops", "bad_ops", "ops is not valid JSON"))) }

    fun decodeList(element: JsonElement?, maxOps: Int = 50): Decoded {
        val arr = element as? JsonArray ?: return Decoded(emptyList(), listOf(ValidationError("ops", "bad_ops", "ops must be a JSON array")))
        if (arr.isEmpty()) return Decoded(emptyList(), listOf(ValidationError("ops", "bad_ops", "ops must not be empty")))
        if (arr.size > maxOps) return Decoded(emptyList(), listOf(ValidationError("ops", "too_many", "at most $maxOps ops")))
        val ops = mutableListOf<ConfigOp>()
        val errors = mutableListOf<ValidationError>()
        arr.forEachIndexed { i, el ->
            when (val r = decodeOne(el, "ops[$i]")) {
                is ConfigOp -> ops += r
                is ValidationError -> errors += r
            }
        }
        return Decoded(ops, errors)
    }

    /** Returns a [ConfigOp] or a [ValidationError]. */
    private fun decodeOne(el: JsonElement, path: String): Any {
        val o = el as? JsonObject ?: return ValidationError(path, "bad_op", "op must be a JSON object")
        val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return ValidationError(path, "bad_op", "op has no \"type\"")
        if (type !in knownTypes) return ValidationError(path, "unsupported_op", "op type '${safe(type)}' is not supported by this version of DayCue")
        return try {
            DayCueJson.decodeFromJsonElement(ConfigOp.serializer(), o)
        } catch (e: SerializationException) {
            val m = e.message.orEmpty()
            if ("polymorphic" in m || "subclass" in m || "discriminator" in m) {
                ValidationError(path, "unsupported_type", "op '${safe(type)}' contains a value type this version of DayCue does not support")
            } else ValidationError(path, "bad_op", "op '${safe(type)}' is malformed")
        } catch (e: RuntimeException) { // e.g. DateTimeParseException from a time serializer
            ValidationError(path, "bad_op", "op '${safe(type)}' is malformed")
        }
    }

    /** Caller text echoed in messages: short, identifier characters only. */
    private fun safe(s: String) = s.take(40).filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
}
