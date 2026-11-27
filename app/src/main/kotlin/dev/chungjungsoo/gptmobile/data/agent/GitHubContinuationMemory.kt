package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceRelevance
import dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Task control state is required input, independently of optional source replay. */
internal object GitHubContinuationMemory {
    private const val SCHEMA = "github.continuation-control.v1"

    fun isRequiredAnnotation(value: JsonObject): Boolean = value["schema"] in setOf(JsonPrimitive(SCHEMA), JsonPrimitive("github.runner-instruction.v1"))

    fun control(result: AgentToolResult): JsonObject? {
        val payload = payload(result) ?: return null
        if (payload["question"] is JsonPrimitive && payload["user_answer"] is JsonPrimitive && payload["cancelled"] is JsonPrimitive) {
            return JsonObject(payload.filterKeys { it in setOf("question", "user_answer", "answer_source", "cancelled") })
        }
        if (!result.isError || result.dispatched || result.errorCode != "INVALID_ARGUMENTS") return null
        val retained = payload.filterKeys {
            it in setOf("validation_errors", "reason", "missing_fields", "invalid_fields", "required_selector", "correction_call", "correction_calls", "recovery_call", "corrected_proposal_limit")
        }
        if (retained.isEmpty()) return null
        return JsonObject(
            retained + result.executionReceipt.orEmpty().filterKeys {
                it in setOf("argument_repair_signature_hash", "argument_repair_attempt", "argument_repair_exhausted")
            } + mapOf("error_code" to JsonPrimitive("INVALID_ARGUMENTS"), "dispatched" to JsonPrimitive(false))
        )
    }

    fun retain(exchanges: List<AgentToolExchange>): String = snapshot(exchanges)?.toString().orEmpty()

    fun snapshot(exchanges: List<AgentToolExchange>, task: String? = null, includeFinding: Boolean = false): JsonObject? {
        val decisions = linkedMapOf<String, JsonObject>()
        val corrections = linkedMapOf<String, JsonObject>()
        val findings = linkedMapOf<String, JsonObject>()
        for (exchange in exchanges) {
            val calls = exchange.calls.associateBy { it.callId }
            for (result in exchange.results) {
                for (carried in result.assistMetadata.filter { it["schema"] == JsonPrimitive(SCHEMA) }) {
                    // The carrier is the complete state at that point. Merging
                    // older rejected calls would resurrect an already corrected constraint.
                    decisions.clear()
                    corrections.clear()
                    findings.clear()
                    (carried["decisions"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { record ->
                        decisions[record["tool"].toString() + ":" + record["question"].toString()] = record
                    }
                    (carried["corrections"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { record ->
                        (record["constraint_key"] as? JsonPrimitive)?.contentOrNull?.let { corrections[it] = record }
                    }
                    if (includeFinding) (carried["finding"] as? JsonObject)?.let { finding -> findings[finding["receipt_reference"].toString()] = finding }
                }
                val call = calls[result.callId] ?: continue
                val operation = call.name.githubOperationFamily()
                val action = GitHubOperationTool.nativeArguments(call.name, call.arguments)?.get("action") ?: call.arguments["action"]
                val key = operation + "__" + call.name.substringAfter("__", "") + ":" + action.toString()
                if (!result.isError) corrections.remove(key)
                if (includeFinding && !result.isError) {
                    val summary = (result.executionReceipt?.get("semantic_summary") ?: payload(result)?.get("semantic_summary")) as? JsonObject
                    if (summary?.get("schema") == JsonPrimitive("github.semantic-summary.v1")) {
                        val finding = buildJsonObject {
                            put("receipt_reference", "tool-batch/${result.callId}")
                            put("observations", GitHubSemanticSummary.bounded(summary, 1400))
                        }
                        findings[finding["receipt_reference"].toString()] = finding
                    }
                }
                val state = control(result) ?: continue
                val record = buildJsonObject {
                    put("tool", call.name)
                    put("receipt_reference", "tool-batch/${result.callId}")
                    if (operation != "github_decide") put("constraint_key", key)
                    state.forEach { (field, value) -> put(field, value) }
                }
                if (operation == "github_decide" && "user_answer" in state) {
                    // A later answer to the same exact question supersedes the old
                    // answer. Distinct questions are never silently forgotten.
                    decisions[record["tool"].toString() + ":" + state["question"].toString()] = record
                } else if (result.isError && result.errorCode == "INVALID_ARGUMENTS") {
                    corrections[key] = record
                }
            }
        }
        if (decisions.isEmpty() && corrections.isEmpty() && findings.isEmpty()) return null
        val relevance = task?.let(::GitHubEvidenceRelevance)
        val finding = findings.values.maxByOrNull { record ->
            val summary = record["observations"] as JsonObject
            (relevance?.score(null, summary) ?: 0) * 4 + when {
                "resource_observations" in summary -> 3
                summary.keys.any { it in setOf("source_excerpts", "search_observations", "path_observations") } -> 2
                "check_counts" in summary -> 1
                else -> 0
            }
        }
        return buildJsonObject {
            put("schema", SCHEMA)
            put("scope", "Recorded task choices and rejected constraints; not write approval or current source verification. Apply answered choices without asking them again. Correct rejected arguments before dispatch.")
            if (decisions.isNotEmpty()) put("decisions", JsonArray(decisions.values.toList()))
            if (corrections.isNotEmpty()) put("corrections", JsonArray(corrections.values.toList()))
            finding?.let { put("finding", it) }
        }
    }

    fun reservation(memory: JsonObject?): Int = memory?.let { ((it.toString().encodeToByteArray().size.toLong() + 2) / 3 + 48).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0

    /** A single app-owned carrier survives eviction without rewriting any source body or call arguments. */
    fun project(exchanges: List<AgentToolExchange>, memory: JsonObject?): List<AgentToolExchange> {
        if (memory == null || exchanges.isEmpty()) return exchanges
        val lastResult = exchanges.indexOfLast { it.results.isNotEmpty() }
        require(lastResult >= 0) { "Mandatory task memory has no paired tool result." }
        return exchanges.mapIndexed { index, exchange ->
            exchange.copy(
                results = exchange.results.mapIndexed { resultIndex, result ->
                    val retained = result.assistMetadata.filterNot { it["schema"] == JsonPrimitive(SCHEMA) }
                    result.copy(assistMetadata = if (index == lastResult && resultIndex == exchange.results.lastIndex) retained + memory else retained)
                }
            )
        }
    }

    fun payload(result: AgentToolResult): JsonObject? = when (val content = result.retainedContent ?: result.content) {
        is ToolResultContent.Json -> content.value as? JsonObject
        is ToolResultContent.Text -> if (content.text.trimStart().startsWith('{')) runCatching { Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull() else null
        is ToolResultContent.ResourceLinks -> null
    }
}
