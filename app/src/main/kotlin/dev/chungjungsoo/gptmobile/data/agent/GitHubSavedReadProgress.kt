package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Delivered intervals survive replay eviction; restored checkpoints reconstruct the same state. */
internal class GitHubSavedReadProgress {
    private data class Cursor(val value: String, val view: String, val offset: Int, val bytes: Int)
    private data class ReadView(val intervals: MutableList<IntRange> = mutableListOf(), val continuations: MutableMap<String, JsonObject> = linkedMapOf())
    private val views = linkedMapOf<Pair<String, String>, ReadView>()

    fun observe(exchanges: List<AgentToolExchange>, deliveredResultDigests: Map<String, String>, cache: ToolExchangeCompactor.RenderCache? = null) {
        for (exchange in exchanges) {
            for (result in exchange.results) {
                if (!ToolResultDeliveryProof.matches(result, deliveredResultDigests, cache)) continue
                val issuer = exchange.calls.firstOrNull { it.callId == result.callId } ?: continue
                if (GitHubOperationTool.operationName(issuer.name) == null) continue
                val body = GitHubReplayRecovery.envelope(result.content) ?: continue
                if (body["schema"] != JsonPrimitive("github.operation.v1") || body["fragments"] !is JsonArray || body["page_capacity_exceeded"] == JsonPrimitive(true) || body["replay_compacted"] == JsonPrimitive(true) || body["observationTruncated"] == JsonPrimitive(true)) continue
                val start = (body["fragment_offset"] as? JsonPrimitive)?.intOrNull ?: continue
                val end = (body["fragment_end"] as? JsonPrimitive)?.intOrNull ?: continue
                val total = (body["total_fragments"] as? JsonPrimitive)?.intOrNull ?: continue
                if (start < 0 || end <= start || end > total) continue
                val recovery = body["recovery_call"] as? JsonObject ?: continue
                val current = recipeCursor(recovery, issuer.name) ?: continue
                if (current.offset != start) continue
                val requested = cursor(issuer.arguments)
                if (requested != null && (requested.view != current.view || requested.offset != start)) continue
                val key = issuer.name to current.view
                val view = views.getOrPut(key) { ReadView() }
                val intervals = (view.intervals + listOf(start until end)).sortedBy { it.first }
                view.intervals.clear()
                for (interval in intervals) {
                    val last = view.intervals.lastOrNull()
                    if (last != null && interval.first.toLong() <= last.last.toLong() + 1) {
                        view.intervals[view.intervals.lastIndex] = last.first..maxOf(last.last, interval.last)
                    } else {
                        view.intervals += interval
                    }
                }
                val recipes = listOfNotNull(body["continuation_call"] as? JsonObject) +
                    (body["continuation_options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                for (recipe in recipes) {
                    val next = recipeCursor(recipe, issuer.name) ?: continue
                    if (next.view == current.view && next.offset == end) view.continuations[next.value] = recipe.getValue("arguments") as JsonObject
                }
                // Losing old planning metadata only disables an optimization. It
                // never grants a cursor or forgets an execution/replay safeguard.
                while (view.continuations.size > 256) view.continuations.remove(view.continuations.keys.first())
                if (view.intervals.size > 256) view.intervals.removeAt(0)
                while (views.size > 64) views.remove(views.keys.first())
            }
        }
    }

    fun advance(call: ProviderEvent.ToolCall, tool: AgentToolDefinition?): ProviderEvent.ToolCall {
        // An explicit model/user reread can be intentional. Only the native
        // recovery planner may advance its own already-consumed page proposal.
        if (!call.callId.startsWith("saved-result-consume-") || tool == null || tool.name != call.name || GitHubOperationTool.operationName(call.name) == null) return call
        val requested = cursor(call.arguments) ?: return call
        val view = views[call.name to requested.view] ?: return call
        val covered = view.intervals.firstOrNull { requested.offset in it } ?: return call
        if (covered.last == Int.MAX_VALUE) return call
        val nextOffset = covered.last + 1
        val next = view.continuations.values.mapNotNull { arguments -> cursor(arguments)?.let { it to arguments } }
            .filter { (cursor, _) -> cursor.offset == nextOffset && cursor.bytes <= requested.bytes }
            .maxByOrNull { it.first.bytes }?.second ?: return call
        if (ToolArgumentValidator.errors(next, tool.inputSchema).isNotEmpty() || !GitHubAuthorizationBoundary.isReplayableRead(call.name, next)) return call
        return call.copy(arguments = next)
    }

    private fun recipeCursor(recipe: JsonObject, name: String): Cursor? {
        if (recipe["tool"] != JsonPrimitive(name)) return null
        return (recipe["arguments"] as? JsonObject)?.let(::cursor)
    }

    private fun cursor(arguments: JsonObject): Cursor? {
        if (arguments.keys != setOf("view_cursor")) return null
        val value = (arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val parts = value.split(':')
        if (parts.size != 3 || parts[0].isBlank()) return null
        val offset = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val bytes = parts[2].toIntOrNull()?.takeIf { it in 1024..8192 } ?: return null
        return Cursor(value, parts[0], offset, bytes)
    }
}
