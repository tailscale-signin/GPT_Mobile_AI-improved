package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Action-specific admission after schema validation, before any repository request. */
internal object GitHubOperationArguments {
    fun errors(operation: String, args: JsonObject): List<String> {
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        val errors = mutableListOf<String>()
        fun required(vararg keys: String) {
            keys.filter { text(it).isBlank() }.forEach { errors += "$it is required" }
        }
        fun positive(key: String) {
            if (key in args && text(key).toLongOrNull()?.let { it > 0 } != true) errors += "$key must be a positive integer ID returned by discovery"
        }
        when (operation) {
            "github_watch_checks" -> {
                positive("pr_number")
                positive("run_id")
                if ("pr_number" !in args && "run_id" !in args) errors += "provide pr_number or run_id from PR or workflow discovery"
            }
            "github_read" -> {
                if (text("evidence_id").isBlank() && (args["ranges"] as? JsonArray).isNullOrEmpty()) {
                    errors += "provide ranges=[{path, start_line, end_line}] or a retained evidence_id; discover exact paths with github_search mode=paths"
                }
                (args["ranges"] as? JsonArray).orEmpty().forEachIndexed { index, item ->
                    val range = item as? JsonObject ?: return@forEachIndexed
                    val path = (range["path"] as? JsonPrimitive)?.content.orEmpty()
                    if (runCatching { GitHubWorkspaceClient.filePath(path) }.isFailure) errors += "ranges[$index].path must be a relative repository file path"
                    val start = (range["start_line"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val end = (range["end_line"] as? JsonPrimitive)?.content?.toIntOrNull()
                    if (start == null || end == null || start < 1 || end < start || end.toLong() - start >= 2000) {
                        errors += "ranges[$index] needs positive, ordered start_line/end_line spanning at most 2000 lines"
                    }
                }
            }
            "github_commit" -> {
                when (text("action")) {
                    "branch" -> required("branch")
                    "delete_branch" -> required("branch", "expected_head_sha")
                    else -> required("stage_id", "expected_head_sha", "branch", "message")
                }
                if ("expected_head_sha" in args && !text("expected_head_sha").matches(Regex("[a-fA-F0-9]{40}"))) errors += "expected_head_sha must be the current 40-character branch head"
            }
            "github_stage_patch" -> {
                if ("restore_stage_id" in args) {
                    required("restore_stage_id")
                    if (listOf("edits", "target_file", "search_snippet", "replacement_snippet").any { it in args }) errors += "restore_stage_id cannot be combined with new edits"
                    return errors
                }
                val compound = listOf("target_file", "search_snippet", "replacement_snippet").any { it in args }
                if (compound) {
                    required("target_file", "search_snippet")
                    if ("replacement_snippet" !in args) errors += "replacement_snippet is required (empty text deletes the exact anchor)"
                    if ("edits" in args) errors += "use either a target_file replacement or edits, not both"
                } else if ((args["edits"] as? JsonArray).isNullOrEmpty()) {
                    errors += "provide target_file, search_snippet and replacement_snippet, or SHA-checked edits; select the intended repository revision before staging"
                }
            }
            "github_ci" -> {
                positive("run_id")
                positive("job_id")
                (args["workflow_id"] as? JsonPrimitive)?.let { id ->
                    if (!id.isString && id.content.toLongOrNull()?.let { it > 0 } != true) errors += "workflow_id must be a positive integer or a discovered workflow filename"
                }
                when (text("action")) {
                    "run", "artifacts", "rerun", "rerun_failed_jobs", "rerun_all", "cancel" -> required("run_id")
                    "job", "logs" -> required("job_id")
                    "jobs" -> if ("run_id" !in args && "job_id" !in args) errors += "jobs requires run_id to list jobs, or job_id to inspect one job; obtain IDs from action=runs"
                    "dispatch" -> required("workflow_id", "ref")
                }
                val start = text("start_line").ifBlank { "1" }.toLongOrNull()
                val end = text("end_line").toLongOrNull()
                if (start == null || start !in 1..Int.MAX_VALUE.toLong() || end != null && (end < start || end - start >= 2000)) {
                    errors += "log start_line/end_line must be positive and ordered, spanning at most 2000 lines"
                }
            }
            "github_pr" -> {
                positive("pull_number")
                positive("comment_id")
                if (text("action") == "review") {
                    val inline = (args["comments"] as? JsonArray).orEmpty()
                    if (inline.isEmpty() && text("event") != "APPROVE" && text("body").isBlank()) {
                        errors += "a COMMENT or REQUEST_CHANGES review needs body text or inline comments; APPROVE may omit body"
                    }
                }
            }
            "github_issue" -> {
                positive("issue_number")
                when (text("action")) {
                    "context", "comments", "history" -> required("issue_number")
                    "create" -> required("title")
                    "update" -> {
                        required("issue_number", "expected_updated_at")
                        if (text("state") == "all") errors += "state=all is only valid for listing issues"
                    }
                }
            }
        }
        return errors
    }
}
