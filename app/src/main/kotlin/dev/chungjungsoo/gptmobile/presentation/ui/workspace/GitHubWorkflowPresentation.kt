package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal enum class GitHubAcceptanceStatus(val label: String) {
    PASS("Verified"),
    FAIL("Failed"),
    BLOCKED("Blocked"),
    NOT_TESTED("Untested")
}

internal data class GitHubAcceptanceItem(val requirement: String, val status: GitHubAcceptanceStatus, val detail: String)
internal data class GitHubCapabilityObservation(
    val label: String,
    val discovered: Boolean?,
    val authorization: String,
    val availability: String,
    val tested: Boolean?,
    val successfullyTested: Boolean?
)
internal data class GitHubOperationGroup(val label: String, val observed: Int, val completed: Int, val failed: Int, val pending: Int)
internal data class GitHubWorkflowSnapshot(
    val runId: String,
    val phase: String,
    val acceptance: GitHubAcceptanceStatus,
    val criteria: List<GitHubAcceptanceItem>,
    val operations: List<GitHubOperationGroup>,
    val capabilities: List<GitHubCapabilityObservation>,
    val observedOperations: Int,
    val eventWindowComplete: Boolean,
    val evidenceComplete: Boolean?,
    val verificationAvailable: Boolean,
    val failureCategory: String?,
    val recovery: List<TaskRecoveryAction>,
    val recoveryExplanation: String
)

/** Presentation uses persisted verifier output. API success never becomes acceptance evidence. */
internal object GitHubWorkflowPresentation {
    private val terminal = setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING, AgentRunStatus.BLOCKED)
    private val pagination = setOf("page", "per_page", "cursor", "continuation", "after", "before", "page_token")
    private val mutationActions = setOf("commit", "commit_staged_patch", "create", "update", "delete", "merge", "publish", "push", "rerun", "cancel", "close", "reopen", "approve", "comment", "request_changes")
    private val code = Regex("\\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\\b")

    fun snapshot(run: AgentRun, events: List<ToolEvent>, eventWindowComplete: Boolean, verification: JsonObject? = null, evidenceReadFailed: Boolean = false, nowMillis: Long = System.currentTimeMillis()): GitHubWorkflowSnapshot {
        val observed = events.filter { it.runId == run.runId }.distinctBy { it.callId.takeIf(String::isNotBlank) ?: it.eventId }
        val retained = (verification?.get("evidence_retention_complete") as? JsonPrimitive)?.booleanOrNull
        val ledger = verification?.get("acceptance_ledger") as? JsonObject
        val complete = run.status == AgentRunStatus.COMPLETED &&
            verification?.get("completion_verified") == JsonPrimitive(true) &&
            retained == true &&
            verification?.get("partial") != JsonPrimitive(true) &&
            (ledger == null || ledger["completion_verified"] == JsonPrimitive(true))
        // Current checkpoints use the acceptance ledger. Legacy records retain their original shape.
        val requirements = (ledger?.get("requirements") ?: verification?.get("requirements")) as? JsonArray
        val criteria = requirements.orEmpty().mapNotNull { it as? JsonObject }.map { requirement ->
            GitHubAcceptanceItem(
                requirement.text(if (ledger != null) "criterion" else "requirement") ?: "Requested work",
                when (requirement.text(if (ledger != null) "state" else "status")) {
                    "PASS", "PASSED" -> if (retained == true && !evidenceReadFailed) GitHubAcceptanceStatus.PASS else GitHubAcceptanceStatus.BLOCKED
                    "FAIL", "FAILED" -> GitHubAcceptanceStatus.FAIL
                    "BLOCKED" -> GitHubAcceptanceStatus.BLOCKED
                    else -> GitHubAcceptanceStatus.NOT_TESTED
                },
                requirement.text(if (ledger != null) "reason" else "acceptance")?.takeIf(String::isNotBlank) ?: "Acceptance evidence has not been recorded."
            )
        }.ifEmpty {
            listOf(
                GitHubAcceptanceItem(
                    "Requested work",
                    if (complete) {
                        GitHubAcceptanceStatus.PASS
                    } else if (evidenceReadFailed || retained == false || run.status == AgentRunStatus.BLOCKED) {
                        GitHubAcceptanceStatus.BLOCKED
                    } else {
                        GitHubAcceptanceStatus.NOT_TESTED
                    },
                    if (complete) "The saved verifier confirmed all acceptance criteria." else "No complete acceptance verification is available for this run."
                )
            )
        }
        val acceptance = when {
            evidenceReadFailed || retained == false -> GitHubAcceptanceStatus.BLOCKED
            criteria.any { it.status == GitHubAcceptanceStatus.FAIL } -> GitHubAcceptanceStatus.FAIL
            complete && criteria.all { it.status == GitHubAcceptanceStatus.PASS } -> GitHubAcceptanceStatus.PASS
            criteria.any { it.status == GitHubAcceptanceStatus.BLOCKED } || run.status == AgentRunStatus.BLOCKED -> GitHubAcceptanceStatus.BLOCKED
            else -> GitHubAcceptanceStatus.NOT_TESTED
        }
        val failureCategory = (verification?.get("original_failure") as? JsonObject)?.text("code")?.takeIf(String::isNotBlank)
            ?: verification?.text("verification_failure") ?: typedFailure(run.terminalError)
            ?: observed.lastOrNull { it.isError || it.status == ToolEventStatus.FAILED }?.let { typedFailure(it.error) ?: jsonObject(it.result)?.text("error_code") }
        val failureText = listOfNotNull(run.terminalError, failureCategory).joinToString(" ")
        val failure = ProfileFailureRecord.classify(failureText, nowMillis = nowMillis)
        val deterministic = !failure.retryAllowed ||
            failureCategory?.let { category ->
                category.startsWith("PRECHECK") ||
                    category.startsWith("REASONING_OFF") ||
                    category in setOf(
                        "AUTH_REQUIRED", "AUTH_INVALID", "INVALID_ARGUMENTS", "GITHUB_SSO_REQUIRED", "GITHUB_ENDPOINT_PERMISSION_REQUIRED",
                        "GITHUB_BRANCH_RULES_BLOCKED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_NOT_FOUND_OR_HIDDEN",
                        "GITHUB_VALIDATION_FAILED", "PERSONAL_DATA_LOCAL_ONLY", "POLICY_RESTRICTED"
                    )
            } == true
        val waiting = (failure.retryAfterMillis?.let { it > nowMillis } == true) ||
            (failure.retryAfterMillis == null && (failure.category in setOf("RATE_LIMIT", "CIRCUIT_BREAKER_OPEN") || failureCategory?.let(GitHubFailurePolicy::isRateLimited) == true))
        val unknown = !eventWindowComplete || run.terminalError?.contains("UNKNOWN", ignoreCase = true) == true || observed.any(::unresolvedEffect)
        val unreconciledGateway = failure.category == "GATEWAY_OUTCOME_UNKNOWN" ||
            observed.any {
                it.error?.contains("GATEWAY_OUTCOME_UNKNOWN") == true || jsonObject(it.result)?.text("error_code") == "GATEWAY_OUTCOME_UNKNOWN"
            }
        val recoverable = run.status in terminal && (run.status != AgentRunStatus.COMPLETED || acceptance != GitHubAcceptanceStatus.PASS)
        val recovery = buildList {
            if (recoverable && !deterministic && !waiting && !unreconciledGateway) add(TaskRecoveryAction.CONTINUE_RUN)
            if (recoverable && !deterministic && !waiting && !unknown && !unreconciledGateway && run.status == AgentRunStatus.FAILED) add(TaskRecoveryAction.RETRY_FAILED_STAGE)
        }
        return GitHubWorkflowSnapshot(
            run.runId,
            when (run.status) {
                AgentRunStatus.COMPLETED -> "Answer ready"
                AgentRunStatus.QUEUED -> "Queued"
                AgentRunStatus.RUNNING -> "Working"
                AgentRunStatus.FAILED -> "Execution failed"
                AgentRunStatus.CANCELED -> "Stopped"
                AgentRunStatus.INTERRUPTED -> "Interrupted"
                AgentRunStatus.PARTIAL -> "Partially complete"
                AgentRunStatus.EVIDENCE_MISSING -> "Evidence unavailable"
                AgentRunStatus.BLOCKED -> "Blocked"
                else -> if (run.status.startsWith("WAITING_")) "Waiting" else "Status unavailable"
            },
            acceptance, criteria, groups(observed), capabilities(verification), observed.size, eventWindowComplete,
            if (evidenceReadFailed) false else retained, verification != null,
            failureCategory,
            recovery,
            when {
                !recoverable -> "Recovery is available after an interrupted or incomplete run."
                unreconciledGateway -> "The gateway outcome is unknown and no saved job can be reconciled. Inspect the remote operation before continuing; Resume and Retry are unavailable to prevent repeating a possible mutation."
                deterministic -> "Update the blocked configuration, authorization or request first, then continue the saved request in Chat. Details retain the recorded failure."
                waiting -> "The recorded service limit is still blocking recovery. Review the retry time or connection status before continuing in Chat."
                unknown -> "Resume checks saved outcomes before continuing. An unfinished operation or incomplete activity history prevents direct retry."
                else -> "Recovery continues this saved request and preserves its completed operation receipts."
            }
        )
    }

    fun groups(events: List<ToolEvent>): List<GitHubOperationGroup> = events.groupBy { event ->
        val arguments = jsonObject(event.arguments).orEmpty().filterKeys { it !in pagination }.toSortedMap()
        event.toolName to JsonObject(arguments).toString()
    }.map { (identity, rows) ->
        val action = jsonObject(identity.second)?.text("action")
        val name = identity.first.substringBefore("__").removePrefix("github_").replace('_', ' ')
        GitHubOperationGroup(
            listOfNotNull(name, action).distinct().joinToString(" · "),
            rows.size,
            rows.count { it.status == ToolEventStatus.COMPLETED && !it.isError },
            rows.count { it.status == ToolEventStatus.FAILED || it.isError },
            rows.count { it.status in setOf(ToolEventStatus.PENDING, ToolEventStatus.RUNNING) }
        )
    }

    private fun capabilities(verification: JsonObject?): List<GitHubCapabilityObservation> {
        val ledger = verification?.get("acceptance_ledger") as? JsonObject
        val rows = ledger?.get("capabilities") as? JsonArray ?: verification?.get("capabilities") as? JsonArray ?: return emptyList()
        return rows.filterIsInstance<JsonObject>().mapNotNull { row ->
            val readiness = row["readiness"] as? JsonObject ?: return@mapNotNull null
            GitHubCapabilityObservation(
                row.text("criterion") ?: row.text("scope") ?: row.text("id") ?: "Tool action",
                (readiness["discovered"] as? JsonPrimitive)?.booleanOrNull,
                readiness.text("authorization") ?: "UNKNOWN",
                readiness.text("availability") ?: "UNKNOWN",
                (readiness["tested"] as? JsonPrimitive)?.booleanOrNull,
                (readiness["successfully_tested"] as? JsonPrimitive)?.booleanOrNull
            )
        }
    }

    private fun unresolvedEffect(event: ToolEvent): Boolean {
        if (event.status in setOf(ToolEventStatus.PENDING, ToolEventStatus.RUNNING)) return true
        val result = jsonObject(event.result)
        val execution = result?.get("execution") as? JsonObject
        if (((execution?.get("unknown_mutations") ?: result?.get("unknown_mutations")) as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true) return true
        val text = listOfNotNull(event.error, result?.text("error_code"), result?.text("code")).joinToString(" ")
        val ambiguous = Regex("(?i)unknown|uncertain|timeout|timed.out|lost.response").containsMatchIn(text)
        val action = jsonObject(event.arguments)?.text("action").orEmpty()
        val mutation = action in mutationActions || Regex("(?i)write|commit|merge|delete|publish").containsMatchIn(event.toolName)
        return ambiguous && mutation
    }

    private fun typedFailure(text: String?): String? = text?.let { code.find(it)?.value }
    private fun jsonObject(value: String?): JsonObject? = value?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
