package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Identity of the upstream collection query, independent of display and page controls. */
internal object GitHubInventoryQuery {
    fun arguments(tool: String, arguments: JsonObject): JsonObject {
        val action = (arguments["action"] as? JsonPrimitive)?.content
        val ci = tool.substringBefore("__") == "github_ci"
        val workflows = ci && action in setOf("workflows", "list_workflows")
        val runs = ci && action in setOf("runs", "list_workflow_runs")
        val ignored = setOf("page", "response_detail", "verify_live") + when {
            workflows -> setOf("ref", "commit_sha")
            // The runs endpoint filters on branch/head_sha; ref is workspace context.
            runs -> setOf("ref", "commit_sha")
            else -> emptySet()
        }
        val normalized = arguments.filterKeys { it !in ignored }.toMutableMap()
        if (workflows || runs) {
            normalized["action"] = JsonPrimitive(if (workflows) "workflows" else "runs")
            normalized.putIfAbsent("per_page", JsonPrimitive(if (workflows) 30 else 100))
        }
        if (runs) (arguments["commit_sha"] as? JsonPrimitive)?.takeIf { it.content.isNotBlank() }?.let { normalized["head_sha"] = it }
        for (key in listOf("owner", "repo")) {
            (normalized[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { normalized[key] = JsonPrimitive(it.content.lowercase(Locale.ROOT)) }
        }
        return JsonObject(normalized.toSortedMap())
    }
}
