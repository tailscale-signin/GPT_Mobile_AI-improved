package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent as HttpTextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.net.URLDecoder
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
            assertEquals("2048", body.getValue("max_tokens").jsonPrimitive.content)
            assertFalse(body.containsKey("models"))
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine)
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val result = OpenAIAPIImpl(network).streamChatCompletion(
                request().copy(model = "paid-model", models = listOf("paid-fallback"), maxTokens = 9000),
                10,
                ProviderRequestConfig("https://wrong.example", "do-not-send", freeProvider = FreeAiProvider.KILO)
            ).single()
            assertEquals("Hello", result.choices!!.single().delta.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `Pollinations uses the legacy GET adapter with role labelled history`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("text.pollinations.ai", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            val prompt = URLDecoder.decode(request.url.encodedPath.removePrefix("/"), "UTF-8")
            assertTrue(prompt.contains("system: Be brief"))
            assertTrue(prompt.contains("user: A & B?"))
            assertTrue(prompt.endsWith("assistant:"))
            respond("A and B.", headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpClient(engine)
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val result = OpenAIAPIImpl(network).streamChatCompletion(
                request(),
                10,
                ProviderRequestConfig(FreeAiProvider.POLLINATIONS.apiUrl, null, freeProvider = FreeAiProvider.POLLINATIONS)
            ).single()
            assertEquals("A and B.", result.choices!!.single().delta.content)
            assertEquals("stop", result.choices.single().finishReason)
        } finally {
            client.close()
        }
    }

    @Test
    fun `legacy adapter rejects excessive prompts before sending them`() {
        assertThrows(IllegalArgumentException::class.java) {
            legacyPollinationsPrompt(request().copy(messages = listOf(ChatMessage(Role.USER, listOf(TextContent("x".repeat(6001)))))))
        }
    }

    private fun request() = ChatCompletionRequest(
        model = "openai",
        messages = listOf(
            ChatMessage(Role.SYSTEM, listOf(TextContent("Be brief"))),
            ChatMessage(Role.USER, listOf(TextContent("A & B?")))
        )
    )
}
