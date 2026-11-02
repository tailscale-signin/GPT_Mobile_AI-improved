package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
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
    val retryAfterMillis: Long? = null,
    val category: String = "INCOMPLETE",
    val retrying: Boolean = false
)

internal fun CombinedProfileFailure.shortStatus(now: Long): String = when {
    retrying -> "Retrying…"
    category == "ENDPOINT_RETIRED" -> "Endpoint retired"
    category == "BILLING_UNAVAILABLE" -> "Provider credits unavailable"
    category == "QUEUED_JOB" -> "Waiting for remote job"
    category == "CACHED_UNUSABLE_RESPONSE" -> "Cached answer unavailable"
    category == "HOSTED_GENERATION_FAILED" -> "Provider generation failed"
    category == "PROVIDER_PAYLOAD_ERROR" -> "Invalid provider response"
    category == "STREAM_DEGRADED" -> "Reply partially recovered"
    category == "NO_VISIBLE_ANSWER" -> "No answer returned"
    category == "REASONING_BUDGET_EXHAUSTED" -> "No answer within output allowance"
    category == "SAVED_POLICY_UNAVAILABLE" -> "Saved request unavailable"
    applicationFailure -> "App paused"
    (retryAfterMillis ?: 0) > now -> {
        val remaining = (retryAfterMillis!! - now).coerceAtLeast(1)
        val seconds = remaining / 1000 + if (remaining % 1000 > 0) 1 else 0
        val countdown = when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${(seconds + 59) / 60}m"
            else -> "${seconds}s"
        }
        "Rate limited · $countdown left"
    }
    category == "RATE_LIMIT" && retryAfterMillis != null -> "Retry available"
    category == "RATE_LIMIT" -> "Paused by provider limit"
    !retryAllowed -> "Not supported"
    category.contains("INCOMPLETE") || category.contains("OUTPUT") || reason.contains("incomplete", true) -> "Reply incomplete"
    else -> "Unavailable"
}

internal fun combinedProfileFailures(
    userMessageId: Int,
    request: String,
    responses: List<MessageV2>,
    runs: Map<String, AgentRun>,
    names: Map<String, String>,
    models: Map<String, String>,
    waitDeadlines: Map<String, ProviderWaitDeadline> = emptyMap()
): List<CombinedProfileFailure> = responses.mapNotNull { message ->
    val uid = message.platformType ?: return@mapNotNull null
    if (message.isCombinedSynthesis()) return@mapNotNull null
    val run = runs[message.currentRunId] ?: return@mapNotNull null
    if (run.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING)) return@mapNotNull null
    val waitingForLimit = run.status == AgentRunStatus.WAITING_PROVIDER_LIMIT
    val generation = message.timeline.lastOrNull { it.generation != null }?.generation
    val content = CombinedContributionQuality.sanitizeContribution(message.content)
    val demo = uid == "builtin-free-porchlm" || names[uid]?.contains("PorchLM", true) == true || models[uid]?.contains("porch", true) == true
    val assessed = CombinedContributionUsability.assess(run.status, content, allowPartial = true, request = request, demo = demo, finishReason = generation?.finishReason, outputTokens = generation?.outputTokens, outputAllowance = generation?.outputAllowance ?: 256.takeIf { uid == "builtin-free-ai_horde" })
    if (run.status == AgentRunStatus.COMPLETED && assessed.admitted && !assessed.usablePartial) return@mapNotNull null
    val details = run.terminalError ?: if (waitingForLimit) "Provider rate limit. This profile is paused and has not completed its contribution." else assessed.reason ?: "Incomplete contribution"
    val savedPolicyUnavailable = details.contains("POLICY_SNAPSHOT_", ignoreCase = true)
    val terminalClassification = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(details, demoExcluded = demo)
    val classified = message.timeline.lastOrNull { it.profileFailure != null }?.profileFailure
        ?.takeUnless { demo || savedPolicyUnavailable || terminalClassification.category in setOf("ENDPOINT_RETIRED", "BILLING_UNAVAILABLE") } ?: dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(details, demoExcluded = demo)
    val appFailure = !waitingForLimit && (classified.category in setOf("APP_EXECUTION", "SAVED_POLICY_UNAVAILABLE") || savedPolicyUnavailable)
    val permanentUnavailable = classified.category in setOf("ENDPOINT_RETIRED", "BILLING_UNAVAILABLE")
    val incompatible = !classified.retryAllowed && (!waitingForLimit || permanentUnavailable)
    val savedDeadline = waitDeadlines[run.runId]?.takeIf {
        waitingForLimit &&
            it.runId == run.runId &&
            it.fence == run.ownershipFence &&
            it.fence == message.ownershipFence &&
            run.assistantMessageId == message.id &&
            run.chatId == message.chatId &&
            it.retryAt > 0
    }
    val retryAt = savedDeadline?.retryAt ?: classified.retryAfterMillis
    val reason = when {
        classified.category == "ENDPOINT_RETIRED" -> "The provider retired this endpoint. Update the endpoint or model before starting a new turn."
        classified.category == "BILLING_UNAVAILABLE" -> "The provider cannot generate with the current account balance. Restore credits before starting a new turn."
        waitingForLimit -> "This profile is paused by a provider limit. Wait for its allowance or combine the available replies without this profile for this turn."
        run.status == AgentRunStatus.WAITING_REMOTE_JOB || classified.category == "QUEUED_JOB" -> "The remote job is still queued or generating. Check its saved status or continue without this profile."
        classified.category == "CACHED_UNUSABLE_RESPONSE" -> "The provider replayed the same cached generation without an answer. Choose a different model, supported settings or provider."
        savedPolicyUnavailable || classified.category == "SAVED_POLICY_UNAVAILABLE" -> "Saved request data is unavailable. Restore its complete backup or start a new request; other replies are saved."
        appFailure -> "App execution paused. Open the app to resume."
        Regex("REASONING_(?:ON|OFF)_UNSUPPORTED", RegexOption.IGNORE_CASE).containsMatchIn(details) -> "Choose Auto in this profile’s reasoning settings, or change to a profile with a verified reasoning toggle. Completed tool results are saved. Retry after changing the configuration."
        incompatible -> "This model or configuration is incompatible. Change it before retrying."
        details.contains("Tool choice is none", true) -> "The provider returned a tool call although tools were disabled."
        details.contains("DNS", true) -> "DNS could not reach the provider. Check the connection before retrying."
        Regex("\\b50[234]\\b").containsMatchIn(details) -> "The provider is temporarily unavailable. Retry this profile."
        details.contains("timeout", true) || details.contains("timed out", true) -> "The provider did not finish in time. Retry this profile."
        retryAt != null -> "Provider rate limit. Retry after ${java.time.Instant.ofEpochMilli(retryAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss z"))}."
        details.contains("429") || details.contains("cooldown", true) || details.contains("rate limit", true) -> "Provider rate limit. Retry after its cooldown."
        !run.terminalError.isNullOrBlank() -> details.take(180)
        content.isBlank() -> "No answer returned."
        assessed.usablePartial -> "The answer is incomplete."
        else -> details.take(180)
    }
    val retryAllowed = !incompatible && run.status != AgentRunStatus.WAITING_REMOTE_JOB && (!waitingForLimit || retryAt != null)
    val category = if (permanentUnavailable) {
        classified.category
    } else {
        when (run.status) {
            AgentRunStatus.WAITING_PROVIDER_LIMIT -> "RATE_LIMIT"
            AgentRunStatus.WAITING_REMOTE_JOB -> "QUEUED_JOB"
            else -> classified.category
        }
    }
    CombinedProfileFailure(userMessageId, uid, names[uid]?.takeIf { it.isNotBlank() } ?: "Saved profile", models[uid] ?: run.modelSnapshot, message.currentRunId, reason, details, retryAllowed, appFailure, retryAt, category)
}
