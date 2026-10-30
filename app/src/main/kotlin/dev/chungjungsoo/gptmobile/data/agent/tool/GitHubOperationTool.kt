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
            if (arguments.size != 1) {
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
        if (operation in setOf("github_context", "github_write", "github_search", "github_read", "github_pr", "github_commit", "github_stage_patch", "github_ci", "github_issue", "github_release")) {
            val errors = ToolArgumentValidator.errors(arguments, definition.inputSchema)
            if (errors.isNotEmpty()) {
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("Invalid $operation arguments: " + errors.joinToString("; ") + ". Correct the fields before retrying; this request was not dispatched."),
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
                    ToolResultContent.Text("GitHub PR $action requires: " + missing.joinToString(", ") + ". Correct the request before retrying; nothing was dispatched."),
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
        return views.present(scope, operation, definition.name, paged, arguments)
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
                    "", "commit" -> "commit_staged_patch"
                    "branch" -> "create_branch"
                    "delete_branch" -> "delete_branch"
                    else -> return null
                }
                "github_write" -> when (requested) {
                    "", "commit" -> "commit_files"
                    else -> return null
                }
                "github_pr" -> when (requested) {
                    "", "context" -> "pr_context"
                    "comments" -> "get_pull_request_comments"
                    "reviews" -> "pull_request_reviews"
                    "create" -> "create_pull_request"
                    "update" -> "update_pull_request"
                    "comment" -> "create_issue_comment"
                    "reply" -> "reply_pull_request_comment"
                    "review" -> "create_pull_request_review"
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
                    "jobs" -> if (args["job_id"] != null && args["run_id"] == null) "get_workflow_job" else "list_workflow_jobs"
                    "job" -> "get_workflow_job"
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
                    "tags" -> "list_tags"
                    "tag" -> "tag_context"
                    "create_tag" -> "create_tag"
                    "list" -> "list_releases"
                    "detail" -> "get_release_by_tag"
                    else -> return null
                }
                "github_issue" -> when (requested) {
                    "", "context" -> "issue_context"
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
            translated["action"] = JsonPrimitive(action)
            return JsonObject(translated)
        }

        fun taskTools(task: String): Set<String> {
            val text = task.lowercase(java.util.Locale.ROOT)
            val explicitlyNamed = names.filter { name ->
                Regex("(?<![a-z0-9_])" + Regex.escape(name) + "(?:__[a-z0-9_-]+)?(?![a-z0-9_])").containsMatchIn(text)
            }
            if (explicitlyNamed.isEmpty() && !isGitHubTask(text) && !Regex("\\b(code|source|ci|pr)\\b").containsMatchIn(text)) return emptySet()
            val selected = linkedSetOf("github_context", "github_search", "github_read")
            selected += explicitlyNamed
            if (Regex("\\b(pr|pull request|review|merge)\\b").containsMatchIn(text)) selected += "github_pr"
            if (Regex("\\b(ci|build|test failure|workflow|pipeline)\\b").containsMatchIn(text)) selected += "github_ci"
            if (Regex("\\b(fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|patch(?:es|ed|ing)?|edit(?:s|ed|ing)?|changes?|implement(?:ation|ed|ing)?)\\b").containsMatchIn(text)) selected += "github_stage_patch"
            if (Regex("\\b(commit|committing|push|pushing|branch)\\b").containsMatchIn(text)) selected += "github_commit"
            if (Regex("\\b(write|writing|create files?|edit(?:s|ed|ing)?|modify|changes?|fix(?:es|ed|ing)?|repair(?:s|ed|ing)?|correct(?:ions?|ed|ing)?|add(?:ed|ing)?|updat(?:e|ed|ing)|delet(?:e|ed|ing)|remove files?|commit|committing|push|pushing|patch(?:es|ed|ing)?|implement(?:ation|ed|ing)?|apply changes)\\b").containsMatchIn(text)) selected += "github_write"
            if ("issue" in text) selected += "github_issue"
            if ("release" in text || "tag" in text) selected += "github_release"
            if (Regex("\\b(watch|monitor|subscribe|ci|checks)\\b").containsMatchIn(text)) selected += "github_watch_checks"
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
                    properties["action"] = choice("resolve", "status", "index", "changes", "efficiency", "capabilities", "branches", "branch", "branch_dependencies", "commits", "commit", "commit_search", "checks", "repository", "repositories", "recovery")
                    properties["ref"] = field("string")
                    properties["branch"] = field("string")
                    properties["query"] = field("string")
                    properties["base_sha"] = buildJsonObject {
                        put("type", "string")
                        put("description", "Comparison base for changes.")
                    }
                    properties["stage_id"] = field("string")
                    properties["refresh"] = field("boolean")
                    properties["page"] = field("integer")
                }
                "github_search" -> {
                    properties["path_prefix"] = field("string")
                    properties["query"] = buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 200)
                    }
                    properties["mode"] = choice("literal", "definitions", "references", "paths", "tests")
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
                    listOf("min_lines", "max_lines", "context_lines").forEach { properties[it] = field("integer") }
                    properties["max_new_blobs"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 32)
                        put("description", "Downloads per call; default 16, parallel 4.")
                    }
                    properties["batch_size"] = buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 1000)
                        put("description", "Candidate limit; network/byte budgets also apply.")
                    }
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
                    listOf("target_file", "search_snippet", "replacement_snippet").forEach { properties[it] = field("string") }
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
                    properties["action"] = choice("commit", "branch", "delete_branch")
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
                    properties["action"] = choice("context", "comments", "reviews", "create", "update", "comment", "reply", "review", "merge")
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
                    properties["include_health"] = field("boolean")
                }
                "github_ci" -> {
                    properties["action"] = choice("failure", "workflows", "definitions", "runs", "run", "jobs", "job", "logs", "artifacts", "dispatch", "rerun", "cancel")
                    listOf("run_id", "job_id", "page", "index_cursor", "per_page").forEach { properties[it] = field("integer") }
                    listOf("workflow_id", "branch", "ref", "status", "actor", "event", "created", "head_sha").forEach { properties[it] = field("string") }
                    properties["inputs"] = field("object")
                }
                "github_watch_checks" -> {
                    properties["pr_number"] = field("integer")
                    properties["run_id"] = field("integer")
                }
                "github_release" -> {
                    properties["action"] = choice("status", "publish", "tags", "tag", "create_tag", "list", "detail")
                    listOf("tag_name", "target_commitish", "name", "body", "workflow_id", "expected_head_sha").forEach { properties[it] = field("string") }
                    properties["draft"] = field("boolean")
                    properties["prerelease"] = field("boolean")
                    properties["page"] = field("integer")
                }
                "github_issue" -> {
                    properties["action"] = choice("context", "comments", "history", "create", "update")
                    properties["issue_number"] = field("integer")
                    properties["page"] = field("integer")
                    listOf("title", "body", "expected_updated_at").forEach { properties[it] = field("string") }
                    properties["state"] = choice("open", "closed")
                }
            }
            val description = when (operation) {
                "github_context" -> "Inspect repository, branches/rules/dependencies, commits/search, checks or retained evidence/stages. Changes requires base_sha."
                "github_search" -> "Pinned literal/definitions/references/paths/tests search. Paths uses tree only; *=all paths. Follow continuations; check coverage/skips. Fuzzy: one identifier, context: lexical."
                "github_read" -> "Read pinned ranges or evidence_id. Use view_cursor only for saved pages."
                "github_stage_patch" -> "Inspect and stage one exact replacement in a single call using target_file, search_snippet and replacement_snippet. Reads the complete pinned blob internally; rejects ambiguous anchors. Alternatively supply SHA-checked edits. Returns a bounded preview and stage_id; no commit occurs."
                "github_commit" -> "Create a working branch, commit a validated stage_id, or delete_branch with expected_head_sha after dependency inspection. Deletion blocks selected/default/protected branches, unmerged commits, open PRs and active runs. GitHub deletion is not an atomic SHA lease."
                "github_write" -> "Every files item requires a repository-relative path (for example app/src/main/kotlin/.../MainActivity.kt), never an absolute path or directory. Stage up to 20 UTF-8 file creations, replacements or deletions for exact user approval and one atomic commit on a working branch. Supply expected_head_sha and expected_blob_sha for existing files. Replacements require content; deletions require delete=true and no content. Never write to the default branch."
                "github_pr" -> "Read PR files, checks and review threads; explicitly post inline review comments, reply to review comments, update/comment/create, or merge after all checks pass. Writes require the current expected head SHA."
                "github_ci" -> "Discover workflows, then inspect runs (100/page; status=failure filters), job steps (job_id), artifacts and failures. action=definitions reads cached, commit-pinned YAML outlines (triggers, inputs, dependencies, artifacts); follow every continuation and inspect parser warnings. Dispatch requires an explicit discovered workflow_id (numeric ID, filename or .github/workflows/file.yml) and ref; never guess a workflow or dispatch all workflows. Follow continuation_call for further pages."
                "github_watch_checks" -> "Subscribe once to background CI updates for pr_number and/or run_id in this saved chat. Pins the current head and posts native chat status updates without model polling turns. A moved PR head requires a new subscription."
                "github_issue" -> "Read complete issue metadata, comments or timeline history. Create or update with exact approval; update requires expected_updated_at from the issue. Timeline is not a full history of body edits."
                else -> "Inspect release notes (detail), tags/annotations (tag), paginated tags/list, create_tag or explicitly publish the selected release."
            } + " Owner+repo together or omitted. Follow continuation_call exactly."
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
