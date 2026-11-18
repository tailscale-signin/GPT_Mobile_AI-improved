package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Route only exact, retained read recipes through the ordinary authorization/budget path. */
internal object GitHubRecoveryCalls {
    private val keys = setOf("continuation_call", "correction_call", "recovery_call", "source_recovery_call")

    fun repairSourceRead(call: ProviderEvent.ToolCall, tool: AgentTool?): ProviderEvent.ToolCall {
        val operation = call.name.githubOperationFamily()
        if (tool == null || operation !in setOf("github_read", "github_search")) return call
        val schema = tool.definition.inputSchema
        if (ToolArgumentValidator.errors(call.arguments, schema).isEmpty()) return call
        val corrected = when (operation) {
            "github_read" -> ToolArgumentValidator.boundedReadCorrection(operation, call.arguments)
            "github_search" -> {
                // Repair only schema-bounded options on the first page. An explicit
                // index_cursor=0 is the beginning of a search, not an issued
                // continuation. Preserve it; never rewrite an advanced cursor,
                // opaque search ID or saved-view selector.
                val query = (call.arguments["query"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                val declared = (schema["properties"] as? JsonObject).orEmpty()
                val unsupportedPresentation = setOf("per_page", "response_detail")
                    .filter { it in call.arguments && it !in declared }.toSet()
                val blobRules = declared["max_new_blobs"] as? JsonObject
                val requestedBlobs = (call.arguments["max_new_blobs"] as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.intOrNull
                val minBlobs = (blobRules?.get("minimum") as? JsonPrimitive)?.intOrNull
                val maxBlobs = (blobRules?.get("maximum") as? JsonPrimitive)?.intOrNull
                val correctedBlobs = if (requestedBlobs != null && minBlobs != null &&
                    maxBlobs != null && minBlobs <= maxBlobs
                ) requestedBlobs.coerceIn(minBlobs, maxBlobs) else null
                if (query.isNullOrBlank() || query.length > 200 ||
                    call.arguments.keys.any { it in setOf("view_cursor", "search_id") } ||
                    call.arguments["index_cursor"] !in setOf(null, JsonPrimitive(0)) ||
                    call.arguments["page"] !in setOf(null, JsonPrimitive(1)) ||
                    (unsupportedPresentation.isEmpty() && (correctedBlobs == null || correctedBlobs == requestedBlobs))
                ) {
                    null
                } else {
                    val withoutPresentation = call.arguments - unsupportedPresentation
                    JsonObject(
                        if (correctedBlobs != null && correctedBlobs != requestedBlobs)
                            withoutPresentation + ("max_new_blobs" to JsonPrimitive(correctedBlobs))
                        else withoutPresentation
                    )
                }
            }
            else -> null
        } ?: return call
        if (ToolArgumentValidator.errors(corrected, schema).isNotEmpty() ||
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors(operation, corrected).isNotEmpty() ||
            !GitHubAuthorizationBoundary.isReplayableRead(call.name, corrected)
        ) {
            return call
        }
        return call.copy(arguments = corrected)
    }

    fun resolve(call: ProviderEvent.ToolCall, exchanges: List<AgentToolExchange>, tools: Map<String, AgentTool>): ProviderEvent.ToolCall {
        val envelope = call.arguments.takeIf { it.size == 1 }?.entries?.singleOrNull()
            ?.takeIf { it.key in keys }?.value as? JsonObject
        val recipes = exchanges.asReversed().asSequence().flatMap { exchange ->
            exchange.results.asReversed().asSequence().mapNotNull { GitHubReplayRecovery.envelope(it.content) }
        }.flatMap { result -> keys.asSequence().mapNotNull { result[it] as? JsonObject } }
        val candidate = if (envelope != null) {
            recipes.firstOrNull { it == envelope }
        } else if (call.arguments.keys == setOf("view_cursor")) {
            recipes.firstOrNull { it["arguments"] == call.arguments }
        } else {
            null
        }
        val recipe = candidate ?: return call
        val name = (recipe["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return call
        val arguments = recipe["arguments"] as? JsonObject ?: return call
        val tool = tools[name] ?: return call
        if (!GitHubAuthorizationBoundary.isReplayableRead(name, arguments) || ToolArgumentValidator.errors(arguments, tool.definition.inputSchema).isNotEmpty()) return call
        return call.copy(name = name, arguments = arguments)
    }
}
