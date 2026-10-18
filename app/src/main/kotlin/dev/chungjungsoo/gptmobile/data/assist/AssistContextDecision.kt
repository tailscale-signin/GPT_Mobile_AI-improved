package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.Serializable

@Serializable
enum class AssistContextMode { AUTO, MANUAL }

/** Export capacity and native allocation are distinct from task/quality qualification. */
data class AssistContextDecision(
    val requestedTokens: Int?,
    val supportedTokens: Int,
    val allocatedTokens: Int,
    val logicalTokens: Int,
    val qualifiedTokens: Int? = null
) {
    fun requireFits(renderedInputTokens: Long, outputTokens: Int, toolResultReserve: Int, mediaTokens: Int = 0, safetyTokens: Int = 256): Long {
        require(renderedInputTokens >= 0 && outputTokens >= 0 && toolResultReserve >= 0 && mediaTokens >= 0 && safetyTokens >= 0)
        val total = renderedInputTokens + outputTokens.toLong() + toolResultReserve + mediaTokens + safetyTokens
        require(total <= logicalTokens) { "Assist request needs $total tokens including reserves; the admitted task limit is $logicalTokens." }
        return total
    }

    companion object {
        fun resolve(policy: LocalAssistPolicy, capacity: Int?, dynamic: Boolean): AssistContextDecision {
            require(capacity != null && capacity > 0) { "Artifact context capacity is unverified; inspect a compatible package first." }
            val requested = policy.contextTokens.takeIf { policy.contextMode == AssistContextMode.MANUAL }
            require(requested == null || requested > 0) { "Choose a positive context limit. Your saved value has not been changed." }
            require(requested == null || requested <= capacity) { "Requested context $requested exceeds this export's exact $capacity-token limit. Choose a compatible export or a smaller context." }
            // No tier is promoted to qualified solely because the export declares it.
            // Auto retains a conservative logical ceiling and does not advertise extended tiers.
            val logical = requested ?: minOf(capacity, 4096)
            require(logical <= 4096) { "Extended Assist context $logical is unavailable until this exact artifact, device and backend pass task and sustained qualification. Your request remains saved." }
            return AssistContextDecision(requested, capacity, if (dynamic) logical else capacity, logical)
        }
    }
}
