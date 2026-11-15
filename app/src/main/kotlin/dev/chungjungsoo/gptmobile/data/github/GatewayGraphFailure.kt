package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    val qualification: JsonObject? = null,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null
) : IllegalStateException(message) {
    fun atRevision(context: GitHubConversationContext): GatewayGraphUnavailableException = GatewayGraphUnavailableException(
        message.orEmpty(),
        prerequisites,
        buildJsonObject {
            put("repository", "${context.owner}/${context.repo}")
            put("commit_sha", context.commitSha)
            put("qualification", "NOT_QUALIFIED")
            put("graph_complete", false)
        },
        httpStatus,
        retryAfterMillis
    )

    fun diagnostic(): JsonObject = buildJsonObject {
        put("failure_category", prerequisites.firstOrNull()?.name ?: GatewayGraphPrerequisite.READINESS_UNKNOWN.name)
        put("missing_prerequisites", JsonArray(prerequisites.distinct().take(GatewayGraphPrerequisite.entries.size).map { JsonPrimitive(it.name) }))
        put("repair_action", (prerequisites.firstOrNull() ?: GatewayGraphPrerequisite.READINESS_UNKNOWN).nextAction)
        qualification?.let { put("selected_revision", it) }
    }
}

/** An observed gateway HTTP failure is not proof that a prior index batch was rejected. */
internal class GatewayGraphHttpFailure(
    val httpStatus: Int,
    val retryAfterMillis: Long? = null,
    val rateLimitObservation: JsonObject? = null,
    val internalCode: String? = null
) : IllegalStateException("The selected gateway repository graph request failed (HTTP $httpStatus). Check its saved status before continuing an unconfirmed index request.") {
    val code: String get() = (rateLimitObservation?.get("code") as? JsonPrimitive)?.contentOrNull ?: internalCode ?: "GRAPH_HTTP_REJECTED"

    companion object {
        /** A gateway 429 and an upstream GitHub rejection describe separate request boundaries. */
        fun fromResponse(status: Int, retryAfterMillis: Long?, detail: JsonObject?): GatewayGraphHttpFailure {
            fun text(key: String) = (detail?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val code = text("code")
            val origin = text("rate_limit_origin")
            val upstream = (detail?.get("upstream_http_status") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val local = origin == "LOCAL_ADMISSION" &&
                code in setOf("GRAPH_CAPACITY_RESERVED", "GITHUB_RATE_LIMIT_WAIT") &&
                detail?.get("upstream_request_dispatched") == JsonPrimitive(false) &&
                detail?.containsKey("upstream_http_status") == false
            val remote = origin == "GITHUB_RESPONSE" &&
                code in setOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED") &&
                detail?.get("upstream_request_dispatched") == JsonPrimitive(true) &&
                upstream in setOf(403, 429)
            val observation = if (status == 429 && (local || remote)) {
                buildJsonObject {
                    put("code", requireNotNull(code))
                    put("rate_limit_origin", requireNotNull(origin))
                    put("upstream_request_dispatched", remote)
                    if (remote) put("upstream_http_status", requireNotNull(upstream))
                }
            } else {
                null
            }
            // Only an actual HTTP 500 from this gateway may identify a bounded internal category.
            val internalCode = if (status == 500) {
                when (code) {
                    "graph_internal_storage_error" -> "GRAPH_INTERNAL_STORAGE"
                    "graph_internal_io_error" -> "GRAPH_INTERNAL_IO"
                    "graph_internal_error" -> "GRAPH_INTERNAL_SERVER"
                    else -> null
                }
            } else {
                null
            }
            return GatewayGraphHttpFailure(status, retryAfterMillis, observation, internalCode)
        }
    }
}
