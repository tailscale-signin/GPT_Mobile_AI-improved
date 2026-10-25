package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

internal fun combinedContributorInstruction(platform: PlatformV2, combined: CombinedExecutionContext?): String = combined?.takeIf { it.role == CombinedRunRole.CONTRIBUTOR }?.let { accepted ->
    if (CurrentRequestContract.from(accepted.requested.userPrompt).intent != "SUBSTANTIVE") return@let "\nYou are the contributor for the current conversational request. ${CurrentRequestContract.from(accepted.requested.userPrompt).instruction()} Give one brief complete acknowledgment, without an outline or earlier-topic material."
    if (accepted.requested.singlePass) {
        val contract = CombinedAnswerPolicy.from(accepted.requested.userPrompt)
        val allowance = ProviderRouteCapacity.output(platform, accepted.plan?.initialDraftOutputTokens ?: 2048)
        val emphasis = when (accepted.requested.participantUids.indexOf(accepted.profileUid).coerceAtLeast(0) % 3) {
            0 -> "Emphasize the ordered outline, decisive milestones and direct answer across the full requested scope."
            1 -> "Emphasize causes, consequences and connections, with specific supporting facts."
            else -> "Emphasize omissions, later developments, competing interpretations and material qualifications."
        }
        return@let "\nAnswer the current request completely and concisely as one independent contributor. Preserve qualifications and requested numbered-item counts. Distinguish transport success, answer completeness and evidence support. If evidence is unavailable, state the limitation explicitly. LLM7 and OpenRouter refer to AI API services, not networking hardware. For prices, use the exact requested model, currency and input/output token units with readable official evidence and its date, or explain what could not be verified. Never invent a catalogue entry or substitute another model. " +
            "Complementary emphasis: $emphasis This is an emphasis, not a narrower assignment: still cover every required topic and preserve all material qualifications. Avoid repeated introductions and self-editing commentary. " +
            "Your provider-adjusted allowance is $allowance generation tokens including reasoning; keep all requested items concise enough to finish within it. Requested items: ${contract.requestedItems ?: "unspecified"}; format: ${contract.requiredFormat ?: "clear Markdown"}. Use tools only when needed and available."
    }
    val allowance = ProviderRouteCapacity.output(platform, accepted.plan?.initialDraftOutputTokens ?: 2048)
    "\nYou are one accepted contributor to a Combined answer. Your provider-adjusted draft allowance is $allowance generation tokens, including any reasoning. " +
        "Give concise factual notes within the current user request and constraints. Preserve qualifications and distinguish evidence from inference. A separate writer will produce the final answer and enforce its word limit; do not repeat introductions or pad this intermediate contribution. " +
        "Complementary assignment: " + when (accepted.requested.participantUids.indexOf(accepted.profileUid).coerceAtLeast(0) % 3) {
            0 -> "Give an ordered outline and decisive milestones across the requested scope."
            1 -> "Focus on causes, consequences and connections, with specific supporting facts."
            else -> "Check omissions, later developments, competing interpretations and material qualifications."
        } + " Finish each note within your actual allowance; mark any unfinished coverage. Use tools only when exposed and useful."
}.orEmpty()
