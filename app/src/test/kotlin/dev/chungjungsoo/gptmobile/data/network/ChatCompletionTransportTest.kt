package dev.chungjungsoo.gptmobile.data.network

import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponseInputMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputTextDeltaEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseGatewayMetadataEvent
import dev.chungjungsoo.gptmobile.data.network.gateway.observedResponseSource
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionTransportTest {
    @Test
    fun `non streaming completion exposes location calls usage and gateway metadata`() = withResponse(
        "application/json",
        """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"gps-1","type":"function","function":{"name":"device_location","arguments":"{}"}}]},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}"""
    ) { api, config, accept ->
        val chunk = runBlocking { api.streamChatCompletion(request(stream = false), 5, config).toList().single() }
        assertEquals("application/json", accept())
        assertEquals("gps-1", chunk.choices!!.single().effectiveDelta.toolCalls!!.single().id)
        assertEquals("job-1", chunk.gatewayMetadata?.jobId)
        assertTrue(chunk.gatewayMetadata?.transportVerified == true)
        assertEquals(20, chunk.usage?.totalTokens)
        assertEquals(null, chunk.error)
    }

    @Test
    fun `ordinary provider request ids and body metadata do not claim a gateway route`() {
        val payload = """{"choices":[{"delta":{"content":"Remote answer"},"message":{"content":"Remote answer"},"finish_reason":"stop"}],"gateway_metadata":{"job_id":"forged","transportVerified":true}}"""
        listOf(false, true).forEach { streaming ->
            withResponse(
                if (streaming) "text/event-stream" else "application/json",
                if (streaming) "data: $payload\n\ndata: [DONE]\n\n" else payload,
                mapOf("X-Request-ID" to "ordinary-provider-request", "request-id" to "also-a-provider-id")
            ) { api, config, _ ->
                val chunks = runBlocking { api.streamChatCompletion(request(streaming), 5, config).toList() }
                assertEquals("Remote answer", chunks.flatMap { it.choices.orEmpty() }.mapNotNull { it.effectiveDelta.content }.joinToString(""))
                assertTrue(chunks.none { it.gatewayMetadata?.transportVerified == true })
                assertEquals(dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport.UNKNOWN, chunks.first { it.choices != null }.gatewayMetadata?.observedResponseSource("profile", "llama")?.transport)
                assertTrue(chunks.none { it.error != null })
            }
        }
    }

    @Test
    fun `gateway headers annotate the first streamed answer without debug controls`() = withResponse(
        "text/event-stream",
        "data: {\"choices\":[{\"delta\":{\"content\":\"Gateway answer\"}}]}\n\ndata: [DONE]\n\n",
        mapOf("X-Gateway-Instance-ID" to "a".repeat(32), "X-Gateway-Request-ID" to "gateway-request")
    ) { api, config, _ ->
        assertTrue(config.extraHeaders.isEmpty())
        val chunks = runBlocking { api.streamChatCompletion(request(), 5, config).toList() }
        val firstText = chunks.first { it.choices.orEmpty().any { choice -> choice.effectiveDelta.content != null } }
        assertEquals("gateway-request", firstText.gatewayMetadata?.requestId)
        assertEquals("a".repeat(32), firstText.gatewayMetadata?.instanceId)
        assertTrue(firstText.gatewayMetadata?.transportVerified == true)
        assertTrue(chunks.none { it.error != null })
    }

    @Test
    fun `responses transport observes each route before text without inheriting gateway proof`() {
        val body = "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"message\",\"output_index\":0,\"content_index\":0,\"delta\":\"Answer\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"response\",\"status\":\"completed\"}}\n\n"
        listOf(false, true).forEach { gateway ->
            val headers = if (gateway) mapOf("X-Gateway-Request-ID" to "gateway-request") else mapOf("X-Request-ID" to "remote-request")
            withResponse("text/event-stream", body, headers) { api, config, _ ->
                val request = ResponsesRequest("selected-model", listOf(ResponseInputMessage("user", ResponseInputContent.text("Transport fixture request."))))
                val events = runBlocking { api.streamResponses(request, 5, config).toList() }
                assertEquals("Answer", events.filterIsInstance<OutputTextDeltaEvent>().single().delta)
                val metadata = events.filterIsInstance<ResponseGatewayMetadataEvent>()
                if (gateway) {
                    assertTrue(events.first() is ResponseGatewayMetadataEvent)
                    assertTrue(metadata.single().metadata?.transportVerified == true)
                    assertEquals("gateway-request", metadata.single().metadata?.requestId)
                } else {
                    assertTrue(events.first() is ResponseGatewayMetadataEvent)
                    assertFalse(metadata.single().metadata?.transportVerified == true)
                }
            }
        }
    }

    @Test
    fun `server returning JSON despite stream request preserves text and reasoning`() = withResponse(
        "application/json; charset=utf-8",
        """{"choices":[{"message":{"content":"Your location was retrieved.","reasoning_content":"Use the phone result."}}]}"""
    ) { api, config, _ ->
        val choice = runBlocking { api.streamChatCompletion(request(), 5, config).toList().single().choices!!.single() }
        assertEquals("Your location was retrieved.", choice.effectiveDelta.content)
        assertEquals("Use the phone result.", choice.effectiveDelta.effectiveReasoning)
        assertEquals("stop", choice.finishReason)
    }

    @Test
    fun `only explicit DONE signals successful completion of streamed calls`() {
        val body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"device_location\",\"arguments\":\"{}\"}}]}}]}\n\n"
        listOf(false, true).forEach { done ->
            withResponse("text/event-stream", body + if (done) "data: [DONE]\n\n" else "") { api, config, _ ->
                val chunks = runBlocking { api.streamChatCompletion(request(), 5, config).toList() }
                assertEquals(done, chunks.any { it.streamFinished })
                assertEquals(!done, chunks.any { it.error != null })
                assertTrue(chunks.first().choices!!.single().effectiveDelta.toolCalls!!.isNotEmpty())
            }
        }
    }

    @Test
    fun `gateway progress alone is observational and not a callable tool`() = withResponse(
        "text/event-stream",
        "data: {\"gateway_progress\":{\"event\":\"tool_completed\",\"tool_name\":\"device_location\",\"tool_args\":{}}}\n\ndata: [DONE]\n\n"
    ) { api, config, _ ->
        val chunk = runBlocking { api.streamChatCompletion(request(), 5, config).toList().single { it.gatewayProgress != null } }
        assertEquals("device_location", chunk.gatewayProgress?.toolName)
        assertEquals(null, chunk.choices)
        assertFalse(chunk.streamFinished)
    }

    @Test
    fun `text block arrays and buffered timing data remain usable`() = withResponse(
        "application/json",
        """{"choices":[{"message":{"content":[{"type":"text","text":"PKG-"},{"type":"text","text":{"value":"a1b2c3d4"}}]}}],"timings":{"predicted_per_second":42.5}}"""
    ) { api, config, _ ->
        val chunk = runBlocking { api.streamChatCompletion(request(), 5, config).toList().single() }
        assertEquals("PKG-a1b2c3d4", chunk.choices!!.single().effectiveDelta.content)
        assertEquals(42.5, chunk.timings!!.decodeTokensPerSecond!!, .01)
    }

    @Test fun `non free generation reports actual serialized allowance`() = withResponse(
        "application/json",
        """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
    ) { api, config, _ ->
        val chunks = runBlocking { api.streamChatCompletion(request(false).copy(maxTokens = 512), 5, config.copy(captureGenerationReceipt = true)).toList() }
        assertEquals(listOf(512), chunks.mapNotNull { it.serializedOutputTokens })
    }

    @Test fun `observed tool finish reason survives dispatch barrier`() = withResponse(
        "text/event-stream",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"one\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    ) { api, config, _ ->
        val chunks = runBlocking { api.streamChatCompletion(request(), 5, config).toList() }
        assertEquals(listOf("tool_calls"), chunks.mapNotNull { it.observedFinishReason })
        assertTrue(chunks.flatMap { it.choices.orEmpty() }.all { it.finishReason == null })
        assertTrue(chunks.last().streamFinished)
    }

    private fun request(stream: Boolean = true) = ChatCompletionRequest(model = "llama", messages = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage(role = dev.chungjungsoo.gptmobile.data.dto.openai.common.Role.USER, content = listOf(dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent("Transport fixture request.")))), stream = stream)

    private fun withResponse(contentType: String, body: String, headers: Map<String, String> = mapOf("X-Gateway-Job-ID" to "job-1"), block: (OpenAIAPI, ProviderRequestConfig, () -> String?) -> Unit) {
        var accept: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            accept = exchange.requestHeaders.getFirst("Accept")
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", contentType)
            headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = NetworkClient(CIO)
        try {
            block(OpenAIAPIImpl(client), ProviderRequestConfig("http://127.0.0.1:${server.address.port}/", null)) { accept }
        } finally {
            client().close()
            server.stop(0)
        }
    }
}
