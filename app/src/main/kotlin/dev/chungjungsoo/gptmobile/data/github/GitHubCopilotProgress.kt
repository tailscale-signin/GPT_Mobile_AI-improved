package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Bounded public task facts. Neither a setting nor an attempted call proves cloud execution. */
internal object GitHubCopilotProgress {
    data class Activity(val preview: String, val subject: String, val next: String, val mutation: Boolean = false)
    data class Finding(val preview: String, val summary: String, val problem: Boolean)

    private val remoteStates = setOf("QUEUED", "IN_PROGRESS", "COMPLETED", "FAILED", "IDLE", "WAITING_FOR_USER", "TIMED_OUT", "CANCELLED")
    private val localStates = setOf("ACCEPTED", "DISPATCHING", "REJECTED", "RECONCILE_REQUIRED", "UNKNOWN", "NOT_STARTED")

    /** This gate also applies when detailed evidence elaboration is disabled. */
    fun sourceOwned(call: ProviderEvent.ToolCall, result: AgentToolResult, facts: JsonObject): Boolean {
        val receipt = result.executionReceipt ?: return false
        val digest = facts.text("source_result_sha256")
        if (result.sharedResult ||
            receipt["read_source"] == JsonPrimitive("RETAINED") ||
            receipt["semantic_summary"] !== facts ||
            receipt["saved_view_source_call_id"] != JsonPrimitive(call.callId) ||
            !digest.matches(Regex("[a-fA-F0-9]{64}")) ||
            receipt["saved_view_source_sha256"] != JsonPrimitive(digest) ||
            receipt["requires_reconciliation"] == JsonPrimitive(true) ||
            receipt["parent_result_successful"] == JsonPrimitive(false)
        ) {
            return false
        }
        val cloud = facts["copilot_task"] as? JsonObject
        val mcp = facts["copilot_request"] as? JsonObject
        if ((cloud == null) == (mcp == null)) return false
        if (cloud != null) {
            if (facts.text("hash_scope") != "IMMUTABLE_SOURCE_RESULT" ||
                call.name.githubOperationFamily() !in setOf("github", "github_pr") ||
                call.arguments.text("action") !in setOf("copilot_create_task", "copilot_task_status") ||
                cloud.text("controller") !in setOf("ANDROID_DIRECT", "GATEWAY") ||
                finding(cloud) == null
            ) {
                return false
            }
            if (cloud["remote_task_confirmed"] == JsonPrimitive(true) && cloud.identifier("task_id") == null) return false
            if (cloud.text("controller") == "GATEWAY" && cloud.identifier("gateway_job_id") == null) return false
            return listOf("task_id", "gateway_job_id", "request_id").all { field ->
                field !in call.arguments || call.arguments.identifier(field)?.let { it == cloud.identifier(field) } == true
            }
        }
        val request = requireNotNull(mcp)
        if (facts.text("hash_scope") != "IMMUTABLE_OPERATION_RECEIPT" ||
            !call.name.startsWith("mcp__") ||
            request != receipt["github_copilot_request"] ||
            request.text("tool") != call.name ||
            request.text("call_id") != call.callId ||
            GitHubEvidenceBook.digest(request.toString()) != digest ||
            mcpFinding(request) == null ||
            mcpActivity(request.text("operation"), call.arguments) == null
        ) {
            return false
        }
        return listOf("owner", "repo").all { field ->
            request.text(field).isNotBlank() && (field !in call.arguments || call.arguments.text(field).equals(request.text(field), true))
        }
    }

    fun activity(operation: String, arguments: JsonObject): Activity? {
        val family = operation.githubOperationFamily()
        if (operation.startsWith("mcp__")) return mcpActivity(operation.substringAfterLast("__"), arguments)
        if (family !in setOf("github", "github_pr", "github_issue")) return null
        val action = arguments.text("action")
        return when (action) {
            "copilot_create_task" -> Activity(
                "Preparing GitHub Copilot cloud task" + arguments.text("prompt").takeIf(String::isNotBlank)
                    ?.let { " · ${GitHubProgressPresentation.taskLabel(it)}" }.orEmpty(),
                "GitHub Copilot task submission",
                "confirm whether GitHub accepted the task and retain its original controller and task identity",
                true
            )
            "copilot_task_status" -> Activity("Checking GitHub Copilot task progress", "GitHub Copilot task status", "report the observed cloud state and inspect any returned changes and checks")
            "copilot_tasks" -> Activity("Finding existing GitHub Copilot tasks", "GitHub Copilot task inventory", "match the saved task identity before resuming; listing tasks does not start cloud work")
            "agent_merge_status" -> Activity("Checking GitHub Copilot repair enrollment", "GitHub Copilot repair enrollment", "report whether Copilot is watching this PR and inspect actual repair and check results")
            "agent_merge_enable", "agent_merge_update" -> Activity("Preparing GitHub Copilot PR repair", "GitHub Copilot repair request", "verify enrollment before claiming Copilot is watching or repairing the PR", true)
            "agent_merge_disable" -> Activity("Stopping GitHub Copilot repair enrollment", "GitHub Copilot stop request", "confirm that the PR is no longer enrolled", true)
            else -> {
                val canonical = when {
                    family == "github_pr" && action in GitHubCollaboration.prActions -> "pr_$action"
                    family == "github_issue" && action in GitHubCollaboration.issueActions -> "issue_$action"
                    family == "github_issue" && action in setOf("create", "update") -> "${action}_issue"
                    else -> action
                }
                if (!GitHubCopilotAgentMergePolicy.requiresConsent(canonical, arguments)) return null
                if (canonical == "pr_request_reviewers") {
                    Activity("Requesting GitHub Copilot review", "GitHub Copilot review request", "verify the review request and read the returned review before claiming findings", true)
                } else {
                    Activity("Assigning repository work to GitHub Copilot", "GitHub Copilot assignment", "verify assignment and follow the resulting cloud task or PR", true)
                }
            }
        }
    }

    fun mcpActivity(operation: String, arguments: JsonObject): Activity? {
        val activity = when {
            operation == "get_copilot_job_status" -> Activity("Checking the Copilot task through GitHub MCP", "Copilot status request through GitHub MCP", "inspect the returned task identity and state; an opaque response cannot establish completion")
            operation == "request_copilot_review" -> Activity("Preparing a Copilot review request through GitHub MCP", "Copilot review request through GitHub MCP", "verify that the request was accepted and inspect the actual review findings", true)
            GitHubMcpRepositoryTool.requiresCopilotConsent(operation, arguments) -> Activity("Preparing a Copilot work request through GitHub MCP", "Copilot work request through GitHub MCP", "verify cloud acceptance and keep the returned task or PR identity before checking progress", true)
            else -> return null
        }
        val intent = listOf("title", "problem_statement", "prompt").firstNotNullOfOrNull { arguments.text(it).takeIf(String::isNotBlank) }
        return if (activity.mutation && intent != null) activity.copy(preview = "${activity.preview} · ${GitHubProgressPresentation.taskLabel(intent)}") else activity
    }

    fun mcpFinding(receipt: JsonObject): Finding? {
        if (receipt.text("schema") != "github.copilot-mcp-request.v1" ||
            receipt["dispatch_observed"] != JsonPrimitive(true) ||
            receipt["response_observed"] != JsonPrimitive(true)
        ) {
            return null
        }
        val label = when (receipt.text("kind")) {
            "REVIEW" -> "Copilot review request"
            "WORK" -> "Copilot work request"
            "STATUS" -> "Copilot status request"
            else -> return null
        }
        return Finding("GitHub MCP returned the $label", "The selected GitHub MCP connection returned a response to the $label. Cloud acceptance, execution state and results remain unverified by this receipt.", false)
    }

    fun facts(operation: String, request: JsonObject, source: JsonObject): JsonObject? {
        if (operation.githubOperationFamily() !in setOf("github", "github_pr") ||
            request.text("action") !in setOf("copilot_create_task", "copilot_task_status") ||
            source.text("execution_owner") != "GITHUB_CLOUD"
        ) {
            return null
        }
        val controller = source.text("orchestration_owner")
        val gateway = controller == "GATEWAY" && source.text("schema") == "github.copilot-tasks.v1"
        val direct = controller == "ANDROID_DIRECT" && source.text("schema") == "github.copilot-task.v1"
        if (!gateway && !direct) return null
        val task = source["task"] as? JsonObject
        val taskId = source.identifier("task_id")
        if (taskId != null && task != null && task.identifier("id") != taskId) return null
        val gatewayId = source.identifier("gateway_job_id")
        val requestId = source.identifier("request_id")
        if (listOf("task_id" to taskId, "gateway_job_id" to gatewayId, "request_id" to requestId).any { (field, observed) ->
                field in request && request.identifier(field) != observed
            }
        ) {
            return null
        }
        if (gateway && gatewayId == null) return null
        val remote = (if (gateway) source.text("remote_state") else task?.text("state").orEmpty()).uppercase(Locale.ROOT)
        val local = if (gateway) source.text("state") else source.text("status")
        val phase = when {
            local in setOf("RECONCILE_REQUIRED", "REJECTED", "NOT_STARTED", "DISPATCHING") -> local
            gateway && source.text("error_code").isNotBlank() -> "BLOCKED"
            taskId != null && remote in remoteStates -> remote
            gateway && taskId == null && local == "ACCEPTED" -> "ACCEPTED"
            direct && taskId != null && local == "REMOTE_ACCEPTED" -> "REMOTE_ACCEPTED"
            else -> "UNKNOWN"
        }
        if (taskId == null && phase !in localStates && phase != "BLOCKED") return null
        return buildJsonObject {
            put("phase", phase)
            put("controller", controller)
            put("remote_task_confirmed", taskId != null)
            taskId?.let { put("task_id", it) }
            gatewayId?.let { put("gateway_job_id", it) }
            requestId?.let { put("request_id", it) }
            val name = if (gateway) source.text("task_name") else task?.text("name").orEmpty()
            name.takeIf(String::isNotBlank)?.let { put("task_name", GitHubProgressPresentation.taskLabel(it)) }
            val pulls = if (gateway) {
                (source["pull_request_numbers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull }
            } else {
                (task?.get("artifacts") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { artifact ->
                    if (artifact.text("provider") != "github" || artifact.text("type") != "pull") return@mapNotNull null
                    ((artifact["data"] as? JsonObject)?.get("id") as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
                }
            }
            put("pull_request_numbers", JsonArray(pulls.filter { it > 0 }.distinct().take(4).map(::JsonPrimitive)))
        }
    }

    fun finding(facts: JsonObject): Finding? {
        val phase = facts.text("phase")
        val confirmed = facts["remote_task_confirmed"] == JsonPrimitive(true)
        if (phase in remoteStates && !confirmed) return null
        val task = facts.text("task_name").takeIf(String::isNotBlank)?.let { " “${GitHubProgressPresentation.taskLabel(it)}”" }.orEmpty()
        val title: String
        val detail: String
        when (phase) {
            "ACCEPTED" -> {
                title = "Gateway accepted the Copilot request"
                detail = "The Gateway saved the Copilot request; GitHub has not yet confirmed a cloud task."
            }
            "DISPATCHING" -> {
                title = "Submitting the GitHub Copilot request"
                detail = "The Gateway began the Copilot submission; GitHub acceptance is not yet confirmed. Reconcile this request before any retry."
            }
            "REMOTE_ACCEPTED" -> {
                if (!confirmed) return null
                title = "GitHub accepted the Copilot task"
                detail = "GitHub accepted Copilot task$task. Its execution result is still pending."
            }
            "QUEUED" -> {
                title = "GitHub Copilot task is queued"
                detail = "GitHub accepted Copilot task$task and reports it queued; execution has not been observed yet."
            }
            "IN_PROGRESS" -> {
                title = "GitHub Copilot is working"
                detail = "GitHub reports Copilot task$task in progress. Changes and validation results remain pending."
            }
            "COMPLETED" -> {
                title = "GitHub Copilot reported task completion"
                detail = "GitHub reports Copilot task$task completed. Review the returned changes and exact-head checks before accepting or merging."
            }
            "IDLE", "WAITING_FOR_USER" -> {
                title = "GitHub Copilot needs input"
                detail = "GitHub reports Copilot task$task waiting for input. Open the saved task to inspect the request."
            }
            "FAILED", "TIMED_OUT", "CANCELLED" -> {
                title = "GitHub Copilot task ${phase.lowercase(Locale.ROOT).replace('_', ' ')}"
                detail = "GitHub reports Copilot task$task ${phase.lowercase(Locale.ROOT).replace('_', ' ')}. The requested result has not been verified."
            }
            "BLOCKED" -> {
                title = "GitHub Copilot progress is unavailable"
                detail = if (confirmed) "The saved Copilot task remains on GitHub, but the Gateway cannot refresh its progress. Restore the original account and access before resuming." else "The Gateway cannot submit the Copilot request with its current account and access. No cloud task is confirmed."
            }
            "REJECTED", "NOT_STARTED" -> {
                title = "GitHub Copilot request did not start"
                detail = "The Copilot request was rejected before a cloud task was confirmed. Inspect the recorded access or request error."
            }
            "RECONCILE_REQUIRED", "UNKNOWN" -> {
                title = "GitHub Copilot outcome needs reconciliation"
                detail = "The current Copilot outcome is unconfirmed. Inspect the saved task and original controller before retrying; no new task is authorized by this observation."
            }
            else -> return null
        }
        val pulls = (facts["pull_request_numbers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { number -> number > 0 } }.take(4)
        val artifacts = if (pulls.isEmpty()) "" else " GitHub returned " + pulls.joinToString { "PR #$it" } + "; this does not verify its code or checks."
        return Finding(title + task, detail + artifacts, phase in setOf("FAILED", "TIMED_OUT", "CANCELLED", "IDLE", "WAITING_FOR_USER", "BLOCKED", "REJECTED", "NOT_STARTED", "RECONCILE_REQUIRED", "UNKNOWN"))
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull.orEmpty()
    private fun JsonObject.identifier(key: String): String? = text(key).takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
}
