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
import java.nio.file.Files
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeAiTransportTest {
    @Test
    fun `Kilo pins the free route and strips credentials while streaming`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(FreeAiProvider.KILO.chatCompletionsUrl, request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = NetworkClient.openAIJson.parseToJsonElement((request.body as HttpTextContent).text).jsonObject
            assertEquals("kilo-auto/free", body.getValue("model").jsonPrimitive.content)
            assertEquals("9000", body.getValue("max_tokens").jsonPrimitive.content)
            assertFalse(body.containsKey("models"))
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val result = OpenAIAPIImpl(network).streamChatCompletion(
                request().copy(models = listOf("paid-fallback"), maxTokens = 9000),
                10,
                ProviderRequestConfig("https://wrong.example", "do-not-send", freeProvider = FreeAiProvider.KILO)
            ).single()
            assertEquals("Hello", result.choices!!.single().delta.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `Pollinations posts structured history with its native bound and preserves terminal usage`() = runBlocking {
        FreeAiRequestLimiter.shared.initialize(Files.createTempDirectory("pollinations-post").resolve("cooldowns.json").toFile())
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(FreeAiProvider.POLLINATIONS.chatCompletionsUrl, request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = NetworkClient.openAIJson.parseToJsonElement((request.body as HttpTextContent).text).jsonObject
            assertEquals("1500", body.getValue("max_tokens").jsonPrimitive.content)
            assertEquals("low", body.getValue("reasoning_effort").jsonPrimitive.content)
            assertEquals(listOf("system", "user"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertFalse(body.containsKey("models"))
            respond("""{"model":"gpt-oss-20b","choices":[{"message":{"role":"assistant","content":"A and B."},"finish_reason":"length"}],"usage":{"prompt_tokens":17,"completion_tokens":1500,"total_tokens":1517}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val result = OpenAIAPIImpl(network).streamChatCompletion(
                request().copy(model = FreeAiProvider.POLLINATIONS.model, maxTokens = 9000, models = listOf("paid-fallback")),
                10,
                ProviderRequestConfig("https://wrong.example", "do-not-send", freeProvider = FreeAiProvider.POLLINATIONS)
            ).toList()
            val response = result.first()
            val choice = requireNotNull(response.choices).single()
            assertEquals("A and B.", choice.effectiveDelta.content)
            assertEquals("length", choice.finishReason)
            assertEquals("gpt-oss-20b", response.model)
            assertEquals(1500, response.usage?.completionTokens)
            assertTrue(result.last().applicationNotice!!.contains("continue"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `empty and reasoning-only streams fail while visible token-limited answers offer continuation`() = runBlocking {
        for (outcome in listOf("empty", "reasoning", "answer")) {
            val engine = MockEngine {
                val data = when (outcome) {
                    "reasoning" -> "data: {\"choices\":[{\"delta\":{\"reasoning\":\"Thinking\"},\"finish_reason\":\"length\"}]}\n\n"
                    "answer" -> "data: {\"choices\":[{\"delta\":{\"content\":\"A useful partial answer.\"},\"finish_reason\":\"length\"}]}\n\n"
                    else -> ""
                }
                respond(data + "data: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            val client = HttpClient(engine) { install(HttpTimeout) }
            try {
                val network = mockk<NetworkClient>()
                every { network.invoke() } returns client
                val result = OpenAIAPIImpl(network).streamChatCompletion(request(), 10, ProviderRequestConfig(FreeAiProvider.KILO.apiUrl, null, freeProvider = FreeAiProvider.KILO)).toList()
                if (outcome == "answer") {
                    assertEquals("length", result.last().choices!!.single().finishReason)
                    assertTrue(result.last().applicationNotice!!.contains("continue?"))
                    assertTrue(result.flatMap { it.choices.orEmpty() }.any { it.delta.content == "A useful partial answer." })
                    assertTrue(result.all { it.error == null })
                } else {
                    assertEquals(if (outcome == "reasoning") "reasoning_budget_exhausted" else "no_visible_answer", result.last().error?.type)
                    assertTrue(result.all { it.applicationNotice == null })
                    assertTrue(result.flatMap { it.choices.orEmpty() }.all { it.delta.content.isNullOrBlank() })
                }
            } finally {
                client.close()
            }
        }
    }

    @Test fun kiloReasoningPreferenceIsNotClearedByTransportPreparation() = runBlocking {
        val engine = MockEngine { request ->
            val body = NetworkClient.openAIJson.parseToJsonElement((request.body as HttpTextContent).text).jsonObject
            assertEquals("false", body.getValue("reasoning").jsonObject.getValue("enabled").jsonPrimitive.content)
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val events = OpenAIAPIImpl(network).streamChatCompletion(request().copy(reasoning = dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning(enabled = false)), 10, ProviderRequestConfig(FreeAiProvider.KILO.apiUrl, null, freeProvider = FreeAiProvider.KILO)).toList()
            assertEquals("Answer", events.single().choices!!.single().delta.content)
        } finally {
            client.close()
        }
    }

    private fun request() = ChatCompletionRequest(
        model = FreeAiProvider.KILO.model,
        messages = listOf(
            ChatMessage(Role.SYSTEM, listOf(TextContent("Be brief"))),
            ChatMessage(Role.USER, listOf(TextContent("A & B?")))
        )
    )
}
