package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent as HttpTextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PollinationsTransportTest {
    private val provider = FreeAiProvider.POLLINATIONS
    private val config get() = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)
    private fun request(cap: Int = 1500, text: String = "Reply OK.") = ChatCompletionRequest(provider.model, listOf(ChatMessage(Role.USER, listOf(TextContent(text)))), maxTokens = cap)
    private fun api(client: HttpClient): OpenAIAPIImpl = OpenAIAPIImpl(mockk<NetworkClient>().also { network -> every { network.invoke() } returns client })

    @Before fun reset() {
        FreeCatalogState.resetForTest()
        FreeAiRequestLimiter.shared.initialize(Files.createTempDirectory("classic-contract").resolve("cooldowns.json").toFile())
    }

    @Test fun structuredPostPreservesLongPromptsAndStreamingTerminalReason() = runBlocking {
        val original = "Retained evidence. ".repeat(500)
        val client = HttpClient(
            MockEngine { wire ->
                assertEquals(HttpMethod.Post, wire.method)
                assertEquals(provider.chatCompletionsUrl, wire.url.toString())
                assertTrue((wire.body as HttpTextContent).text.contains(original))
                assertNull(wire.headers[HttpHeaders.Authorization])
                respond("data: {\"model\":\"gpt-oss-20b\",\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        ) { install(HttpTimeout) }
        try {
            val chunks = api(client).streamChatCompletion(request(text = original), 30, config).toList()
            assertEquals("stop", chunks.single().choices!!.single().finishReason)
            assertEquals("gpt-oss-20b", chunks.single().model)
        } finally {
            client.close()
        }
    }

    @Test fun smallerHardAllowanceNeverDispatches() = runBlocking {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                respond("unexpected")
            }
        ) { install(HttpTimeout) }
        try {
            val chunks = api(client).streamChatCompletion(request(1499), 30, config).toList()
            assertEquals(0, calls)
            assertEquals("REQUEST_SCHEMA_INCOMPATIBLE", chunks.last().error?.type)
            assertTrue(chunks.last().error!!.message.contains("1500"))
        } finally {
            client.close()
        }
    }

    @Test fun exhaustedClassicSlotRetains402RetryAfterAndBlocksFurtherDispatch() = runBlocking {
        var calls = 0
        val client = HttpClient(
            MockEngine { wire ->
                calls++
                assertEquals(provider.chatCompletionsUrl, wire.url.toString())
                respond("""{"error":{"code":"free_slot_exhausted","message":"Free slot is busy"}}""", HttpStatusCode.PaymentRequired, headersOf("Retry-After", "120"))
            }
        ) { install(HttpTimeout) }
        try {
            val first = api(client).streamChatCompletion(request(), 30, config).toList()
            assertEquals("PROVIDER_RATE_LIMITED", first.last().error?.type)
            val retained = FreeAiRequestLimiter.shared.savedCooldown(provider)!!
            assertEquals(402, retained.status)
            assertEquals("retry_after_seconds", retained.deadlineSource)
            assertEquals(FreeGenerationReadiness.RATE_LIMITED, FreeCatalogState.status.value[provider]?.readiness)
            val second = api(client).streamChatCompletion(request(), 30, config).toList()
            assertEquals("LOCAL_COOLDOWN_ACTIVE", second.last().error?.type)
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }

    @Test fun paymentResponseWithoutCooldownDoesNotAuthorizeAnotherHost() = runBlocking {
        var calls = 0
        val client = HttpClient(
            MockEngine { wire ->
                calls++
                assertEquals(provider.chatCompletionsUrl, wire.url.toString())
                respond("""{"error":{"message":"Payment required"}}""", HttpStatusCode.PaymentRequired)
            }
        ) { install(HttpTimeout) }
        try {
            val chunks = api(client).streamChatCompletion(request(), 30, config).toList()
            assertEquals(1, calls)
            assertTrue(chunks.any { it.error != null })
            assertFalse(chunks.any { it.choices.orEmpty().any { choice -> !choice.effectiveDelta.content.isNullOrBlank() } })
            assertNull(FreeAiRequestLimiter.shared.savedCooldown(provider))
        } finally {
            client.close()
        }
    }
}
