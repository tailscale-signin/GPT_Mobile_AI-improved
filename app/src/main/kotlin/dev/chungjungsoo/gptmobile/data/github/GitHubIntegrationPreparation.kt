package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A durable prerequisite plan is not an execution receipt or permission to create unrelated resources. */
internal object GitHubIntegrationPreparation {
    fun snapshot(
        task: String,
        receipts: Map<String, JsonObject>,
        tools: List<AgentToolDefinition>,
        capabilities: JsonArray,
        remainingToolCalls: Int?,
        approval: JsonObject?,
        stageBudget: JsonObject? = null,
        acceptedContract: JsonObject? = null
    ): JsonObject {
        val budget = stageBudget ?: GitHubChecklistBudget.checkpoint(receipts, remainingToolCalls, GitHubSelfTestFixtures.minimumCalls(tools), acceptedContract)
        val remaining = (budget["remaining_calls"] as? JsonPrimitive)?.intOrNull ?: GitHubChecklistBudget.remaining(receipts, remainingToolCalls)
        val acquisitionAllowed = GitHubChecklistBudget.acquisitionAllowed(receipts, remaining)
        val minimum = GitHubSelfTestFixtures.minimumCalls(tools) + GitHubChecklistBudget.VERIFICATION_RESERVE
        val readCalls = if (acquisitionAllowed) GitHubSelfTestReads.available(tools, receipts.values.toList()) else emptyList()
        val canCreate = !GitHubChecklistScope.readOnly(task) && approval?.get("state") == JsonPrimitive("READY") && (remaining == null || remaining >= minimum)
        val fixtureCall = GitHubSelfTestFixtures.next(task, tools, receipts, allowCreate = canCreate, cleanupOnly = !acquisitionAllowed, abortIncomplete = !acquisitionAllowed)
        val pending = capabilities.filterIsInstance<JsonObject>().filter { it.text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") }
        val blockers = pending.groupBy { it.text("blocker_code").ifBlank { "BLOCKER_DETAIL_NOT_RECORDED" } }
        val fixtureActive = GitHubChecklistBudget.cleanupReserve(receipts) > 0
        fun call(value: ProviderEvent.ToolCall) = buildJsonObject {
            put("tool", value.name)
            put("arguments", value.arguments)
            put("plan_only", true)
        }
        return buildJsonObject {
            put("schema", "github.integration-preparation.v1")
            put("request_digest", GitHubEvidenceBook.digest(task))
            put("execution_verified", false)
            put(
                "status",
                when {
                    fixtureActive && !acquisitionAllowed -> "CLEANUP_RESERVE_ACTIVE"
                    fixtureCall != null -> "OWNED_FIXTURE_STEP_PREPARED"
                    readCalls.isNotEmpty() -> "BOUNDED_READS_PREPARED"
                    pending.isEmpty() -> "NO_PENDING_CAPABILITY_PREREQUISITES"
                    else -> "PREREQUISITES_REQUIRED"
                }
            )
            put("automatic_permission_changes", false)
            put("automatic_budget_increase", false)
            put("scope", "Prepare exact authorized reads and the supported task-owned content fixture. Missing IDs, approval or cleanup recipes remain explicit prerequisites.")
            put("budget", budget)
            put("ready_read_count", readCalls.size)
            put("required_pending_capability_count", pending.count { it["required"] != JsonPrimitive(false) })
            put("conditional_pending_capability_count", pending.count { it["required"] == JsonPrimitive(false) })
            put("ready_reads", JsonArray(readCalls.take(16).map(::call)))
            put("additional_ready_read_count", (readCalls.size - 16).coerceAtLeast(0))
            put("owned_fixture_creation_admissible", canCreate && fixtureCall?.arguments?.get("action") == JsonPrimitive("branch"))
            fixtureCall?.let { put("next_owned_fixture_call", call(it)) }
            approval?.let { put("fixture_approval_preflight", it) }
            put("resume_policy", "Revalidate the current catalog and leases, reuse completed receipts, and resume the next missing step. Reconcile uncertain mutations before continuing. Verify owned-resource absence before releasing cleanup reserve.")
            put(
                "blockers",
                JsonArray(
                    blockers.map { (code, rows) ->
                        buildJsonObject {
                            put("code", code)
                            put("capability_ids", JsonArray(rows.mapNotNull { it["id"] }))
                            put("required_capability_ids", JsonArray(rows.filter { it["required"] != JsonPrimitive(false) }.mapNotNull { it["id"] }))
                            put("conditional_capability_ids", JsonArray(rows.filter { it["required"] == JsonPrimitive(false) }.mapNotNull { it["id"] }))
                            val exactInputs = rows.flatMap { (it["required_inputs"] as? JsonArray).orEmpty() }.distinct()
                            put("required_inputs", if (exactInputs.isNotEmpty()) JsonArray(exactInputs) else JsonArray(requiredInputs(code).map(::JsonPrimitive)))
                            put("next_action", GitHubCriterionBlocker.from(code)?.nextAction ?: GitHubCriterionBlocker.BLOCKER_DETAIL_NOT_RECORDED.nextAction)
                            put("explanations", JsonArray(rows.mapNotNull { it["next_action"] }.distinct()))
                            if (code == GitHubCriterionBlocker.OWNED_FIXTURE_REQUIRED.name) {
                                put(
                                    "supported_preparation_ids",
                                    JsonArray(
                                        rows.filter { row ->
                                            val id = row.text("id")
                                            GitHubSelfTestFixtures.supports(id.substringBefore(':'), id.substringAfter(':', ""))
                                        }.mapNotNull { it["id"] }
                                    )
                                )
                                put("unsupported_lifecycle_policy", "Provide a separately authorized disposable resource with a verified cleanup recipe; a test-all request does not authorize merging or mutating existing unrelated work.")
                            }
                        }
                    }
                )
            )
        }
    }

    private fun requiredInputs(code: String): List<String> = when (code) {
        "OWNED_FIXTURE_REQUIRED" -> listOf("Task/repository-bound ownership", "Exact current resource lease", "Authorized cleanup operation", "Remaining cleanup and verification allowance")
        "FIXTURE_APPROVAL_STATE_UNKNOWN" -> listOf("Effective task-bound approval decision")
        "FIXTURE_APPROVAL_MODE_REQUIRED" -> listOf("GitHub workspace > Settings > Chat decisions > Fixture-only auto-approval for a new task, or the existing exact Approve & Commit interaction", "The retained task approval preflight and any staged patch approval remain unchanged")
        "APPROVAL_REQUIRED" -> listOf("Decision on the existing exact approval card")
        "ISSUE_ID_REQUIRED" -> listOf("Issue number from an authorized issue inventory; PR numbers cannot substitute")
        "PULL_REQUEST_ID_REQUIRED" -> listOf("Pull request number from an authorized PR inventory", "Observed head SHA")
        "WORKFLOW_RUN_OR_JOB_REQUIRED" -> listOf("Run at the selected repository revision", "Observed executed job for logs or completed failed run for failure analysis")
        "CHECK_SUBSCRIPTION_REQUIRED" -> listOf("Observed run/PR and exact head SHA", "Bounded inline observation or separately verified saved-chat subscription")
        "RELEASE_OR_TAG_REQUIRED" -> listOf("Tag or release from the selected repository inventory")
        "REPOSITORY_INPUT_REQUIRED" -> listOf("Authorized repository/ref", "Observed immutable revision and source path")
        "REPOSITORY_LIFECYCLE_REQUIRED" -> listOf("Retained create/fork/import/clone operation", "Gateway operation_id or native expected_repository_id with its original destination and operation")
        "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED" -> listOf("Current operation authorization and catalog binding")
        "CONTINUATION_REQUIRED" -> listOf("Exact issued continuation and sufficient remaining read allowance")
        else -> listOf("The retained criterion's exact blocker and current authorized tool contract")
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
