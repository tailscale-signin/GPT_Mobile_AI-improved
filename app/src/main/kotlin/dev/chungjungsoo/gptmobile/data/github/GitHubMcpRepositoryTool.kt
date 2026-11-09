package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Official MCP schemas keep their identity and approval flow while honoring this task's destination. */
internal class GitHubMcpRepositoryTool(
    private val delegate: AgentTool,
    private val repository: GitHubRepositoryContext,
    private val operation: String,
    private val approvedReadOnly: Boolean = false,
    private val readSources: Set<String> = emptySet()
) : PreparableAgentTool,
    OwnedAgentTool {
    override val definition get() = delegate.definition
    override val managesExecutionBudget get() = delegate.managesExecutionBudget
    override val coordinatesChildWork get() = delegate.coordinatesChildWork
    override val executionOwner get() = (delegate as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = GitHubMcpRepositoryTool(
        delegate.withChildBudget { child -> bind(GitHubMcpRepositoryTool(child, repository, operation, approvedReadOnly, readSources)) },
        repository,
        operation,
        approvedReadOnly,
        readSources
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        val bounded = bound(arguments)
        receipt(delegate.execute(callId, bounded), arguments, bounded)
    } catch (rejected: Rejected) {
        rejected.result(callId)
    }

    override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
        val bounded = try {
            bound(arguments)
        } catch (rejected: Rejected) {
            return { rejected.result(callId) }
        }
        val prepared = (delegate as? PreparableAgentTool)?.prepareExecution(callId, bounded) ?: { delegate.execute(callId, bounded) }
        return { receipt(prepared(), arguments, bounded) }
    }

    private fun bound(arguments: JsonObject): JsonObject {
        val properties = definition.inputSchema["properties"] as? JsonObject
        // Global query and opaque operation schemas have their existing exact approval.
        // Account creation/fork/import/clone have distinct source/destination contracts.
        if (operation in GitHubRepositoryLifecycle.actions || properties?.keys?.containsAll(listOf("owner", "repo")) != true) return arguments
        fun target(name: String): String? {
            val value = arguments[name]?.takeUnless { it == JsonNull } ?: return null
            if (value !is JsonPrimitive || !value.isString) throw Rejected("INVALID_ARGUMENTS", "$name must be a repository name string.")
            return value.content
        }
        val requested = try {
            normalizeGitHubRepository(target("owner"), target("repo"))
        } catch (invalid: IllegalArgumentException) {
            throw Rejected("INVALID_ARGUMENTS", invalid.message.orEmpty())
        }
        val owner = requested.first ?: repository.owner
        val repo = requested.second ?: repository.repo
        val selected = owner.equals(repository.owner, true) && repo.equals(repository.repo, true)
        val explicitSource = requested.first != null &&
            requested.second != null &&
            "$owner/$repo".lowercase(java.util.Locale.ROOT) in readSources
        val readOnly = operation in READS || approvedReadOnly && operation !in WRITES
        if (!selected && !(readOnly && explicitSource)) {
            throw Rejected(
                "GITHUB_REPOSITORY_TARGET_MISMATCH",
                "This GitHub task writes to ${repository.fullName}. Use that owner and repository, or use Change repository to select the verified intended destination before resuming the saved request. Creation receipts and unfinished requirements remain retained. Explicit source repositories are available only for read operations."
            )
        }
        return JsonObject(arguments + mapOf("owner" to JsonPrimitive(owner), "repo" to JsonPrimitive(repo)))
    }

    private fun receipt(result: AgentToolResult, original: JsonObject, bounded: JsonObject): AgentToolResult =
        if (original == bounded || result.executionReceipt?.get("corrected_arguments") is JsonObject) {
            result
        } else {
            result.copy(
                executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("corrected_arguments" to bounded))
            )
        }

    private class Rejected(val code: String, message: String) : IllegalArgumentException(message) {
        fun result(callId: String) = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.mcp-target-admission.v1")
                    put("error_code", code)
                    put("failed_stage", "tool_precheck")
                    put("dispatch_state", "PRECHECK_REJECTED")
                    put("dispatched", false)
                    if (code == "GITHUB_REPOSITORY_TARGET_MISMATCH") put("recovery_actions", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("CHANGE_REPOSITORY"))))
                    put("message", message.orEmpty())
                }
            ),
            isError = true,
            errorCode = code,
            dispatched = false
        )
    }

    private companion object {
        val READS = setOf(
            "get_file_contents", "read_code", "read_ranges", "search_code", "search_repository", "find_symbol", "find_references", "find_tests",
            "resolve_context", "repo_status", "get_repository", "index_status", "get_pull_request", "pull_request_read", "list_pull_requests", "get_pull_request_comments",
            "get_issue", "issue_read", "list_issues", "workflow_catalog", "list_workflows", "list_workflow_runs", "get_workflow_run", "list_workflow_jobs", "list_workflow_artifacts",
            "get_job_logs", "ci_failure_digest", "get_latest_release", "list_releases", "get_commit", "list_commits", "get_tag", "list_tags", "list_branches", "get_branch"
        )
        val WRITES = setOf(
            "create_branch", "push_files", "commit_files", "commit_staged_patch", "create_or_update_file", "delete_file", "create_pull_request", "update_pull_request",
            "create_pull_request_review", "reply_pull_request_comment", "merge_pull_request", "create_issue", "update_issue", "add_issue_comment", "dispatch_workflow",
            "rerun_workflow", "rerun_failed_jobs", "cancel_workflow", "publish_release", "delete_repository", "update_repository"
        )
    }
}
