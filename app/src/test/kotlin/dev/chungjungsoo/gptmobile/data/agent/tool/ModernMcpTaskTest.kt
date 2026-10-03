package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskHandle
import dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskStore
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernMcpTaskTest {
    private val config = McpConnectionConfig("remote", "https://example.com/mcp", false, "Bearer token")
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private var saved: WorkspaceRecord? = null
    private fun store(): RemoteTaskStore = mockk<RemoteTaskStore>().also { store ->
        coEvery { store.latest(any()) } answers { saved }
        coEvery { store.save(any(), any(), any(), any(), any()) } answers {
            val task = secondArg<JsonObject>()
            WorkspaceRecord("remote-task", "remote", "task", Json.encodeToString(RemoteTaskHandle(config.connectionUid, RemoteTaskStore.identity(config), task, arg(3), arg(4))), 1, "run").also { saved = it }
        }
    }

    @Test fun negotiatedTaskSurvivesDisconnectAndResumesWithoutReplayingOriginalAction() = runBlocking {
        var disconnected = true
        var calls = 0
        val methods = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                methods += method
                val result = when (method) {
                    "server/discover" -> obj("""{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{},"extensions":{"io.modelcontextprotocol/tasks":{}}}}""")
                    "tools/list" -> obj("""{"tools":[{"name":"work","inputSchema":{"type":"object"}}]}""")
                    "tools/call" -> {
                        calls++
                        assertTrue(body.getValue("params").jsonObject.getValue("_meta").jsonObject.getValue("io.modelcontextprotocol/clientCapabilities").jsonObject.getValue("extensions").jsonObject.containsKey("io.modelcontextprotocol/tasks"))
                        obj("""{"resultType":"task","task":{"taskId":"saved-id","status":"working"}}""")
                    }
                    "tasks/get" -> {
                        if (disconnected) return@MockEngine respond("lost connection", HttpStatusCode.ServiceUnavailable)
                        obj("""{"resultType":"complete","taskId":"saved-id","status":"completed","result":{"content":[{"type":"text","text":"done"}]}}""")
                    }
                    else -> error(method)
                }
                respond(
                    buildJsonObject {
                        put("id", body.getValue("id"))
                        put("result", result)
                    }.toString(),
                    headers = headersOf("Content-Type", "application/json")
                )
            }
        )
        client.use {
            val transport = ModernMcpTransport(it, tasks = store())
            assertTrue(transport.supports(config))
            assertTrue(runCatching { transport.callTool(config, "work", obj("{}"), "call") }.isFailure)
            assertEquals("working", Json.decodeFromString<RemoteTaskHandle>(requireNotNull(saved).payload).task.getValue("status").jsonPrimitive.content)
            disconnected = false
            val recovered = transport.refreshTask(config, requireNotNull(saved))
            assertEquals("completed", Json.decodeFromString<RemoteTaskHandle>(recovered.payload).task.getValue("status").jsonPrimitive.content)
            assertEquals(1, calls)
            val before = methods.size
            assertTrue(runCatching { transport.refreshTask(McpConnectionConfig(config.connectionUid, config.endpointUrl, false, "Bearer changed"), recovered) }.isFailure)
            assertEquals(before, methods.size)
        }
    }

    @Test fun unnegotiatedTaskExtensionIsNeverAdvertisedAndUnexpectedHandleIsRejected() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                val result = when (method) {
                    "server/discover" -> obj("""{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""")
                    "tools/list" -> obj("""{"tools":[{"name":"work","inputSchema":{"type":"object"}}]}""")
                    "tools/call" -> {
                        assertFalse(body.toString().contains("io.modelcontextprotocol/tasks"))
                        obj("""{"resultType":"task","task":{"taskId":"unexpected","status":"working"}}""")
                    }
                    else -> error("No task polling is allowed without negotiation")
                }
                respond(
                    buildJsonObject {
                        put("id", body.getValue("id"))
                        put("result", result)
                    }.toString(),
                    headers = headersOf("Content-Type", "application/json")
                )
            }
        )
        client.use {
            val transport = ModernMcpTransport(it, tasks = store())
            transport.supports(config)
            assertTrue(runCatching { transport.callTool(config, "work", obj("{}")) }.isFailure)
            assertEquals(null, saved)
        }
    }
}
