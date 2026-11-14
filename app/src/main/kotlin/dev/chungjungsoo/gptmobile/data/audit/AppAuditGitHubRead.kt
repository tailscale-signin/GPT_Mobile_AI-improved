package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubReadRequestBudgetExceeded
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Response bodies stay in this call. Only typed counts, status and actual transport evidence leave it. */
internal suspend fun appAuditGitHubRead(
    check: AppAuditGitHubCheck,
    repository: GitHubRepositoryContext?,
    accountId: String?,
    client: GitHubWorkspaceClient,
    beforeDispatch: suspend () -> Unit
): AppAuditObservation {
    require(check in AppAuditGitHubCatalog.live && check.action in GitHubWorkspaceClient.readActions)
    val arguments = buildJsonObject {
        repository?.let {
            put("owner", it.owner)
            put("repo", it.repo)
            put("ref", it.ref)
        }
        put("page", 1)
        put("per_page", AppAuditGitHubCatalog.PAGE_SIZE)
        put("include_patches", false)
    }
    val accounting = GitHubDispatchAccounting(
        forceLiveReads = true,
        record = {},
        maxReadRequests = AppAuditGitHubCatalog.MAX_REQUESTS_PER_READ,
        requireReadInventory = true
    )
    beforeDispatch()
    var failed = false
    var requestLimit = false
    var changed = false
    var accountChanged = false
    var valid = false
    var count = 0
    try {
        val response = withContext(accounting) { client.execute(requireNotNull(check.action), arguments) } as? JsonObject
        val responseId = (response?.get("id") as? JsonPrimitive)?.longOrNull
        accountChanged = check.action == "get_account" && accountId != null && responseId != null && responseId.toString() != accountId
        val rows = (response?.get(if (check.action == "get_commit_checks") "check_runs" else "items") as? JsonArray)
        val sha = (response?.get("sha") as? JsonPrimitive)?.contentOrNull
        valid = when (check.action) {
            "get_account" -> responseId != null && responseId > 0 && !accountChanged
            "get_repository" ->
                responseId != null &&
                    responseId > 0 &&
                    (response["full_name"] as? JsonPrimitive)?.contentOrNull?.equals(repository?.fullName, ignoreCase = true) == true
            "get_commit_detail" -> sha?.matches(Regex("[a-fA-F0-9]{40}")) == true &&
                (repository?.ref?.matches(Regex("[a-fA-F0-9]{40}")) != true || repository.ref.equals(sha, ignoreCase = true)) &&
                (response["files"] as? JsonArray)?.size?.let { it <= AppAuditGitHubCatalog.PAGE_SIZE } == true
            else ->
                rows != null &&
                    rows.size <= AppAuditGitHubCatalog.PAGE_SIZE &&
                    rows.all { it is JsonObject } &&
                    response["page"] == JsonPrimitive(1) &&
                    response["per_page"] == JsonPrimitive(AppAuditGitHubCatalog.PAGE_SIZE)
        }
        count = rows?.size ?: if (valid) 1 else 0
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AppAuditGitHubConfigurationChanged) {
        changed = true
        failed = true
    } catch (_: GitHubReadRequestBudgetExceeded) {
        requestLimit = true
        failed = true
    } catch (_: Exception) {
        failed = true
    }
    val result = accounting.finish(AgentToolResult("app-audit-github", ToolResultContent.Text("Bounded GitHub metadata check"), failed || !valid), false)
    val receipt = requireNotNull(result.executionReceipt)
    val requests = (receipt["read_requests"] as? JsonPrimitive)?.intOrNull ?: 0
    val responses = (receipt["read_response_observations"] as? JsonPrimitive)?.intOrNull ?: 0
    val status = (receipt["http_status"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 }
    val live = receipt["live_read_performed"] == JsonPrimitive(true) && receipt["cached_read_observations"] == JsonPrimitive(0)
    val (outcome, reason) = when {
        changed || accountChanged -> AppAuditOutcome.BLOCKED to "CONFIGURATION_CHANGED"
        requestLimit -> AppAuditOutcome.BLOCKED to "GITHUB_AUDIT_REQUEST_LIMIT"
        status in setOf(401, 403) -> AppAuditOutcome.BLOCKED to "GITHUB_AUDIT_ACCESS_DENIED"
        status == 429 -> AppAuditOutcome.BLOCKED to "GITHUB_AUDIT_RATE_LIMITED"
        status == 404 -> AppAuditOutcome.UNAVAILABLE to "GITHUB_AUDIT_RESOURCE_UNAVAILABLE"
        failed && requests == 0 -> AppAuditOutcome.BLOCKED to "GITHUB_AUDIT_READ_BLOCKED"
        failed && (status == null || status >= 500) -> AppAuditOutcome.UNAVAILABLE to "GITHUB_AUDIT_TRANSPORT_UNAVAILABLE"
        !live -> AppAuditOutcome.UNAVAILABLE to "GITHUB_AUDIT_LIVE_RESPONSE_REQUIRED"
        !valid || failed -> AppAuditOutcome.FAIL to "GITHUB_AUDIT_RESPONSE_INVALID"
        else -> AppAuditOutcome.PASS to "GITHUB_AUDIT_READ_VERIFIED"
    }
    return AppAuditObservation(
        outcome, reason, stage = "validation", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
        observationSource = if (responses > 0) AppAuditObservationSource.LIVE else AppAuditObservationSource.NONE,
        dispatchEvidence = when {
            responses > 0 -> AppAuditDispatchEvidence.CONFIRMED
            requests > 0 -> AppAuditDispatchEvidence.ATTEMPTED
            else -> AppAuditDispatchEvidence.NOT_DISPATCHED
        },
        httpStatus = status, configChanged = changed || accountChanged,
        metrics = listOf(AppAuditMetric(AppAuditMetricName.RESULT_COUNT, count.toDouble()), AppAuditMetric(AppAuditMetricName.CHECK_COUNT, 1.0)),
        observedAtEpochMs = System.currentTimeMillis().takeIf { responses > 0 }
    )
}
