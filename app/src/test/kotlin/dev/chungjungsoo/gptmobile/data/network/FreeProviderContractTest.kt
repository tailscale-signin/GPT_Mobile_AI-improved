package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FreeProviderContractTest {
    private val layout get() = javaClass.getResource("/free/gradio/qwen-config.json")!!.readText()
    private val info get() = javaClass.getResource("/free/gradio/qwen-info.json")!!.readText()

    @Before fun reset() {
        FreeCatalogState.resetForTest()
        FreeAiRequestLimiter.shared.initialize(Files.createTempDirectory("provider-contract").resolve("cooldowns.json").toFile())
    }

    private fun request(provider: FreeAiProvider) = ChatCompletionRequest(provider.model, listOf(ChatMessage(Role.USER, listOf(TextContent("Hi. Reply only OK.")))), maxTokens = 128)

    private fun api(client: HttpClient): OpenAIAPIImpl {
        val network = mockk<NetworkClient>()
        every { network.invoke() } returns client
        return OpenAIAPIImpl(network)
    }

    @Test fun rawCallUsesAllDiscoveredPositionsAndTheMatchingEventRetrieval() = runBlocking {
        var posts = 0
        var gets = 0
        val client = HttpClient(
            MockEngine { wire ->
                when {
                    wire.url.encodedPath == "/config" -> respond(layout)
                    wire.url.encodedPath.endsWith("/info") -> respond(info)
                    wire.method == HttpMethod.Post -> {
                        val body = Json.parseToJsonElement((wire.body as HttpTextContent).text).jsonObject
                        val data = body["data"] as JsonArray
                        // Faithful to Gradio 5.27 validate_inputs: undersized raw arrays fail.
                        if (data.size != 4) return@MockEngine respond("validation failed", HttpStatusCode.BadRequest)
                        assertEquals(JsonNull, data[2])
                        assertEquals(JsonNull, data[3])
                        assertNull(body["session_hash"])
                        assertEquals("/gradio_api/call/add_message", wire.url.encodedPath)
                        posts++
                        respond("""{"event_id":"contract-event"}""")
                    }
                    else -> {
                        assertEquals("/gradio_api/call/add_message/contract-event", wire.url.encodedPath)
                        assertNull(wire.url.parameters["session_hash"])
                        gets++
                        respond("event: complete\ndata: [[{\"role\":\"assistant\",\"content\":\"OK\"}]]\n\n")
                    }
                }
            }
        ) { install(HttpTimeout) }
        try {
            val provider = FreeAiProvider.HUGGING_FACE
            val chunks = api(client).streamChatCompletion(request(provider), 30, ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)).toList()
            assertNull(chunks.last().error)
            assertEquals("OK", chunks.last().choices!!.single().effectiveDelta.content)
            assertEquals(1, posts)
            assertEquals(1, gets)
        } finally {
            client.close()
        }
    }

    @Test fun unsupportedInputIsRejectedBeforeAnyGenerationSubmission() = runBlocking {
        var posts = 0
        val client = HttpClient(
            MockEngine { wire ->
                if (wire.method == HttpMethod.Post) posts++
                respond(if (wire.url.encodedPath == "/config") layout.replace("\"state\"", "\"browserstate\"") else info)
            }
        ) { install(HttpTimeout) }
        try {
            val provider = FreeAiProvider.HUGGING_FACE
            val chunks = api(client).streamChatCompletion(request(provider), 30, ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)).toList()
            assertEquals("REQUEST_SCHEMA_INCOMPATIBLE", chunks.last().error!!.type)
            assertEquals(0, posts)
        } finally {
            client.close()
        }
    }

    @Test fun hiddenStatesCanBeInterleavedAndOnlyDiscoveredStatesGetPlaceholders() {
        val config = layout.replace("33,\n        38,\n        60,\n        1", "60,\n        33,\n        1,\n        38")
        val contract = GradioInputContract.discover(Json.parseToJsonElement(config).jsonObject, Json.parseToJsonElement(info).jsonObject)
        assertEquals(listOf(false, true, false, true), contract.publicSlots)
        val body = FreeQueuedTransport.gradioPayload(request(FreeAiProvider.HUGGING_FACE), "test", contract)["data"] as JsonArray
        assertEquals(4, body.size)
        assertEquals(JsonNull, body[0])
        assertEquals(JsonNull, body[2])
    }

    @Test fun terminalNullUsesOnlyThePriorProviderDiagnosticAndNeverPublishesAnAnswer() = runBlocking {
        val file = Files.createTempDirectory("provider-history").resolve("history.json").toFile()
        FreeProviderDiagnostics.initialize(file)
        val stream = """event: generating
            |data: [{"value":[{"role":"user","content":"PRIVATE PROMPT"},{"role":"assistant","status":"done","loading":false,"content":[{"type":"text","content":"<span style=\"color: var(--color-red-500)\">'NoneType' object has no attribute 'choices' Authorization: Bearer secret-value</span>"}]}]}]
            |
            |event: error
            |data: null
            |
            |
        """.trimMargin()
        val client = HttpClient(
            MockEngine { wire ->
                respond(
                    when {
                        wire.url.encodedPath == "/config" -> layout
                        wire.url.encodedPath.endsWith("/info") -> info
                        wire.method == HttpMethod.Post -> """{"event_id":"failed-backend"}"""
                        else -> stream
                    }
                )
            }
        ) { install(HttpTimeout) }
        try {
            val provider = FreeAiProvider.HUGGING_FACE
            val chunks = api(client).streamChatCompletion(request(provider), 30, ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, queueScope = "42:turn", queuePersistent = true)).toList()
            assertEquals("HOSTED_GENERATION_FAILED", chunks.last().error!!.type)
            assertTrue(chunks.last().error!!.message.contains("'NoneType' object has no attribute 'choices'"))
            assertTrue(chunks.flatMap { it.choices.orEmpty() }.all { it.effectiveDelta.content.isNullOrBlank() })
            val saved = FreeProviderDiagnostics.snapshot(42).single()
            assertEquals(true, saved.requestSent)
            assertEquals(true, saved.terminal)
            assertFalse(file.readText().contains("secret-value"))
            assertFalse(file.readText().contains("PRIVATE PROMPT"))
        } finally {
            client.close()
            FreeProviderDiagnostics.clear()
            file.parentFile.deleteRecursively()
        }
    }

    @Test fun errorLikeModelTextWithSuccessfulCompletionIsAnAnswer() = runBlocking {
        val client = HttpClient(
            MockEngine { wire ->
                respond(
                    when {
                        wire.url.encodedPath == "/config" -> layout
                        wire.url.encodedPath.endsWith("/info") -> info
                        wire.method == HttpMethod.Post -> """{"event_id":"normal-answer"}"""
                        else -> "event: complete\ndata: [[{\"role\":\"assistant\",\"content\":\"Example error: NoneType object has no choices\"}]]\n\n"
                    }
                )
            }
        ) { install(HttpTimeout) }
        try {
            val p = FreeAiProvider.HUGGING_FACE
            val chunks = api(client).streamChatCompletion(request(p), 30, ProviderRequestConfig(p.apiUrl, null, freeProvider = p)).toList()
            assertNull(chunks.last().error)
            assertTrue(chunks.last().choices!!.single().effectiveDelta.content!!.contains("Example error"))
        } finally {
            client.close()
        }
    }

    @Test fun llm7TokenReachesDispatchAndMissingTokenMakesZeroHttpRequests() = runBlocking {
        var posts = 0
        val client = HttpClient(
            MockEngine { wire ->
                posts++
                assertEquals("Bearer synthetic-free-token", wire.headers[HttpHeaders.Authorization])
                respond("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        ) { install(HttpTimeout) }
        try {
            val p = FreeAiProvider.LLM7
            val config = ProviderRequestConfig(p.apiUrl, null, freeProvider = p)
            val missing = api(client).streamChatCompletion(request(p), 30, config).toList()
            assertEquals("AUTHENTICATION_REQUIRED", missing.last().error!!.type)
            assertEquals(0, posts)
            val success = api(client).streamChatCompletion(request(p), 30, config.copy(token = "synthetic-free-token")).toList()
            assertNull(success.last().error)
            assertEquals(1, posts)
        } finally {
            client.close()
        }
    }

    @Test fun savedCooldownBlocksDispatchAndExportsItsOriginalCauseAcrossChats() = runBlocking {
        val history = Files.createTempDirectory("cooldown-origin").resolve("history.json").toFile()
        FreeProviderDiagnostics.initialize(history)
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                respond("""{"error":{"code":"daily_tokens_exhausted","message":"Synthetic token quota reached","quota_scope":"token"}}""", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "10800"))
            }
        ) { install(HttpTimeout) }
        try {
            val p = FreeAiProvider.LLM7
            val config = ProviderRequestConfig(p.apiUrl, "synthetic-free-token", freeProvider = p, queueScope = "1:turn", queuePersistent = true)
            api(client).streamChatCompletion(request(p), 30, config).toList()
            val blocked = api(client).streamChatCompletion(request(p), 30, config.copy(queueScope = "2:turn")).toList()
            assertEquals(1, calls)
            assertEquals("LOCAL_COOLDOWN_ACTIVE", blocked.last().error!!.type)
            val origin = FreeProviderDiagnostics.snapshot(1).single()
            val local = FreeProviderDiagnostics.snapshot(2).single()
            assertEquals("PROVIDER_RATE_LIMITED", origin.category)
            assertEquals(true, origin.requestSent)
            assertEquals(false, local.requestSent)
            assertEquals(origin.failureId, local.cooldownOrigin!!.originatingFailureId)
            assertEquals(429, local.cooldownOrigin!!.status)
            assertEquals("daily_tokens_exhausted", local.cooldownOrigin!!.providerCode)
            assertEquals("retry_after_seconds", local.cooldownOrigin!!.deadlineSource)
            assertEquals(origin.retryAt, local.retryAt)
            assertFalse(history.readText().contains("synthetic-free-token"))
        } finally {
            client.close()
            FreeProviderDiagnostics.clear()
            history.parentFile.deleteRecursively()
        }
    }

    @Test fun rejectedLlm7TokenGivesAnAuthenticationActionWithoutLeakingTheToken() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond("""{"error":{"message":"Rejected synthetic-free-token"}}""", HttpStatusCode.Unauthorized)
            }
        ) { install(HttpTimeout) }
        try {
            val provider = FreeAiProvider.LLM7
            val chunks = api(client).streamChatCompletion(request(provider), 30, ProviderRequestConfig(provider.apiUrl, "synthetic-free-token", freeProvider = provider)).toList()
            val error = chunks.last().error!!
            assertEquals("AUTHENTICATION_REJECTED", error.type)
            assertTrue(error.message.contains("Check API token"))
            assertFalse(error.message.contains("synthetic-free-token"))
        } finally {
            client.close()
        }
    }
}
