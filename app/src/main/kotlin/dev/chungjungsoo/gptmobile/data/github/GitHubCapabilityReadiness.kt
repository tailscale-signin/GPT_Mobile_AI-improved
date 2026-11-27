package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Exact admission inventories describe a missing capability; they never enable a disabled tool. */
internal object GitHubCapabilityReadiness {
    const val FEATURE = "CAPABILITY_READINESS_EVIDENCE_V1"
    val observations = setOf("CATALOG_NO_GITHUB_CAPABILITIES", "CATALOG_CAPABILITIES_NOT_ADVERTISED")
    val countFields = setOf("github_authorized_tool_count", "github_advertised_tool_count")

    fun observe(advertised: List<AgentToolDefinition>, authorized: List<AgentToolDefinition>, catalogObservation: String?): JsonObject {
        fun count(tools: List<AgentToolDefinition>) = tools.filter { it.modelVisible && (it.name.startsWith("github_") || it.name.isGatewayRepositoryTool()) }.distinctBy { it.name }.size
        val allowed = count(authorized)
        return buildJsonObject {
            put("github_prerequisite_observation", catalogObservation ?: if (allowed == 0) "CATALOG_NO_GITHUB_CAPABILITIES" else "CATALOG_CAPABILITIES_NOT_ADVERTISED")
            put("github_authorized_tool_count", allowed)
            put("github_advertised_tool_count", count(advertised))
        }
    }

    fun guidance(observation: String?): String = when (observation) {
        "CATALOG_TOOL_NOT_EXPOSED" -> "The authorized tool catalog is not exposed in this round. Review the selected GitHub connection and enabled tools, then resume the saved task."
        "CATALOG_INITIAL_REQUEST_UNAVAILABLE" -> "The GitHub catalog could not prepare its initial discovery request. Review connection readiness, then resume the saved task."
        "CATALOG_NO_GITHUB_CAPABILITIES" -> "The selected catalog contains no authorized GitHub operations. Review the repository connection and profile/chat tool selections, then resume the saved task."
        else -> "Authorized GitHub operations are missing from this round's tool inventory. Reload the selected connection's tools, then resume the saved task."
    }

    fun validValue(key: String, value: JsonElement): JsonElement? = (value as? JsonPrimitive)?.takeIf {
        key in countFields && !it.isString && it.intOrNull in 0..16_000_000
    }
}
