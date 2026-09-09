package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformAgentRunnerTest {

    @Test
    fun `agentRunnerForPlatform uses platform maxToolCalls when runOverride is null`() {
        val platform = PlatformV2(
            id = "test-platform",
            name = "Test Platform",
            clientType = ClientType.OPENAI,
            token = "sk-test",
            maxToolCalls = 25
        )

        val runner = agentRunnerForPlatform(platform, runOverride = null)

        assertEquals(25, runner.limits.maxToolCalls)
    }

    @Test
    fun `agentRunnerForPlatform uses runOverride when provided`() {
        val platform = PlatformV2(
            id = "test-platform",
            name = "Test Platform",
            clientType = ClientType.OPENAI,
            token = "sk-test",
            maxToolCalls = 25
        )

        val runner = agentRunnerForPlatform(platform, runOverride = 5)

        assertEquals(5, runner.limits.maxToolCalls)
    }

    @Test
    fun `agentRunnerForPlatform retains default maxToolCalls when platform has default value`() {
        val platform = PlatformV2(
            id = "test-platform",
            name = "Test Platform",
            clientType = ClientType.OPENAI,
            token = "sk-test"
        )

        val runner = agentRunnerForPlatform(platform, runOverride = null)

        assertEquals(Int.MAX_VALUE, runner.limits.maxToolCalls)
    }
}
