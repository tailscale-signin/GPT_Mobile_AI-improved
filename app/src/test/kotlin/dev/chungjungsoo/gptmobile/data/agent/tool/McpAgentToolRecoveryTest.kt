package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ResponseRecoveryContext
import dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAgentToolRecoveryTest {
    @org.junit.Before fun resetHealth() = LocalServiceHealth.networkChanged()

    @Test fun remoteNestedToolErrorCannotRepeatAWriteNowOrAfterRecovery() = runBlocking {
        assertUncertainWrite(
            """{"isError":true,"content":[{"type":"text","text":"Ticket created; notification failed: unknown tool send_notification"}]}""",
            expectedCause = "MCP_TOOL_ERROR"
        )
    }

    @Test fun missingOrInvalidStructuredOutputCannotRepeatACompletedWrite() = runBlocking {
        for (body in listOf(
            """{"content":[{"type":"text","text":"Ticket created"}]}""",
            """{"content":[{"type":"text","text":"Ticket created"}],"structuredContent":{"ticket_id":17}}"""
        )) {
            assertUncertainWrite(body, outputSchema = RESULT_SCHEMA, expectedCause = "MCP_OUTPUT_INVALID")
        }
    }

    @Test fun explicitlyApprovedReadsCanRetryRemoteErrorsWithoutRediscovery() = runBlocking {
        var reads = 0
        McpClientManagerTest.McpFixtureServer(
            toolsResponse = catalogue("lookup_ticket"),
            toolResult = {
                reads++
                if (reads == 1) """{"isError":true,"content":[{"type":"text","text":"Nested read failed: unknown tool lookup_details"}]}""" else """{"content":[{"type":"text","text":"Ticket details"}]}"""
            }
        ).use { server ->
            val network = NetworkClient(CIO)
            val manager = McpClientManager(network())
            try {
                val tool = tool(manager, server.url, "lookup_ticket", approvedReadOnly = true)
                val results = AgentRunner().run(provider(tool.definition), listOf(MeasuredAgentTool(tool, approvedReadOnly = true)))
                    .toList().filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
                assertEquals(2, reads)
                assertEquals("READ_FAILED", results.first().errorCode)
                assertTrue(results.first().dispatched)
                assertEquals(JsonPrimitive(true), results.first().executionReceipt?.get("retryable"))
                assertFalse(results.last().isError)
                assertEquals(1, server.methods.count { it == "tools/list" })
            } finally {
                manager.closeAll()
                network().close()
            }
        }
    }

    @Test fun localMissingToolAdmissionCarriesProofWithoutDispatch() = runBlocking {
        McpClientManagerTest.McpFixtureServer(toolsResponse = catalogue("different_tool")).use { server ->
            val network = NetworkClient(CIO)
            val manager = McpClientManager(network())
            try {
                val remote = tool(manager, server.url, "create_ticket")
                val result = MeasuredAgentTool(remote).execute("missing", JsonObject(emptyMap()))
                assertEquals("UNKNOWN_TOOL", result.errorCode)
                assertFalse(result.dispatched)
                assertEquals(JsonPrimitive("LOCAL_CATALOGUE_PRECHECK"), result.executionReceipt?.get("dispatch_evidence"))
                assertEquals(0, server.toolParams.size)
                assertTrue(ToolResultCheckpoint.confirmsNoEffect(saved(result, remote.definition.name)))
            } finally {
                manager.closeAll()
                network().close()
            }
        }
    }

    @Test fun validStructuredOutputRemainsSuccessful() = runBlocking {
        McpClientManagerTest.McpFixtureServer(
            toolsResponse = catalogue("create_ticket", RESULT_SCHEMA),
            toolResult = { """{"content":[],"structuredContent":{"ticket_id":"created-17"}}""" }
        ).use { server ->
            val network = NetworkClient(CIO)
            val manager = McpClientManager(network())
            try {
                val result = tool(manager, server.url, "create_ticket", outputSchema = RESULT_SCHEMA).execute("valid", JsonObject(emptyMap()))
                assertFalse(result.isError)
                assertTrue(result.dispatched)
                assertTrue(result.content.toString().contains("created-17"))
                assertEquals(1, server.toolParams.size)
            } finally {
                manager.closeAll()
                network().close()
            }
        }
    }

    private suspend fun assertUncertainWrite(body: String, outputSchema: String? = null, expectedCause: String) {
        var sideEffects = 0
        McpClientManagerTest.McpFixtureServer(
            toolsResponse = catalogue("create_ticket", outputSchema),
            toolResult = {
                sideEffects++
                body
            }
        ).use { server ->
            val network = NetworkClient(CIO)
            val manager = McpClientManager(network())
            try {
                val remote = tool(manager, server.url, "create_ticket", outputSchema = outputSchema)
                val measured = MeasuredAgentTool(remote)
                val results = AgentRunner().run(provider(remote.definition), listOf(measured)).toList()
                    .filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
                assertEquals(1, sideEffects)
                assertEquals(1, server.methods.count { it == "tools/list" })
                assertEquals(listOf("DISPATCH_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED"), results.map { it.errorCode })
                assertTrue(results.first().dispatched)
                assertFalse(results.last().dispatched)
                assertEquals(JsonPrimitive(expectedCause), results.first().executionReceipt?.get("cause_code"))
                assertEquals(JsonPrimitive("UNKNOWN_OUTCOME"), results.first().executionReceipt?.get("execution_state"))

                val event = saved(results.first(), remote.definition.name)
                assertFalse(ToolResultCheckpoint.confirmsNoEffect(event))
                val run = AgentRun("prior-run", 1, 1, 2, "profile", "LLAMA", "model", status = "FAILED")
                val recovered = ResponseRecoveryContext(listOf(run), emptyList(), listOf(event), rootWorkId = "root")
                    .reuseCompletedTool(measured, "create_ticket", "connection")
                    .execute("after-restart", JsonObject(emptyMap()))
                assertEquals("RECONCILIATION_REQUIRED", recovered.errorCode)
                assertFalse(recovered.dispatched)
                assertEquals(1, sideEffects)
            } finally {
                manager.closeAll()
                network().close()
            }
        }
    }

    private fun tool(manager: McpClientManager, url: String, name: String, approvedReadOnly: Boolean = false, outputSchema: String? = null) = McpAgentTool(
        definition = AgentToolDefinition("mcp__fixture__$name", "Fixture remote operation", Json.parseToJsonElement("""{"type":"object"}""") as JsonObject),
        authType = ToolConnectionAuthType.NONE,
        config = { _, _ -> McpConnectionConfig("connection", url, true) },
        remoteToolName = name,
        clientManager = manager,
        outputSchema = outputSchema?.let { Json.decodeFromString<ToolSchema>(it) },
        approvedReadOnly = approvedReadOnly
    )

    private fun provider(definition: AgentToolDefinition): AgentProviderSession = object : AgentProviderSession {
        private var round = 0
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            if (round++ == 0) {
                emit(ProviderEvent.ToolCall("first", definition.name, JsonObject(emptyMap())))
                emit(ProviderEvent.ToolCall("different-id", definition.name, JsonObject(emptyMap())))
            } else {
                emit(ProviderEvent.TextDelta("Retained operation receipt; reconcile any uncertain write before continuing."))
            }
            emit(ProviderEvent.Completed)
        }
    }

    private fun saved(result: dev.chungjungsoo.gptmobile.data.agent.AgentToolResult, modelName: String) = ToolEvent(
        eventId = "event",
        runId = "prior-run",
        sequence = 0,
        callId = result.callId,
        connectionUidSnapshot = "connection",
        connectionNameSnapshot = "MCP fixture",
        toolName = "create_ticket",
        modelToolName = modelName,
        arguments = "{}",
        result = ToolResultCheckpoint.encode(result.content.toString(), ToolEventResultType.TEXT, "Remote tool receipt", executionReceipt = result.executionReceipt),
        resultType = ToolEventResultType.CHECKPOINT,
        status = ToolEventStatus.FAILED,
        isError = true
    )

    private fun catalogue(name: String, outputSchema: String? = null) = """{"tools":[{"name":"$name","inputSchema":{"type":"object"}${outputSchema?.let { ",\"outputSchema\":$it" }.orEmpty()}}]}"""

    private companion object {
        const val RESULT_SCHEMA = """{"type":"object","properties":{"ticket_id":{"type":"string"}},"required":["ticket_id"]}"""
    }
}
