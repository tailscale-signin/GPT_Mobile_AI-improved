package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedToolCallBrokerTest {

    @Test
    fun `identical concurrent calls execute once and preserve each model call id`() = runBlocking {
        val broker = SharedToolCallBroker()
        val executions = AtomicInteger()

        val results = coroutineScope {
            listOf("call-a", "call-b", "call-c").map { callId ->
                async {
                    broker.executeShared(
                        scopeId = "chat:7:turn:42",
                        toolIdentity = "builtin:device_location",
                        callId = callId,
                        arguments = buildJsonObject {}
                    ) {
                        executions.incrementAndGet()
                        delay(30)
                        AgentToolResult(
                            callId = callId,
                            content = ToolResultContent.Text("43.67,-79.30"),
                            isError = false
                        )
                    }
                }
            }.map { it.await() }
        }

        assertEquals(1, executions.get())
        assertEquals(listOf("call-a", "call-b", "call-c"), results.map { it.callId })
        assertEquals(1, results.map { it.content }.distinct().size)
    }

    @Test
    fun `canonical argument ordering shares the same read request`() = runBlocking {
        val broker = SharedToolCallBroker()
        val executions = AtomicInteger()

        val firstArguments = buildJsonObject {
            put("query", "restaurants nearby")
            put("radius", 1500)
        }
        val reorderedArguments = buildJsonObject {
            put("radius", 1500)
            put("query", "restaurants nearby")
        }

        broker.executeShared("scope", "builtin:web_search", "first", firstArguments) {
            executions.incrementAndGet()
            AgentToolResult("first", ToolResultContent.Text("result"), false)
        }
        broker.executeShared("scope", "builtin:web_search", "second", reorderedArguments) {
            executions.incrementAndGet()
            AgentToolResult("second", ToolResultContent.Text("duplicate"), false)
        }

        assertEquals(1, executions.get())
    }

    @Test
    fun `different arguments or turns execute independently`() = runBlocking {
        val broker = SharedToolCallBroker()
        val executions = AtomicInteger()

        suspend fun execute(scope: String, radius: Int, callId: String) {
            broker.executeShared(
                scopeId = scope,
                toolIdentity = "builtin:web_search",
                callId = callId,
                arguments = buildJsonObject { put("radius", radius) }
            ) {
                executions.incrementAndGet()
                AgentToolResult(callId, ToolResultContent.Text("radius=$radius"), false)
            }
        }

        execute("chat:7:turn:42", 1000, "one")
        execute("chat:7:turn:42", 2000, "two")
        execute("chat:7:turn:43", 1000, "three")

        assertEquals(3, executions.get())
    }
}
