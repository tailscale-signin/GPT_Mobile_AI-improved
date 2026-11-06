package dev.chungjungsoo.gptmobile.data.network.gateway

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Shared protocol v2 projection. No free-form messages, exceptions, URLs or payloads cross this boundary. */
internal object GatewayDiagnosticEvent {
    const val MAX_RECORD_BYTES = 2048
    const val MAX_EVENTS = 5000
    const val MAX_BATCH_EVENTS = 64
    const val MAX_BATCH_BYTES = 65_536
    const val CAPTURE_DURATION_MILLIS = 7 * 24 * 60 * 60 * 1000L
    val hex32 = Regex("[a-f0-9]{32}")
    private val hex64 = Regex("[a-f0-9]{64}")
    private val span = Regex("[a-f0-9]{16}")
    private val opaque = Regex("(?:[a-f0-9]{32,64}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}|[a-z]{2,16}_[a-f0-9]{32,64})")
    private val choices = mapOf(
        "stage" to setOf("request", "response", "transport", "lifecycle", "network", "model_queue", "model_load", "prefill", "decode", "mcp_discovery", "mcp_call", "operation_dispatch", "operation_reconciliation", "renderer", "cancellation", "terminal_delivery", "unknown"),
        "direction" to setOf("client_to_gateway", "gateway_to_client", "gateway_to_backend", "backend_to_gateway", "local"),
        "severity" to setOf("debug", "info", "warning", "error", "critical"),
        "category" to setOf("transport", "authentication", "authorization", "model", "tool", "storage", "renderer", "lifecycle", "network", "cancellation", "unknown"),
        "owner" to setOf("android", "gateway", "backend", "unknown"),
        "dispatch_state" to setOf("not_dispatched", "dispatched", "response_received", "unknown"),
        "retry_class" to setOf("none", "safe_read", "after_backoff", "reconcile", "manual", "unknown"),
        "error_code" to setOf("NONE", "UNKNOWN_CAUSE", "HTTP_ERROR", "TRANSPORT_FAILURE", "TIMEOUT", "CANCELLED", "AUTHENTICATION_FAILED", "AUTHORIZATION_FAILED", "MODEL_UNAVAILABLE", "MCP_UNAVAILABLE", "TOOL_FAILURE", "REPOSITORY_GRAPH_UNAVAILABLE", "GITHUB_TRANSIENT_NETWORK", "JAVA_HEAP_PRESSURE", "STORAGE_UNAVAILABLE", "RENDERER_STALL", "SOURCE_UNAVAILABLE", "PRECHECK_REJECTED", "DISPATCH_UNCERTAIN")
    )
    private val numericAttributes = setOf("status", "received_bytes", "sent_bytes", "queue_ms", "load_ms", "prefill_ms", "decode_ms", "output_tokens", "input_tokens", "frame_count", "slow_frames", "dropped_frames", "gc_count", "heap_bytes", "network_epoch", "android_sdk", "app_version_code", "occurrence_count", "source_record_bytes", "stalls_over_500_ms", "refresh_rate_millihz", "gc_duration_ms", "native_heap_bytes")
    private val digestAttributes = setOf("profile_digest", "model_digest", "endpoint_digest", "repository_digest", "app_revision", "gateway_revision", "capability_digest", "runtime_config_digest", "source_event_digest")
    private val boolAttributes = setOf("vendor_log_available", "source_available", "client_metadata_accepted", "truncation_applied")

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).diagnosticHex()

    fun identity(origin: String, boot: String, sequence: Long): String = digest("$origin:$boot:$sequence")

    /** Project before queueing; the caller adds origin/sequence only after durable allocation. */
    fun fields(value: JsonObject): JsonObject = buildJsonObject {
        choices.forEach { (name, permitted) -> value.diagnosticText(name)?.takeIf { it in permitted }?.let { put(name, it) } }
        if (value.diagnosticText("error_code") == "PRECHECK_REJECTED") put("dispatch_state", "not_dispatched")
        if (value.diagnosticText("error_code") == "DISPATCH_UNCERTAIN") {
            put("dispatch_state", "unknown")
            put("retry_class", "reconcile")
        }
        listOf("trace_id", "span_id", "parent_span_id").forEach { name ->
            value.diagnosticText(name)?.takeIf { (if (name == "trace_id") hex32 else span).matches(it) && it.any { char -> char != '0' } }?.let { put(name, it) }
        }
        listOf("run_id", "job_id", "request_id", "operation_id", "tool_call_id", "binding_id", "incident_id").forEach { name ->
            value.diagnosticText(name)?.takeIf(opaque::matches)?.let { put(name, it) }
        }
        listOf("local_duration_ms", "clock_uncertainty_ms").forEach { name ->
            value.diagnosticLong(name)?.takeIf { it in 0..CAPTURE_DURATION_MILLIS }?.let { put(name, it) }
        }
        val attributes = value["bounded_attributes"] as? JsonObject
        if (attributes != null) {
            put(
                "bounded_attributes",
                buildJsonObject {
                    numericAttributes.forEach { name -> attributes.diagnosticLong(name)?.takeIf { it in 0..(1L shl 53) }?.let { put(name, it) } }
                    digestAttributes.forEach { name -> attributes.diagnosticText(name)?.takeIf { hex64.matches(it) || Regex("[a-f0-9]{40}").matches(it) }?.let { put(name, it) } }
                    boolAttributes.forEach { name ->
                        (attributes[name] as? JsonPrimitive)?.booleanOrNull?.let { put(name, if (name == "vendor_log_available") false else it) }
                    }
                }
            )
        }
        put("redaction_applied", true)
        put("truncated", (value["truncated"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    fun create(origin: String, boot: String, sequence: Long, occurredAt: Long, fields: JsonObject): JsonObject? = sanitize(
        JsonObject(
            fields + buildJsonObject {
                put("schema_version", 2)
                put("origin_device_pseudonym", origin)
                put("origin_boot_id", boot)
                put("origin_sequence", sequence)
                put("event_id", identity(origin, boot, sequence))
                put("occurred_at_utc", occurredAt)
                put("owner", "android")
            }
        )
    )

    fun sanitize(value: JsonObject): JsonObject? {
        if (value.diagnosticLong("schema_version") != 2L) return null
        val origin = value.diagnosticText("origin_device_pseudonym")?.takeIf(hex32::matches) ?: return null
        val boot = value.diagnosticText("origin_boot_id")?.takeIf(hex32::matches) ?: return null
        val sequence = value.diagnosticLong("origin_sequence")?.takeIf { it in 1..(1L shl 53) } ?: return null
        val eventId = value.diagnosticText("event_id")?.takeIf { it == identity(origin, boot, sequence) } ?: return null
        val occurredAt = value.diagnosticLong("occurred_at_utc")?.takeIf { it in 0..(1L shl 53) } ?: return null
        var result = JsonObject(
            fields(value) + buildJsonObject {
                put("schema_version", 2)
                put("origin_device_pseudonym", origin)
                put("origin_boot_id", boot)
                put("origin_sequence", sequence)
                put("event_id", eventId)
                put("occurred_at_utc", occurredAt)
                listOf("gateway_ingest_sequence", "observed_at_utc").forEach { name ->
                    value.diagnosticLong(name)?.takeIf { it in 0..(1L shl 53) }?.let { put(name, it) }
                }
            }
        )
        while (bytes(result) + 100 > MAX_RECORD_BYTES) {
            val attributes = (result["bounded_attributes"] as? JsonObject).orEmpty()
            if (attributes.isEmpty()) return null
            result = JsonObject(result + mapOf("truncated" to JsonPrimitive(true), "bounded_attributes" to JsonObject(attributes - attributes.keys.sorted().last())))
        }
        return result
    }

    fun parse(line: String): JsonObject? {
        if (line.toByteArray(Charsets.UTF_8).size + 1 > MAX_RECORD_BYTES) return null
        return runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()?.let(::sanitize)
    }

    fun bytes(value: JsonObject): Int = value.toString().toByteArray(Charsets.UTF_8).size + 1
}

internal fun JsonObject.diagnosticText(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.diagnosticLong(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
internal fun ByteArray.diagnosticHex(): String {
    val alphabet = "0123456789abcdef"
    return CharArray(size * 2).also { chars ->
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 15]
        }
    }.concatToString()
}
