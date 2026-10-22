package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent as HttpTextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeQueuedTransportTest {
    private fun request(model: String) = ChatCompletionRequest(model, listOf(ChatMessage(Role.USER, listOf(TextContent("A short novel sentence about clouds.")))), maxTokens = 512)

    @Test fun interruptedHordePollResumesSavedJobWithoutDuplicateSubmission() = runBlocking {
        var posts = 0
        var polls = 0
        val engine = MockEngine { wire ->
            assertNull(wire.headers[HttpHeaders.Authorization])
            if (wire.method == HttpMethod.Post) {
                posts++
                assertEquals("0000000000", wire.headers["apikey"])
                val body = Json.parseToJsonElement((wire.body as HttpTextContent).text).jsonObject
                assertEquals("512", body.getValue("params").jsonObject.getValue("max_length").jsonPrimitive.content)
                respond("""{"id":"job-1"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                polls++
                if (polls == 1) throw java.io.IOException("connection interrupted")
                respond("""{"done":true,"generations":[{"text":"A cloud folds its silver map.","model":"worker/model"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, queueScope = java.util.UUID.randomUUID().toString(), profileUid = "horde")
            assertNotNull(api.streamChatCompletion(request("automatic"), 30, config).toList().last().error)
            val result = api.streamChatCompletion(request("automatic"), 30, config).toList()
            assertEquals(1, posts)
            assertEquals("A cloud folds its silver map.", result.last().choices!!.single().delta.content)
            assertEquals("worker/model", result.last().model)
            assertNull(result.last().usage)
        } finally {
            client.close()
        }
    }

    @Test fun strictFreeRouteDoesNotDispatchPaidModelAndPreservesNamedFreeModel() = runBlocking {
        var calls = 0
        val engine = MockEngine { wire ->
            calls++
            assertNull(wire.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((wire.body as HttpTextContent).text).jsonObject
            assertEquals("cohere/north-mini-code", body.getValue("model").jsonPrimitive.content)
            respond("data: {\"model\":\"cohere/north-mini-code\",\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.BLOCKRUN.apiUrl, "never-send", freeProvider = FreeAiProvider.BLOCKRUN)
            assertNotNull(api.streamChatCompletion(request("paid/model"), 30, config).toList().last().error)
            assertEquals(0, calls)
            assertEquals("Hello", api.streamChatCompletion(request("cohere/north-mini-code"), 30, config).toList().last().choices!!.single().delta.content)
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }

    @Test fun gradioExtractsOnlyFinalAnswerAndRejectsInvalidRecords() {
        val data = Json.parseToJsonElement("""["",{"value":[{"role":"user","content":"hello"},{"role":"assistant","content":[{"type":"tool","content":"private reasoning"},{"type":"text","content":"Hello there"}]}]}]""")
        assertEquals("Hello there", FreeQueuedTransport.gradioAnswer(data))
        assertThrows(IllegalStateException::class.java) { FreeQueuedTransport.gradioAnswer(Json.parseToJsonElement("[]")) }
        assertThrows(IllegalArgumentException::class.java) { FreeQueuedTransport.hordePayload(request("automatic").copy(maxTokens = 8), "test") }
    }

    @Test fun ovhQuotaIsPerModelWhileBlockrunProfilesShareTheSamePool() {
        val limiter = FreeAiRequestLimiter { 1000L }
        limiter.reserve(FreeAiProvider.OVHCLOUD, "a")
        limiter.reserve(FreeAiProvider.OVHCLOUD, "a")
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD, "a") }
        limiter.reserve(FreeAiProvider.OVHCLOUD, "b")
        repeat(30) { limiter.reserve(FreeAiProvider.BLOCKRUN, "model-$it") }
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.BLOCKRUN, "new-model") }
    }
}
