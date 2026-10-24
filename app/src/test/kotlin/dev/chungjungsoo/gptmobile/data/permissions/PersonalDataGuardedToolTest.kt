package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataGuardedToolTest {
    @Test fun revocationAfterPreparationPreventsDispatchAndPreservesExecutionOwnership() = runTest {
        var granted = true
        var calls = 0
        val child = object : PreparableAgentTool, OwnedAgentTool {
            override val definition = AgentToolDefinition("private_read", "Read private data", JsonObject(emptyMap()))
            override val executionOwner = AgentToolExecutionOwner.GATEWAY
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()
            override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult = {
                calls++
                AgentToolResult(callId, ToolResultContent.Text("Private value"), false)
            }
        }
        val guarded = PersonalDataGuardedTool(child) { granted }
        assertEquals(AgentToolExecutionOwner.GATEWAY, guarded.executionOwner)
        val prepared = guarded.prepareExecution("call", JsonObject(emptyMap()))
        granted = false
        assertTrue(runCatching { prepared() }.exceptionOrNull()?.message.orEmpty().contains("PERMISSION_REVOKED"))
        assertEquals(0, calls)
        granted = true
        assertEquals("Private value", (guarded.execute("next", JsonObject(emptyMap())).content as ToolResultContent.Text).text)
        assertEquals(1, calls)
    }
}
