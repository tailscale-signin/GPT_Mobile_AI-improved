package dev.chungjungsoo.gptmobile.data.context

/** Allocate reply and actionable evidence within the resolved route ceiling. */
internal object ReplayAllowance {
    const val MINIMUM_TOOL_REPLAY = 128
    private const val TARGET_TOOL_REPLAY = 512
    const val MINIMUM_TOOL_REPLY = 128
    data class Allocation(val output: Int, val replay: Int, val ceiling: Int, val input: Int, val safety: Int)

    /** A saved tool configuration alone is not evidence that a request exposes executable tools. */
    fun needsConservativeToolReserve(githubMode: Boolean, requireExecution: Boolean, availableTools: Int): Boolean =
        githubMode || (requireExecution && availableTools > 0)

    /** Unknown provider context is not a verified unlimited capacity. Plan tool work before history/schema selection. */
    fun conservativeToolPlanningBudget(settings: TokenBudgetSettings, toolEvidenceRequired: Boolean): TokenBudgetSettings {
        if (!toolEvidenceRequired || settings.contextTokens != Int.MAX_VALUE) return settings
        val output = settings.outputTokens.takeIf { it > 0 } ?: 768
        return settings.copy(contextTokens = 4096, outputTokens = minOf(output, 768))
    }
    fun inlineRecoveryCharacters(contextTokens: Int): Int =
        (contextTokens / 8).coerceIn(64, 2000) * 3

    fun allocate(context: Int, input: Int, output: Int, fallback: Int): Allocation {
        // Int.MAX_VALUE denotes unknown/unlimited, not a verified route capacity.
        val ceiling = if (context in 1 until Int.MAX_VALUE) context else fallback.coerceIn(0, 4096)
        val prompt = input.coerceAtLeast(0)
        val remaining = (RequestAdmission.maximumEstimatedPrompt(ceiling.toLong()) - prompt).coerceAtLeast(0).toInt()
        val evidence = minOf(4096, (remaining - 512).coerceAtLeast(0) / 2)
        val reply = minOf(output.coerceAtLeast(0), (remaining - evidence).coerceAtLeast(0))
        val replay = (RequestAdmission.maximumEstimatedPrompt(ceiling.toLong() - reply) - prompt).coerceIn(0, 24_000).toInt()
        return Allocation(reply, replay, ceiling, prompt, RequestAdmission.estimateSafety(prompt.toLong() + replay).toInt())
    }

    /** Keep an ordinary exact call/result envelope usable before any operation is dispatched. */
    fun forToolTurn(context: Int, input: Int, output: Int, fallback: Int): Allocation {
        val original = allocate(context, input, output, fallback)
        if (original.replay >= TARGET_TOOL_REPLAY) return original
        val minimumReply = minOf(output.coerceAtLeast(0), MINIMUM_TOOL_REPLY)
        val available = RequestAdmission.maximumEstimatedPrompt(original.ceiling.toLong() - minimumReply) - original.input
        val target = minOf(TARGET_TOOL_REPLAY.toLong(), available)
        if (target < MINIMUM_TOOL_REPLAY) return original
        val requiredPrompt = original.input.toLong() + target
        val replyHeadroom = original.ceiling.toLong() - requiredPrompt - RequestAdmission.estimateSafety(requiredPrompt)
        val reply = minOf(output.toLong().coerceAtLeast(0), replyHeadroom.coerceAtLeast(0)).toInt()
        if (reply < minimumReply) return original
        val replay = (RequestAdmission.maximumEstimatedPrompt(original.ceiling.toLong() - reply) - original.input).coerceIn(0, 24_000).toInt()
        return original.copy(output = reply, replay = replay, safety = RequestAdmission.estimateSafety(original.input.toLong() + replay).toInt())
    }

    fun tokens(context: Int, input: Int, output: Int, fallback: Int): Int = allocate(context, input, output, fallback).replay
}
