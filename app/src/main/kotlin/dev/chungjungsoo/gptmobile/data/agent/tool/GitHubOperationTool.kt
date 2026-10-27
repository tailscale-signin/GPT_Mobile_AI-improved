package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small model-facing contracts backed by the same authorized native integration as the UI. */
internal class GitHubOperationTool(
    val operation: String,
    private val backend: AgentTool,
    private val scope: String,
    modelName: String = operation,
    private val views: GitHubResultViews = GitHubResultViews.shared
) : AgentTool {
    override val managesExecutionBudget get() = backend.managesExecutionBudget
    override val definition = definition(operation, modelName)

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if ("view_cursor" in arguments) {
            val cursor = (arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return AgentToolResult(callId, ToolResultContent.Text("view_cursor must be the exact saved cursor string."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            return views.read(scope, operation, definition.name, cursor, callId)
        }
        // Explicit repository overrides must be complete; both absent uses the pinned task context.
        val explicitOwner = (arguments["owner"] as? JsonPrimitive)?.content?.trim()
        val explicitRepo = (arguments["repo"] as? JsonPrimitive)?.content?.trim()
        if ((explicitOwner != null || explicitRepo != null) &&
            (explicitOwner.isNullOrBlank() || explicitRepo.isNullOrBlank())
        ) {
            return AgentToolResult(
                callId,
                ToolResultContent.Text("Specify both owner and repo, or omit both to use the selected GitHub repository."),
                true,
                errorCode = "INVALID_ARGUMENTS",
                dispatched = false
            )
        }
        if (operation == "github_write") {
            val errors = ToolArgumentValidator.errors(arguments, definition.inputSchema)
            if (errors.isNotEmpty()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("Invalid GitHub write arguments: " + errors.joinToString("; ")),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        val native = nativeArguments(operation, arguments)
            ?: return AgentToolResult(callId, ToolResultContent.Text("Unsupported GitHub operation."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val result = backend.execute(callId, native)
        return views.present(scope, operation, definition.name, result, arguments)
    }

    companion object {
        val names = setOf("github_context", "github_search", "github_read", "github_pr", "github_ci", "github_stage_patch", "github_commit", "github_write", "github_release")
        fun operationName(name: String): String? = name.substringBefore("__").takeIf { it in names }
        fun immutableRead(name: String, arguments: JsonObject): Boolean = operationName(name) in setOf("github_read", "github_search") &&
            (arguments["commit_sha"] as? JsonPrimitive)?.content?.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) == true &&
            "view_cursor" !in arguments

        fun nativeArguments(name: String, args: JsonObject): JsonObject? {
            val op = operationName(name) ?: return null
            if ("view_cursor" in args) return JsonObject(args + ("action" to JsonPrimitive("read_saved_result")))
            val requested = (args["action"] as? JsonPrimitive)?.content.orEmpty()
            val action = when (op) {
                "github_context" -> when (requested) {
                    "", "resolve" -> "resolve_context"
                    "status" -> "repo_status"
                    "index" -> "index_status"
                    "changes" -> "changed_since"
                    "efficiency" -> "efficiency"
                    else -> return null
                }
                "github_search" -> "search_repository"
                "github_read" -> "read_ranges"
                "github_stage_patch" -> "stage_patch"
                "github_commit" -> when (requested) {
                    "", "commit" -> "commit_staged_patch"
                    "branch" -> "create_branch"
                    else -> return null
                }
                "github_write" -> when (requested) {
                    "", "commit" -> "commit_files"
                    else -> return null
                }
                "github_pr" -> when (requested) {
                    "", "context" -> "pr_context"
                    "comments" -> "get_pull_request_comments"
                    "create" -> "create_pull_request"
                    "update" -> "update_pull_request"
                    "comment" -> "create_issue_comment"
                    "reply" -> "reply_pull_request_comment"
                    "review" -> "create_pull_request_review"
                    "merge" -> "merge_pull_request"
                    else -> return null
                }
                "github_ci" -> when (requested) {
                    "", "failure" -> "ci_failure_digest"
                    "workflows" -> "list_workflows"
                    "runs" -> "list_workflow_runs"
                    "run" -> "get_workflow_run"
                    "jobs" -> "list_workflow_jobs"
                    "logs" -> "get_job_logs"
                    "artifacts" -> "list_workflow_artifacts"
                    "dispatch" -> "dispatch_workflow"
                    "rerun" -> "rerun_failed_jobs"
                    "cancel" -> "cancel_workflow"
                    else -> return null
                }
                "github_release" -> when (requested) {
                    "", "status" -> "release_status"
                    "publish" -> "publish_release"
                    else -> return null
                }
                else -> return null
            }
            val translated = args.toMutableMap()
            if (action == "create_branch") args["from_branch"]?.let { translated["base"] = it }
            if (action == "changed_since") args["base_sha"]?.let { translated["base"] = it }
            if (action == "pr_context") args["expected_head_sha"]?.let { translated["expected_head"] = it }
            translated["action"] = JsonPrimitive(action)
            return JsonObject(translated)
        }

        fun taskTools(task: String): Set<String> {
            val text = task.lowercase(java.util.Locale.ROOT)
            if (!isGitHubTask(text) && !Regex("\\b(code|source|ci|pr)\\b").containsMatchIn(text)) return emptySet()
            val selected = linkedSetOf("github_context", "github_search", "github_read")
            if (Regex("\\b(pr|pull request|review|merge)\\b").containsMatchIn(text)) selected += "github_pr"
            if (Regex("\\b(ci|build|test failure|workflow|pipeline)\\b").containsMatchIn(text)) selected += "github_ci"
            if (Regex("\\b(fix|patch|edit|change|implement)\\b").containsMatchIn(text)) selected += "github_stage_patch"
            if (Regex("\\b(commit|committing|push|pushing|branch)\\b").containsMatchIn(text)) selected += "github_commit"
            if (Regex("\\b(write|writing|create file|edit|modify|change|fix|add|update|delete|remove file|commit|committing|push|pushing|patch|implement|apply changes)\\b").containsMatchIn(text)) selected += "github_write"
            if ("release" in text) selected += "github_release"
            return selected
        }

        fun definition(operation: String, modelName: String = operation): AgentToolDefinition {
            require(operation in names)
            fun field(type: String) = buildJsonObject { put("type", type) }
            fun choice(vararg names: String) = buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(names.map(::JsonPrimitive)))
            }
            val properties = linkedMapOf("owner" to field("string"), "repo" to field("string"), "commit_sha" to field("string"), "view_cursor" to field("string"))
            when (operation) {
                "github_context" -> {
                    properties["action"] = choice("resolve", "status", "index", "changes", "efficiency")
                    properties["ref"] = field("string")
                    properties["base_sha"] = buildJsonObject {
                        put("type", "string")
                        put("description", "Required for action=changes: base commit SHA or ref to compare with the pinned head. For pull request files use github_pr context with include_files=true.")
                    }
                    properties["refresh"] = field("boolean")
                    properties["page"] = field("integer")
                }
                "github_search" -> {
                    properties["path_prefix"] = field("string")
                    properties["query"] = field("string")
                    properties["mode"] = choice("literal", "definitions", "references", "paths", "tests")
                    properties["index_cursor"] = field("integer")
                    properties["page"] = field("integer")
                }
                "github_read" -> {
                    properties["evidence_id"] = field("string")
                    properties["ranges"] = buildJsonObject {
                        put("type", "array")
                        put("maxItems", 8)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("path", field("string"))
                                        put("start_line", field("integer"))
                                        put("end_line", field("integer"))
                                        put("start_offset", field("integer"))
                                    }
                                )
                                put("required", JsonArray(listOf("path", "start_line", "end_line").map(::JsonPrimitive)))
                            }
                        )
                    }
                }
                "github_stage_patch" -> {
                    properties["expected_head_sha"] = field("string")
                    properties["branch"] = field("string")
                    properties["edits"] = buildJsonObject {
                        put("type", "array")
                        put("maxItems", 20)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        listOf("path", "expected_blob_sha", "old_text", "new_text").forEach { put(it, field("string")) }
                                        put("occurrence", field("integer"))
                                    }
                                )
                                put("required", JsonArray(listOf("path", "expected_blob_sha", "old_text", "new_text").map(::JsonPrimitive)))
                            }
                        )
                    }
                }
                "github_commit" -> {
                    properties["action"] = choice("commit", "branch")
                    listOf("stage_id", "branch", "message", "expected_head_sha", "from_branch").forEach { properties[it] = field("string") }
                }
                "github_write" -> {
                    properties["action"] = choice("commit")
                    listOf("branch", "message").forEach { properties[it] = field("string") }
                    properties["expected_head_sha"] = buildJsonObject {
                        put("type", "string")
                        put("pattern", "^[a-fA-F0-9]{40}$")
                    }
                    properties["files"] = buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 20)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        listOf("path", "content", "expected_blob_sha").forEach { put(it, field("string")) }
                                        put("delete", field("boolean"))
                                    }
                                )
                                put("required", JsonArray(listOf("path").map(::JsonPrimitive)))
                                put(
                                    "oneOf",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("required", JsonArray(listOf(JsonPrimitive("content"))))
                                                put("properties", buildJsonObject { put("delete", buildJsonObject { put("const", false) }) })
                                            },
                                            buildJsonObject {
                                                put("required", JsonArray(listOf("delete", "expected_blob_sha").map(::JsonPrimitive)))
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put("delete", buildJsonObject { put("const", true) })
                                                        put("content", buildJsonObject { put("const", "") })
                                                    }
                                                )
                                            }
                                        )
                                    )
                                )
                            }
                        )
                    }
                }
                "github_pr" -> {
                    properties["action"] = choice("context", "comments", "create", "update", "comment", "reply", "review", "merge")
                    properties["pull_number"] = field("integer")
                    listOf("title", "body", "head", "base", "expected_head_sha", "expected_base_sha").forEach { properties[it] = field("string") }
                    properties["comment_id"] = field("integer")
                    properties["event"] = choice("APPROVE", "REQUEST_CHANGES", "COMMENT")
                    properties["comments"] = buildJsonObject {
                        put("type", "array")
                        put("maxItems", 30)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("path", field("string"))
                                        put("line", field("integer"))
                                        put("side", choice("LEFT", "RIGHT"))
                                        put("body", field("string"))
                                        put("start_line", field("integer"))
                                        put("start_side", choice("LEFT", "RIGHT"))
                                    }
                                )
                                put("required", JsonArray(listOf("path", "line", "body").map(::JsonPrimitive)))
                                put("additionalProperties", false)
                            }
                        )
                    }
                    properties["draft"] = field("boolean")
                    properties["page"] = field("integer")
                    properties["check_page"] = field("integer")
                    properties["include_patches"] = field("boolean")
                    properties["include_files"] = field("boolean")
                    properties["include_checks"] = field("boolean")
                }
                "github_ci" -> {
                    properties["action"] = choice("failure", "workflows", "runs", "run", "jobs", "logs", "artifacts", "dispatch", "rerun", "cancel")
                    listOf("run_id", "job_id", "page").forEach { properties[it] = field("integer") }
                    listOf("workflow_id", "branch", "ref").forEach { properties[it] = field("string") }
                    properties["inputs"] = field("object")
                }
                "github_release" -> {
                    properties["action"] = choice("status", "publish")
                    listOf("tag_name", "target_commitish", "name", "body", "workflow_id").forEach { properties[it] = field("string") }
                    properties["draft"] = field("boolean")
                    properties["prerelease"] = field("boolean")
                }
            }
            val description = when (operation) {
                "github_context" -> "Resolve and pin the selected repository; check remote status, index, changes or request efficiency. Changes requires base_sha; for a pull request's changed files use github_pr context with include_files=true."
                "github_search" -> "Search pinned code and symbols locally. Follow index_cursor until complete."
                "github_read" -> "Read exact source ranges or a saved evidence_id at a pinned commit."
                "github_stage_patch" -> "Validate expected head/blob SHAs and preview exact edits without committing."
                "github_commit" -> "Create a working branch or commit a validated stage_id with head checks."
                "github_write" -> "Stage up to 20 UTF-8 file creations, replacements or deletions for exact user approval and one atomic commit on a working branch. Supply expected_head_sha and expected_blob_sha for existing files. Replacements require content; deletions require delete=true and no content. Never write to the default branch."
                "github_pr" -> "Read PR files, checks and review threads; explicitly post inline review comments, reply to review comments, update/comment/create, or merge after all checks pass. Writes require the current expected head SHA."
                "github_ci" -> "List workflows and runs, inspect run/job status, artifacts and parsed failure logs, or explicitly dispatch/rerun/cancel a workflow."
                else -> "Check release readiness or explicitly publish the selected release."
            } + " Omitted repository uses this task's context. Expand retained results with view_cursor."
            return AgentToolDefinition(
                modelName,
                description,
                buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(properties))
                    put("additionalProperties", false)
                    if (operation == "github_write") {
                        put("required", JsonArray(listOf("branch", "expected_head_sha", "message", "files").map(::JsonPrimitive)))
                    }
                }
            )
        }
    }
}
