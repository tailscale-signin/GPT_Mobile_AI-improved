package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolCatalogDiscoveryState {
    NOT_DISCOVERED,
    PENDING,
    READY,
    STALE,
    FAILED,
    RECOVERY_BACKOFF,
    AUTHENTICATION_REQUIRED,
    CONFIGURATION_REQUIRED
}

/** Metadata for selected, authorized connections; it never authorizes execution. */
data class ToolCatalogConnectionDiscovery(
    val connectionUid: String,
    val state: ToolCatalogDiscoveryState,
    val revision: String,
    val knownSchemaCount: Int? = null,
    val errorCode: String? = null,
    val retryAtMillis: Long? = null
)

data class ToolCatalogAcquisition(val connections: List<ToolCatalogConnectionDiscovery>) {
    val revision: String get() = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(
        connections.sortedBy { it.connectionUid }.joinToString("\n") { "${it.connectionUid}:${it.revision}" }
    )

    val readinessCode: String? get() = when {
        connections.any { it.state in setOf(ToolCatalogDiscoveryState.PENDING, ToolCatalogDiscoveryState.NOT_DISCOVERED, ToolCatalogDiscoveryState.STALE) } -> "ACQUISITION_PENDING"
        connections.any { it.state in setOf(ToolCatalogDiscoveryState.FAILED, ToolCatalogDiscoveryState.RECOVERY_BACKOFF, ToolCatalogDiscoveryState.AUTHENTICATION_REQUIRED, ToolCatalogDiscoveryState.CONFIGURATION_REQUIRED) } -> "ACQUISITION_FAILED"
        else -> null
    }

    internal fun receipt(): JsonObject = buildJsonObject {
        put("connection_count", connections.size)
        put("pending_connections", connections.count { it.state == ToolCatalogDiscoveryState.PENDING })
        put("ready_connections", connections.count { it.state == ToolCatalogDiscoveryState.READY })
        put("details_truncated", connections.size > 16)
        put(
            "connections",
            JsonArray(
                connections.sortedBy { it.connectionUid }.take(16).map { connection ->
                    buildJsonObject {
                        put("connection_id", connection.connectionUid.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(128))
                        put("state", connection.state.name)
                        connection.knownSchemaCount?.let { put("last_observed_schema_count", it) }
                        connection.errorCode?.takeIf { it.matches(Regex("[A-Z0-9_]{1,96}")) }?.let { put("error_code", it) }
                        connection.retryAtMillis?.takeIf { it != Long.MAX_VALUE }?.let { put("retry_at_epoch_ms", it) }
                    }
                }
            )
        )
        put("ready_tools_remain_usable", true)
        put("interpretation", "Connection discovery is independent. Pending is not an empty catalog; failed discovery does not disable ready tools. Cached schemas are metadata only. Execution still rechecks current authorization and transport. Retry discovery after the connection state changes, not repeatedly while its state is unchanged.")
    }
}
