package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import org.json.JSONObject

/** Pronunciation tokens encode reply content and obey the same privacy guard as text. */
internal class GatewayVoiceTransport(private val http: HttpClient, private val endpoint: String, private val credential: String?) {
    suspend fun exchange(method: String, path: String, payload: JSONObject?, limit: Int): JSONObject {
        require((method to path) in setOf("GET" to "/gateway/voice/capabilities", "POST" to "/gateway/voice/synthesize", "POST" to "/gateway/voice/cancel"))
        if (path == "/gateway/voice/synthesize") PrivacyModeStore.requireUnprotectedTransport()
        return http.prepareRequest(endpoint + path) {
            this.method = HttpMethod.parse(method)
            if (!credential.isNullOrBlank()) bearerAuth(credential)
            if (payload != null) {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        }.execute { response ->
            check(response.status.value in 200..299) { "Gateway speech request failed (${response.status.value})" }
            JSONObject(response.boundedMetadata(limit))
        }
    }
}
