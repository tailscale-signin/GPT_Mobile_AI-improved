package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Completion tokens include hidden reasoning on these families. Keep room for visible output. */
internal fun delegationOutputBudget(profile: PlatformV2, requested: Int): Int {
    val reasoningModel = profile.reasoning ||
        listOf("deepseek", "qwen3", "qwen-3", "qwq", "nemotron")
            .any { profile.model.contains(it, ignoreCase = true) }
    val calculated = if (reasoningModel) maxOf(2048, requested) else requested
    return minOf(calculated, profile.maxTokens?.takeIf { it > 0 } ?: calculated)
}
