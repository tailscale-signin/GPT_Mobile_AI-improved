package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.network.gateway.validGatewayAvailabilityModel
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.util.AttributeKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

/** Also covers an already running tool, its queued requests, retries and redirects. */
internal val PrivateExternalServiceGate = createClientPlugin("PrivateExternalServiceGate") {
    on(SendingRequest) { _, _ -> PrivacyModeStore.requireUnprotectedTransport() }
}

/** Only a model selected by an active readiness probe can be sent in a metadata query. */
internal object PrivateGatewayMetadata {
    val grantKey = AttributeKey<String>("privateReadinessGrant")
    const val HEADER = "X-GPTMobile-Internal-Readiness"
    private data class Probe(val endpoint: HttpUrl, val model: String)
    private val active = ConcurrentHashMap<String, Probe>()
    private val metadataPaths = setOf("/props", "/models", "/v1/models", "/api/tags", "/gateway/live", "/gateway/ready", "/gateway/identity", "/gateway/capabilities")

    suspend fun <T> withReadiness(endpoint: String, model: String?, read: suspend (String?) -> T): T {
        val url = endpoint.toHttpUrlOrNull()
        if (url == null ||
            url.encodedPath != "/gateway/ready" ||
            !PrivacyProfileEligibility.privateGateway(endpoint) ||
            model == null ||
            !validGatewayAvailabilityModel(model)
        ) {
            return read(null)
        }
        val ticket = UUID.randomUUID().toString()
        active[ticket] = Probe(url, model)
        try {
            return read(ticket)
        } finally {
            active.remove(ticket)
        }
    }

    // Strip the engine bridge before transport. The network interceptor rechecks the
    // actual target if the engine follows a redirect or Privacy Mode changes mid-call.
    val stripHeader = Interceptor { chain ->
        val request = chain.request()
        val probe = request.header(HEADER)?.let(active::get)
        chain.proceed(request.newBuilder().removeHeader(HEADER).tag(Probe::class.java, probe).build())
    }

    fun allows(request: okhttp3.Request): Boolean = allows(request.method, request.url, request.tag(Probe::class.java))

    fun allows(method: String, url: HttpUrl, ticket: String?): Boolean = allows(method, url, ticket?.let(active::get))

    private fun allows(method: String, url: HttpUrl, probe: Probe?): Boolean {
        if (method != "GET") return false
        if (url.query == null) return url.encodedPath in metadataPaths
        if (url.encodedPath != "/gateway/ready" || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val model = url.queryParameterValues("model").singleOrNull() ?: return false
        if (url.queryParameterNames != setOf("model") &&
            !(url.queryParameterNames == setOf("model", "autoload") && url.queryParameterValues("autoload") == listOf("false"))
        ) {
            return false
        }
        return probe == Probe(url.newBuilder().query(null).build(), model)
    }
}

internal val PrivateMetadataGate = createClientPlugin("PrivateMetadataGate") {
    on(SendingRequest) { request, _ ->
        if (PrivacyModeStore.active()) {
            val url = request.url.build().toString().toHttpUrlOrNull()
            if (url == null || !PrivateGatewayMetadata.allows(request.method.value, url, request.attributes.getOrNull(PrivateGatewayMetadata.grantKey))) throw PrivateMetadataDenied()
        }
    }
}

internal val OkHttpReadinessGrant = createClientPlugin("OkHttpReadinessGrant") {
    on(SendingRequest) { request, _ ->
        request.headers.remove(PrivateGatewayMetadata.HEADER)
        request.attributes.getOrNull(PrivateGatewayMetadata.grantKey)?.let { request.headers.append(PrivateGatewayMetadata.HEADER, it) }
    }
}

internal class PrivateMetadataDenied : java.io.IOException("PRIVACY_SERVICE_UNVERIFIED: This service has no verified retention policy.")
