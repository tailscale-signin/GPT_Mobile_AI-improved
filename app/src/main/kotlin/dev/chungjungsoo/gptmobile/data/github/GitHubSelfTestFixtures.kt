package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Owned disposable resources exercise authorized writes without merging unrelated work. */
internal object GitHubSelfTestFixtures {
    private fun root(receipt: JsonObject): JsonObject? = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun args(receipt: JsonObject) = receipt["arguments"] as? JsonObject

    fun supports(tool: String, action: String): Boolean = when (tool.substringBefore("__")) {
        "github_commit" -> action in setOf("branch", "delete_branch", "delete_fixture_branch", "commit", "commit_staged_patch", "")
        "github_write" -> action in setOf("commit", "")
        "github_stage_patch" -> action in setOf("", "default")
        else -> false
    }

    fun prerequisite(tool: String, action: String): String = when (tool.substringBefore("__")) {
        "github_pr" -> when (action) {
            "comment", "reply", "review" -> "An owned disposable PR and an authorized deletion/dismissal operation for every created comment or review are required; the current client cannot clean these resources."
            "create", "update" -> "An owned disposable PR with an exact head lease, verified close operation and dependency-free branch cleanup plan is required; the content fixture does not create PRs."
            "merge" -> "An independently authorized disposable merge target and final-state verification are required; merging changes the base and is outside reversible content fixtures."
            else -> "A matching observed PR identifier and a separately authorized resource lifecycle are required."
        }
        "github_issue" -> "An owned disposable issue, its current expected_updated_at lease, verified close operation and cleanup for any comments/reactions are required; the content fixture does not create issues."
        "github_release" -> "An owned disposable release/tag with authorized deletion and remote absence verification is required; the current fixture has no release/tag cleanup lifecycle."
        "github_ci", "github_watch_checks" -> "An explicitly authorized disposable workflow/ref, owned dispatch receipt, terminal run verification and cancellation/cleanup policy are required; existing workflow history is read evidence only."
        else -> if (supports(tool, action)) "A verified task-owned fixture, exact origin/current head, authorized cleanup and enough remaining calls to finish the lifecycle are required." else "An authorized disposable resource with durable ownership, exact mutation leases and a supported verified cleanup operation is required."
    }

    fun minimumCalls(tools: List<AgentToolDefinition>): Int = if (GitHubContentFixture.available(tools, "codex-self-test/" + "a".repeat(20), "main", "a".repeat(40))) 11 else 4

    fun next(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, allowCreate: Boolean = true, cleanupOnly: Boolean = false, abortIncomplete: Boolean = false): ProviderEvent.ToolCall? {
        if (GitHubChecklistScope.readOnly(task) || !Regex("(?i)\\b(?:all|full|complete|writes|mutations|fixtures)\\b").containsMatchIn(task)) return null
        val observed = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
        val source = observed.firstOrNull { it.text("tool").substringBefore("__") == "github_context" && args(it)?.text("action") == "status" && it["success"] == JsonPrimitive(true) } ?: return null
        val status = root(source) ?: return null
        val branch = status.text("branch")
        val sha = status.text("commit_sha")
        val default = (status["repository"] as? JsonObject)?.text("default_branch")
        if (branch.isBlank() || !sha.matches(Regex("[a-fA-F0-9]{40}"))) return null
        val fixture = "codex-self-test/" + GitHubEvidenceBook.digest(source.text("binding") + source.text("receipt_id")).take(20)
        val creation = observed.lastOrNull { it.text("tool").substringBefore("__") == "github_commit" && args(it)?.text("action") == "branch" && args(it)?.text("branch") == fixture }
        if (creation?.let(GitHubContentFixture::isCreation) == true || creation == null && GitHubContentFixture.available(tools, fixture, branch, sha)) {
            return GitHubContentFixture.next(source, creation, tools, receipts, allowCreate, cleanupOnly, abortIncomplete)
        }
        // Only the legacy empty-branch fixture relies on ordinary merged-branch deletion.
        // The owned content lifecycle separately proves its exact chain before cleanup.
        if (branch != default) return null
        val recipes = buildList<Pair<String, JsonObject>> {
            if (creation == null) {
                if (!allowCreate) return null
                // Verify every cleanup operation is authorized before creating
                // a fixture that this session might otherwise be unable to remove.
                val cleanup = tools.firstOrNull {
                    it.name.substringBefore("__") == "github_commit" &&
                        ToolArgumentValidator.errors(
                            buildJsonObject {
                                put("action", "delete_branch")
                                put("branch", fixture)
                                put("expected_head_sha", sha)
                            },
                            it.inputSchema
                        ).isEmpty()
                } ?: return null
                if (!cleanup.modelVisible) return null
                add(
                    "github_commit" to buildJsonObject {
                        put("action", "branch")
                        put("branch", fixture)
                        put("from_branch", branch)
                        put("expected_head_sha", sha)
                        put("select_created_branch", false)
                    }
                )
            } else if (creation["success"] == JsonPrimitive(true) && root(creation)?.get("created") == JsonPrimitive(true) && root(creation)?.get("receipt_verified") == JsonPrimitive(true) && root(creation)?.text("head_sha") == sha) {
                add(
                    "github_context" to buildJsonObject {
                        put("action", "resolve")
                        put("ref", branch)
                        put("refresh", true)
                    }
                )
                add(
                    "github_context" to buildJsonObject {
                        put("action", "branch_dependencies")
                        put("branch", fixture)
                    }
                )
                add(
                    "github_commit" to buildJsonObject {
                        put("action", "delete_branch")
                        put("branch", fixture)
                        put("expected_head_sha", sha)
                    }
                )
            }
        }
        for ((name, arguments) in recipes) {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == name && it.modelVisible } ?: return null
            // A rejected or ambiguous mutation is a blocker, never permission to retry it.
            val matching = observed.filter { GitHubActionIdentity.matches(tool.name, arguments, it) }
            val prior = matching.lastOrNull { it["success"] == JsonPrimitive(true) } ?: matching.lastOrNull()
            if (prior != null) {
                if (prior["success"] != JsonPrimitive(true)) return null
                continue
            }
            if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) return null
            return ProviderEvent.ToolCall("fixture-" + GitHubEvidenceBook.digest("${tool.name}:$arguments").take(24), tool.name, arguments)
        }
        return null
    }

    private fun eligible(receipt: JsonObject): Boolean = receipt["trusted"] == JsonPrimitive(true) &&
        receipt["verification_eligible"] != JsonPrimitive(false) &&
        receipt["success"] == JsonPrimitive(true)

    /** A later proven creation starts a new lifecycle; a rejected duplicate never does. */
    fun lifecycle(creation: JsonObject, receipts: Map<String, JsonObject>): List<JsonObject> {
        val branch = args(creation)?.text("branch")
        return receipts.values.dropWhile { it !== creation && it != creation }.drop(1).takeWhile { receipt ->
            !(eligible(receipt) && receipt.text("tool").substringBefore("__") == "github_commit" && args(receipt)?.text("action") == "branch" && args(receipt)?.text("branch") == branch && receipt["binding"] == creation["binding"] && receipt["repository"] == creation["repository"] && root(receipt)?.get("created") == JsonPrimitive(true) && root(receipt)?.get("receipt_verified") == JsonPrimitive(true))
        }.filter { it["binding"] == creation["binding"] && it["repository"] == creation["repository"] }
    }

    /** Only live branch-existence reads expire. Pinned immutable content and saved views remain useful. */
    fun retiredReadPredicate(receipts: Map<String, JsonObject>): (JsonObject) -> Boolean {
        val byId = receipts.values.associateBy { it.text("receipt_id") }
        val latestCreations = receipts.values.filter { eligible(it) && it.text("tool").substringBefore("__") == "github_commit" && args(it)?.text("action") == "branch" && root(it)?.get("created") == JsonPrimitive(true) && root(it)?.get("receipt_verified") == JsonPrimitive(true) }
            .associateBy { Triple(it["binding"], it["repository"], args(it)?.text("branch")) }
        val deleted = checkpoint(receipts).filterIsInstance<JsonObject>().filter { it["final_state_verified"] == JsonPrimitive(true) }.mapNotNull { fixture ->
            val creation = byId[fixture.text("creation_receipt")] ?: return@mapNotNull null
            if (latestCreations[Triple(creation["binding"], creation["repository"], fixture.text("branch"))] != creation) return@mapNotNull null
            Triple(creation["binding"], creation["repository"], fixture.text("branch"))
        }.toSet()
        return { receipt ->
            val arguments = args(receipt)
            val branch = arguments?.text("branch").orEmpty().ifBlank { arguments?.text("ref").orEmpty() }
                .ifBlank { root(receipt)?.text("branch").orEmpty() }
            receipt.text("tool").substringBefore("__") == "github_context" &&
                arguments?.text("action") in setOf("branch", "branch_dependencies", "resolve", "status") &&
                arguments?.text("commit_sha").isNullOrBlank() &&
                arguments?.containsKey("view_cursor") != true &&
                Triple(receipt["binding"], receipt["repository"], branch) in deleted
        }
    }

    /** Both receipts must prove the same owned, unchanged branch in the same task/repository. */
    fun checkpoint(receipts: Map<String, JsonObject>): JsonArray = JsonArray(
        receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) && it.text("tool").substringBefore("__") == "github_commit" && args(it)?.text("action") == "branch" && args(it)?.text("branch")?.startsWith("codex-self-test/") == true
        }.map { creation ->
            if (GitHubContentFixture.isCreation(creation)) return@map GitHubContentFixture.checkpoint(creation, receipts)
            val branch = args(creation)?.text("branch").orEmpty()
            val sha = args(creation)?.text("expected_head_sha").orEmpty()
            val source = receipts.values.firstOrNull { receipt ->
                eligible(receipt) &&
                    receipt.text("tool").substringBefore("__") == "github_context" &&
                    args(receipt)?.text("action") == "status" &&
                    receipt["binding"] == creation["binding"] &&
                    receipt["repository"] == creation["repository"] &&
                    root(receipt)?.text("commit_sha") == sha &&
                    root(receipt)?.text("branch") == args(creation)?.text("from_branch") &&
                    (root(receipt)?.get("repository") as? JsonObject)?.text("default_branch") == args(creation)?.text("from_branch") &&
                    branch == "codex-self-test/" + GitHubEvidenceBook.digest(receipt.text("binding") + receipt.text("receipt_id")).take(20)
            }
            val owned = eligible(creation) &&
                source != null &&
                sha.matches(Regex("[a-fA-F0-9]{40}")) &&
                root(creation)?.get("created") == JsonPrimitive(true) &&
                root(creation)?.get("receipt_verified") == JsonPrimitive(true) &&
                root(creation)?.text("head_sha") == sha &&
                root(creation)?.text("branch") == branch
            val cleanups = lifecycle(creation, receipts).filter {
                it["trusted"] == JsonPrimitive(true) &&
                    it.text("tool").substringBefore("__") == "github_commit" &&
                    args(it)?.text("action") == "delete_branch" &&
                    args(it)?.text("branch") == branch &&
                    it["binding"] == creation["binding"] &&
                    it["repository"] == creation["repository"]
            }
            fun verified(cleanup: JsonObject) = owned &&
                eligible(cleanup) &&
                args(cleanup)?.text("expected_head_sha") == sha &&
                root(cleanup)?.get("deleted") == JsonPrimitive(true) &&
                root(cleanup)?.get("absence_verified") == JsonPrimitive(true) &&
                root(cleanup)?.text("branch") == branch &&
                root(cleanup)?.text("observed_head_sha") == sha
            val verifiedCleanup = cleanups.lastOrNull(::verified)
            val cleanup = verifiedCleanup ?: cleanups.lastOrNull()
            val cleaned = verifiedCleanup != null
            buildJsonObject {
                put("branch", branch)
                put("ownership_verified", owned)
                put("final_state_verified", cleaned)
                put("response_accepted", cleanup?.let { root(it)?.get("deleted") == JsonPrimitive(true) } ?: false)
                put("creation_receipt", creation["receipt_id"] ?: JsonPrimitive("unknown"))
                put(
                    "cleanup_status",
                    if (!owned) {
                        "OWNERSHIP_NOT_ESTABLISHED"
                    } else if (cleaned) {
                        "CLEANED"
                    } else {
                        "PENDING_OR_BLOCKED"
                    }
                )
                cleanup?.get("receipt_id")?.let { put("cleanup_receipt", it) }
                put("cleanup_attempt_receipts", JsonArray(cleanups.takeLast(8).mapNotNull { it["receipt_id"] }))
                put("mutation_scope", "Unchanged disposable branch only. Content commits, releases, merges and workflow mutations require a separately authorized fixture plan.")
            }
        }
    )

    fun complete(receipts: Map<String, JsonObject>): Boolean {
        val fixtures = checkpoint(receipts).filterIsInstance<JsonObject>()
        if (fixtures.isEmpty() || fixtures.any { it.text("cleanup_status") != "CLEANED" }) return false
        if (fixtures.any { it.text("fixture_plan") == GitHubOwnedFixtures.PLAN && it["content_verified"] != JsonPrimitive(true) }) return false
        val reconciledDeletions = fixtures.flatMap { (it["reconciled_deletion_receipts"] as? JsonArray).orEmpty() }.toSet()
        val resolvedProposals = fixtures.flatMap { (it["approval_resolutions"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>().mapNotNull { it["proposal_receipt"] }.toSet()
        val proved = fixtures.flatMap { listOfNotNull(it["creation_receipt"], it["cleanup_receipt"]) + (it["mutation_receipts"] as? JsonArray).orEmpty() }.toSet() + reconciledDeletions + resolvedProposals
        return receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                (it.text("tool").startsWith("github") || it.text("tool").substringBefore("__") == "repository_graph") &&
                requiresFixtureProof(it)
        }.all { supports(it.text("tool"), args(it)?.text("action").orEmpty()) && (eligible(it) || it["receipt_id"] in reconciledDeletions) && it["receipt_id"] in proved }
    }

    private fun requiresFixtureProof(receipt: JsonObject): Boolean {
        val execution = receipt["execution"] as? JsonObject
        val counters = listOf("mutation_requests", "accepted_mutations", "unknown_mutations")
        fun count(key: String) = (execution?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val sent = counters.any { (count(it) ?: 0) > 0 }
        val invalidCounters = counters.any { key ->
            execution?.containsKey(key) == true && count(key)?.let { it < 0 } != false
        }
        if (sent || invalidCounters) return true
        if (receipt["success"] != JsonPrimitive(true) && receipt["dispatched"] == JsonPrimitive(false) && !sent) return false
        // Current native receipts classify local staging separately from remote
        // mutation capability. Neither a draft nor an unsubmitted approval creates
        // a GitHub resource that invalidates an already verified fixture cleanup.
        if (receipt["remote_mutation"] == JsonPrimitive(false)) return false
        val outcome = GitHubOperationOutcome.snapshot(receipt)
        val noWrites = counters.all {
            count(it) == 0L
        }
        if (noWrites && (outcome["proposal_prepared"] == JsonPrimitive(true) || outcome["approval_pending"] == JsonPrimitive(true))) return false
        if (receipt["remote_mutation"] == JsonPrimitive(true)) return true
        // Legacy receipts predate explicit remote_mutation. Local watch/decide
        // operations create no GitHub resource and need no deletion lifecycle.
        val action = args(receipt)?.text("action").orEmpty()
        return when (receipt.text("tool").substringBefore("__")) {
            "github_stage_patch" -> true
            "github_commit" -> action in setOf("branch", "commit", "commit_staged_patch", "delete_branch", "delete_fixture_branch", "")
            "github_write" -> action in setOf("commit", "")
            "github_pr" -> action in GitHubCollaboration.prActions || action in setOf("create", "update", "comment", "reply", "review", "merge", "ready", "draft")
            "github_issue" -> action in GitHubCollaboration.issueActions || action in setOf("create", "update")
            "github_release" -> action in setOf("create_tag", "publish")
            "github_ci" -> action in setOf("dispatch", "rerun", "rerun_failed_jobs", "rerun_all", "cancel")
            "github" -> action in GitHubWorkspaceClient.writeActions || action in setOf("create_branch", "update_file", "create_pull_request", "create_issue_comment", "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow")
            else -> false
        }
    }
}
