package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Action-specific admission after schema validation, before any repository request. */
internal object GitHubOperationArguments {
    /** Advertise the same conditional prerequisites enforced before dispatch. */
    fun schemaAlternatives(operation: String): JsonArray? {
        val alternatives: List<Pair<List<String>, List<String>>> = when (operation) {
            "github_commit" -> listOf(
                listOf("branch", "delete_branch") to listOf("branch", "expected_head_sha"),
                listOf("delete_fixture_branch") to listOf("branch", "expected_head_sha", "origin_head_sha"),
                listOf("commit", "commit_staged_patch") to listOf("stage_id", "branch", "message", "expected_head_sha")
            )
            "github_pr" -> listOf(listOf("list") to emptyList(), listOf("headers", "context", "comments", "reviews") to listOf("pull_number"), listOf("create") to listOf("title", "head", "base"), listOf("update", "merge", "review") to listOf("pull_number", "expected_head_sha"), listOf("comment") to listOf("pull_number", "body", "expected_head_sha"), listOf("reply") to listOf("pull_number", "comment_id", "body", "expected_head_sha"))
            "github_issue" -> listOf(listOf("list") to emptyList(), listOf("headers", "context", "comments", "history") to listOf("issue_number"), listOf("create") to listOf("title"), listOf("update") to listOf("issue_number", "expected_updated_at"))
            "github_ci" -> listOf(listOf("failure", "workflows", "definitions", "runs", "status") to emptyList(), listOf("run", "artifacts", "rerun", "rerun_failed_jobs", "rerun_all", "cancel", "jobs") to listOf("run_id"), listOf("job", "logs", "jobs") to listOf("job_id"), listOf("dispatch") to listOf("workflow_id", "ref", "expected_head_sha"))
            else -> return null
        }
        val default = when (operation) {
            "github_commit" -> "commit"
            "github_pr" -> "context"
            "github_issue" -> "list"
            else -> "workflows"
        }
        return JsonArray(
            listOf(buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive("view_cursor")))) }) + alternatives.map { (actions, required) ->
                buildJsonObject {
                    put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray(actions.map(::JsonPrimitive))) }) })
                    put("required", JsonArray((required + if (default in actions) emptyList() else listOf("action")).map(::JsonPrimitive)))
                }
            }
        )
    }
    fun errors(operation: String, args: JsonObject): List<String> {
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        val errors = mutableListOf<String>()
        fun required(vararg keys: String) {
            keys.filter { text(it).isBlank() }.forEach { errors += "$it is required" }
        }
        fun positive(key: String) {
            if (key in args && text(key).toLongOrNull()?.let { it > 0 } != true) errors += "$key must be a positive integer ID returned by discovery"
        }
        fun unused(vararg keys: String) {
            keys.filter { it in args }.forEach { errors += "$it is not used by this action; choose the intended action or remove the field before dispatch" }
        }
        for (key in listOf("branch", "from_branch")) {
            if (key in args && runCatching { dev.chungjungsoo.gptmobile.data.github.normalizeGitHubBranch(text(key)) }.isFailure) {
                errors += "$key must be a valid branch name or refs/heads/name; do not use a tag or revision expression"
            }
        }
        for (key in listOf("commit_sha", "expected_head_sha", "expected_base_sha", "head_sha", "tree_sha", "origin_head_sha")) {
            if (key in args && (!text(key).matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) || text(key).all { it == '0' })) {
                errors += "$key must be a real commit/tree SHA, not a placeholder; discover the current head with github_context action=branch before retrying"
            }
        }
        when (operation) {
            "github_context" -> {
                if ("fixture_plan" in args) {
                    if (text("action") != "recovery") errors += "fixture_plan is only used by read-only fixture recovery"
                    required("branch", "base_sha", "commit_sha")
                    unused("ref", "stage_id")
                }
            }
            "github_watch_checks" -> {
                positive("pr_number")
                positive("run_id")
                if ("commit_sha" in args && !text("commit_sha").matches(Regex("[a-fA-F0-9]{40}"))) errors += "commit_sha must be the exact 40-character head to monitor"
                if ("pr_number" !in args && "run_id" !in args) errors += "provide pr_number or run_id from PR or workflow discovery"
                if ("wait_timeout_seconds" in args && text("wait_timeout_seconds").toIntOrNull()?.let { it in 1..30 } != true) errors += "wait_timeout_seconds must be 1..30; omit it for the existing background subscription"
            }
            "github_read" -> {
                if ("ranges" in args && args["ranges"] !is JsonArray) errors += "ranges must be an array of source range objects"
                if (text("evidence_id").isBlank() && (args["ranges"] as? JsonArray).isNullOrEmpty()) {
                    errors += "provide ranges=[{path, start_line, end_line or max_lines}] or a retained evidence_id; end_line=-1 reads to EOF within the output bound"
                }
                (args["ranges"] as? JsonArray).orEmpty().forEachIndexed { index, item ->
                    val range = item as? JsonObject
                    if (range == null) {
                        errors += "ranges[$index] must be a source range object"
                        return@forEachIndexed
                    }
                    if (range.keys.any { it !in setOf("path", "start_line", "end_line", "max_lines", "start_offset") }) {
                        errors += "ranges[$index] accepts only path, start_line, end_line, max_lines and start_offset"
                    }
                    val path = (range["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    if (runCatching { GitHubWorkspaceClient.filePath(path) }.isFailure) errors += "ranges[$index].path must be a relative repository file path"
                    fun number(key: String) = (range[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    val start = number("start_line") ?: 1.takeIf { "start_line" !in range }
                    val end = number("end_line")
                    val maximum = number("max_lines")
                    val offset = number("start_offset")
                    if ("start_offset" in range && (offset == null || offset < 0)) errors += "ranges[$index].start_offset must be an integer from 0 through ${Int.MAX_VALUE}"
                    if (start == null ||
                        start < 1 ||
                        "end_line" in range &&
                        "max_lines" in range ||
                        "max_lines" in range &&
                        (maximum == null || maximum !in 1..2000) ||
                        "end_line" in range &&
                        (end == null || end != -1 && (end < start || end.toLong() - start >= 2000))
                    ) {
                        errors += "ranges[$index] needs start_line>=1 and either max_lines=1..2000 or an ordered end_line spanning at most 2000 lines; end_line=-1 means bounded EOF. Do not combine end_line with max_lines. Split larger explicit reads into ranges such as 1..2000, 2001..4000"
                    }
                }
            }
            "github_commit" -> {
                when (text("action")) {
                    "branch" -> {
                        required("branch", "expected_head_sha")
                        unused("stage_id", "message")
                    }
                    "delete_branch" -> {
                        required("branch", "expected_head_sha")
                        unused("stage_id", "message", "from_branch", "fixture_plan", "origin_head_sha")
                    }
                    "delete_fixture_branch" -> {
                        required("branch", "expected_head_sha", "origin_head_sha")
                        unused("stage_id", "message", "from_branch", "fixture_plan")
                    }
                    else -> required("stage_id", "expected_head_sha", "branch", "message")
                }
                if ("expected_head_sha" in args && !text("expected_head_sha").matches(Regex("[a-fA-F0-9]{40}"))) errors += "expected_head_sha must be the current 40-character branch head"
            }
            "github_stage_patch" -> {
                if (args["commit_immediately"] == JsonPrimitive(true)) required("branch", "expected_head_sha", "message")
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
                    if ((!id.isString && id.content.toLongOrNull()?.let { it > 0 } != true) ||
                        runCatching { dev.chungjungsoo.gptmobile.data.github.normalizeGitHubWorkflowSelector(id.content) }.isFailure
                    ) {
                        errors += "workflow_id must be a positive integer or a discovered workflow filename"
                    }
                }
                when (text("action")) {
                    "run", "artifacts", "rerun", "rerun_failed_jobs", "rerun_all", "cancel" -> required("run_id")
                    "job", "logs" -> required("job_id")
                    "jobs" -> if ("run_id" !in args && "job_id" !in args) errors += "jobs requires run_id to list jobs, or job_id to inspect one job; obtain IDs from action=runs"
                    "dispatch" -> {
                        required("workflow_id", "ref", "expected_head_sha")
                        unused("run_id", "job_id", "run_attempt")
                    }
                }
                if (text("action") != "dispatch") unused("inputs")
                if ("expected_head_sha" in args && !text("expected_head_sha").matches(Regex("[a-fA-F0-9]{40}"))) errors += "expected_head_sha must be the current 40-character workflow head"
                val start = text("start_line").ifBlank { "1" }.toLongOrNull()
                val end = text("end_line").toLongOrNull()
                if (start == null || start !in 1..Int.MAX_VALUE.toLong() || end != null && (end < start || end - start >= 2000)) {
                    errors += "log start_line/end_line must be positive and ordered, spanning at most 2000 lines"
                }
            }
            "github_pr" -> {
                positive("pull_number")
                positive("comment_id")
                when (text("action").ifBlank { "context" }) {
                    "create" -> {
                        required("title", "head", "base")
                        unused("pull_number", "comment_id", "event", "comments", "state")
                        if (text("head").isNotBlank() && text("head") == text("base")) errors += "head and base must differ; discover branches and existing pull requests before creating a PR"
                    }
                    "update" -> {
                        required("pull_number", "expected_head_sha")
                        if (listOf("title", "body", "base", "state").none { it in args }) errors += "update requires at least one of title, body, base or state"
                        if ("state" in args && text("state") !in setOf("open", "closed")) errors += "state must be open or closed for an update"
                        unused("head", "draft", "comment_id", "event", "comments")
                    }
                    "merge" -> {
                        required("pull_number", "expected_head_sha")
                        unused("title", "body", "head", "base", "state", "draft", "comment_id", "event", "comments")
                    }
                    "comment", "reply" -> {
                        required("pull_number", "expected_head_sha", "body")
                        if (text("action") == "reply") required("comment_id") else unused("comment_id")
                        unused("title", "head", "base", "state", "draft", "event", "comments")
                    }
                    "review" -> required("pull_number", "expected_head_sha")
                    "context", "headers", "comments", "reviews", "list" -> unused("title", "body", "head", "base", "draft", "comment_id", "event", "comments")
                }
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
                    "headers", "context", "comments", "history" -> if (text("issue_number").isBlank()) {
                        errors += "issue_number is required; use github_issue action=list to discover an existing issue number before retrying"
                    }
                    "create" -> {
                        required("title")
                        unused("issue_number", "expected_updated_at", "state")
                    }
                    "update" -> {
                        required("issue_number", "expected_updated_at")
                        if (text("state") == "all") errors += "state=all is only valid for listing issues"
                        if (listOf("title", "body", "state").none { it in args }) errors += "update requires at least one of title, body or state"
                    }
                }
            }
            "github_release" -> {
                positive("workflow_run_id")
                when (text("action")) {
                    "detail", "tag", "create_tag", "publish" -> required("tag_name")
                }
                if (text("action") == "create_tag" && text("target_commitish").isBlank() && text("ref").isBlank()) errors += "create_tag requires target_commitish or ref from repository discovery"
                if (text("action") !in setOf("publish")) unused("inputs", "release_strategy")
            }
        }
        return errors
    }
}
