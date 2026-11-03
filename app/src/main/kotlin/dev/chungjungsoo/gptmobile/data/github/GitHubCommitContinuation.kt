package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A post-approval message is a request, never a mutation receipt. Reconcile with bounded reads. */
internal object GitHubCommitContinuation {
    fun next(sha: String, requestKey: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): ProviderEvent.ToolCall? {
        require(sha.matches(Regex("[a-fA-F0-9]{40}")))
        val prefix = "commit-resume-" + GitHubEvidenceBook.digest("$requestKey:$sha").take(20)
        val observed = receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && it.text("call_id").startsWith("$prefix-")
        }
        fun read(operation: String, action: String, fields: JsonObject = JsonObject(emptyMap())): ProviderEvent.ToolCall? {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == operation } ?: return null
            val id = "$prefix-$action"
            if (observed.any { it["call_id"] == JsonPrimitive(id) }) return null
            val args = JsonObject(fields + ("action" to JsonPrimitive(action)))
            if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty()) return null
            return ProviderEvent.ToolCall(id, tool.name, args)
        }
        read("github_context", "status")?.let { return it }
        read("github_context", "commit", buildJsonObject { put("commit_sha", sha) })?.let { return it }
        read("github_context", "checks", buildJsonObject { put("commit_sha", sha) })?.let { return it }
        read(
            "github_ci",
            "runs",
            buildJsonObject {
                put("head_sha", sha)
                put("per_page", 5)
            }
        )?.let { return it }
        val watch = tools.firstOrNull { it.name.substringBefore("__") == "github_watch_checks" } ?: return null
        val runs = observed.filter { it.text("call_id") == "$prefix-runs" && it["success"] == JsonPrimitive(true) }.flatMap { receipt ->
            val root = receipt["result"] as? JsonObject ?: return@flatMap emptyList()
            (((root["data"] as? JsonObject) ?: root)["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }
        for (run in runs) {
            if (run.text("head_sha") != sha || run.text("status") !in setOf("queued", "in_progress", "waiting", "pending", "requested")) continue
            val id = run["id"] ?: continue
            val callId = "$prefix-watch-$id"
            if (observed.any { it["call_id"] == JsonPrimitive(callId) }) continue
            val args = buildJsonObject {
                put("run_id", id)
                put("commit_sha", sha)
            }
            if (ToolArgumentValidator.errors(args, watch.inputSchema).isEmpty()) return ProviderEvent.ToolCall(callId, watch.name, args)
        }
        return null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
