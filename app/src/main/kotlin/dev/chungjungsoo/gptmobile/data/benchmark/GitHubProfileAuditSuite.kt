package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubCollaboration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Frozen offline GitHub tool-call scenarios; no repository access or mutations are possible. */
internal data class GitHubProfileAuditFixture(
    val id: String,
    val operation: String,
    val action: String?,
    val definition: AgentToolDefinition,
    val arguments: JsonObject
)

internal object GitHubProfileAuditSuite {
    const val WORKLOAD = "github_ai_fixture_v1"
    private val sha = "a".repeat(40)
    private val fixtures: List<GitHubProfileAuditFixture> by lazy {
        GitHubOperationTool.names.sorted().flatMap { operation ->
            val definition = GitHubOperationTool.definition(operation)
            val properties = definition.inputSchema["properties"] as? JsonObject ?: error("No GitHub tool properties: $operation")
            val actions = ((properties["action"] as? JsonObject)?.get("enum") as? JsonArray)
                .orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.distinct().ifEmpty { listOf("default") }
            actions.map { variant ->
                val action = variant.takeUnless { it == "default" }
                GitHubProfileAuditFixture(
                    "github-ai-v1:$operation:$variant",
                    operation,
                    action,
                    definition,
                    arguments(operation.removeSuffix("_inspect"), action, properties.keys)
                )
            }
        }
    }
    private val lookup by lazy { fixtures.associateBy { it.id } }

    fun fixture(test: BenchmarkCase): GitHubProfileAuditFixture? =
        test.takeIf { it.workload == WORKLOAD }?.id?.let(lookup::get)

    fun familyCount(): Int = GitHubOperationTool.names.size
    fun actionCount(): Int = fixtures.size

    fun cases(allActions: Boolean): List<BenchmarkCase> {
        val selected =
            if (allActions) {
                fixtures
            } else {
                fixtures.groupBy { it.operation }.values.map { family ->
                    family.firstOrNull { it.action in setOf("list", "status", "workflows", "capabilities", "search", "resolve", "ranges") }
                        ?: family.first()
                }
            }
        return selected.map { item ->
            BenchmarkCase(
                id = item.id,
                label = item.operation + " · " + (item.action ?: "default"),
                category = "tools",
                workload = WORKLOAD,
                validatorVersion = "benchmark-validator-v5",
                prompt = "You are testing an isolated, in-memory GitHub tool fixture, NOT an actual repository. " +
                    "Only this case's selected GitHub action is advertised; do not choose other actions. " +
                    "Call the provided tool " + item.operation + " exactly once using precisely these arguments: " +
                    item.arguments + ". After receiving the tool result, output only the value of FIXTURE_CODE, no other text or tool call."
            )
        }
    }

    /** Values are intentionally fabricated and are never submitted to GitHub. */
    private fun arguments(operation: String, action: String?, fields: Set<String>): JsonObject = buildJsonObject {
        fun str(key: String, value: String) {
            if (key in fields) put(key, value)
        }

        fun num(key: String, value: Int) {
            if (key in fields) put(key, value)
        }

        fun names(key: String) {
            if (key in fields) put(key, buildJsonArray { add(JsonPrimitive("fixture-user")) })
        }

        action?.let { str("action", it) }
        if (operation != "github_repository") {
            str("owner", "fixture")
            str("repo", "offline")
        }
        when (operation) {
            "github_repository" -> when (action) {
                "create", "fork", "import" -> {
                    str("destination_owner", "fixture-user")
                    str("destination_repo", "offline")
                    str("owner_type", "user")
                    str("visibility", "private")
                    if (action != "create") {
                        str("source_owner", "fixture-user")
                        str("source_repo", "original")
                    }
                    if (action == "import") {
                        str("source_ref", "main")
                        str("copy_mode", "snapshot")
                    }
                }
                "clone" -> {
                    str("source_owner", "fixture-user")
                    str("source_repo", "original")
                    str("source_ref", "main")
                }
                "status" -> {
                    str("operation", "create_repository")
                    str("operation_id", "repoop_" + "a".repeat(32))
                }
            }
            "github_context" -> if (action == "repositories") str("query", "fixture")
            "github_search" -> str("query", "fixture")
            "github_read" -> if ("ranges" in fields) {
                put(
                    "ranges",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("path", "README.md")
                                put("start_line", 1)
                                put("max_lines", 5)
                            }
                        )
                    }
                )
            }
            "github_watch_checks" -> num("pr_number", 1)
            "github_stage_patch" -> {
                str("target_file", "README.md")
                str("search_snippet", "before")
                str("replacement_snippet", "after")
            }
            "github_write" -> {
                str("branch", "fixture-branch")
                str("expected_head_sha", sha)
                str("message", "Offline fixture")
                if ("files" in fields) {
                    put(
                        "files",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("path", "fixture.txt")
                                    put("content", "fixture")
                                }
                            )
                        }
                    )
                }
            }
            "github_commit" -> {
                str("branch", "fixture-branch")
                str("expected_head_sha", sha)
                when (action) {
                    "branch" -> str("from_branch", "main")
                    "delete_fixture_branch" -> str("origin_head_sha", "b".repeat(40))
                    "delete_branch" -> Unit
                    else -> {
                        str("stage_id", "offline-stage")
                        str("message", "Offline fixture")
                    }
                }
            }
            "github_ci" -> when (action) {
                "run", "artifacts", "rerun", "rerun_failed_jobs", "rerun_all", "cancel", "jobs" -> num("run_id", 1)
                "job", "logs" -> num("job_id", 1)
                "dispatch" -> {
                    str("workflow_id", "ci.yml")
                    str("ref", "main")
                    str("expected_head_sha", sha)
                }
            }
            "github_pr" -> {
                when (action) {
                    "list" -> Unit
                    "create" -> {
                        str("title", "Fixture")
                        str("head", "feature")
                        str("base", "main")
                    }
                    "headers", "context", "comments", "reviews", "threads", "agent_merge_status" -> num("pull_number", 1)
                    else -> {
                        num("pull_number", 1)
                        str("expected_head_sha", sha)
                        when (action) {
                            "update" -> str("title", "Fixture update")
                            "agent_merge_update" -> if ("allowed_actions" in fields) {
                                put("allowed_actions", buildJsonArray { add(JsonPrimitive("fix_ci")) })
                            }
                            "comment", "reply" -> {
                                str("body", "Fixture comment")
                                if (action == "reply") num("comment_id", 1)
                            }
                            "review" -> str("event", "APPROVE")
                        }
                    }
                }
                if (action in GitHubCollaboration.prActions) {
                    when (action) {
                        "request_reviewers", "remove_reviewers" -> names("reviewers")
                        "resolve_thread", "reopen_thread" -> str("thread_id", "fixture-thread")
                        "dismiss_review" -> {
                            num("review_id", 1)
                            str("body", "Fixture reason")
                        }
                        "edit_comment", "edit_review_comment" -> {
                            num("comment_id", 1)
                            str("body", "Fixture")
                            str("expected_comment_updated_at", "2026-01-01T00:00:00Z")
                        }
                        "add_labels" -> names("labels")
                        "remove_label" -> str("label", "fixture")
                        "add_assignees", "remove_assignees" -> names("assignees")
                        "react", "remove_reaction" -> {
                            str("reaction", "+1")
                            if (action == "remove_reaction") num("reaction_id", 1)
                        }
                    }
                }
            }
            "github_issue" -> {
                when (action) {
                    "headers", "context", "comments", "history" -> num("issue_number", 1)
                    "create" -> str("title", "Fixture")
                    "update" -> {
                        num("issue_number", 1)
                        str("expected_updated_at", "2026-01-01T00:00:00Z")
                        str("title", "Fixture update")
                    }
                    in GitHubCollaboration.issueActions -> {
                        num("issue_number", 1)
                        str("expected_updated_at", "2026-01-01T00:00:00Z")
                    }
                }
                if (action in GitHubCollaboration.issueActions) {
                    when (action) {
                        "edit_comment" -> {
                            num("comment_id", 1)
                            str("body", "Fixture")
                            str("expected_comment_updated_at", "2026-01-01T00:00:00Z")
                        }
                        "add_labels" -> names("labels")
                        "remove_label" -> str("label", "fixture")
                        "add_assignees", "remove_assignees" -> names("assignees")
                        "react", "remove_reaction" -> {
                            str("reaction", "+1")
                            if (action == "remove_reaction") num("reaction_id", 1)
                        }
                    }
                }
            }
            "github_release" -> {
                if (action in setOf("detail", "tag", "create_tag", "publish")) str("tag_name", "fixture-v1")
                if (action == "create_tag") str("target_commitish", sha)
            }
        }
    }
}
