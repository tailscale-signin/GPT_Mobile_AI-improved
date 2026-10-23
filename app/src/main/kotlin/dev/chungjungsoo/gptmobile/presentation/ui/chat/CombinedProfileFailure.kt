package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** One decision queue derived from durable current attempts; successful replies are never replaced. */
data class CombinedProfileFailure(
    val userMessageId: Int,
    val profileUid: String,
    val profileName: String,
    val model: String,
    val attemptId: String?,
    val reason: String,
    val details: String,
    val retryAllowed: Boolean,
    val applicationFailure: Boolean = false,
    val retryAfterMillis: Long? = null
)

internal fun combinedProfileFailures(
    userMessageId: Int,
    request: String,
    responses: List<MessageV2>,
    runs: Map<String, AgentRun>,
    names: Map<String, String>,
    models: Map<String, String>
): List<CombinedProfileFailure> = responses.mapNotNull { message ->
    val uid = message.platformType ?: return@mapNotNull null
    if (message.isCombinedSynthesis()) return@mapNotNull null
    val run = runs[message.currentRunId] ?: return@mapNotNull null
    if (run.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING)) return@mapNotNull null
    val generation = message.timeline.lastOrNull { it.generation != null }?.generation
    val content = CombinedContributionQuality.sanitizeContribution(message.content)
    val demo = uid == "builtin-free-porchlm" || names[uid]?.contains("PorchLM", true) == true || models[uid]?.contains("porch", true) == true
    val assessed = CombinedContributionUsability.assess(run.status, content, allowPartial = true, request = request, demo = demo, finishReason = generation?.finishReason, outputTokens = generation?.outputTokens, outputAllowance = generation?.outputAllowance ?: 256.takeIf { uid == "builtin-free-ai_horde" })
    if (run.status == AgentRunStatus.COMPLETED && assessed.admitted && !assessed.usablePartial) return@mapNotNull null
    val details = run.terminalError ?: assessed.reason ?: "Incomplete contribution"
    val appFailure = details.contains("ForegroundService", true) || details.contains("mAllowStartForeground", true) || details.startsWith("APP_EXECUTION_")
    val incompatible = details.contains("enforceable", true) || details.contains("unsupported", true) || details.contains("budget-blocked", true)
    val retryAt = Regex("(?i)rate limited until ([0-9]{10,16})").find(details)?.groupValues?.get(1)?.toLongOrNull()
    val reason = when {
        appFailure -> "App execution paused. Open the app to resume."
        incompatible -> "This model or configuration is incompatible. Change it before retrying."
        details.contains("Tool choice is none", true) -> "The provider returned a tool call although tools were disabled."
        details.contains("QUEUE_JOB_PENDING", true) || details.contains("DNS", true) -> "Connection lost. Check any saved queued job before submitting again."
        Regex("\\b50[234]\\b").containsMatchIn(details) -> "The provider is temporarily unavailable. Retry this profile."
        details.contains("timeout", true) || details.contains("timed out", true) -> "The provider did not finish in time. Retry this profile."
        retryAt != null -> "Provider rate limit. Retry after ${java.time.Instant.ofEpochMilli(retryAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss z"))}."
        details.contains("429") || details.contains("cooldown", true) || details.contains("rate limit", true) -> "Provider rate limit. Retry after its cooldown."
        !run.terminalError.isNullOrBlank() -> details.take(180)
        content.isBlank() -> "No answer returned."
        assessed.usablePartial -> "The answer is incomplete."
        else -> details.take(180)
    }
    CombinedProfileFailure(userMessageId, uid, names[uid] ?: uid, models[uid] ?: run.modelSnapshot, message.currentRunId, reason, details, !incompatible, appFailure, retryAt)
}
