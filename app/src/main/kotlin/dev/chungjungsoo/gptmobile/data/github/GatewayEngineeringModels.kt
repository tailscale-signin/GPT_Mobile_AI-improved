package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class GatewayEngineeringCapabilities(
    val service: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val enabled: Boolean = false,
    @SerialName("sampling_policy_protocol") val samplingPolicyProtocol: Int = 0,
    @SerialName("principal_id") val principalId: String? = null,
    @SerialName("gateway_version") val gatewayVersion: String? = null,
    @SerialName("server_build") val serverBuild: String? = null,
    @SerialName("code_graph") val codeGraph: GatewayGraphCapability? = null,
    val readiness: GatewayEngineeringReadiness? = null,
    @SerialName("admission_preflight_protocol") val admissionPreflightProtocol: Int = 0,
    @SerialName("gateway_id") val gatewayId: String? = null,
    @SerialName("task_scope_protocol") val taskScopeProtocol: Int = 0,
    @SerialName("task_scopes") val taskScopes: List<String> = emptyList(),
    @SerialName("conversation_sync_v1") val conversationSync: Boolean = false,
    @SerialName("durable_detach_v1") val durableDetach: Boolean = false,
    @SerialName("repository_operations") val repositoryOperations: GatewayRepositoryCapability? = null,
    @SerialName("duration_hours") val durationHours: List<Int> = listOf(3, 6, 12, 0),
    @SerialName("execution_budget_limits") val executionBudgetLimits: GatewayEngineeringBudget? = null,
    @SerialName("execution_batch") val executionBatch: GatewayIntegrationBatchCapability? = null
) {
    val supportsFullBatch: Boolean get() = executionBatch?.let {
        it.protocolVersion == 1 && it.fullEnabled && "full" in it.modes
    } == true
    val supportsDurableChat: Boolean get() = taskScopeProtocol == 1 && taskScopes.containsAll(listOf("read", "plan", "implement")) && conversationSync && durableDetach

    /** A valid capability HTTP response is not evidence that a dependent job was admitted. */
    fun requireJobReadiness(savedResultsOnly: Boolean = false) {
        val reason = if (savedResultsOnly) {
            GatewayAdmissionReason.ENGINEERING_STORAGE_UNAVAILABLE.takeIf { readiness?.savedResults == false }
        } else {
            jobReadinessFailure()
        }
        if (reason != null) throw GatewayAdmissionException(reason, 200, null, GatewayAdmissionSurface.CAPABILITIES)
    }

    internal fun jobReadinessFailure(): GatewayAdmissionReason? {
        if (enabled && readiness?.ready != false) return null
        val checks = readiness?.checks
        return when {
            checks?.journal == "disabled" -> GatewayAdmissionReason.ENGINEERING_STORAGE_DISABLED
            checks?.journal in setOf("unavailable", "write_failed") -> GatewayAdmissionReason.ENGINEERING_STORAGE_UNAVAILABLE
            checks?.hostGithubConnection == "missing" -> GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED
            checks?.draining == true -> GatewayAdmissionReason.ENGINEERING_DRAINING
            checks?.worker == "unavailable" -> GatewayAdmissionReason.ENGINEERING_WORKER_UNAVAILABLE
            else -> GatewayAdmissionReason.ENGINEERING_UNAVAILABLE
        }
    }

    fun unavailableReason(graph: Boolean = false): String? {
        val ready = if (graph) codeGraph?.enabled == true && readiness?.graphReady != false else enabled && readiness?.ready != false
        if (ready) return null
        val checks = readiness?.checks
        return when {
            checks?.journal == "disabled" -> "Enable durable storage in the selected desktop gateway before using engineering jobs or repository graphs."
            checks?.journal in setOf("unavailable", "write_failed") -> "The engineering journal is unavailable. Check the desktop gateway storage and retained jobs before retrying."
            (if (graph) checks?.graphHostGithubConnection ?: checks?.hostGithubConnection else checks?.hostGithubConnection) == "missing" -> "Configure the GitHub connection on the selected gateway host. Chat inference availability does not establish engineering or repository graph readiness."
            checks?.draining == true -> "The selected engineering gateway is stopping or updating. Reconnect to its retained jobs when it is ready."
            !graph && checks?.worker == "unavailable" -> "The engineering worker is not running. Check the selected desktop gateway; retained jobs remain available."
            graph && codeGraph?.enabled != true -> "The selected gateway has not advertised an available repository graph service. Check its engineering status and update it if needed."
            else -> "The gateway engineering service is disabled or unavailable. Chat inference readiness does not establish job readiness."
        }
    }
}

@Serializable
data class GatewayIntegrationBatchCapability(
    @SerialName("protocol_version") val protocolVersion: Int = 0,
    val modes: List<String> = emptyList(),
    @SerialName("default_mode") val defaultMode: String = "standard",
    @SerialName("full_enabled") val fullEnabled: Boolean = false,
    @SerialName("github_api_max_inflight") val githubApiMaxInflight: Int = 1
)

@Serializable
data class GatewayIntegrationBatchProgress(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val mode: String = "",
    val revision: Int = 0,
    val completed: Int = 0,
    val total: Int = 0,
    @SerialName("current_phase") val currentPhase: String? = null,
    @SerialName("current_state") val currentState: String? = null,
    val status: String? = null
) {
    val readableLabel: String get() = "Full Batch · $completed of $total stages complete · ${currentPhase ?: "planning"}"
}

@Serializable
data class GatewayRepositoryCapability(
    val schema: String = "",
    val enabled: Boolean = false,
    val operations: List<String> = emptyList(),
    @SerialName("copy_modes") val copyModes: List<String> = emptyList()
) {
    fun supports(operation: String): Boolean = schema == "github.repository-lifecycle.v1" &&
        enabled &&
        operation in setOf("clone_repository", "import_repository") &&
        operation in operations &&
        (operation != "import_repository" || "history" in copyModes)
}

@Serializable
data class GatewayGraphCapability(@SerialName("schema_version") val schemaVersion: Int = 0, val enabled: Boolean = false)

@Serializable
data class GatewayEngineeringReadiness(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val ready: Boolean = false,
    @SerialName("graph_ready") val graphReady: Boolean = false,
    @SerialName("saved_results") val savedResults: Boolean = false,
    val checks: GatewayEngineeringChecks = GatewayEngineeringChecks()
)

@Serializable
data class GatewayEngineeringChecks(
    val journal: String? = null,
    @SerialName("host_github_connection") val hostGithubConnection: String? = null,
    val worker: String? = null,
    @SerialName("validation_policy") val validationPolicy: String? = null,
    val acceptance: String? = null,
    val draining: Boolean = false,
    @SerialName("graph_host_github_connection") val graphHostGithubConnection: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GatewayEngineeringSettings(@EncodeDefault val durationHours: Int = 24, val repairAttempts: Int = 5, val acceptance: List<GatewayEngineeringAcceptance> = emptyList(), val executionBudget: GatewayEngineeringBudget? = null) {
    init {
        require(durationHours in setOf(0, 3, 6, 12, 24) && repairAttempts in 1..5)
        require(acceptance.size <= 32 && acceptance.map { it.id }.distinct().size == acceptance.size)
    }
}

@Serializable
data class GatewayEngineeringAcceptance(val id: String, val paths: List<String>, val checks: List<String>, val evidence: List<GatewayRequirementEvidence> = emptyList()) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(paths.size in 1..32 && checks.size in 1..32)
        require((paths + checks).all { it.isNotBlank() && it.length <= 400 })
        require(paths.all { !it.startsWith('/') && '\\' !in it && it.split('/').none { part -> part in setOf("", ".", "..", ".git") } })
        require(evidence.size <= 32)
    }
}

@Serializable
data class GatewayRequirementEvidence(val kind: String, val name: String) {
    init {
        require(kind in setOf("behavior_test", "api_receipt", "device_trace", "model_runtime", "artifact") && name.length in 1..200)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GatewayEngineeringRequest(
    val repository: String,
    val branch: String,
    val base: String,
    val objective: String,
    val model: String,
    // Legacy retained requests omitted their three-hour default. New admissions supply settings explicitly.
    @EncodeDefault @SerialName("duration_hours") val durationHours: Int = 3,
    @SerialName("repair_attempts") val repairAttempts: Int = 5,
    @SerialName("chat_id") val chatId: Int,
    @SerialName("request_id") val requestId: String,
    val acceptance: List<GatewayEngineeringAcceptance> = emptyList(),
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("memory_mode") val memoryMode: GitHubEngineeringMemoryMode = GitHubEngineeringMemoryMode.HYBRID,
    @SerialName("execution_binding_id") val executionBindingId: String? = null,
    @SerialName("task_scope") val taskScope: String = "implement",
    @SerialName("client_conversation_id") val clientConversationId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("expected_revision") val expectedRevision: Long? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("execution_budget") val executionBudget: GatewayEngineeringBudget? = null,
    @SerialName("draft_repair") val draftRepair: GitHubDraftRepair? = null,
    @SerialName("sampling_policy") val samplingPolicy: GitHubSamplingSettings? = null,
    @SerialName("execution_mode") val executionMode: String? = null,
    @SerialName("batch_policy_version") val batchPolicyVersion: Int? = null
)

@Serializable
data class GatewayEngineeringUpdate(val revision: Int, val state: String, val message: String, @SerialName("created_at") val createdAt: Double)

@Serializable
data class GatewayEngineeringJob(
    val id: String,
    val repository: String,
    val branch: String,
    @SerialName("chat_id") val chatId: Int = 0,
    val state: String,
    val revision: Int,
    @SerialName("created_at") val createdAt: Double,
    val deadline: Double,
    val message: String,
    @SerialName("milestones_completed") val milestonesCompleted: Int = 0,
    @SerialName("milestones_total") val milestonesTotal: Int = 1,
    @SerialName("repair_attempts") val repairAttempts: Int = 0,
    val pr: Int? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    @SerialName("changed_files") val changedFiles: List<String> = emptyList(),
    val tests: List<JsonObject> = emptyList(),
    @SerialName("checks_passed") val checksPassed: Boolean = false,
    @SerialName("merge_ready") val mergeReady: Boolean = false,
    @SerialName("approval_expires_at") val approvalExpiresAt: Double? = null,
    @SerialName("merge_sha") val mergeSha: String? = null,
    val updates: List<GatewayEngineeringUpdate> = emptyList(),
    @SerialName("next_revision") val nextRevision: Int? = null,
    @SerialName("has_more_updates") val hasMoreUpdates: Boolean = false,
    @SerialName("finished_at") val finishedAt: Double? = null,
    @SerialName("completion_verification") val completionVerification: JsonObject? = null,
    @SerialName("latest_evidence") val latestEvidence: JsonObject? = null,
    @SerialName("execution_binding_id") val executionBindingId: String? = null,
    @SerialName("execution_binding") val executionBinding: JsonObject? = null,
    @SerialName("latest_execution_receipt") val latestExecutionReceipt: JsonObject? = null,
    @SerialName("blocked_reason") val blockedReason: JsonObject? = null,
    @SerialName("final_report") val finalReport: JsonObject? = null,
    @SerialName("gateway_id") val gatewayId: String? = null,
    @SerialName("principal_id") val principalId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("command_id") val commandId: String? = null,
    @SerialName("accepted_at") val acceptedAt: Double? = null,
    @SerialName("server_now") val serverNow: Double? = null,
    @SerialName("detach_safe") val detachSafe: Boolean = false,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("working_branch") val workingBranch: String? = null,
    val base: String? = null,
    @SerialName("duration_hours") val durationHours: Int? = null,
    @SerialName("execution_budget") val executionBudget: GatewayEngineeringBudget? = null,
    val acceptance: List<GatewayEngineeringAcceptance> = emptyList(),
    val admission: JsonObject? = null,
    @SerialName("provider_retry_at") val providerRetryAt: Double? = null,
    @SerialName("execution_mode") val executionMode: String? = null,
    @SerialName("batch_progress") val batchProgress: GatewayIntegrationBatchProgress? = null
) {
    val ownershipLabel: String get() = if (detachSafe && acceptedAt != null && id.isNotBlank()) {
        when {
            terminal -> "Saved on Gateway"
            providerRetryAt != null -> "Saved on Gateway · waiting for provider"
            state in setOf("MERGE_READY", "NEEDS_INPUT") -> "Saved on Gateway · waiting for your input"
            else -> "Saved and running on Gateway"
        }
    } else {
        "Gateway ownership not confirmed"
    }
    val terminal: Boolean get() = state in setOf("COMPLETED", "CANCELLED", "FAILED")
    val progress: Float get() = (milestonesCompleted.toFloat() / milestonesTotal.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** No credentials or merge approval are persisted on Android. Pending creates retain their idempotency key. */
@Serializable
data class GatewayEngineeringBinding(
    val profileUid: String,
    val endpoint: String,
    val request: GatewayEngineeringRequest,
    val job: GatewayEngineeringJob? = null,
    val updates: List<GatewayEngineeringUpdate> = emptyList(),
    val syncedRevision: Int = -1,
    val seenRevision: Int = -1,
    val notifiedRevision: Int = -1,
    val principalId: String? = null,
    val localChatId: Int? = null,
    val executionSelection: GatewayExecutionSelection? = null,
    val executionAdmission: GatewayExecutionAdmission? = null,
    val localUserMessageId: Int? = null,
    val localAssistantMessageId: Int? = null,
    val localRunId: String? = null,
    val gatewayId: String? = null,
    val originalContext: GitHubConversationContext? = null,
    val budgetSelection: GatewayEngineeringBudgetSelection? = null
) {
    /** Remote request identity stays immutable when a backup maps local conversation IDs. */
    val chatId: Int get() = localChatId ?: request.chatId
}

/** The implement workflow commits changes; an explicit prohibition cannot be silently discarded. */
internal fun requireGatewayCommitPermission(objective: String, taskScope: String) {
    val denied = Regex("\\b(?:(?:do not|don't|never)\\s+(?:commit|push)|without\\s+(?:commit(?:s|ting)?|push(?:es|ing)?))\\b", RegexOption.IGNORE_CASE)
    check(taskScope != "implement" || !denied.containsMatchIn(objective)) { "Gateway implementation jobs include commits. This request explicitly forbids them; revise the task or use a read/planning request." }
}

/** A server-owned branch is authorized only from this exact pinned implement request. */
internal fun gatewayDerivedWorkingBranch(request: GatewayEngineeringRequest): String? {
    if (request.taskScope != "implement" || request.branch != request.base || request.sourceBranch != request.branch || request.sourceSha?.matches(Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")) != true) return null
    val payload = Json.encodeToString(listOf(request.requestId, request.repository, requireNotNull(request.sourceSha)))
    val suffix = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(20)
    return "codex/gateway-$suffix"
}

internal fun gatewayWorkingBranchMatches(request: GatewayEngineeringRequest, actualBranch: String?, sourceBranch: String?, sourceSha: String?, workingBranch: String?): Boolean {
    val derived = gatewayDerivedWorkingBranch(request)
    if (derived != null) return actualBranch == derived && workingBranch == derived && sourceBranch == request.sourceBranch && sourceSha == request.sourceSha
    return actualBranch == request.branch && (sourceBranch == null || request.sourceBranch == null || sourceBranch == request.sourceBranch) && (sourceSha == null || request.sourceSha == null || sourceSha == request.sourceSha)
}

@Serializable
data class GatewayEngineeringPreflight(
    val schema: String = "",
    val ready: Boolean = false,
    val acceptance: List<GatewayEngineeringAcceptance> = emptyList(),
    val blockers: List<GatewayEngineeringBlocker> = emptyList(),
    @SerialName("objective_sha256") val objectiveSha256: String = "",
    @SerialName("source_sha") val sourceSha: String? = null
)

@Serializable
data class GatewayEngineeringBlocker(
    val dependency: String,
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("failed_stage") val failedStage: String,
    val message: String,
    @SerialName("recovery_action") val recoveryAction: String
)
