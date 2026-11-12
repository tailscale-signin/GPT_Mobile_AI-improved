package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.isSelectedModelBlocker
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Durable classification. Legacy text is parsed once at the boundary, not by each dialog. */
@Serializable
data class ProfileFailureRecord(
    val category: String,
    val retryAllowed: Boolean,
    val retryAfterMillis: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val dispatched: Boolean? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val remoteAcceptance: RemoteRequestAcceptance = RemoteRequestAcceptance.UNKNOWN,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val phase: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val resourceKeys: Set<String> = emptySet(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invocationId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val serverRequestId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val generationCompleted: Boolean? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val requestReplayAllowed: Boolean? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val causeTypes: List<String> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val dispatchState: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val recoveryActions: List<String> = emptyList()
) {
    val isTaskVerification: Boolean get() = category in setOf("TASK_INCOMPLETE", "EVIDENCE_REQUIRED") ||
        category.startsWith("GITHUB_EXTRACTION_") ||
        category in setOf(
            "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION",
            "GITHUB_TOOL_BUDGET_EXHAUSTED",
            "GITHUB_TASK_BUDGET_EXHAUSTED",
            "TOOL_BUDGET_EXHAUSTED",
            "TASK_BUDGET_EXHAUSTED",
            "VERIFICATION_RESERVE_REACHED"
        )
    val isUnsentSetup: Boolean get() = dispatched == false &&
        remoteAcceptance == RemoteRequestAcceptance.NO &&
        (
            phase == "connection_preflight" &&
                category.startsWith("GATEWAY_ENGINEERING_") ||
                phase == "provider_admission" &&
                category.isSelectedModelBlocker()
            )

    companion object {
        fun reviewRetryBlock(details: String?): String? = details?.takeIf { classify(it).category == "REVIEW_INPUT_REQUIRED" }?.let {
            "This saved review requires an updated request or new evidence. Send an updated prompt; the saved draft and tool results are retained."
        }

        private val phases = setOf("admission", "planning", "repository_admission", "provider_admission", "request_validation", "message_admission", "backend_admission", "binding_admission", "execution_admission", "connection_preflight", "dns_resolution", "dns", "connection", "response_stream", "tls", "cancelled", "transport", "provider_generation", "model_invocation", "tool_precheck", "tool_readiness", "tool_admission", "task_budget", "inventory_pagination", "tool_dispatch", "tool_execution", "tool_continuation", "tool_reconciliation", "backend_template", "gateway_recovery", "gateway_dispatch", "review_compatibility", "answer_review", "claim_extraction", "claim_verification", "verification", "final_synthesis", "delivery", "lifecycle")
        private val executionCode = Regex("EXECUTION_[A-Z0-9_]{1,72}")
        private val repositoryAdmissionCodes = setOf("GITHUB_REPOSITORY_UNAVAILABLE", "GITHUB_REF_UNAVAILABLE")
        private val githubSchemaAdmissionCodes = setOf("GITHUB_TOOL_CONTEXT_BUDGET", "GITHUB_TOOL_CONTRACT_UNSUPPORTED")
        private val readinessCodes = setOf("REQUIRED_TOOL_ACQUISITION_PENDING", "REQUIRED_TOOL_ACQUISITION_TIMED_OUT", "REQUIRED_TOOL_ACQUISITION_FAILED", "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE", "REQUIRED_TOOL_AMBIGUOUS_CONFIGURATION", "REQUIRED_TOOL_AUTHORIZATION_REQUIRED")
        private val engineeringPreflightCodes = setOf("ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED", "VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED", "SOURCE_REVISION_REQUIRED", "VALIDATION_REVISION_UNAVAILABLE", "VALIDATION_RUNTIME_UNAVAILABLE", "ENGINEERING_REPOSITORY_ACCESS_REQUIRED", "SOURCE_REVISION_CHANGED")
        private val gatewayAdmissionCodes = dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason.entries.mapTo(hashSetOf()) { "GATEWAY_${it.name}" }
        private val continuationCodes = setOf("OUTPUT_CONTINUATION_PENDING_TOOLS", "OUTPUT_CONTINUATION_NO_PROGRESS", "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED", "OUTPUT_CONTINUATION_UNAVAILABLE", "FINAL_RESPONSE_REQUIRED")
        private val typedCodes = setOf("GITHUB_REPOSITORY_TARGET_REQUIRED", "GITHUB_REPOSITORY_TARGET_AMBIGUOUS", "REQUEST_REJECTED", "CIRCUIT_BREAKER_OPEN", "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "TLS_FAILURE", "CLIENT_CANCELLED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "DISPATCH_POLICY_CHANGED", "POLICY_STALE", "STALE_INVENTORY", "SERIALIZATION_ERROR", "PARSING_ERROR", "SERVICE_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "GITHUB_TASK_INCOMPLETE", "GITHUB_EVIDENCE_REQUIRED", "GITHUB_EVIDENCE_RETENTION_INCOMPLETE", "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION", "GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED", "GITHUB_TOOL_BUDGET_EXHAUSTED", "GITHUB_TASK_BUDGET_EXHAUSTED", "TOOL_BUDGET_EXHAUSTED", "TASK_BUDGET_EXHAUSTED", "VERIFICATION_RESERVE_REACHED", "SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "DEPENDENCY_DEFERRED", "REPLAY_EVIDENCE_REQUIRED", "TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH", "RECONCILIATION_REQUIRED", "GATEWAY_OUTCOME_UNKNOWN", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")

        /** Preserve typed stage/dispatch facts; arbitrary provider text is never promoted to evidence. */
        fun fromProvider(message: String, errorCode: String? = null, details: JsonObject? = null): ProfileFailureRecord {
            val invocationId = (details?.get("invocation_id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf {
                it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            }
            val rejectedStatus = (details?.get("http_status") as? JsonPrimitive)?.contentOrNull in setOf("409", "422")
            val code = errorCode?.uppercase(java.util.Locale.ROOT)?.takeIf {
                it == "WAITING_FOR_APPROVAL" || executionCode.matches(it) || it in continuationCodes || it in typedCodes || it in repositoryAdmissionCodes || it in githubSchemaAdmissionCodes || it in gatewayAdmissionCodes || it in engineeringPreflightCodes || it in readinessCodes || it in dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.codes || it in dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.codes
            } ?: "REQUEST_REJECTED".takeIf { rejectedStatus }
            val observed = classify(listOfNotNull(if (code == "EXECUTION_OPERATION_UNKNOWN_OUTCOME") GatewayOutcomeRecovery.ERROR_CODE else code, message).joinToString(": "))
            val serverRequestId = dev.chungjungsoo.gptmobile.data.network.safeProviderRequestIdentity((details?.get("server_request_id") as? JsonPrimitive)?.contentOrNull)
            val generationCompleted = (details?.get("generation_completed") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            val causeTypes = (details?.get("cause_types") as? JsonArray).orEmpty().take(16).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.length <= 160 && value.matches(Regex("[A-Za-z0-9_.$]+")) } }
            // A local rejection must not replace an already ambiguous remote operation.
            if (observed.category in setOf(GatewayOutcomeRecovery.ERROR_CODE, "TOOL_OUTCOME_UNKNOWN")) {
                // Positive acceptance and identity are useful observations, not proof
                // that uncertain tool work finished. A later negative admission
                // receipt cannot establish that the earlier operation was never sent.
                return observed.copy(
                    retryAllowed = false,
                    dispatched = true.takeIf { observed.category == GatewayOutcomeRecovery.ERROR_CODE || details?.get("dispatched") == JsonPrimitive(true) },
                    remoteAcceptance = if ((details?.get("remote_acceptance") as? JsonPrimitive)?.contentOrNull in setOf("YES", "ACCEPTED")) RemoteRequestAcceptance.YES else observed.remoteAcceptance,
                    invocationId = invocationId,
                    serverRequestId = serverRequestId,
                    generationCompleted = generationCompleted,
                    requestReplayAllowed = false,
                    causeTypes = causeTypes
                )
            }
            val authoritative = code?.let { if (executionCode.matches(it)) ProfileFailureRecord(it, retryAllowed = false, phase = "provider_admission") else classify(it) }
            val legacy = if (authoritative != null) {
                // The current typed failure outranks incidental codes in provider
                // prose. Preserve any additional retry restriction and timing facts.
                observed.copy(
                    category = authoritative.category,
                    retryAllowed = authoritative.retryAllowed && observed.retryAllowed,
                    phase = authoritative.phase.takeUnless { it == "stage_not_recorded" } ?: observed.phase,
                    retryAfterMillis = if (authoritative.isTaskVerification) null else observed.retryAfterMillis
                )
            } else {
                observed
            }
            val stage = (details?.get("failed_stage") as? JsonPrimitive)?.contentOrNull?.takeIf { it == "approval" || it in phases }
            val dispatchFact = details?.get("dispatched")
                ?: details?.get("inference_dispatched")?.takeIf { stage == "connection_preflight" }
            val dispatchState = (details?.get("dispatch_state") as? JsonPrimitive)?.contentOrNull?.lowercase(java.util.Locale.ROOT)
            val dispatched = (dispatchFact as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: false.takeIf { dispatchState in setOf("not_dispatched", "precheck_rejected") }
                ?: true.takeIf { dispatchState == "dispatched" }
                ?: true.takeIf { details?.get("remote_acceptance") in setOf(JsonPrimitive("YES"), JsonPrimitive("ACCEPTED")) }
                ?: legacy.dispatched
            val localCircuitRejection = legacy.category == "CIRCUIT_BREAKER_OPEN" && stage == "provider_admission" && dispatched == false
            val authorityPrecheck = legacy.category == "POLICY_STALE" && stage in setOf("provider_admission", "tool_precheck") && dispatched == false
            val catalogPrecheck = dispatched == false && (legacy.category == "STALE_INVENTORY" && stage in setOf("inventory_pagination", "tool_precheck") || legacy.category in readinessCodes && stage == "tool_readiness")
            val repositoryPrecheck = dispatched == false && legacy.category in repositoryAdmissionCodes && stage == "repository_admission"
            val retryAfter = (details?.get("retry_after_ms") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0L..86_400_000L }
            val observedAcceptance = when ((details?.get("remote_acceptance") as? JsonPrimitive)?.contentOrNull) {
                "YES", "ACCEPTED" -> RemoteRequestAcceptance.YES
                "NO", "NOT_ACCEPTED" -> RemoteRequestAcceptance.NO
                else -> legacy.remoteAcceptance
            }
            val logicalContinuation = code in setOf("CONNECTION_ABORTED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "DNS_RESOLUTION_FAILED", "TRANSPORT_EOF") &&
                details?.get("logical_continuation_allowed") == JsonPrimitive(true) &&
                details["requires_reconciliation"] != JsonPrimitive(true)
            return legacy.copy(
                phase = stage ?: legacy.phase.ifBlank { "stage_not_recorded" },
                retryAllowed = legacy.retryAllowed && (logicalContinuation || details?.get("retryable") != JsonPrimitive(false)),
                dispatched = dispatched,
                remoteAcceptance = if (localCircuitRejection || authorityPrecheck || catalogPrecheck || repositoryPrecheck || dispatchState in setOf("not_dispatched", "precheck_rejected")) RemoteRequestAcceptance.NO else observedAcceptance,
                retryAfterMillis = if ((localCircuitRejection || repositoryPrecheck) && retryAfter != null) System.currentTimeMillis() + retryAfter else legacy.retryAfterMillis,
                invocationId = invocationId,
                serverRequestId = serverRequestId,
                generationCompleted = generationCompleted,
                requestReplayAllowed = (details?.get("request_replay_allowed") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
                causeTypes = causeTypes,
                dispatchState = dispatchState?.uppercase(java.util.Locale.ROOT)?.takeIf { it in setOf("NOT_DISPATCHED", "PRECHECK_REJECTED", "DISPATCHED", "FAILED", "COMPLETED", "UNKNOWN") },
                recoveryActions = (details?.get("recovery_actions") as? JsonArray).orEmpty().mapNotNull { item ->
                    (item as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY", "SELECT_REF") }
                }.distinct()
            )
        }

        private val httpStatus = Regex("""(?i)\b(?:HTTP(?:/\d(?:\.\d)?)?(?:\s+(?:error|status)(?:\s+code)?)?|status(?:\s+code)?|statusCode|responseCode)["']?(?:\s*[:=]\s*|\s+)(\d{3})\b""")

        fun classify(details: String, demoExcluded: Boolean = false, nowMillis: Long = System.currentTimeMillis()): ProfileFailureRecord {
            if (GatewayOutcomeRecovery.requiresReconciliation(details)) {
                return ProfileFailureRecord(GatewayOutcomeRecovery.ERROR_CODE, retryAllowed = false, dispatched = true, remoteAcceptance = RemoteRequestAcceptance.UNKNOWN, phase = "gateway_dispatch")
            }
            val epoch = Regex("(?i)rate limited until ([0-9]{10,16})").find(details)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { if (it < 100_000_000_000L) it * 1000 else it }
            val iso = Regex("(?i)next eligible retry:\\s*([^\\s]+)").find(details)?.groupValues?.get(1)
                ?.let { runCatching { Instant.parse(it.trimEnd('.', ',', ';')).toEpochMilli() }.getOrNull() }
            val circuit = details.contains("CIRCUIT_BREAKER_OPEN", true) || details.contains("GITHUB_CIRCUIT_OPEN", true) || details.contains("Circuit breaker", true)
            val cooldown = Regex("(?i)(?:retryAfterMs\\s*[=:]\\s*|cooldown remaining:\\s*)([0-9]+)").find(details)?.groupValues?.get(1)?.toLongOrNull()
            val retryAt = iso ?: epoch ?: cooldown?.let { nowMillis + it.coerceIn(0, 86_400_000) }
            val unavailablePolicy = details.contains("POLICY_SNAPSHOT_", true)
            val incompatible = demoExcluded || listOf("Serializer for subclass", "Serializer for class", "enforceable", "unsupported", "budget-blocked", "demo exclusion", "demo endpoint", "demo model", "demo provider", "TOOL_CHOICE_MISMATCH", "RESEARCH_CONFIGURATION_CHANGED", "COMBINED_OUTPUT_ALLOWANCE_CHANGED", "Profile is disabled", "PROFILE_DISABLED", "PROFILE_REMOVED", "PROFILE_CONFIGURATION_CHANGED", "PROFILE_IDENTITY_AMBIGUOUS", "GITHUB_PROFILE_LOCKED", "DISPATCH_POLICY_CHANGED", "Unknown Free route").any { details.contains(it, true) }
            val statuses = httpStatus.findAll(details).map { it.groupValues[1] }.toSet()
            val permanentEndpoint = "410" in statuses || details.trim() == "410" || Regex("(?i)\\b410\\s+Gone\\b|endpoint.{0,40}(?:retired|removed|gone)|model.{0,30}(?:decommissioned|retired)").containsMatchIn(details)
            val billing = "402" in statuses || details.trim() == "402" || Regex("(?i)credit balance|insufficient (?:credits|funds|balance)|billing.{0,30}(?:required|disabled|exhausted)|payment required").containsMatchIn(details)
            val reviewBlocked = details.contains("GITHUB_REPOSITORY_TARGET_REQUIRED", true) || details.contains("GITHUB_REPOSITORY_TARGET_AMBIGUOUS", true) || details.contains("GITHUB_REVIEW_INCOMPATIBLE", true) || details.contains("GITHUB_REVIEW_INDETERMINATE", true) || details.contains("REVIEW_INPUT_REQUIRED", true) || details.contains("Saved review requires new evidence", true) || details.contains("GITHUB_REVIEW_BLOCKED", true) || details.contains("unchanged draft will not be reviewed again", true)
            val privacyBlocked = details.contains("POLICY_RESTRICTED", true) || details.contains("PERSONAL_DATA_LOCAL_ONLY", true)
            val githubCode = dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.recordedCode(details)
            val contract = dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.find(details)
            val engineeringPreflight = engineeringPreflightCodes.firstOrNull { details.contains(it, true) }
            val gatewayAdmission = gatewayAdmissionCodes.firstOrNull { details.contains(it, true) }
            val readinessCode = readinessCodes.firstOrNull { details.contains(it, true) }
            val repositoryAdmission = repositoryAdmissionCodes.firstOrNull { details.contains(it, true) }
            val githubSchemaAdmission = githubSchemaAdmissionCodes.firstOrNull { details.contains(it, true) }
            val continuationCode = continuationCodes.firstOrNull { details.contains(it, true) }
            val verificationCode = typedCodes.firstOrNull { code ->
                details.contains(code, true) &&
                    (
                        code.startsWith("GITHUB_EXTRACTION_") ||
                            code in setOf(
                                "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION",
                                "GITHUB_TOOL_BUDGET_EXHAUSTED",
                                "GITHUB_TASK_BUDGET_EXHAUSTED",
                                "TOOL_BUDGET_EXHAUSTED",
                                "TASK_BUDGET_EXHAUSTED",
                                "VERIFICATION_RESERVE_REACHED",
                                "SAVED_VIEW_UNAVAILABLE",
                                "SAVED_VIEW_WRONG_OPERATION",
                                "SAVED_VIEW_CURSOR_ALTERED",
                                "DEPENDENCY_DEFERRED"
                            )
                        )
            }
            val category = when {
                listOf("TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH", "RECONCILIATION_REQUIRED", "GATEWAY_TOOL_OUTCOME_UNKNOWN").any { details.contains(it, true) } -> "TOOL_OUTCOME_UNKNOWN"
                details.contains("DISPATCH_POLICY_CHANGED", true) || details.contains("POLICY_STALE", true) -> "POLICY_STALE"
                details.contains("STALE_INVENTORY", true) -> "STALE_INVENTORY"
                readinessCode != null -> readinessCode
                repositoryAdmission != null -> repositoryAdmission
                githubSchemaAdmission != null -> githubSchemaAdmission
                details.contains("WAITING_FOR_APPROVAL", true) -> "WAITING_FOR_APPROVAL"
                continuationCode != null -> continuationCode
                verificationCode != null -> verificationCode
                contract != null -> contract.code
                engineeringPreflight != null -> engineeringPreflight
                gatewayAdmission != null -> gatewayAdmission
                githubCode != null -> when {
                    githubCode == "AUTH_REQUIRED" -> "AUTHENTICATION_FAILED"
                    dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.isRateLimited(githubCode) -> "RATE_LIMIT"
                    githubCode == "GITHUB_TRANSIENT_SERVER" -> "UPSTREAM_UNAVAILABLE"
                    githubCode in setOf("PERMISSION_DENIED", "PERMISSION_REVOKED", "GITHUB_SSO_REQUIRED", "GITHUB_ENDPOINT_PERMISSION_REQUIRED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_BRANCH_RULES_BLOCKED") -> "ACCESS_FORBIDDEN"
                    else -> githubCode
                }
                details.contains("REQUEST_REJECTED", true) || statuses.any { it in setOf("409", "422") } -> "REQUEST_REJECTED"
                circuit -> "CIRCUIT_BREAKER_OPEN"
                reviewBlocked -> "REVIEW_INPUT_REQUIRED"
                details.contains("GITHUB_REVIEW_", true) -> "ANSWER_REVIEW"
                details.contains("GITHUB_TASK_INCOMPLETE", true) -> "TASK_INCOMPLETE"
                listOf("GITHUB_EVIDENCE_REQUIRED", "GITHUB_EVIDENCE_RETENTION_INCOMPLETE", "REPLAY_EVIDENCE_REQUIRED").any { details.contains(it, true) } -> "EVIDENCE_REQUIRED"
                details.contains("TLS_FAILURE", true) -> "TLS_FAILURE"
                details.contains("CONNECTION_ABORTED", true) -> "CONNECTION_ABORTED"
                details.contains("CLIENT_CANCELLED", true) -> "CLIENT_CANCELLED"
                details.contains("AUTHENTICATION_FAILED", true) -> "AUTHENTICATION_FAILED"
                details.contains("ACCESS_FORBIDDEN", true) -> "ACCESS_FORBIDDEN"
                details.contains("SERIALIZATION_ERROR", true) || details.contains("PARSING_ERROR", true) -> "SERIALIZATION_ERROR"
                privacyBlocked -> "PRIVACY_CONFIGURATION"
                permanentEndpoint -> "ENDPOINT_RETIRED"
                billing -> "BILLING_UNAVAILABLE"
                details.contains("QUEUE_JOB_PENDING", true) -> "QUEUED_JOB"
                details.contains("CACHED_UNUSABLE_RESPONSE", true) -> "CACHED_UNUSABLE_RESPONSE"
                details.contains("STREAM_DEGRADED", true) -> "STREAM_DEGRADED"
                unavailablePolicy -> "SAVED_POLICY_UNAVAILABLE"
                details.contains("TOOL_CHOICE_MISMATCH", true) -> "TOOL_CHOICE_MISMATCH"
                details.contains("PROVIDER_PAYLOAD_ERROR", true) -> "PROVIDER_PAYLOAD_ERROR"
                details.contains("ADMISSION_EXPIRED", true) -> "ADMISSION_EXPIRED"
                dev.chungjungsoo.gptmobile.data.network.error.isOutputProtocolFailure(details) -> "OUTPUT_PROTOCOL_INVALID"
                incompatible -> "INCOMPATIBLE_CONFIGURATION"
                details.contains("ForegroundService", true) || details.contains("mAllowStartForeground", true) || details.startsWith("APP_EXECUTION_") -> "APP_EXECUTION"
                details.contains("REASONING_BUDGET_EXHAUSTED", true) || details.contains("COMBINED_WRITER_STALLED", true) -> "REASONING_BUDGET_EXHAUSTED"
                details.contains("NO_VISIBLE_ANSWER", true) || details.contains("COMBINED_EMPTY_OUTPUT", true) -> "NO_VISIBLE_ANSWER"
                details.contains("HOSTED_GENERATION_FAILED", true) -> "HOSTED_GENERATION_FAILED"
                retryAt != null || Regex("(?i)429|rate.?limit|cooldown|quota").containsMatchIn(details) -> "RATE_LIMIT"
                Regex("(?i)DNS|UnknownHostException|unable to resolve host|host cannot be resolved|name or service not known").containsMatchIn(details) -> "DNS_RESOLUTION_FAILED"
                Regex("(?i)timeout|timed out|NETWORK_TIMEOUT").containsMatchIn(details) -> "TIMEOUT"
                Regex("(?i)unexpected end of stream|TRANSPORT_EOF|EOFException").containsMatchIn(details) -> "TRANSPORT_EOF"
                Regex("(?i)\\b50[234]\\b").containsMatchIn(details) || details.contains("SERVICE_UNAVAILABLE", true) || details.contains("PROVIDER_UNAVAILABLE", true) -> "UPSTREAM_UNAVAILABLE"
                details.contains("connection", true) || details.contains("NETWORK_DISCONNECTED", true) -> "CONNECTION"
                else -> "INCOMPLETE"
            }
            return ProfileFailureRecord(
                category,
                !incompatible && !unavailablePolicy && engineeringPreflight == null && repositoryAdmission == null && githubSchemaAdmission == null && gatewayAdmission == null && (continuationCode == null || continuationCode == "OUTPUT_CONTINUATION_UNAVAILABLE") && verificationCode == null && (readinessCode == null || readinessCode == "REQUIRED_TOOL_ACQUISITION_TIMED_OUT") && contract == null && githubCode?.let { dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.requiresCorrection(it) } != true && category !in setOf("TASK_INCOMPLETE", "EVIDENCE_REQUIRED", "REQUEST_REJECTED", "POLICY_STALE", "STALE_INVENTORY", "REVIEW_INPUT_REQUIRED", "PRIVACY_CONFIGURATION", "QUEUED_JOB", "CACHED_UNUSABLE_RESPONSE", "ENDPOINT_RETIRED", "BILLING_UNAVAILABLE", "TOOL_OUTCOME_UNKNOWN", "TLS_FAILURE", "CLIENT_CANCELLED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "SERIALIZATION_ERROR", "WAITING_FOR_APPROVAL"),
                retryAt.takeUnless { category in setOf("TASK_INCOMPLETE", "EVIDENCE_REQUIRED") },
                dispatched = false.takeIf { !circuit && category !in setOf("POLICY_STALE", "STALE_INVENTORY") && (details.contains("dispatched=false", true) || details.contains("No request was dispatched", true)) },
                phase = when {
                    repositoryAdmission != null -> "repository_admission"
                    githubSchemaAdmission != null -> "provider_admission"
                    details.contains("GITHUB_REPOSITORY_TARGET_", true) -> "planning"
                    contract != null -> contract.phase
                    engineeringPreflight != null -> "admission"
                    gatewayAdmission != null -> "connection_preflight"
                    category == "DNS_RESOLUTION_FAILED" -> "dns_resolution"
                    category == "TLS_FAILURE" -> "tls"
                    category == "CONNECTION_ABORTED" -> "response_stream"
                    category == "CLIENT_CANCELLED" -> "cancelled"
                    category in setOf("CONNECTION", "TIMEOUT", "TRANSPORT_EOF") -> "transport"
                    category == "TOOL_OUTCOME_UNKNOWN" -> "tool_dispatch"
                    category == "WAITING_FOR_APPROVAL" -> "tool_continuation"
                    category == "OUTPUT_CONTINUATION_PENDING_TOOLS" -> "tool_reconciliation"
                    category.startsWith("GITHUB_EXTRACTION_") -> "claim_extraction"
                    category in setOf("GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION", "GITHUB_TOOL_BUDGET_EXHAUSTED", "GITHUB_TASK_BUDGET_EXHAUSTED", "TOOL_BUDGET_EXHAUSTED", "TASK_BUDGET_EXHAUSTED", "VERIFICATION_RESERVE_REACHED") -> "verification"
                    category == "DEPENDENCY_DEFERRED" -> "tool_admission"
                    category.startsWith("SAVED_VIEW_") -> "inventory_pagination"
                    continuationCode != null -> "final_synthesis"
                    category == "STALE_INVENTORY" -> "inventory_pagination"
                    category in readinessCodes -> "tool_readiness"
                    category in setOf("TASK_INCOMPLETE", "EVIDENCE_REQUIRED") -> "verification"
                    category in setOf("ANSWER_REVIEW", "REVIEW_INPUT_REQUIRED") -> "answer_review"
                    category == "APP_EXECUTION" -> "lifecycle"
                    category in setOf("REQUEST_REJECTED", "POLICY_STALE", "PRIVACY_CONFIGURATION", "SAVED_POLICY_UNAVAILABLE", "INCOMPATIBLE_CONFIGURATION", "ADMISSION_EXPIRED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN") -> "provider_admission"
                    category == "NO_VISIBLE_ANSWER" -> "delivery"
                    category in setOf("REASONING_BUDGET_EXHAUSTED", "OUTPUT_PROTOCOL_INVALID") -> "final_synthesis"
                    else -> "stage_not_recorded"
                }
            )
        }
    }
}

/** Local dispatch does not establish whether a remote service accepted a request. */
@Serializable
enum class RemoteRequestAcceptance { YES, NO, UNKNOWN }
