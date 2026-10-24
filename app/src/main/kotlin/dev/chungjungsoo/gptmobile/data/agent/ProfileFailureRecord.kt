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
        fun classify(details: String, demoExcluded: Boolean = false): ProfileFailureRecord {
            val epoch = Regex("(?i)rate limited until ([0-9]{10,16})").find(details)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { if (it < 100_000_000_000L) it * 1000 else it }
            val iso = Regex("(?i)next eligible retry:\\s*([^\\s]+)").find(details)?.groupValues?.get(1)
                ?.let { runCatching { Instant.parse(it.trimEnd('.', ',', ';')).toEpochMilli() }.getOrNull() }
            val retryAt = iso ?: epoch
            val unavailablePolicy = details.contains("POLICY_SNAPSHOT_", true)
            val incompatible = demoExcluded || listOf("enforceable", "unsupported", "budget-blocked", "demo exclusion", "demo endpoint", "demo model", "demo provider", "TOOL_CHOICE_MISMATCH", "RESEARCH_CONFIGURATION_CHANGED", "COMBINED_OUTPUT_ALLOWANCE_CHANGED", "Profile is disabled", "PROFILE_DISABLED", "PROFILE_REMOVED", "PROFILE_CONFIGURATION_CHANGED", "PROFILE_IDENTITY_AMBIGUOUS", "DISPATCH_POLICY_CHANGED", "Unknown Free route").any { details.contains(it, true) }
            val category = when {
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
                details.contains("QUEUE_JOB_PENDING", true) -> "QUEUED_JOB"
                Regex("(?i)timeout|timed out").containsMatchIn(details) -> "TIMEOUT"
                details.contains("DNS", true) -> "DNS_RESOLUTION_FAILED"
                Regex("(?i)\\b50[234]\\b").containsMatchIn(details) -> "UPSTREAM_UNAVAILABLE"
                details.contains("connection", true) -> "CONNECTION"
                else -> "INCOMPLETE"
            }
            return ProfileFailureRecord(category, !incompatible && !unavailablePolicy, retryAt)
        }
    }
}

/** Local dispatch does not establish whether a remote service accepted a request. */
@Serializable
enum class RemoteRequestAcceptance { YES, NO, UNKNOWN }
