package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve official MCP schemas and approval flow while enforcing destination and live Copilot consent. */
internal class GitHubMcpRepositoryTool(
    private val delegate: AgentTool,
    private val repository: GitHubRepositoryContext,
    private val operation: String,
    private val approvedReadOnly: Boolean = false,
    private val readSources: Set<String> = emptySet(),
    private val copilotAllowed: suspend () -> Boolean = { false }
) : PreparableAgentTool,
    OwnedAgentTool {
    override val definition get() = delegate.definition
    override val managesExecutionBudget get() = delegate.managesExecutionBudget
    override val coordinatesChildWork get() = delegate.coordinatesChildWork
    override val executionOwner get() = (delegate as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = GitHubMcpRepositoryTool(
        delegate.withChildBudget { child -> bind(GitHubMcpRepositoryTool(child, repository, operation, approvedReadOnly, readSources, copilotAllowed)) },
        repository,
        operation,
        approvedReadOnly,
        readSources,
        copilotAllowed
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        val bounded = bound(arguments)
        requireCopilotConsent(bounded)
        receipt(delegate.execute(callId, bounded), arguments, bounded)
    } catch (rejected: Rejected) {
        rejected.result(callId)
    }

    override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
        val bounded = try {
            bound(arguments).also { requireCopilotConsent(it) }
        } catch (rejected: Rejected) {
            return { rejected.result(callId) }
        }
        val prepared = (delegate as? PreparableAgentTool)?.prepareExecution(callId, bounded) ?: { delegate.execute(callId, bounded) }
        return {
            try {
                // Consent can be revoked while the existing exact approval is open.
                requireCopilotConsent(bounded)
                receipt(prepared(), arguments, bounded)
            } catch (rejected: Rejected) {
                rejected.result(callId)
            }
        }
    }

    private suspend fun requireCopilotConsent(arguments: JsonObject) {
        if (!requiresCopilotConsent(operation, arguments)) return
        val allowed = try {
            copilotAllowed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!allowed) {
            throw Rejected(
                "GITHUB_COPILOT_DISABLED",
                "GitHub Copilot is off for this conversation. Enable it in the GitHub plugin and this chat's settings before starting cloud work or requesting a review. Nothing was sent."
            )
        }
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
        val readOnly = isReadOnlyOperation(operation, approvedReadOnly)
        if (!selected && !(readOnly && explicitSource)) {
            throw Rejected(
                "GITHUB_REPOSITORY_TARGET_MISMATCH",
                "This GitHub task writes to ${repository.fullName}. Use that owner and repository, or use Change repository to select the verified intended destination before resuming the saved request. Creation receipts and unfinished requirements remain retained. Explicit source repositories are available only for read operations."
            )
        }
        return JsonObject(arguments + mapOf("owner" to JsonPrimitive(owner), "repo" to JsonPrimitive(repo)))
    }

    private fun receipt(result: AgentToolResult, original: JsonObject, bounded: JsonObject): AgentToolResult {
        val normalized = if (original == bounded) {
            result
        } else {
            dev.chungjungsoo.gptmobile.data.agent.ToolArgumentNormalization.retain(result, bounded, dev.chungjungsoo.gptmobile.data.agent.ToolArgumentNormalization.Kind.REPOSITORY_BINDING)
        }
        if (result.isError || !result.dispatched || result.sharedResult || GitHubCopilotProgress.mcpActivity(operation, bounded) == null) return normalized
        // This receipt proves a response to an admitted named request, not the
        // meaning of an opaque remote body or any successful cloud execution.
        val observed = buildJsonObject {
            put("schema", "github.copilot-mcp-request.v1")
            put("tool", definition.name)
            put("operation", operation)
            put("call_id", result.callId)
            put("owner", bounded["owner"] ?: JsonPrimitive(repository.owner))
            put("repo", bounded["repo"] ?: JsonPrimitive(repository.repo))
            put("dispatch_observed", true)
            put("response_observed", true)
            put(
                "kind",
                when (operation) {
                    "request_copilot_review" -> "REVIEW"
                    "get_copilot_job_status" -> "STATUS"
                    else -> "WORK"
                }
            )
        }
        val digest = GitHubEvidenceBook.digest(observed.toString())
        return normalized.copy(
            executionReceipt = JsonObject(
                normalized.executionReceipt.orEmpty() + buildJsonObject {
                    put("github_copilot_request", observed)
                    put("saved_view_source_call_id", result.callId)
                    put("saved_view_source_sha256", digest)
                    put(
                        "semantic_summary",
                        buildJsonObject {
                            put("schema", "github.semantic-summary.v1")
                            put("operation", definition.name.substringBefore("__"))
                            put("hash_scope", "IMMUTABLE_OPERATION_RECEIPT")
                            put("source_result_sha256", digest)
                            put("copilot_request", observed)
                        }
                    )
                }
            )
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

    companion object {
        /** These published tools start paid cloud work; observing an existing job remains available. */
        fun requiresCopilotConsent(operation: String): Boolean = operation in COPILOT_WRITES

        /** Official issue_write exposes method and assignees as top-level schema properties. */
        fun requiresCopilotConsent(operation: String, arguments: JsonObject): Boolean =
            requiresCopilotConsent(operation) ||
                when (operation) {
                    "create_issue", "update_issue" -> GitHubCopilotAgentMergePolicy.requiresConsent(operation, arguments)
                    "issue_write" -> (arguments["method"] as? JsonPrimitive)?.content in setOf("create", "update") &&
                        GitHubCopilotAgentMergePolicy.requiresConsent("update_issue", arguments)
                    else -> false
                }

        /** A saved read grant cannot make a known remote mutation replayable or source-only. */
        fun isReadOnlyOperation(operation: String, approvedReadOnly: Boolean = false): Boolean =
            operation in READS || approvedReadOnly && operation !in WRITES && operation !in COPILOT_WRITES

        private val COPILOT_WRITES = setOf(
            "assign_copilot_to_issue",
            "assign_copilot_to_issue_with_intent",
            "create_pull_request_with_copilot",
            "request_copilot_review"
        )
        private val READS = setOf(
            "get_file_contents", "read_code", "read_ranges", "search_code", "search_repository", "find_symbol", "find_references", "find_tests",
            "resolve_context", "repo_status", "get_repository", "index_status", "get_pull_request", "pull_request_read", "list_pull_requests", "get_pull_request_comments",
            "get_issue", "issue_read", "list_issues", "workflow_catalog", "list_workflows", "list_workflow_runs", "get_workflow_run", "list_workflow_jobs", "list_workflow_artifacts",
            "get_job_logs", "ci_failure_digest", "get_latest_release", "list_releases", "get_commit", "list_commits", "get_tag", "list_tags", "list_branches", "get_branch",
            "get_copilot_job_status"
        )
        private val WRITES = setOf(
            "create_branch", "push_files", "commit_files", "commit_staged_patch", "create_or_update_file", "delete_file", "create_pull_request", "update_pull_request",
            "create_pull_request_review", "reply_pull_request_comment", "merge_pull_request", "create_issue", "update_issue", "issue_write", "add_issue_comment", "dispatch_workflow",
            "rerun_workflow", "rerun_failed_jobs", "cancel_workflow", "publish_release", "delete_repository", "update_repository"
        )
    }
}
