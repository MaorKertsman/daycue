package app.daycue.integrations.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRevokeSigningTest {
    private class Recorder(val status: Int = 204, val body: String = "") : HttpTransport {
        var method = ""; var url = ""; var headers: Map<String, String> = emptyMap(); var sent: String? = "unset"
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse {
            this.method = method; this.url = url; this.headers = headers; this.sent = body
            return HttpResponse(status, this.body)
        }
    }

    @Test fun signingStringIsExactlyTheDocumentedOne() {
        assertEquals("daycue.companion.revoke.v1\ndv_abc/1\n1700000000000", SigningStrings.companionRevoke("dv_abc/1", 1_700_000_000_000L))
    }

    @Test fun revokeSendsSignedHeadersOverRawIdAndTheSignatureVerifies() = runBlocking {
        val signer = SoftwareSigner()
        val t = Recorder()
        val id = "dv a/b" // raw id is signed, the URL path is encoded
        HttpRelayApi("https://relay.example/", "tok", t, signer = signer, nowMs = { 1_700_000_000_123L }).revokeCompanion(id)
        assertEquals("DELETE", t.method)
        assertEquals("https://relay.example/v1/phone/companions/dv+a%2Fb", t.url)
        assertNull(t.sent)
        assertEquals("Bearer tok", t.headers["Authorization"])
        assertEquals("1700000000123", t.headers["X-DayCue-Signed-At"])
        val sig = Base64Url.decode(t.headers.getValue("X-DayCue-Signature"))
        assertTrue(!t.headers.getValue("X-DayCue-Signature").contains("=") && !t.headers.getValue("X-DayCue-Signature").contains("+"))
        assertTrue(EcdsaVerify.verify(signer.publicKeySpki(), "daycue.companion.revoke.v1\ndv a/b\n1700000000123", sig))
        assertTrue(!EcdsaVerify.verify(signer.publicKeySpki(), "daycue.companion.revoke.v1\nother\n1700000000123", sig))
    }

    @Test fun errorsAreMapped() = runBlocking {
        fun result(status: Int, body: String, signer: DeviceSigner? = SoftwareSigner()) = runBlocking {
            CompanionDirectory { HttpRelayApi("https://r.example", "t", Recorder(status, body), signer = signer) }.revoke("co")
        }
        assertEquals(CompanionRevokeResult.Done, result(204, ""))
        assertEquals(CompanionRevokeResult.NotFound, result(404, """{"error":"unknown_companion"}"""))
        assertEquals(CompanionRevokeResult.Unauthorized, result(401, """{"error":"unauthorized"}"""))
        assertEquals(CompanionRevokeResult.BadSignature, result(403, """{"error":"bad_signature"}"""))
        assertEquals(CompanionRevokeResult.NotSupportedByRelay, result(404, """{"error":"not_found"}"""))
        assertEquals(CompanionRevokeResult.NotSupportedByRelay, result(405, ""))
        assertEquals(CompanionRevokeResult.Failed, result(500, ""))
        assertEquals(CompanionRevokeResult.BadSignature, result(204, "", signer = null))
        val offline = object : HttpTransport {
            override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse =
                throw RelayException.Network(java.io.IOException("down"))
        }
        assertEquals(CompanionRevokeResult.Offline, CompanionDirectory { HttpRelayApi("https://r.example", "t", offline, signer = SoftwareSigner()) }.revoke("co"))
    }
}
