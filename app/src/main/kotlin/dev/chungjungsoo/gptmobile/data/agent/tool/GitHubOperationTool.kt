package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.AgentToolWorkflowLink
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
        val started = System.nanoTime()
        val result = executeOperation(callId, arguments)
        val trace = buildJsonObject {
            put("schema", "github.trace.v1")
            put("call_id", callId)
            put("tool", operation)
            put("action", (arguments["action"] as? JsonPrimitive)?.content.orEmpty())
            put("response_time_ms", ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
            put("success", !result.isError)
            put("dispatched", result.dispatched)
            put("reused_receipt", result.sharedResult)
            put("pages_returned", if (result.isError) 0 else 1)
            result.errorCode?.let { put("error_code", it) }
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("GitHubToolTrace", trace.toString())
        return result
    }

    private suspend fun executeOperation(callId: String, arguments: JsonObject): AgentToolResult {
        val normalized = try {
            if ("view_cursor" in arguments) arguments else (backend as? GitHubTool)?.normalizeOperationRepository(arguments) ?: arguments
        } catch (invalid: IllegalArgumentException) {
            return AgentToolResult(callId, ToolResultContent.Text(invalid.message.orEmpty()), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        return executeNormalized(callId, normalized)
    }

    private suspend fun executeNormalized(callId: String, arguments: JsonObject): AgentToolResult {
        val wrongHandles = arguments.keys.intersect(setOf("cursor", "next_cursor", "evidence_id", "tracking_id")) - if (operation == "github_read" && "view_cursor" !in arguments) setOf("evidence_id") else emptySet()
        if (wrongHandles.isNotEmpty() && "view_cursor" !in arguments) {
            return AgentToolResult(callId, ToolResultContent.Text("${wrongHandles.sorted().joinToString()} cannot paginate this operation. Use the exact continuation_call returned by this tool. evidence_id only reopens source through github_read; it is never a pagination cursor. Nothing was dispatched."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if ("view_cursor" in arguments) {
            // Providers often carry repository/action defaults into a saved-page call.
            // Validate those against the retained read instead of forcing a model repair loop.
            if (arguments.keys.any { it != "view_cursor" && it !in GitHubResultViews.SAVED_CONTEXT_KEYS }) {
                val evidence = (arguments["evidence_id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
                val restoreEvidence = operation == "github_read" && evidence != null && (arguments["view_cursor"] as? JsonPrimitive)?.content?.contains(':') != true
                val corrected = if (restoreEvidence) {
                    buildJsonObject {
                        put("evidence_id", requireNotNull(evidence))
                        listOf("owner", "repo").forEach { key -> arguments[key]?.let { put(key, it) } }
                    }
                } else {
                    buildJsonObject { put("view_cursor", arguments.getValue("view_cursor")) }
                }
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.arguments.v1")
                            put("operation", operation)
                            put("message", if (restoreEvidence) "evidence_id and view_cursor are different handles. Reopen source evidence with evidence_id alone, without an invented cursor. Nothing was dispatched." else "Use only view_cursor. Repository, commit and range fields belong to a new read, not a saved page. Nothing was dispatched.")
                            put(
                                "correction_call",
                                buildJsonObject {
                                    put("tool", definition.name)
                                    put("arguments", corrected)
                                }
                            )
                        }
                    ),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
            val cursor = (arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return AgentToolResult(callId, ToolResultContent.Text("view_cursor must be the exact saved cursor string."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            if (!cursor.contains(':')) {
                return AgentToolResult(callId, ToolResultContent.Text("Invalid saved-view cursor. Use the exact continuation_call arguments returned by the previous result. To read an evidence_id, omit view_cursor and provide evidence_id alone with the repository context."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            }
            return views.read(scope, operation, definition.name, cursor, callId, JsonObject(arguments - "view_cursor"))
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
        if (operation in setOf("github_context", "github_write", "github_search", "github_read", "github_pr", "github_commit", "github_stage_patch", "github_ci", "github_watch_checks", "github_issue", "github_release")) {
            val errors = ToolArgumentValidator.errors(arguments, definition.inputSchema).ifEmpty {
                GitHubOperationArguments.errors(operation, arguments)
            }
            if (errors.isNotEmpty()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text(ToolArgumentValidator.repairMessage(definition.name, errors, definition.inputSchema)),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        if (operation == "github_write") {
            val paths = mutableSetOf<String>()
            val invalid = (arguments["files"] as? JsonArray).orEmpty().mapIndexedNotNull { index, raw ->
                val path = ((raw as? JsonObject)?.get("path") as? JsonPrimitive)?.content.orEmpty()
                val failure = runCatching { dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient.filePath(path) }.exceptionOrNull()
                when {
                    failure != null -> "files[$index].path: ${failure.message}"
                    !paths.add(path) -> "files[$index].path duplicates another change: $path"
                    else -> null
                }
            }
            if (invalid.isNotEmpty()) return AgentToolResult(callId, ToolResultContent.Text(invalid.joinToString("; ") + ". No files were staged."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if (operation == "github_context" && (arguments["action"] as? JsonPrimitive)?.content == "changes") {
            val base = (arguments["base_sha"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (base.isNullOrBlank()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("github_context action=changes requires a non-blank base_sha (commit SHA or ref). Resolve the intended comparison base first; for PR files use github_pr action=context with include_files=true. Nothing was dispatched."),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        if (operation == "github_search") {
            val query = (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (query.isNullOrBlank() || query.length > 200) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("github_search requires a non-blank query of at most 200 characters. Use mode=paths to find file names; use index_cursor to continue a previous search with the same query."),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        if (operation == "github_pr") {
            val action = (arguments["action"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "context" }
            val requiredFields = when (action) {
                "headers", "context", "comments", "reviews" -> listOf("pull_number")
                "create" -> listOf("title", "head", "base")
                "update", "merge" -> listOf("pull_number", "expected_head_sha")
                "comment" -> listOf("pull_number", "body", "expected_head_sha")
                "review" -> listOf("pull_number", "expected_head_sha")
                "reply" -> listOf("pull_number", "comment_id", "body", "expected_head_sha")
                else -> emptyList()
            }
            val shaActions = setOf("update", "merge", "comment", "reply", "review")
            if (action in shaActions) {
                val expected = (arguments["expected_head_sha"] as? JsonPrimitive)?.content.orEmpty()
                if (!expected.matches(Regex("[a-fA-F0-9]{40}"))) {
                    return AgentToolResult(
                        callId,
                        ToolResultContent.Text("GitHub PR $action requires a current 40-character expected_head_sha. Refresh the PR head before retrying."),
                        true,
                        errorCode = "INVALID_ARGUMENTS",
                        dispatched = false
                    )
                }
            }
            val missing = requiredFields.filter { key ->
                val value = arguments[key]
                value == null || (value is JsonPrimitive && value.content.isBlank())
            }
            if (missing.isNotEmpty()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("GitHub PR $action requires: " + missing.joinToString(", ") + ". For an unknown PR number, use github_pr action=list to discover pull requests, then retry with the observed pull_number. Nothing was dispatched."),
                    true,
                    errorCode = "INVALID_ARGUMENTS",
                    dispatched = false
                )
            }
        }
        val native = nativeArguments(operation, arguments)
            ?: return AgentToolResult(callId, ToolResultContent.Text("Unsupported GitHub operation."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val result = backend.execute(callId, native)
        val data = when (val content = result.content) {
            is ToolResultContent.Json -> content.value as? JsonObject
            is ToolResultContent.Text -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
            else -> null
        }
        val paged = if (!result.isError && data?.get("has_more") == JsonPrimitive(true) && "continuation" !in data && operation != "github_search") {
            result.copy(content = ToolResultContent.Json(JsonObject(data + ("continuation" to JsonObject(native + ("page" to JsonPrimitive(((arguments["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1) + 1)))))))
        } else {
            result
        }
        val outputAllowance = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ToolOutputAllowance]?.bytes ?: 8 * 1024
        return views.present(scope, operation, definition.name, paged, arguments, maxPageBytes = outputAllowance)
    }

    companion object {
        val names = setOf("github_context", "github_search", "github_read", "github_pr", "github_ci", "github_watch_checks", "github_stage_patch", "github_commit", "github_write", "github_release", "github_issue")
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
                    "capabilities" -> "write_capabilities"
                    "branches" -> "list_branches"
                    "branch" -> "branch_context"
                    "branch_dependencies" -> "branch_dependencies"
                    "commits" -> "list_commits"
                    "commit" -> "get_commit_detail"
                    "commit_search" -> "search_commits"
                    "checks" -> "get_commit_status_summary"
                    "repository" -> "repository_details"
                    "repositories" -> "search_repositories"
                    "recovery" -> "recovery_context"
                    else -> return null
                }
                "github_search" -> "search_repository"
                "github_read" -> "read_ranges"
                "github_stage_patch" -> "stage_patch"
                "github_watch_checks" -> "watch_checks"
                "github_commit" -> when (requested) {
                    "", "commit", "commit_staged_patch" -> "commit_staged_patch"
                    "branch" -> "create_branch"
                    "delete_branch" -> "delete_branch"
                    else -> return null
                }
                "github_write" -> when (requested) {
                    "", "commit" -> "commit_files"
                    else -> return null
                }
                "github_pr" -> when (requested) {
                    "headers" -> "pr_headers"
                    "", "context" -> "pr_context"
                    "list" -> "list_pull_requests"
                    "comments" -> "get_pull_request_comments"
                    "reviews" -> "pull_request_reviews"
                    "create" -> "create_pull_request"
                    "update" -> "update_pull_request"
                    "comment" -> "create_issue_comment"
                    "reply" -> "reply_pull_request_comment"
                    "review" -> if ((args["comments"] as? JsonArray).isNullOrEmpty()) "submit_pull_request_review" else "create_pull_request_review"
                    "merge" -> "merge_pull_request"
                    else -> return null
                }
                "github_ci" -> when (requested) {
                    "" -> "list_workflows"
                    "failure" -> "ci_failure_digest"
                    "workflows" -> "list_workflows"
                    "definitions" -> "workflow_catalog"
                    "runs" -> "list_workflow_runs"
                    "run" -> "get_workflow_run"
                    "status" -> when {
                        args["job_id"] != null -> "get_workflow_job"
                        args["run_id"] != null -> "get_workflow_run"
                        else -> "get_commit_status_summary"
                    }
                    "jobs" -> if (args["job_id"] != null && args["run_id"] == null) "get_workflow_job" else "list_workflow_jobs"
                    "job" -> "get_workflow_job"
                    "logs" -> "get_job_logs"
                    "artifacts" -> "list_workflow_artifacts"
                    "dispatch" -> "dispatch_workflow"
                    "rerun", "rerun_failed_jobs" -> "rerun_failed_jobs"
                    "rerun_all" -> "rerun_workflow"
                    "cancel" -> "cancel_workflow"
                    else -> return null
                }
                "github_release" -> when (requested) {
                    "", "status" -> "release_status"
                    "publish" -> "publish_release"
                    "tags" -> "list_tags"
                    "tag" -> "tag_context"
                    "create_tag" -> "create_tag"
                    "list" -> "list_releases"
                    "detail" -> "get_release_by_tag"
                    else -> return null
                }
                "github_issue" -> when (requested) {
                    "headers" -> "issue_headers"
                    "" -> if (args["issue_number"] == null) "list_issues" else "issue_context"
                    "list" -> "list_issues"
                    "context" -> "issue_context"
                    "comments" -> "issue_comments"
                    "history" -> "issue_events"
                    "create" -> "create_issue"
                    "update" -> "update_issue"
                    else -> return null
                }
                else -> return null
            }
            val translated = args.toMutableMap()
            if (action == "create_branch") args["from_branch"]?.let { translated["base"] = it }
            if (action == "changed_since") args["base_sha"]?.let { translated["base"] = it }
            if (action == "pr_context") args["expected_head_sha"]?.let { translated["expected_head"] = it }
            if (action == "get_commit_status_summary" && args["ref"] == null) args["branch"]?.let { translated["ref"] = it }
            translated["action"] = JsonPrimitive(action)
            return JsonObject(translated)
        }

        fun taskTools(task: String): Set<String> {
            val text = task.lowercase(java.util.Locale.ROOT).replace("github_pull_request", "github_pr")
            val explicitlyNamed = names.filter { name ->
                Regex("(?<![a-z0-9_])" + Regex.escape(name) + "(?:__[a-z0-9_-]+)?(?![a-z0-9_])").containsMatchIn(text)
            }
            val issueRequest = Regex("\\b(?:list|show|inspect|read|create|update|close|reopen|triage)\\s+(?:(?:the|all|open|closed|github|repository)\\s+)*issues?\\b|\\bissue\\s*#?\\d+\\b").containsMatchIn(text)
            if (explicitlyNamed.isEmpty() && !isGitHubTask(text) && !issueRequest && !Regex("\\b(code|source|ci|pr)\\b").containsMatchIn(text)) return emptySet()
            val selected = linkedSetOf("github_context", "github_search", "github_read")
            selected += explicitlyNamed
            if (Regex("\\b(pr|pull request|review|merge)\\b").containsMatchIn(text)) selected += "github_pr"
            if (Regex("\\b(ci|build|test failure|workflow|pipeline)\\b").containsMatchIn(text)) selected += "github_ci"
            if (Regex("\\b(fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|patch(?:es|ed|ing)?|edit(?:s|ed|ing)?|changes?|implement(?:ation|ed|ing)?)\\b").containsMatchIn(text)) selected += "github_stage_patch"
            if (Regex("\\b(commit|committing|push|pushing|branch)\\b").containsMatchIn(text)) selected += "github_commit"
            if (Regex("\\b(write|writing|create files?|edit(?:s|ed|ing)?|modify|changes?|fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|add(?:ed|ing)?|updat(?:e|ed|ing)|delet(?:e|ed|ing)|remove files?|commit|committing|push|pushing|patch(?:es|ed|ing)?|implement(?:ation|ed|ing)?|apply changes)\\b").containsMatchIn(text)) selected += "github_write"
            if (Regex("\\bissues?\\b").containsMatchIn(text) || issueRequest) selected += "github_issue"
            if ("release" in text || "tag" in text) selected += "github_release"
            if (Regex("\\b(watch|monitor|subscribe|ci|checks)\\b").containsMatchIn(text)) selected += "github_watch_checks"
            return selected
        }

        fun definition(operation: String, modelName: String = operation): AgentToolDefinition {
            require(operation in names)
            fun sourceName(source: String): String = if (source == operation) {
                modelName
            } else if ("__" in modelName) {
                source + "__" + modelName.substringAfter("__")
            } else {
                source
            }
            fun link(action: String, source: String, sourceAction: String, purpose: String) = AgentToolWorkflowLink(action, sourceName(source), sourceAction, purpose)
            val workflowLinks = when (operation) {
                "github_write" -> listOf(link("commit", "github_read", "ranges", "Inspect existing file content and blob SHA before preparing a replacement."))
                "github_stage_patch" -> listOf(link("default", "github_read", "ranges", "Inspect the pinned file before selecting an exact patch anchor."))
                "github_commit" -> listOf(link("commit_staged_patch", "github_stage_patch", "default", "A staged or restored patch can provide the stage ID."))
                "github_pr" -> listOf(link("review", "github_pr", "context", "Read the current PR head before submitting a review."), link("merge", "github_pr", "context", "Inspect current PR merge health before requesting a merge."), link("merge", "github_ci", "status", "Inspect current checks before requesting a merge."))
                "github_ci" -> listOf(link("logs", "github_ci", "jobs", "Job listings can provide the job ID required for logs."))
                "github_watch_checks" -> listOf(link("default", "github_pr", "context", "A PR context can identify the pull request and current head."), link("default", "github_ci", "runs", "Workflow runs can provide a run ID for a run-specific watch."))
                else -> emptyList()
            }
            fun field(type: String) = buildJsonObject { put("type", type) }
            fun integer(min: Int = 1, max: Int = Int.MAX_VALUE, description: String = "") = buildJsonObject {
                put("type", "integer")
                put("minimum", min)
                put("maximum", max)
                if (description.isNotBlank()) put("description", description)
            }
            fun choice(vararg names: String) = buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(names.map(::JsonPrimitive)))
            }
            val properties = linkedMapOf("owner" to field("string"), "repo" to field("string"), "commit_sha" to field("string"), "view_cursor" to field("string"))
            when (operation) {
                "github_context" -> {
                    properties["action"] = choice("resolve", "status", "index", "changes", "efficiency", "capabilities", "branches", "branch", "branch_dependencies", "commits", "commit", "commit_search", "checks", "repository", "repositories", "recovery")
                    properties["ref"] = field("string")
                    properties["branch"] = field("string")
                    properties["query"] = field("string")
                    properties["base_sha"] = field("string")
                    properties["stage_id"] = field("string")
                    properties["refresh"] = field("boolean")
                    properties["page"] = field("integer")
                    properties["per_page"] = integer(1, 100, "Commit files per page; default 30.")
                    properties["include_patches"] = field("boolean")
                }
                "github_search" -> {
                    properties["path_prefix"] = field("string")
                    properties["query"] = buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 200)
                    }
                    properties["mode"] = choice("literal", "definitions", "references", "paths", "tests", "patterns")
                    properties["index_cursor"] = field("integer")
                    properties["search_id"] = field("string")
                    properties["file_extension"] = field("string")
                    properties["path_suffix"] = field("string")
                    properties["case_sensitive"] = field("boolean")
                    properties["fuzzy_match"] = field("boolean")
                    properties["fuzzy_threshold"] = buildJsonObject {
                        put("type", "number")
                        put("minimum", 0.5)
                        put("maximum", 1.0)
                    }
                    listOf("min_lines", "max_lines").forEach { properties[it] = field("integer") }
                    properties["context_lines"] = integer(0, 100)
                    properties["max_new_blobs"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 100)
                        put("description", "New blobs; default 16.")
                    }
                    properties["batch_size"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 1000)
                        put("description", "Candidate files; default 1000.")
                    }
                    properties["page"] = field("integer")
                }
                "github_read" -> {
                    properties["action"] = choice("ranges")
                    properties["evidence_id"] = field("string")
                    properties["ranges"] = buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 8)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("path", field("string"))
                                        put("start_line", integer())
                                        put("end_line", integer())
                                        put("start_offset", integer(0))
                                    }
                                )
                                put("required", JsonArray(listOf("path", "start_line", "end_line").map(::JsonPrimitive)))
                            }
                        )
                    }
                }
                "github_stage_patch" -> {
                    listOf("target_file", "search_snippet", "replacement_snippet").forEach { properties[it] = field("string") }
                    properties["restore_stage_id"] = field("string")
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
                    properties["action"] = choice("commit", "commit_staged_patch", "branch", "delete_branch")
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
                                        put(
                                            "path",
                                            buildJsonObject {
                                                put("type", "string")
                                                put("minLength", 1)
                                                put("description", "Required for every file: an observed repository-relative file path, never a directory or an inferred path.")
                                            }
                                        )
                                        listOf("content", "expected_blob_sha").forEach { put(it, field("string")) }
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
                    properties["action"] = choice("list", "headers", "context", "comments", "reviews", "create", "update", "comment", "reply", "review", "merge")
                    properties["pull_number"] = field("integer")
                    properties["state"] = choice("open", "closed", "all")
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
                    properties["include_health"] = field("boolean")
                }
                "github_ci" -> {
                    properties["action"] = choice("failure", "workflows", "definitions", "runs", "run", "status", "jobs", "job", "logs", "artifacts", "dispatch", "rerun", "rerun_failed_jobs", "rerun_all", "cancel")
                    properties["execution_id"] = buildJsonObject {
                        put("type", "string")
                        put("maxLength", 80)
                        put("description", "Reuse for recovery. Supply a new ID only for a separately requested execution after reconciling the previous receipt; never change it to retry a lost response.")
                    }
                    listOf("run_id", "job_id").forEach { key ->
                        properties[key] = buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("description", if (key == "run_id") "Run ID from action=runs; different from workflow_id." else "Job ID from action=jobs; never a step number.")
                        }
                    }
                    properties["page"] = integer(1, 1000)
                    properties["index_cursor"] = integer(0)
                    properties["per_page"] = integer(1, 100)
                    properties["start_line"] = integer()
                    properties["end_line"] = integer()
                    properties["start_offset"] = integer(0)
                    properties["expected_head_sha"] = field("string")
                    listOf("workflow_id", "branch", "ref", "status", "actor", "event", "created", "head_sha").forEach { properties[it] = field("string") }
                    properties["workflow_id"] = buildJsonObject {
                        put("type", JsonArray(listOf("string", "integer").map(::JsonPrimitive)))
                        put("minimum", 1)
                        put("minLength", 1)
                        put("description", "Workflow ID or YAML filename from workflows; never a run ID.")
                    }
                    properties["inputs"] = field("object")
                }
                "github_watch_checks" -> {
                    properties["pr_number"] = field("integer")
                    properties["run_id"] = field("integer")
                    properties["commit_sha"] = field("string")
                }
                "github_release" -> {
                    properties["execution_id"] = buildJsonObject {
                        put("type", "string")
                        put("maxLength", 80)
                        put("description", "Reuse for recovery; a separately requested publication needs a new ID after reconciling the earlier receipt.")
                    }
                    properties["action"] = choice("status", "publish", "tags", "tag", "create_tag", "list", "detail")
                    listOf("tag_name", "target_commitish", "name", "body", "workflow_id", "expected_head_sha", "ref").forEach { properties[it] = field("string") }
                    properties["workflow_run_id"] = integer(1, description = "Exact run ID from the accepted release dispatch receipt.")
                    properties["inputs"] = field("object")
                    properties["release_strategy"] = choice("auto", "workflow", "direct")
                    properties["draft"] = field("boolean")
                    properties["prerelease"] = field("boolean")
                    properties["page"] = field("integer")
                }
                "github_issue" -> {
                    properties["action"] = choice("list", "headers", "context", "comments", "history", "create", "update")
                    properties["issue_number"] = field("integer")
                    properties["page"] = field("integer")
                    listOf("title", "body", "expected_updated_at").forEach { properties[it] = field("string") }
                    properties["state"] = choice("open", "closed", "all")
                }
            }
            val description = when (operation) {
                "github_context" -> "Inspect repo, branches, commits, checks, evidence. changes needs base_sha; commit returns 30 summaries. include_patches adds excerpts."
                "github_search" -> "Search pinned code/paths. patterns: * between literals; fuzzy: one identifier. Check coverage."
                "github_read" -> "Read pinned ranges (1-based, <=2000 lines), evidence_id or view_cursor alone. Discover paths first; expired handles: github_context action=recovery."
                "github_stage_patch" -> "Stage an exact replacement with target_file, search_snippet and replacement_snippet, or SHA-checked edits. Reads the pinned blob and rejects ambiguous anchors. Returns immutable version/stage_id and preserves publication receipts. Supply restore_stage_id alone to select a saved local patch at the same branch/base. Commit on a permitted working branch using its current expected_head_sha."
                "github_commit" -> "Create a working branch, commit a validated stage_id, or delete_branch with expected_head_sha after dependency inspection. Deletion blocks selected/default/protected branches, unmerged commits, open PRs and active runs. GitHub deletion is not an atomic SHA lease."
                "github_write" -> "Every files item requires a repository-relative path (for example app/src/main/kotlin/.../MainActivity.kt), never an absolute path or directory. Stage up to 20 UTF-8 file creations, replacements or deletions for exact user approval and one atomic commit on a working branch. Supply expected_head_sha and expected_blob_sha for existing files. Replacements require content; deletions require delete=true and no content. Never write to the default branch."
                "github_pr" -> "Canonical pull-request tool (also known as github_pull_request). Use list to discover pull_number. Prefer headers for title/state/head/base only; it uses a minimal API field selection with no bodies or threads. Use context for files/checks and include_health=true for merge readiness. Use github_watch_checks once for ongoing CI; do not repeatedly poll from the model. Writes require the current expected head SHA."
                "github_ci" -> "Discover workflows, then runs (100/page), then jobs using run_id. job/logs require job_id; artifacts requires run_id. status inspects run_id/job_id or selected commit/ref checks. Empty collections are valid. definitions reads pinned YAML outlines. Logs use start_line/end_line/start_offset continuations. dispatch requires a discovered workflow_id and explicit ref; a run_id is not a workflow_id. rerun/rerun_failed_jobs retries failed jobs only; rerun_all retries the full completed run. Never cancel an active run to force a retry. Follow continuation_call exactly."
                "github_watch_checks" -> "Subscribe once to background CI updates for pr_number and/or run_id in this saved chat. Pins the current head and posts native chat status updates without model polling turns. A moved PR head requires a new subscription."
                "github_issue" -> "List issues (PRs excluded). Prefer headers for title/state/updated_at without bodies or comments; use context/comments/history only when those details are needed. Create/update requires exact approval; update needs expected_updated_at. Timeline is not a full body-edit history."
                else -> "Inspect release notes (detail), tags/annotations (tag), paginated tags/list, create_tag or explicitly publish the selected release."
            } + " Follow continuation_call. Pair owner+repo."
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
                },
                workflowLinks = workflowLinks
            )
        }
    }
}
