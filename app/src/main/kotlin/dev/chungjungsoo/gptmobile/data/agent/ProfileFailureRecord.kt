package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import kotlinx.serialization.Serializable

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
            val incompatible = demoExcluded || listOf("Serializer for subclass", "Serializer for class", "enforceable", "unsupported", "budget-blocked", "demo exclusion", "demo endpoint", "demo model", "demo provider", "TOOL_CHOICE_MISMATCH", "RESEARCH_CONFIGURATION_CHANGED", "COMBINED_OUTPUT_ALLOWANCE_CHANGED", "Profile is disabled", "PROFILE_DISABLED", "PROFILE_REMOVED", "PROFILE_CONFIGURATION_CHANGED", "PROFILE_IDENTITY_AMBIGUOUS", "DISPATCH_POLICY_CHANGED", "Unknown Free route").any { details.contains(it, true) }
            val statuses = httpStatus.findAll(details).map { it.groupValues[1] }.toSet()
            val permanentEndpoint = "410" in statuses || details.trim() == "410" || Regex("(?i)\\b410\\s+Gone\\b|endpoint.{0,40}(?:retired|removed|gone)|model.{0,30}(?:decommissioned|retired)").containsMatchIn(details)
            val billing = "402" in statuses || details.trim() == "402" || Regex("(?i)credit balance|insufficient (?:credits|funds|balance)|billing.{0,30}(?:required|disabled|exhausted)|payment required").containsMatchIn(details)
            val reviewBlocked = details.contains("Saved review requires new evidence", true) || details.contains("GITHUB_REVIEW_BLOCKED", true) || details.contains("unchanged draft will not be reviewed again", true)
            val privacyBlocked = details.contains("POLICY_RESTRICTED", true) || details.contains("PERSONAL_DATA_LOCAL_ONLY", true)
            val category = when {
                circuit -> "CIRCUIT_BREAKER_OPEN"
                reviewBlocked -> "REVIEW_INPUT_REQUIRED"
                details.contains("GITHUB_REVIEW_", true) -> "ANSWER_REVIEW"
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
                Regex("(?i)timeout|timed out").containsMatchIn(details) -> "TIMEOUT"
                details.contains("DNS", true) -> "DNS_RESOLUTION_FAILED"
                Regex("(?i)\\b50[234]\\b").containsMatchIn(details) -> "UPSTREAM_UNAVAILABLE"
                details.contains("connection", true) -> "CONNECTION"
                else -> "INCOMPLETE"
            }
            return ProfileFailureRecord(
                category,
                !incompatible && !unavailablePolicy && category !in setOf("REVIEW_INPUT_REQUIRED", "PRIVACY_CONFIGURATION", "QUEUED_JOB", "CACHED_UNUSABLE_RESPONSE", "ENDPOINT_RETIRED", "BILLING_UNAVAILABLE"),
                retryAt,
                dispatched = false.takeIf { circuit || details.contains("dispatched=false", true) || details.contains("No request was dispatched", true) },
                remoteAcceptance = if (circuit) RemoteRequestAcceptance.NO else RemoteRequestAcceptance.UNKNOWN,
                phase = when {
                    circuit -> "provider_admission"
                    category == "DNS_RESOLUTION_FAILED" -> "dns_resolution"
                    category == "CONNECTION" -> "transport"
                    category == "ANSWER_REVIEW" -> "answer_review"
                    else -> ""
                }
            )
        }
    }
}

/** Local dispatch does not establish whether a remote service accepted a request. */
@Serializable
enum class RemoteRequestAcceptance { YES, NO, UNKNOWN }
