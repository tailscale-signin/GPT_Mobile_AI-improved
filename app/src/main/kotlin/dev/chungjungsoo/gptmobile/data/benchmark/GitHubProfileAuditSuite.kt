package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
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

/**
 * Second, explicitly OFFLINE engineering lane. Unlike github_ai_fixture_v1 it
 * does not provide exact arguments in the model prompt. Each case enforces an
 * ordered, evidence-bearing multi-call workflow with fabricated repository
 * content and no network connection or remote mutation.
 *
 * Passing these cases is NOT evidence of a real Copilot cloud job, GitHub
 * write, CI result, or installed-device/Gateway recovery.
 */
internal object GitHubEngineeringBenchmarkSuite {
    const val WORKLOAD = "github_engineering_fixture_v1"

    private val challenges = listOf(
        Triple(
            "release-gate",
            "Code repair · release authorization",
            "Investigate the synthetic Kotlin release guard, repair its permissive condition, run the fixture unit tests, then verify the tested revision."
        ),
        Triple(
            "security-review",
            "Code review · privilege boundary",
            "Review the synthetic permission code for privilege escalation, repair the unsafe condition, run fixture security tests, then verify the tested revision."
        ),
        Triple(
            "pagination",
            "Repository read · exhaustive pagination",
            "Read the entire synthetic pull-request file inventory. Follow only the continuation cursors actually returned. Verify total coverage."
        ),
        Triple(
            "copilot-handshake",
            "Copilot · cloud worker readiness",
            "In the authorized, offline Copilot SDK simulator, create a cloud session, wait for a producer=copilot-agent ready event, then send the requested task and inspect the result. Do not send early."
        ),
        Triple(
            "unknown-dispatch",
            "GitHub · uncertain mutation reconciliation",
            "Simulate an uncertain GitHub job submission. Do not retry or repeat the write. Reconcile its durable job ID and verify the remote outcome."
        ),
        Triple(
            "agent-merge",
            "Copilot · scoped CI repair",
            "An authorized offline disposable PR has failing CI. Inspect Agent Merge status, enable only fix_ci with the observed exact head SHA, and verify the repair. Never merge the PR."
        ),
        Triple(
            "copilot-default-off",
            "Copilot · default-off protection",
            "Copilot is disabled and no authorization to enable it was given. Inspect its status and report the result. Never enable, dispatch, or write."
        )
    )

    fun cases(): List<BenchmarkCase> = challenges.map { (id, label, prompt) ->
        BenchmarkCase(
            id = "github-engineering-v1:" + id,
            label = label,
            category = "tools",
            workload = WORKLOAD,
            validatorVersion = "benchmark-validator-v6",
            prompt = "This is an isolated, in-memory GitHub engineering simulator, never a real repository. " +
                "Use only the fixture tool and its returned evidence, without guessing SHA/cursors/session IDs. " +
                prompt + " Finish by returning only the FIXTURE_CODE from the final verified receipt."
        )
    }
}

/** A small deterministic state machine, never a replacement for live engineering qualification. */
internal class GitHubEngineeringScenario(private val test: BenchmarkCase, private val code: String) {
    private val kind = test.id.substringAfterLast(':')
    private val shaBefore = "a".repeat(40)
    private val shaAfter = "b".repeat(40)
    private val sessionId = "SESSION-" + code
    private val jobId = "JOB-" + code
    private val cursor2 = "ISSUED-PAGE-2-" + code
    private val cursor3 = "ISSUED-PAGE-3-" + code
    private val stages = when (kind) {
        "release-gate", "security-review" -> listOf("read", "patch", "test", "verify")
        "pagination" -> listOf("list", "list", "list", "verify")
        "copilot-handshake" -> listOf("create", "status", "status", "send", "result")
        "unknown-dispatch" -> listOf("dispatch", "reconcile", "verify")
        "agent-merge" -> listOf("agent_merge_status", "agent_merge_enable", "agent_merge_verify")
        "copilot-default-off" -> listOf("status")
        else -> error("Unknown engineering benchmark: " + kind)
    }
    private var stage = 0
    private var rejected = false

    private val schema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "action",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(stages.distinct().map { JsonPrimitive(it) }))
                    }
                )
                listOf("cursor", "patch", "path", "expected_head_sha", "sha", "session_id", "task", "job_id").forEach { key ->
                    put(key, buildJsonObject { put("type", "string") })
                }
                put(
                    "allowed_actions",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    }
                )
            }
        )
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    val tool: AgentTool = object : AgentTool {
        override val definition = AgentToolDefinition(
            "benchmark_github_workflow",
            "OFFLINE GitHub fixture. Read responses and use exact issued fields. " +
                "Actions: " + stages.distinct().joinToString(", ") + ". " +
                "Only an explicitly authorized simulator; never connected to GitHub or Copilot.",
            schema
        )

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult =
            this@GitHubEngineeringScenario.execute(callId, arguments)
    }

    fun valid(call: ProviderEvent.ToolCall): Boolean =
        call.name == tool.definition.name &&
            ToolArgumentValidator.errors(call.arguments, schema).isEmpty()

    private fun string(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun requiredKeys(action: String): Set<String> = when (action) {
        "read", "list", "create", "dispatch", "agent_merge_status", "agent_merge_verify" -> setOf("action")
        "patch" -> setOf("action", "path", "patch", "expected_head_sha")
        "test", "verify" -> if (kind == "release-gate" || kind == "security-review") {
            setOf("action", "sha")
        } else {
            setOf("action")
        }
        "status" -> if (kind == "copilot-handshake") setOf("action", "session_id") else setOf("action")
        "send" -> setOf("action", "session_id", "task")
        "result" -> setOf("action", "session_id")
        "reconcile" -> setOf("action", "job_id")
        "agent_merge_enable" -> setOf("action", "expected_head_sha", "allowed_actions")
        else -> emptySet()
    }

    private fun correctArguments(args: JsonObject, action: String): Boolean {
        if (args.keys != requiredKeys(action)) {
            if (
                kind != "pagination" ||
                action != "list" ||
                stage !in 1..2 ||
                args.keys != setOf("action", "cursor")
            ) {
                return false
            }
        }
        return when (action) {
            "patch" -> {
                val patch = string(args, "patch").orEmpty().filterNot(Char::isWhitespace)
                val fixed = if (kind == "release-gate") {
                    patch.contains("ciGreen&&approved") && !patch.contains("||")
                } else {
                    patch.contains("role==\"admin\"&&tokenValid") && !patch.contains("||")
                }
                fixed &&
                    string(args, "expected_head_sha") == shaBefore &&
                    string(args, "path") == "src/main/kotlin/AccessGate.kt"
            }
            "test" -> string(args, "sha") == shaAfter
            "verify" -> if (kind == "release-gate" || kind == "security-review") {
                string(args, "sha") == shaAfter
            } else {
                true
            }
            "list" -> when (stage) {
                0 -> "cursor" !in args
                1 -> string(args, "cursor") == cursor2
                2 -> string(args, "cursor") == cursor3
                else -> false
            }
            "status", "send", "result" -> if (kind == "copilot-handshake") {
                string(args, "session_id") == sessionId &&
                    (action != "send" || string(args, "task").orEmpty().isNotBlank())
            } else {
                true
            }
            "reconcile" -> string(args, "job_id") == jobId
            "agent_merge_enable" ->
                string(args, "expected_head_sha") == shaBefore &&
                    args["allowed_actions"] == JsonArray(listOf(JsonPrimitive("fix_ci")))
            else -> true
        }
    }

    private fun response(action: String, completedStep: Int): String = when (kind) {
        "release-gate", "security-review" -> when (action) {
            "read" -> if (kind == "release-gate") {
                "path=src/main/kotlin/AccessGate.kt; head_sha=" + shaBefore +
                    "; source: fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = ciGreen || approved"
            } else {
                "path=src/main/kotlin/AccessGate.kt; head_sha=" + shaBefore +
                    "; source: fun elevated(role: String, tokenValid: Boolean) = role == \"admin\" || tokenValid"
            }
            "patch" -> "STAGED on disposable fixture; new_sha=" + shaAfter + "; TESTS_NOT_RUN"
            "test" -> "SIMULATED_UNIT_TESTS_PASS (truth table and authorization); tested_sha=" + shaAfter
            else -> "SIMULATED_VERIFIED; tested_sha=" + shaAfter + "; FIXTURE_CODE=" + code
        }
        "pagination" -> when (completedStep) {
            0 -> "Page 1: 30 records, total=78, hasNextPage=true, continuation_call.cursor=" + cursor2
            1 -> "Page 2: 30 records, total=78, hasNextPage=true, continuation_call.cursor=" + cursor3
            2 -> "Page 3: 18 records, total=78, hasNextPage=false, no further cursor"
            else -> "SIMULATED_PAGE_COVERAGE=78/78; FIXTURE_CODE=" + code
        }
        "copilot-handshake" -> when (completedStep) {
            0 -> "SDK createSession reserved; session_id=" + sessionId + "; worker=STARTING; do not send yet"
            1 -> "session.start producer=bootstrap; worker=STARTING; poll again"
            2 -> "session.start producer=copilot-agent; worker=READY; first send now permitted"
            3 -> "SIMULATED_COPILOT_TASK_ACCEPTED; session_id=" + sessionId
            else -> "SIMULATED_COPILOT_TASK_VERIFIED; FIXTURE_CODE=" + code
        }
        "unknown-dispatch" -> when (completedStep) {
            0 -> "DISPATCH_OUTCOME_UNKNOWN; job_id=" + jobId + "; do not replay; reconcile by job ID"
            1 -> "durable job_id=" + jobId + "; remote_accepted=true; no duplicate dispatch"
            else -> "SIMULATED_RECONCILED; FIXTURE_CODE=" + code
        }
        "agent-merge" -> when (completedStep) {
            0 ->
                "Agent Merge OFF; authorized offline fixture; PR failed CI; head_sha=" + shaBefore +
                    "; allowed actions: fix_ci only. No merge permitted."
            1 -> "SIMULATED_AGENT_MERGE_ENABLED; allowed_actions=[fix_ci]; repair in progress"
            else -> "SIMULATED_REQUIRED_CI_PASSED; no PR merge; FIXTURE_CODE=" + code
        }
        else -> "COPILOT_DISABLED; no consent to enable; FIXTURE_CODE=" + code
    }

    private fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val action = string(arguments, "action").orEmpty()
        val expected = stages.getOrNull(stage)
        if (rejected || action != expected || !correctArguments(arguments, action)) {
            rejected = true
            return AgentToolResult(
                callId,
                ToolResultContent.Text("SIMULATED_PRECHECK_REJECTED: wrong order, missing receipt, or unauthorized operation"),
                true,
                errorCode = "SIMULATED_PRECHECK_REJECTED"
            )
        }
        val previous = stage++
        val uncertain = kind == "unknown-dispatch" && previous == 0
        return AgentToolResult(
            callId,
            ToolResultContent.Text(response(action, previous)),
            uncertain,
            errorCode = if (uncertain) "SIMULATED_DISPATCH_OUTCOME_UNKNOWN" else null
        )
    }

    fun expectedError(call: ProviderEvent.ToolCall, result: AgentToolResult): Boolean =
        kind == "unknown-dispatch" &&
            string(call.arguments, "action") == "dispatch" &&
            result.errorCode == "SIMULATED_DISPATCH_OUTCOME_UNKNOWN"

    fun passed(
        calls: List<ProviderEvent.ToolCall>,
        successful: Set<String>,
        expectedErrors: Set<String>
    ): Boolean = !rejected &&
        stage == stages.size &&
        calls.size == stages.size &&
        calls.all(::valid) &&
        successful.size + expectedErrors.size == calls.size &&
        (expectedErrors.size == if (kind == "unknown-dispatch") 1 else 0)

    fun assess(
        calls: List<ProviderEvent.ToolCall>,
        successful: Set<String>,
        expectedErrors: Set<String>,
        answer: String,
        raw: String,
        complete: Boolean
    ): BenchmarkToolAssessment {
        val done = passed(calls, successful, expectedErrors)
        val output = benchmarkOutputView(raw)
        return BenchmarkToolAssessment(
            toolSelectionCorrect = calls.isNotEmpty() && calls.all { it.name == tool.definition.name },
            argumentsCorrect = calls.isNotEmpty() && calls.all(::valid) && !rejected,
            executionSuccessful = done,
            resultUsed = if (complete) done && answer == code else null,
            finalFormatCompliant = if (complete) answer == code else null,
            progressProtocolCompliant = output.protocolComplete && !output.containsProgress,
            expectedRetryObserved = if (kind == "unknown-dispatch") expectedErrors.size == 1 else null,
            callLimitRespected = calls.size <= stages.size,
            attemptedCalls = calls.size,
            successfulCalls = successful.size,
            expectedErrorCalls = expectedErrors.size,
            evidenceClass = "OFFLINE_ENGINEERING_SIMULATION",
            workflowCompleted = done
        )
    }
}
