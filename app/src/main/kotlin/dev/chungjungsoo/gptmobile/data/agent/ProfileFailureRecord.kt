package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import kotlinx.serialization.Serializable
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
    val resourceKeys: Set<String> = emptySet()
) {
    companion object {
        fun reviewRetryBlock(details: String?): String? = details?.takeIf { classify(it).category == "REVIEW_INPUT_REQUIRED" }?.let {
            "This saved review requires an updated request or new evidence. Send an updated prompt; the saved draft and tool results are retained."
        }

        private val phases = setOf("planning", "provider_admission", "request_validation", "message_admission", "backend_admission", "connection_preflight", "dns_resolution", "dns", "connection", "response_stream", "tls", "cancelled", "transport", "provider_generation", "model_invocation", "tool_precheck", "tool_dispatch", "tool_execution", "tool_continuation", "backend_template", "gateway_recovery", "gateway_dispatch", "answer_review", "claim_extraction", "claim_verification", "verification", "final_synthesis", "delivery", "lifecycle")
        private val typedCodes = setOf("GITHUB_REPOSITORY_TARGET_REQUIRED", "GITHUB_REPOSITORY_TARGET_AMBIGUOUS", "REQUEST_REJECTED", "CIRCUIT_BREAKER_OPEN", "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "TLS_FAILURE", "CLIENT_CANCELLED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "DISPATCH_POLICY_CHANGED", "POLICY_STALE", "STALE_INVENTORY", "SERIALIZATION_ERROR", "PARSING_ERROR", "SERVICE_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "GITHUB_TASK_INCOMPLETE", "GITHUB_EVIDENCE_REQUIRED", "GITHUB_EVIDENCE_RETENTION_INCOMPLETE", "REPLAY_EVIDENCE_REQUIRED", "TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH", "RECONCILIATION_REQUIRED", "GATEWAY_OUTCOME_UNKNOWN", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")

        /** Preserve typed stage/dispatch facts; arbitrary provider text is never promoted to evidence. */
        fun fromProvider(message: String, errorCode: String? = null, details: JsonObject? = null): ProfileFailureRecord {
            val rejectedStatus = (details?.get("http_status") as? JsonPrimitive)?.contentOrNull in setOf("409", "422")
            val code = errorCode?.uppercase(java.util.Locale.ROOT)?.takeIf {
                it in typedCodes || it in dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.codes || it in dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.codes
            } ?: "REQUEST_REJECTED".takeIf { rejectedStatus }
            val observed = classify(listOfNotNull(code, message).joinToString(": "))
            // A local rejection must not replace an already ambiguous remote operation.
            if (observed.category == GatewayOutcomeRecovery.ERROR_CODE) return observed
            if (observed.category == "TOOL_OUTCOME_UNKNOWN") {
                // A later local rejection cannot establish that the earlier
                // uncertain tool operation was never sent.
                return observed.copy(dispatched = true.takeIf { details?.get("dispatched") == JsonPrimitive(true) })
            }
            val authoritative = code?.let { classify(it) }
            val legacy = if (authoritative != null) {
                // The current typed failure outranks incidental codes in provider
                // prose. Preserve any additional retry restriction and timing facts.
                observed.copy(
                    category = authoritative.category,
                    retryAllowed = authoritative.retryAllowed && observed.retryAllowed,
                    phase = authoritative.phase.takeUnless { it == "stage_not_recorded" } ?: observed.phase
                )
            } else {
                observed
            }
            val stage = (details?.get("failed_stage") as? JsonPrimitive)?.contentOrNull?.takeIf { it in phases }
            val dispatchFact = details?.get("dispatched")
                ?: details?.get("inference_dispatched")?.takeIf { stage == "connection_preflight" }
            val dispatchState = (details?.get("dispatch_state") as? JsonPrimitive)?.contentOrNull?.lowercase(java.util.Locale.ROOT)
            val dispatched = (dispatchFact as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: false.takeIf { dispatchState in setOf("not_dispatched", "precheck_rejected") }
                ?: legacy.dispatched
            val localCircuitRejection = legacy.category == "CIRCUIT_BREAKER_OPEN" && stage == "provider_admission" && dispatched == false
            val authorityPrecheck = legacy.category in setOf("POLICY_STALE", "STALE_INVENTORY") && stage in setOf("provider_admission", "tool_precheck") && dispatched == false
            val retryAfter = (details?.get("retry_after_ms") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0L..86_400_000L }
            return legacy.copy(
                phase = stage ?: legacy.phase.ifBlank { "stage_not_recorded" },
                retryAllowed = legacy.retryAllowed && details?.get("retryable") != JsonPrimitive(false),
                dispatched = dispatched,
                remoteAcceptance = if (localCircuitRejection || authorityPrecheck || dispatchState in setOf("not_dispatched", "precheck_rejected")) RemoteRequestAcceptance.NO else legacy.remoteAcceptance,
                retryAfterMillis = if (localCircuitRejection && retryAfter != null) System.currentTimeMillis() + retryAfter else legacy.retryAfterMillis
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
            val category = when {
                listOf("TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH", "RECONCILIATION_REQUIRED", "GATEWAY_TOOL_OUTCOME_UNKNOWN").any { details.contains(it, true) } -> "TOOL_OUTCOME_UNKNOWN"
                details.contains("DISPATCH_POLICY_CHANGED", true) || details.contains("POLICY_STALE", true) -> "POLICY_STALE"
                details.contains("STALE_INVENTORY", true) -> "STALE_INVENTORY"
                contract != null -> contract.code
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
                !incompatible && !unavailablePolicy && contract == null && githubCode?.let { dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.requiresCorrection(it) } != true && category !in setOf("REQUEST_REJECTED", "POLICY_STALE", "STALE_INVENTORY", "REVIEW_INPUT_REQUIRED", "PRIVACY_CONFIGURATION", "QUEUED_JOB", "CACHED_UNUSABLE_RESPONSE", "ENDPOINT_RETIRED", "BILLING_UNAVAILABLE", "TOOL_OUTCOME_UNKNOWN", "TLS_FAILURE", "CLIENT_CANCELLED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "SERIALIZATION_ERROR"),
                retryAt,
                dispatched = false.takeIf { !circuit && category !in setOf("POLICY_STALE", "STALE_INVENTORY") && (details.contains("dispatched=false", true) || details.contains("No request was dispatched", true)) },
                phase = when {
                    details.contains("GITHUB_REPOSITORY_TARGET_", true) -> "planning"
                    contract != null -> contract.phase
                    category == "DNS_RESOLUTION_FAILED" -> "dns_resolution"
                    category == "TLS_FAILURE" -> "tls"
                    category == "CLIENT_CANCELLED" -> "cancelled"
                    category in setOf("CONNECTION", "TIMEOUT", "TRANSPORT_EOF") -> "transport"
                    category == "TOOL_OUTCOME_UNKNOWN" -> "tool_dispatch"
                    category == "STALE_INVENTORY" -> "tool_precheck"
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
