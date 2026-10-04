package app.daycue.integrations.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * The relay's `canonicalJson` (mcp/src/util.ts): keys sorted by UTF-16 code unit, no whitespace, strings
 * escaped like `JSON.stringify`. Used for ack v2 (signature covers `sha256(canonicalJson(result))`) and for
 * recomputing the command `payloadHash` on the phone (RELAY.md 4.6, security review L-3).
 */
object CanonicalJson {
    fun encode(e: JsonElement?): String = StringBuilder().also { write(it, e ?: JsonNull) }.toString()

    private fun write(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonArray -> { sb.append('['); e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(sb, v) }; sb.append(']') }
            is JsonObject -> {
                sb.append('{')
                e.keys.sorted().forEachIndexed { i, k -> if (i > 0) sb.append(','); string(sb, k); sb.append(':'); write(sb, e.getValue(k)) }
                sb.append('}')
            }
            is JsonPrimitive -> if (e.isString) string(sb, e.content) else sb.append(number(e.content))
        }
    }

    /** Booleans and numbers keep the text the relay serialized (it came from JS, so it is already JS-formatted). */
    private fun number(content: String): String = content

    /** `JSON.stringify` string escaping (well-formed: only lone surrogates are escaped besides controls). */
    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
