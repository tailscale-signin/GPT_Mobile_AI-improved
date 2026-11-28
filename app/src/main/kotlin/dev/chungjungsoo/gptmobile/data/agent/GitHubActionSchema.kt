package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A presentation subset of an authorized contract; execution permissions never change. */
internal object GitHubActionSchema {
    fun authorized(original: AgentToolDefinition, selected: AgentToolDefinition): AgentToolDefinition {
        if (original == selected) return original
        if (compactRead(original) == selected) return selected
        val properties = selected.inputSchema["properties"] as? JsonObject
        val selector = properties?.get("action") as? JsonObject
        val action = ((selector?.get("enum") as? JsonArray)?.singleOrNull() as? JsonPrimitive)?.content
        return if (action != null && project(original, action) == selected) selected else original
    }

    /** Reduce documentation on a tight route while preserving every validation constraint. */
    fun compactRead(tool: AgentToolDefinition): AgentToolDefinition {
        val help = when (GitHubOperationTool.operationName(tool.name)) {
            "github_context" -> "Inspect the selected repository, branch, immutable revision and checks."
            "github_pr" -> "Use a listed pull request action and its returned pull_number."
            "github_ci" -> "Inspect workflows, runs, jobs and logs. Use returned run_id, run_attempt, job_id and expected_head_sha. Runs filter by status. Log ranges span at most 2000 lines."
            else -> return tool
        }
        val fields = tool.inputSchema["properties"] as? JsonObject ?: return tool
        val action = fields["action"] as? JsonObject ?: return tool
        if ((action["enum"] as? JsonArray).orEmpty().size <= 1) return tool
        val compact = fields.mapValues { (name, value) ->
            if (name == "view_cursor" || value !is JsonObject) value else JsonObject(value - "description")
        }
        return tool.copy(
            description = "$help Use an issued view_cursor alone to read a saved page.",
            inputSchema = JsonObject(tool.inputSchema + ("properties" to JsonObject(compact)))
        )
    }

    private fun fields(operation: String, action: String): String? = when (operation) {
        "github_context" -> when (action) {
            "resolve", "status", "index", "efficiency", "repository" -> "ref branch commit_sha refresh"
            "capabilities" -> "stage_id"
            "changes" -> "base_sha ref branch commit_sha include_patches page per_page"
            "branches", "commits" -> "branch ref commit_sha page per_page"
            "branch" -> "branch ref"
            "branch_dependencies" -> "branch fixture_plan engineering_task_id page"
            "commit", "checks" -> "commit_sha ref include_patches page per_page"
            "commit_search", "repositories" -> "query page per_page"
            "recovery" -> "stage_id engineering_task_id fixture_plan branch base_sha commit_sha page"
            else -> null
        }
        "github_ci" -> when (action) {
            "workflows", "definitions" -> "workflow_id ref branch commit_sha page per_page index_cursor"
            "runs" -> "workflow_id branch ref commit_sha head_sha status actor event created page per_page"
            "failure" -> "workflow_id branch ref head_sha run_id run_attempt expected_head_sha status actor event created page per_page"
            "run", "artifacts" -> "run_id run_attempt expected_head_sha page per_page"
            "status" -> "run_id job_id ref branch commit_sha head_sha expected_head_sha page"
            "jobs" -> "run_id job_id run_attempt expected_head_sha page per_page"
            "job" -> "job_id run_id run_attempt expected_head_sha"
            "logs" -> "job_id run_id run_attempt expected_head_sha start_line end_line start_offset"
            "dispatch" -> "workflow_id ref expected_head_sha inputs execution_id"
            "rerun", "rerun_failed_jobs", "rerun_all", "cancel" -> "run_id expected_head_sha execution_id"
            else -> null
        }
        "github_pr", "github_issue" -> when (action) {
            "list" -> if (operation == "github_issue") "state sort direction page per_page after" else "state head base sort direction page per_page"
            "headers", "context" -> "pull_number issue_number expected_head_sha expected_base_sha include_health include_files include_checks include_patches check_page page per_page"
            "comments", "reviews", "history" -> "pull_number issue_number page per_page after"
            "threads" -> "pull_number issue_number page per_page after expected_head_sha"
            "agent_merge_status" -> "pull_number"
            "agent_merge_enable", "agent_merge_update" -> "pull_number expected_head_sha allowed_actions"
            "agent_merge_disable" -> "pull_number expected_head_sha"
            "create" -> "title body head base draft labels assignees"
            "update" -> "pull_number issue_number expected_head_sha expected_updated_at title body state base"
            "merge", "enable_auto_merge" -> "pull_number expected_head_sha expected_base_sha merge_method"
            "ready", "draft", "disable_auto_merge", "unlock" -> "pull_number issue_number expected_head_sha expected_updated_at"
            "comment", "reply", "edit_comment", "edit_review_comment" -> "pull_number issue_number expected_head_sha expected_updated_at comment_id body expected_comment_updated_at"
            "review" -> "pull_number expected_head_sha event body comments"
            "request_reviewers", "remove_reviewers" -> "pull_number expected_head_sha reviewers team_reviewers"
            "resolve_thread", "reopen_thread" -> "pull_number expected_head_sha thread_id"
            "dismiss_review" -> "pull_number expected_head_sha review_id body"
            "add_labels", "remove_label" -> "pull_number issue_number expected_head_sha expected_updated_at labels label"
            "add_assignees", "remove_assignees" -> "pull_number issue_number expected_head_sha expected_updated_at assignees"
            "react", "remove_reaction" -> "pull_number issue_number expected_head_sha expected_updated_at comment_id reaction reaction_id"
            "lock" -> "pull_number issue_number expected_head_sha expected_updated_at lock_reason"
            else -> null
        }
        "github_commit" -> when (action) {
            "branch" -> "branch from_branch expected_head_sha select_created_branch fixture_plan"
            "delete_branch" -> "branch expected_head_sha"
            "delete_fixture_branch" -> "branch expected_head_sha origin_head_sha fixture_plan engineering_task_id"
            "commit", "commit_staged_patch" -> "stage_id branch message expected_head_sha"
            else -> null
        }
        "github_release" -> when (action) {
            "status" -> "tag_name ref workflow_id workflow_run_id execution_id"
            "tags", "list" -> "page per_page"
            "tag" -> "tag_name"
            "detail" -> "tag_name release_id"
            "create_tag" -> "tag_name target_commitish expected_head_sha body execution_id"
            "publish" -> "tag_name target_commitish name body workflow_id workflow_run_id expected_head_sha ref inputs release_strategy draft prerelease execution_id"
            else -> null
        }
        "github_repository" -> when (action) {
            "capabilities" -> ""
            "create" -> "destination_owner destination_repo owner_type visibility description auto_init"
            "fork" -> "destination_owner destination_repo owner_type visibility source_owner source_repo default_branch_only"
            "import" -> "destination_owner destination_repo owner_type visibility source_owner source_repo source_ref source_commit_sha copy_mode expected_head_sha"
            "clone" -> "source_owner source_repo source_ref source_commit_sha expected_head_sha"
            "status" -> "operation operation_id destination_owner destination_repo owner_type visibility expected_repository_id"
            else -> null
        }
        else -> null
    }

    fun project(tool: AgentToolDefinition, action: String): AgentToolDefinition? {
        val operation = GitHubOperationTool.operationName(tool.name) ?: return null
        val original = tool.inputSchema["properties"] as? JsonObject ?: return null
        val selector = original["action"] as? JsonObject ?: return null
        if (JsonPrimitive(action) !in (selector["enum"] as? JsonArray).orEmpty()) return null
        // Single-action source/search contracts already have no unrelated action fields.
        val names = fields(operation, action)?.split(' ')?.toSet() ?: original.keys
        val alternatives = (tool.inputSchema["anyOf"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter {
            val property = (it["properties"] as? JsonObject)?.get("action") as? JsonObject
            JsonPrimitive(action) in (property?.get("enum") as? JsonArray).orEmpty()
        }.map { branch ->
            JsonObject(branch + ("required" to JsonArray(((branch["required"] as? JsonArray).orEmpty() + JsonPrimitive("action")).distinct())))
        }
        val required = alternatives.flatMap { (it["required"] as? JsonArray).orEmpty() }.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        val keep = names + required + setOf("owner", "repo", "action", "verify_live", "view_cursor", "response_detail")
        val properties = original.filterKeys { it in keep }.mapValues { (field, value) ->
            (value as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key != "description" || field == "view_cursor" }) } ?: value
        }.toMutableMap()
        properties["action"] = JsonObject(selector + ("enum" to JsonArray(listOf(JsonPrimitive(action)))))
        val guidance = when (operation to action) {
            "github_read" to "ranges" -> " Choose end_line or max_lines, never both; omit both for a bounded default read. end_line=-1 means bounded EOF."
            "github_ci" to "runs" -> " Runs filters use status; returned runs include conclusion."
            "github_ci" to "logs" -> " Supply an observed job_id. Line bounds must be positive, ordered and span at most 2000 lines."
            else -> ""
        }
        return tool.copy(
            description = "$operation action=$action. Use only this selected action, or discover another action. Current revision guards and approvals still apply. Follow exact continuation_call. For a saved-view continuation containing view_cursor, send that field alone and do not carry over action, owner, repo, page, ref, branch or search_id.$guidance",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties))
                put("additionalProperties", false)
                // Cursor-only recovery must be exclusive in the selected model
                // contract, not just rejected after the model spends another call.
                if ("view_cursor" in properties) {
                    put("oneOf", dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.exclusiveSavedViewAlternatives(JsonObject(properties)))
                }
                if (operation == "github_read") {
                    tool.inputSchema["anyOf"]?.let { put("anyOf", it) }
                    return@buildJsonObject
                }
                val selected = alternatives.ifEmpty {
                    listOf(buildJsonObject { put("required", JsonArray(((tool.inputSchema["required"] as? JsonArray).orEmpty() + JsonPrimitive("action")).distinct())) })
                }
                put("anyOf", JsonArray(listOf(buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive("view_cursor")))) }) + selected))
            }
        )
    }
}
