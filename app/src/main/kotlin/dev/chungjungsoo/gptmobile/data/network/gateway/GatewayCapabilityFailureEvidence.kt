package dev.chungjungsoo.gptmobile.data.network.gateway

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Capability-read evidence only. Never copy the endpoint, response, field path or exception message. */
internal object GatewayCapabilityFailureEvidence {
    const val FEATURE = "GATEWAY_CAPABILITY_EVIDENCE_V1"
    const val KIND = "gateway_capability_failure_kind"
    const val DECODE_REASON = "gateway_capability_decode_reason"
    val fields = setOf(KIND, DECODE_REASON)
    val codes = setOf("GATEWAY_CAPABILITY_UNAVAILABLE", "GATEWAY_ISOLATION_UNAVAILABLE")
    val kinds = setOf("CONNECT_TIMEOUT", "TIMEOUT", "DNS", "TLS", "CONNECTION", "CONNECTION_CLOSED", "HTTP_REJECTED", "DECODE_REJECTED", "ISOLATION_UNVERIFIED", "UNKNOWN")
    val decodeReasons = setOf("MALFORMED_JSON", "SCHEMA_MISMATCH", "NULL_NOT_ALLOWED", "EXPECTED_BOOLEAN", "EXPECTED_INTEGER", "EXPECTED_STRING", "EXPECTED_OBJECT", "MISSING_REQUIRED_FIELD", "EXPECTED_ARRAY")

    fun failureDetails(error: GatewayCapabilityDiscoveryException, partialOutput: Boolean): JsonObject = buildJsonObject {
        put("code", error.code)
        put("failed_stage", "connection_preflight")
        // The owning transport supplies dispatch facts. This preflight failure
        // cannot establish the outcome of earlier work in the retained run.
        put("repair", "CHECK_CONNECTION")
        put("retryable", false)
        put("request_replay_allowed", false)
        put("logical_continuation_allowed", false)
        put("partial_output_preserved", partialOutput)
        error.httpStatus?.let { put("http_status", it) }
        val kind = kind(error)
        put(KIND, kind)
        error.decodeReason?.takeIf { kind == "DECODE_REJECTED" && it in decodeReasons }?.let { put(DECODE_REASON, it) }
    }

    private fun kind(error: GatewayCapabilityDiscoveryException): String {
        if (error.code == "GATEWAY_ISOLATION_UNAVAILABLE") return "ISOLATION_UNVERIFIED"
        if (error.decodeReason in decodeReasons) return "DECODE_REJECTED"
        if (error.httpStatus in 400..599) return "HTTP_REJECTED"
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = generateSequence<Throwable>(error) { it.cause }.takeWhile(seen::add).take(16).toList()
        return when {
            causes.any { it is SSLException || it is CertificateException } -> "TLS"
            causes.any { it is UnknownHostException || it is UnresolvedAddressException } -> "DNS"
            causes.any { it.javaClass.name == "io.ktor.client.network.sockets.ConnectTimeoutException" } -> "CONNECT_TIMEOUT"
            causes.any { it is SocketTimeoutException || it.javaClass.name in setOf("io.ktor.client.plugins.HttpRequestTimeoutException", "io.ktor.client.network.sockets.SocketTimeoutException") } -> "TIMEOUT"
            causes.any { it is EOFException } -> "CONNECTION_CLOSED"
            causes.any { it is ConnectException || it is NoRouteToHostException || it is SocketException } -> "CONNECTION"
            else -> "UNKNOWN"
        }
    }

    fun valid(values: Map<String, JsonElement>): Boolean {
        if (fields.none { it in values }) return true
        val code = text(values["provider_failure_code"])
        val kind = text(values[KIND])
        if (code !in codes || kind !in kinds || (kind == "ISOLATION_UNVERIFIED") != (code == "GATEWAY_ISOLATION_UNAVAILABLE")) return false
        return DECODE_REASON !in values || kind == "DECODE_REJECTED" && text(values[DECODE_REASON]) in decodeReasons
    }

    fun retained(code: String, details: JsonObject?): Map<String, JsonElement> {
        val selected = details.orEmpty().filterKeys { it in fields }
        return selected.takeIf { valid(it + ("provider_failure_code" to JsonPrimitive(code))) }.orEmpty()
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
