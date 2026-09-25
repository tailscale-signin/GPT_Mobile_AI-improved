package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    @Test
    fun `nested objects and escaped keys share without changing array order`() = runTest {
        val broker = SharedToolCallBroker()
        val arguments = Json.parseToJsonElement(
            """{"quote\"\n\u0000\\": {"b": 2, "a": 1}, "items": [{"y": 2, "x": 1}, 3]}"""
        ) as kotlinx.serialization.json.JsonObject
        val reordered = Json.parseToJsonElement(
            """{"items": [{"x": 1, "y": 2}, 3], "quote\"\n\u0000\\": {"a": 1, "b": 2}}"""
        ) as kotlinx.serialization.json.JsonObject
        val executions = AtomicInteger()

        for ((index, value) in listOf(arguments, reordered).withIndex()) {
            broker.executeShared("scope", "tool", "$index", value) {
                executions.incrementAndGet()
                AgentToolResult("$index", ToolResultContent.Text("result"), false)
            }
        }
        assertEquals(1, executions.get())

        val reversedArray = Json.parseToJsonElement(
            """{"items": [3, {"x": 1, "y": 2}], "quote\"\n\u0000\\": {"a": 1, "b": 2}}"""
        ) as kotlinx.serialization.json.JsonObject
        broker.executeShared("scope", "tool", "different", reversedArray) {
            executions.incrementAndGet()
            AgentToolResult("different", ToolResultContent.Text("different"), false)
        }
        assertEquals(2, executions.get())
    }

    @Test
    fun `long running call stays shared and ttl starts when its result arrives`() = runTest {
        var now = 0L
        val broker = SharedToolCallBroker(ttlMillis = 30_000, nowMillis = { now })
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger()
        val owner = async {
            broker.executeShared("scope", "tool", "owner", buildJsonObject {}) {
                executions.incrementAndGet()
                release.await()
                AgentToolResult("owner", ToolResultContent.Text("fresh"), false)
            }
        }
        runCurrent()
        now = 40_000
        val follower = async {
            broker.executeShared("scope", "tool", "follower", buildJsonObject {}) {
                executions.incrementAndGet()
                AgentToolResult("follower", ToolResultContent.Text("duplicate"), false)
            }
        }
        runCurrent()
        assertEquals(1, executions.get())
        release.complete(Unit)
        assertEquals(owner.await().content, follower.await().content)
        assertEquals("follower", follower.await().callId)

        now = 69_999
        val cached = broker.executeShared("scope", "tool", "cached", buildJsonObject {}) {
            error("Result should remain cached for 30 seconds after completion")
        }
        assertEquals("cached", cached.callId)
        now = 70_000
        broker.executeShared("scope", "tool", "fresh", buildJsonObject {}) {
            executions.incrementAndGet()
            AgentToolResult("fresh", ToolResultContent.Text("updated"), false)
        }
        assertEquals(2, executions.get())
    }

    @Test
    fun `canceling owner lets surviving follower execute again`() = runTest {
        val broker = SharedToolCallBroker()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            broker.executeShared("scope", "tool", "owner", buildJsonObject {}) {
                release.await()
                error("Canceled owner must not return a result")
            }
        }
        runCurrent()
        val follower = async {
            broker.executeShared("scope", "tool", "follower", buildJsonObject {}) {
                AgentToolResult("follower", ToolResultContent.Text("survived"), false)
            }
        }
        runCurrent()
        owner.cancelAndJoin()
        assertEquals(ToolResultContent.Text("survived"), follower.await().content)
    }

    @Test
    fun `canceling a follower does not cancel the shared execution`() = runTest {
        val broker = SharedToolCallBroker()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            broker.executeShared("scope", "tool", "owner", buildJsonObject {}) {
                release.await()
                AgentToolResult("owner", ToolResultContent.Text("result"), false)
            }
        }
        runCurrent()
        val follower = async {
            broker.executeShared("scope", "tool", "follower", buildJsonObject {}) {
                error("Follower must share the running execution")
            }
        }
        runCurrent()
        follower.cancelAndJoin()
        assertTrue(owner.isActive)
        release.complete(Unit)
        assertEquals("owner", owner.await().callId)
    }

    @Test
    fun `failed results are retried instead of cached`() = runTest {
        val broker = SharedToolCallBroker()
        val first = broker.executeShared("scope", "tool", "first", buildJsonObject {}) {
            AgentToolResult("first", ToolResultContent.Text("temporary failure"), true)
        }
        val retry = broker.executeShared("scope", "tool", "retry", buildJsonObject {}) {
            AgentToolResult("retry", ToolResultContent.Text("recovered"), false)
        }
        assertTrue(first.isError)
        assertEquals(ToolResultContent.Text("recovered"), retry.content)
    }
}
