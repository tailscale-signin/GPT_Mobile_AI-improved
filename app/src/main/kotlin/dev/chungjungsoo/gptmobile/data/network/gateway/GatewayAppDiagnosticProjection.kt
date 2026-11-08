package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.classifyDiagnostic
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticEventMessage
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticExecutionEvidence
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Typed local events and legacy text cross the same allowlist; neither sends free-form messages. */
internal fun gatewayAppDiagnosticProjection(entry: AppLogEntry): JsonObject? {
    val message = diagnosticEventMessage(entry.message)
    val eventName = message.substringBefore(" · ")
    if (entry.tag == "Gateway" && eventName == "CLIENT_EXCHANGE") return null
    val classification = classifyDiagnostic(entry.level, entry.tag, entry.message)
    val fields = diagnosticFields(entry.message)
    val execution = diagnosticExecutionEvidence(fields, eventName)
    val stage = when {
        eventName == "RUN_TERMINAL" -> "terminal_delivery"
        entry.tag in setOf("Lifecycle", "Application", "AppCrash", "Diagnostics", "ProcessExit") -> "lifecycle"
        entry.tag in setOf("Frame", "Rendering", "ChatRendering") -> "renderer"
        entry.tag in setOf("Network", "Connectivity") -> "network"
        entry.tag in setOf("MCP", "Mcp", "McpRuntime") -> "mcp_call"
        entry.tag in setOf("Model", "LocalConnection", "Admission") -> "request"
        entry.tag in setOf("Agent", "AgentRun", "Tool", "ToolExecution", "GitHub", "GitHubTool") -> "operation_dispatch"
        classification.severity in setOf("error", "critical", "warning") -> "unknown"
        else -> return null
    }
    val error = when {
        execution.state == "UNKNOWN_OUTCOME" -> "DISPATCH_UNCERTAIN"
        execution.dispatched == false && classification.actionable -> "PRECHECK_REJECTED"
        classification.category in setOf("fatal", "memory_recovery") && message.contains("memory", true) -> "JAVA_HEAP_PRESSURE"
        classification.category in setOf("transport_availability", "network_callback_lifecycle") -> "TRANSPORT_FAILURE"
        stage == "renderer" && classification.severity in setOf("error", "warning", "critical") -> "RENDERER_STALL"
        classification.severity in setOf("error", "critical") -> "UNKNOWN_CAUSE"
        else -> "NONE"
    }
    return GatewayDiagnosticEvent.fields(
        buildJsonObject {
            put("stage", stage)
            put("direction", "local")
            put("owner", "android")
            put("severity", classification.severity)
            put("error_code", error)
            put(
                "dispatch_state",
                if (execution.dispatched == false) {
                    "not_dispatched"
                } else if (execution.dispatched == true) {
                    "dispatched"
                } else {
                    "unknown"
                }
            )
            put("retry_class", if ((fields["retryable"] as? JsonPrimitive)?.booleanOrNull == false) "manual" else "unknown")
            put(
                "category",
                when (stage) {
                    "renderer" -> "renderer"
                    "network" -> "network"
                    "mcp_call", "operation_dispatch", "terminal_delivery" -> "tool"
                    "lifecycle" -> "lifecycle"
                    "request" -> "model"
                    else -> "unknown"
                }
            )
            val names = mapOf("runId" to "run_id", "jobId" to "job_id", "requestDigest" to "request_id", "operationId" to "operation_id", "toolCallId" to "tool_call_id", "bindingId" to "binding_id", "invocationId" to "incident_id", "incidentId" to "incident_id", "causalIncidentId" to "incident_id")
            names.forEach { (name, field) ->
                (fields[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-z0-9_-]{16,80}")) }?.let { put(field, it) }
            }
            put(
                "bounded_attributes",
                buildJsonObject {
                    put("occurrence_count", 1)
                    put("source_event_digest", GatewayDiagnosticEvent.digest(entry.eventId))
                    put("vendor_log_available", false)
                    put("app_version_code", BuildConfig.VERSION_CODE)
                    put("app_revision", BuildConfig.APP_COMMIT)
                    put("android_sdk", android.os.Build.VERSION.SDK_INT)
                    listOf("slowFrames" to "slow_frames", "frameCount" to "frame_count", "droppedFrames" to "dropped_frames").forEach { (name, field) ->
                        fields[name]?.let { put(field, it) }
                    }
                }
            )
        }
    )
}
