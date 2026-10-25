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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FreeAiTransportTest {
    private lateinit var providerStateDirectory: java.io.File

    @Before fun initializeProviderState() {
        dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.resetForTest()
        providerStateDirectory = Files.createTempDirectory("free-ai-transport").toFile()
        FreeAiRequestLimiter.shared.initialize(java.io.File(providerStateDirectory, "cooldowns.json"))
    }

    @After fun clearProviderState() {
        dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.resetForTest()
        providerStateDirectory.deleteRecursively()
    }

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
            val chunks = OpenAIAPIImpl(network).streamChatCompletion(
                request().copy(models = listOf("paid-fallback"), maxTokens = 9000),
                10,
                ProviderRequestConfig("https://wrong.example", "do-not-send", freeProvider = FreeAiProvider.KILO)
            ).toList()
            assertEquals(listOf(9000), chunks.mapNotNull { it.serializedOutputTokens })
            val result = chunks.single { it.choices != null }
            assertNull(result.error?.message, result.error)
            assertEquals("Hello", result.choices!!.single().delta.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `Pollinations posts structured history with its native bound and preserves terminal usage`() = runBlocking {
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
            assertEquals(listOf(1500), result.mapNotNull { it.serializedOutputTokens })
            val response = result.first { it.choices != null }
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
    fun `HTTP 200 payload failures have the same category in JSON and streaming responses`() = runBlocking {
        val payload = """{"error":{"message":"Tool generation was attempted with tool_choice none","type":"invalid_request_error","code":"tool_use_failed"}}"""
        for (streaming in listOf(false, true)) {
            val engine = MockEngine {
                respond(
                    if (streaming) "data: $payload\n\ndata: [DONE]\n\n" else payload,
                    headers = headersOf(HttpHeaders.ContentType, if (streaming) "text/event-stream" else "application/json")
                )
            }
            val client = HttpClient(engine) { install(HttpTimeout) }
            try {
                val network = mockk<NetworkClient>()
                every { network.invoke() } returns client
                val result = OpenAIAPIImpl(network).streamChatCompletion(
                    request().copy(toolChoice = "none"),
                    10,
                    ProviderRequestConfig(FreeAiProvider.KILO.apiUrl, null, freeProvider = FreeAiProvider.KILO)
                ).toList()
                assertEquals("tool_choice_mismatch", result.last().error?.type)
                assertTrue(result.last().error!!.message.startsWith("TOOL_CHOICE_MISMATCH:"))
                assertTrue(result.flatMap { it.choices.orEmpty() }.all { it.effectiveDelta.content.isNullOrBlank() })
            } finally {
                client.close()
            }
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
            assertEquals("Answer", events.single { it.choices != null }.choices!!.single().delta.content)
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
