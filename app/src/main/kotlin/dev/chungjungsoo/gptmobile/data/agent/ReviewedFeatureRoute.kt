package dev.chungjungsoo.gptmobile.data.agent

import java.util.Locale

/** App-reviewed discovery hints. Only the caller's currently admitted definitions can be ranked. */
internal data class ReviewedFeatureRoute(
    val id: String,
    val triggers: List<String>,
    val exclusions: List<String>,
    val tools: List<String>,
    val proof: String
)

internal class ReviewedFeatureSelection(request: String, originalRequest: String = request) {
    private val selected = mutableMapOf<String, ReviewedFeatureRoute>()
    private val declined = mutableSetOf<String>()

    init {
        val query = normalize(request)
        val original = if (request == originalRequest) query else normalize(originalRequest)
        for (route in reviewedFeatureRoutes) {
            if (route.exclusions.any { " $it " in original || " $it " in query }) {
                declined.addAll(route.tools)
            } else if (route.triggers.any { " $it " in query }) {
                for (tool in route.tools) selected[tool] = route
            }
        }
    }

    fun route(tool: String): ReviewedFeatureRoute? = selected[tool]
    fun declined(tool: AgentToolDefinition): Boolean =
        tool.name in declined ||
            "web_search" in declined &&
            AgentToolCapability.WEB_SEARCH in tool.capabilities ||
            "read_url" in declined &&
            AgentToolCapability.PAGE_READER in tool.capabilities

    private companion object {
        val separator = Regex("[^\\p{L}\\p{N}]+")
        fun normalize(value: String): String = " " + separator.replace(value.take(8000).lowercase(Locale.ROOT), " ").trim() + " "
    }
}
