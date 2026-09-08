package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Creates an isolated runner so tool-call budgets cannot leak between concurrent runs. */
fun agentRunnerForPlatform(platform: PlatformV2, runOverride: Int? = null): AgentRunner = AgentRunner(
    AgentRunLimits(maxToolCalls = runOverride ?: platform.maxToolCalls)
)
