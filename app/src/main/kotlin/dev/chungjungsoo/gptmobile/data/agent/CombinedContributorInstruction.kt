package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

internal fun combinedContributorInstruction(platform: PlatformV2, combined: CombinedExecutionContext?): String = combined?.takeIf { it.role == CombinedRunRole.CONTRIBUTOR }?.let { accepted ->
    if (CurrentRequestContract.from(accepted.requested.userPrompt).intent != "SUBSTANTIVE") return@let "\nYou are the contributor for the current conversational request. ${CurrentRequestContract.from(accepted.requested.userPrompt).instruction()} Give one brief complete acknowledgment, without an outline or earlier-topic material."
    val allowance = ProviderRouteCapacity.output(platform, accepted.plan?.initialDraftOutputTokens ?: 2048)
    "\nYou are one accepted contributor to a Combined answer. Your provider-adjusted draft allowance is $allowance generation tokens, including any reasoning. " +
        "Give concise factual notes within the current user request and constraints. Preserve qualifications and distinguish evidence from inference. A separate writer will produce the final answer and enforce its word limit; do not repeat introductions or pad this intermediate contribution. " +
        "Complementary assignment: " + when (accepted.requested.participantUids.indexOf(accepted.profileUid).coerceAtLeast(0) % 3) {
            0 -> "Give an ordered outline and decisive milestones across the requested scope."
            1 -> "Focus on causes, consequences and connections, with specific supporting facts."
            else -> "Check omissions, later developments, competing interpretations and material qualifications."
        } + " Finish each note within your actual allowance; mark any unfinished coverage. Use tools only when exposed and useful."
}.orEmpty()
