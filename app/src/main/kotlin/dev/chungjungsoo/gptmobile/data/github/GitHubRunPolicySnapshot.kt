package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Immutable admission evidence. It records grants; it never grants an operation. */
internal object GitHubRunPolicySnapshot {
    fun capture(runId: String, profile: String, model: String, authority: String, task: String, tools: List<AgentToolDefinition>, limits: AgentRunLimits, configuration: JsonElement, usedCalls: Int?): JsonObject = buildJsonObject {
        put("schema", "github.run-policy.v1")
        put("run_id", runId)
        put("task_sha256", GitHubEvidenceBook.digest(GitHubTaskContinuity.request(task)))
        put("profile_sha256", GitHubEvidenceBook.digest(profile))
        put("model_sha256", GitHubEvidenceBook.digest(model))
        put("authority_revision", authority)
        put("chat_configuration", configuration)
        put(
            "tool_contracts",
            JsonArray(
                tools.filter { it.modelVisible }.sortedBy { it.name }.map { tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("input_schema", tool.inputSchema)
                    }
                }
            )
        )
        put("max_tool_calls", limits.maxToolCalls)
        put("used_tool_calls", usedCalls?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("remaining_tool_calls", usedCalls?.let { kotlinx.serialization.json.JsonPrimitive((limits.maxToolCalls - it).coerceAtLeast(0)) } ?: kotlinx.serialization.json.JsonNull)
        put("run_timeout_ms", limits.runTimeoutMillis)
        put("fixture_scope", if (GitHubChecklistScope.supportedFixturePlan(task)) "SUPPORTED_OWNED_FIXTURES" else "ACCEPTED_REQUEST")
        put("approval_scope", "EXACT_ACTION_BOUND_APPROVALS_REMAIN_REQUIRED")
        put("source", "EXECUTION_ADMISSION_NOT_CURRENT_SETTINGS")
        put("dynamic_tools", "REQUIRE_CURRENT_AUTHORIZATION_AND_CONTRACT_VALIDATION")
        if (GitHubTestChecklist.integration(task)) {
            put(
                "test_matrix",
                JsonArray(
                    GitHubTestChecklist.rows(emptyMap(), tools, task).map { raw ->
                        JsonObject((raw as JsonObject).filterKeys { it in setOf("id", "required", "applicability", "blocker_code") })
                    }
                )
            )
        }
    }
}
