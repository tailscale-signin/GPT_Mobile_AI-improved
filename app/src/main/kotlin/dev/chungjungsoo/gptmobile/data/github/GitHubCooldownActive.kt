package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable observations from the local rate guard, never evidence about an earlier request. */
class GitHubCooldownActive internal constructor(
    val resource: Resource,
    val basis: Basis,
    val delayMillis: Long,
    val retryNotBeforeMillis: Long,
    val observedAtMillis: Long? = null,
    val remainingRequests: Long? = null
) : IllegalStateException(
    "$ERROR_CODE: GitHub requests are cooling down. Wait ${delayMillis / 1000 + if (delayMillis % 1000 == 0L) 0 else 1} seconds before resuming the saved task. Use retained results and reconcile any uncertain operation before repeating it."
) {
    enum class Resource { CORE, SEARCH, GRAPHQL, OTHER }
    enum class Basis { SECONDARY_DEADLINE, RESOURCE_RESET, RESOURCE_RETRY_AFTER, RESOURCE_RESET_AND_RETRY_AFTER }

    val errorCode: String = ERROR_CODE

    internal fun failureFields(): JsonObject = buildJsonObject {
        put("github_cooldown_resource", resource.name)
        put("github_cooldown_basis", basis.name)
        put("github_cooldown_delay_ms", delayMillis)
        put("github_cooldown_deadline_ms", retryNotBeforeMillis)
        observedAtMillis?.takeIf { it >= 0 }?.let { put("github_cooldown_observed_at_ms", it) }
        remainingRequests?.takeIf { it in 0L..16_000_000L }?.let { put("github_cooldown_remaining_requests", it) }
    }

    companion object {
        const val ERROR_CODE = "GITHUB_COOLDOWN_ACTIVE"

        internal fun resource(value: String): Resource = when (value) {
            "core" -> Resource.CORE
            "search" -> Resource.SEARCH
            "graphql" -> Resource.GRAPHQL
            else -> Resource.OTHER
        }
    }
}
