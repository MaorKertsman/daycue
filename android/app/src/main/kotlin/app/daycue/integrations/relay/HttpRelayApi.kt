package app.daycue.integrations.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val status: Int, val body: String)

interface HttpTransport {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse
}

/**
 * `HttpURLConnection` transport. Read timeout defaults to 100 s: a cold-starting free-tier relay needs
 * about a minute (RELAY.md section 2); a timeout means "try later", never command failure.
 */
class UrlConnectionTransport : HttpTransport {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse =
        withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = method
                    c.connectTimeout = 15_000
                    c.readTimeout = timeoutMs
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (body != null) {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = c.responseCode
                    val stream = if (status >= 400) c.errorStream else c.inputStream
                    HttpResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
                } finally { c.disconnect() }
            } catch (e: java.io.IOException) {
                throw RelayException.Network(e)
            }
        }
}

private fun httpError(status: Int, body: String): RelayException.Http {
    val j = runCatching { WireJson.parseToJsonElement(body).jsonObject }.getOrNull()
    return RelayException.Http(
        status,
        j?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_$status",
        j?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(200),
    )
}

class HttpRelayApi(
    baseUrl: String,
    private val token: String?,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val timeoutMs: Int = 100_000,
    /** Phone device key; required only for calls the relay wants signed (companion revoke). */
    private val signer: DeviceSigner? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RelayApi {
    private val base = baseUrl.trimEnd('/')

    private suspend fun call(method: String, path: String, body: JsonElement? = null, extraHeaders: Map<String, String> = emptyMap()): String {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
            putAll(extraHeaders)
        }
        val r = transport.request(method, "$base$path", headers, body?.toString(), timeoutMs)
        if (r.status in 200..299) return r.body
        throw httpError(r.status, r.body)
    }

    override suspend fun pull(): PullResponse = WireJson.decodeFromString(PullResponse.serializer(), call("GET", "/v1/phone/commands"))
    override suspend fun ack(commandId: String, body: JsonObject) { call("POST", "/v1/phone/commands/$commandId/ack", body) }
    override suspend fun putSnapshot(body: JsonElement): SnapshotResponse = WireJson.decodeFromString(SnapshotResponse.serializer(), call("PUT", "/v1/phone/snapshot", body))
    override suspend fun putPush(fcmToken: String?, wakeOnActivity: Boolean?) {
        call("PUT", "/v1/phone/push", buildJsonObject {
            put("fcmToken", fcmToken?.let { JsonPrimitive(it) } ?: JsonNull)
            if (wakeOnActivity != null) put("wakeOnActivity", wakeOnActivity)
        })
    }
    override suspend fun activity(): ActivityResponse = WireJson.decodeFromString(ActivityResponse.serializer(), call("GET", "/v1/phone/activity"))
    override suspend fun companionCode(): CompanionCode = WireJson.decodeFromString(CompanionCode.serializer(), call("POST", "/v1/phone/companion-codes", JsonObject(emptyMap())))

    override suspend fun grants(): GrantsResponse = WireJson.decodeFromString(GrantsResponse.serializer(), call("GET", "/v1/phone/grants"))
    override suspend fun decideGrant(grantId: String, body: JsonObject): GrantDecisionResponse =
        WireJson.decodeFromString(GrantDecisionResponse.serializer(), call("POST", "/v1/phone/grants/${java.net.URLEncoder.encode(grantId, "UTF-8")}/decision", body))
    override suspend fun unpairSelf() { call("DELETE", "/v1/phone/self") }
    override suspend fun revokeCompanion(companionId: String) {
        val s = signer ?: throw RelayException.Http(0, NO_DEVICE_KEY, "no phone device key available to sign the revoke")
        val signedAt = nowMs()
        val sig = Base64Url.encode(s.sign(SigningStrings.companionRevoke(companionId, signedAt).toByteArray(Charsets.UTF_8)))
        call("DELETE", "/v1/phone/companions/${java.net.URLEncoder.encode(companionId, "UTF-8")}", null,
            mapOf("X-DayCue-Signed-At" to signedAt.toString(), "X-DayCue-Signature" to sig))
    }

    companion object {
        const val NO_DEVICE_KEY = "no_device_key"

        /** Unauthenticated pairing call. [publicKeySpki] is `PublicKey.getEncoded()`. */
        suspend fun pair(
            baseUrl: String, code: String, publicKeySpki: ByteArray, label: String,
            fcmToken: String? = null, transport: HttpTransport = UrlConnectionTransport(),
        ): PairResponse {
            val body = buildJsonObject {
                put("code", code)
                put("publicKey", Base64Url.encode(publicKeySpki))
                put("label", label)
                if (fcmToken != null) put("fcmToken", fcmToken)
            }
            val r = transport.request("POST", baseUrl.trimEnd('/') + "/v1/pair/phone",
                mapOf("Content-Type" to "application/json", "Accept" to "application/json"), body.toString(), 100_000)
            if (r.status !in 200..299) throw httpError(r.status, r.body)
            return WireJson.decodeFromString(PairResponse.serializer(), r.body)
        }
    }
}
