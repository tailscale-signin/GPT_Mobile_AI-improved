package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun GatewayEngineeringCapabilities.requireGraphReadiness(): String {
    if (codeGraph?.schemaVersion != 1) throw GatewayGraphUnavailableException("The selected gateway repository graph protocol is unsupported. Update the desktop gateway.", listOf(GatewayGraphPrerequisite.GRAPH_PROTOCOL_UNSUPPORTED))
    val missing = GatewayGraphPrerequisite.missing(this)
    if (missing.isNotEmpty()) throw GatewayGraphUnavailableException(unavailableReason(graph = true) ?: "The selected gateway repository graph prerequisites are not ready.", missing)
    return requireNotNull(principalId)
}

/** Fixed prerequisite codes survive diagnostic previews without exposing gateway payloads. */
internal enum class GatewayGraphPrerequisite(val nextAction: String) {
    JOURNAL_DISABLED("ENABLE_SELECTED_GATEWAY_DURABLE_STORAGE"),
    JOURNAL_UNAVAILABLE("RESTORE_SELECTED_GATEWAY_JOURNAL"),
    HOST_GITHUB_CONNECTION_MISSING("CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB"),
    GATEWAY_DRAINING("WAIT_FOR_SELECTED_GATEWAY_TO_FINISH_DRAINING"),
    GRAPH_SERVICE_UNAVAILABLE("RESTORE_SELECTED_GATEWAY_GRAPH_SERVICE"),
    GRAPH_PROTOCOL_UNSUPPORTED("UPDATE_SELECTED_GATEWAY_GRAPH_PROTOCOL"),
    ENGINEERING_SURFACE_UNSUPPORTED("SELECT_CONFIGURED_ENGINEERING_SURFACE"),
    TRANSPORT_UNAVAILABLE("RESTORE_SELECTED_GATEWAY_CONNECTION"),
    READINESS_UNKNOWN("INSPECT_SELECTED_GATEWAY_READINESS");

    companion object {
        fun missing(capability: GatewayEngineeringCapabilities): List<GatewayGraphPrerequisite> = buildList {
            val checks = capability.readiness?.checks
            when (checks?.journal) {
                "disabled" -> add(JOURNAL_DISABLED)
                "unavailable", "write_failed" -> add(JOURNAL_UNAVAILABLE)
            }
            if ((checks?.graphHostGithubConnection ?: checks?.hostGithubConnection) == "missing") add(HOST_GITHUB_CONNECTION_MISSING)
            if (checks?.draining == true) add(GATEWAY_DRAINING)
            if (isEmpty() && (capability.codeGraph?.enabled != true || capability.readiness?.graphReady == false)) add(GRAPH_SERVICE_UNAVAILABLE)
        }
    }
}

/** Availability only; authorization, identity and TLS rejection never acquire this fallback type. */
internal open class GatewayGraphUnavailableException(
    message: String,
    val prerequisites: List<GatewayGraphPrerequisite> = listOf(GatewayGraphPrerequisite.READINESS_UNKNOWN),
    val qualification: JsonObject? = null
) : IllegalStateException(message) {
    fun atRevision(context: GitHubConversationContext): GatewayGraphUnavailableException = GatewayGraphUnavailableException(
        message.orEmpty(),
        prerequisites,
        buildJsonObject {
            put("repository", "${context.owner}/${context.repo}")
            put("commit_sha", context.commitSha)
            put("qualification", "NOT_QUALIFIED")
            put("graph_complete", false)
        }
    )

    fun diagnostic(): JsonObject = buildJsonObject {
        put("failure_category", prerequisites.firstOrNull()?.name ?: GatewayGraphPrerequisite.READINESS_UNKNOWN.name)
        put("missing_prerequisites", JsonArray(prerequisites.distinct().take(GatewayGraphPrerequisite.entries.size).map { JsonPrimitive(it.name) }))
        put("repair_action", (prerequisites.firstOrNull() ?: GatewayGraphPrerequisite.READINESS_UNKNOWN).nextAction)
        qualification?.let { put("selected_revision", it) }
    }
}
