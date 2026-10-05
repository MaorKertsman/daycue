package app.daycue.integrations.relay

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Base64Url {
    fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Accepts base64 and base64url, with or without padding. */
    fun decode(s: String): ByteArray {
        val n = s.trim().replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(n + "=".repeat((4 - n.length % 4) % 4))
    }
}

/** ECDSA P-256 device key. Android: Keystore (non-exportable). JVM tests: software key. */
interface DeviceSigner {
    /** X.509 SubjectPublicKeyInfo. */
    fun publicKeySpki(): ByteArray

    /** SHA256withECDSA over [message]; Java DER output (the relay accepts DER and raw r||s). */
    fun sign(message: ByteArray): ByteArray
}

/** Exact signing strings of RELAY.md (lines joined with a newline, no trailing newline). */
object SigningStrings {
    fun ack(commandId: String, payloadHash: String, outcome: String, newVersion: Long?, ackedAt: Long): String =
        "daycue.ack.v1\n$commandId\n$payloadHash\n$outcome\n${newVersion?.toString() ?: ""}\n$ackedAt"

    /** Ack v2: additionally covers `sha256(canonicalJson(result))` (lowercase hex). */
    fun ackV2(commandId: String, payloadHash: String, outcome: String, newVersion: Long?, ackedAt: Long, resultHashHex: String): String =
        "daycue.ack.v2\n$commandId\n$payloadHash\n$outcome\n${newVersion?.toString() ?: ""}\n$ackedAt\n$resultHashHex"

    /** Phone decision on a grant (RELAY.md 4.6); [approvedScopes] sorted and joined by one space (empty = none). */
    fun grant(grantId: String, decision: String, approvedScopes: Collection<String>, decidedAt: Long): String =
        "daycue.grant.v1\n$grantId\n$decision\n${approvedScopes.sorted().joinToString(" ")}\n$decidedAt"

    /** Phone revokes a companion (RELAY.md 4.4.2); [companionId] is the raw id, not URL-encoded. */
    fun companionRevoke(companionId: String, signedAt: Long): String =
        "daycue.companion.revoke.v1\n$companionId\n$signedAt"

    fun signal(companionId: String, state: String, observedAt: Long, ttlSeconds: Int): String =
        "daycue.signal.v1\n$companionId\n$state\n$observedAt\n$ttlSeconds"
}

object EcdsaVerify {
    fun verify(publicKeySpki: ByteArray, message: String, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
        val der = if (signature.size == 64) rawToDer(signature) else signature
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(message.toByteArray(Charsets.UTF_8)); verify(der)
        }
    } catch (e: Exception) { false }

    /** IEEE P1363 (r||s, what .NET produces) to ASN.1 DER. */
    fun rawToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte()) i++
            var v = b.copyOfRange(i, b.size)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
