package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Creates an isolated runner so a platform's tool-call budget cannot leak across concurrent runs. */
fun agentRunnerForPlatform(platform: PlatformV2): AgentRunner = AgentRunner(
    AgentRunLimits(maxToolCalls = platform.maxToolCalls)
)
