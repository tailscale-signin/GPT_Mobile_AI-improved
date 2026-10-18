package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/**
 * Creates an isolated runner so tool-call budgets cannot leak between concurrent runs.
 * Applies a 45-second per-tool timeout safeguard so that stalled or hanging local/MCP
 * tools never lock the entire conversation indefinitely.
 */
fun agentRunnerForPlatform(
    platform: PlatformV2,
    runOverride: Int? = null,
    maxRoundsOverride: Int? = null,
    combined: CombinedExecutionContext? = null
): AgentRunner = AgentRunner(
    AgentRunLimits(
        maxRounds = if (combined != null) Int.MAX_VALUE else maxRoundsOverride ?: Int.MAX_VALUE,
        maxToolCalls = combined?.let { CombinedPolicyResolver.toolCallLimit(it, platform.maxToolCalls, runOverride) } ?: runOverride ?: platform.maxToolCalls,
        maxConcurrentTools = combined?.plan?.maxParallelToolCalls ?: AgentRunLimits.defaultMaxConcurrentTools(),
        maxObservedToolCalls = if (combined != null) ((runOverride ?: platform.maxToolCalls).coerceAtLeast(0).toLong() * 2 + 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 256,
        toolTimeoutMillis = 45_000L,
        combined = combined,
        allowToolStormFinalization = dev.chungjungsoo.gptmobile.BuildConfig.TOOL_STORM_FINALIZATION
    )
)
