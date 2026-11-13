package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The current failure and a retained connection snapshot are separate observations. */
internal object McpReportDiagnostics {
    fun fields(
        connectionUid: String?,
        failurePhase: String,
        failureCategory: String,
        connectionState: String? = null,
        readinessState: String? = null,
        connectionHealth: String? = null,
        observedAgeMs: Long? = null
    ): JsonObject = buildJsonObject {
        fun observed(key: String, value: String?) {
            value?.let { IssueReportIncidentCapsule.validValue(key, JsonPrimitive(it)) }?.let { put(key, it) }
        }
        IssueReportContext.identifierHash("mcp-connection", connectionUid)?.let { put("mcp_connection_hash", it) }
        observed("mcp_failure_phase", failurePhase)
        observed("mcp_failure_category", failureCategory)
        observed("mcp_connection_state", connectionState)
        observed("mcp_readiness_state", readinessState)
        observed("mcp_connection_health", connectionHealth)
        put("mcp_health_observation", if (connectionState == null) "UNAVAILABLE" else "RETAINED_SNAPSHOT")
        observedAgeMs?.takeIf { it in 0L..86_400_000L }?.let { put("mcp_health_age_ms", it) }
    }
}
