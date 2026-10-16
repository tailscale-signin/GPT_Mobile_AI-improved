package dev.chungjungsoo.gptmobile.data.nvidia

import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaInferenceProbeTest {
    @Test
    fun checkSendsAuthenticatedMinimalRequestAndRequiresVisibleAnswer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        var payload = ""
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            payload = exchange.requestBody.bufferedReader().use { it.readText() }
            val bytes = """{"choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val network = NetworkClient(CIO)
        try {
            val result = NvidiaInferenceProbe(network).check("http://127.0.0.1:${server.address.port}/v1", "test-secret", "openai/gpt-oss-20b")
            assertEquals(200, result.status)
            assertTrue(result.visibleAnswer)
            assertEquals(2, result.outputTokens)
            assertEquals("Bearer test-secret", authorization)
            val json = Json.parseToJsonElement(payload).jsonObject
            assertEquals("low", json.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals("Reply with OK.", json.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(64, json.getValue("max_tokens").jsonPrimitive.content.toInt())
            assertFalse(payload.contains("test-secret"))
        } finally {
            network().close()
            server.stop(0)
        }
    }
}
