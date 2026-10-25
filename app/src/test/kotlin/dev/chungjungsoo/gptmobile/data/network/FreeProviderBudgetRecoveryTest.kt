package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent as WireText
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeProviderBudgetRecoveryTest {
    @Test fun exhaustedWorkerDeadlineRejectsBeforePhysicalAttemptAdmission() = runBlocking {
        var clock = 0L
        var physicalAttempts = 0
        val client = HttpClient(MockEngine { error("No HTTP request may be dispatched") })
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val budget = WorkerRequestBudget(10000, java.util.concurrent.atomic.AtomicInteger(), clock = { clock })
            clock = 9501
            val dispatch = dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(emptySet())
            val failure = kotlinx.coroutines.withContext(budget + dispatch + dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptObserver { physicalAttempts++ }) {
                runCatching {
                    ModelRequestPreparation(network).prepare("""{"messages":[{"role":"user","content":"Question"}],"max_tokens":100}""", ProviderRequestConfig("https://example.invalid", null), "model", 100)
                }.exceptionOrNull()
            }
            assertTrue(failure?.message.orEmpty().contains("DELEGATE_DEADLINE_EXHAUSTED"))
            assertEquals(0, physicalAttempts)
            assertTrue(dispatch.receipts.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test fun bridgeNodeChecksFinalWireBudgetIncludingSystemAndHistory() = runBlocking {
        val client = HttpClient(
            MockEngine { wire ->
                val payload = (wire.body as WireText).text
                val root = Json.parseToJsonElement(payload).jsonObject
                val output = root.getValue("max_tokens").jsonPrimitive.int
                val measured = RequestAdmission.estimate(payload)
                assertTrue(output in 1..7999)
                assertTrue(measured.promptTokens + measured.uncertaintyTokens + output <= 8000)
                assertTrue(payload.contains("System instructions"))
                respond("""{"choices":[{"message":{"content":"Hello"},"finish_reason":"stop"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val provider = FreeAiProvider.BRIDGENODE
            val request = ChatCompletionRequest(provider.model, listOf(ChatMessage(Role.SYSTEM, listOf(TextContent("System instructions"))), ChatMessage(Role.USER, listOf(TextContent("test")))), maxTokens = 8192)
            val chunks = OpenAIAPIImpl(network).streamChatCompletion(request, 30, ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)).toList()
            assertNull(chunks.last().error)
            assertEquals("Hello", chunks.last().choices!!.single().effectiveDelta.content)
        } finally {
            client.close()
        }
    }

    @Test fun totalContextRejectionLearnsSeparateCapacityAndRetriesOnlyOnce() = runBlocking {
        var calls = 0
        val outputs = mutableListOf<Int>()
        val provider = FreeAiProvider.BRIDGENODE
        val model = "gpt-oss-120b"
        val client = HttpClient(
            MockEngine { wire ->
                calls++
                outputs += Json.parseToJsonElement((wire.body as WireText).text).jsonObject.getValue("max_tokens").jsonPrimitive.int
                respond("""{"error":{"code":"context_window_exceeded","message":"Input + max_tokens exceed this model's context window (8000 tokens): ~76 input tokens + 8000 max_tokens."}}""", HttpStatusCode.BadRequest)
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val result = OpenAIAPIImpl(network).streamChatCompletion(ChatCompletionRequest(model, listOf(ChatMessage(Role.USER, listOf(TextContent("test")))), maxTokens = 8192), 30, ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)).toList()
            assertEquals(2, calls)
            assertTrue(outputs[1] < outputs[0])
            assertNotNull(result.last().error)
            assertEquals(8000, FreeCatalogState.model(provider, model)?.contextTokens)
            assertNull(FreeCatalogState.model(provider, model)?.outputTokens)
            assertEquals(true, FreeCatalogState.status.value[provider]?.reachable)
            assertEquals(FreeGenerationReadiness.INVALID_REQUEST, FreeCatalogState.status.value[provider]?.readiness)
        } finally {
            client.close()
        }
    }

    @Test fun finalAdmissionCountsToolDefinitionsAndRetainsThem() = runBlocking {
        val client = HttpClient(MockEngine { error("Admission must not send HTTP") })
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val provider = FreeAiProvider.BRIDGENODE
            val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)
            val preparation = ModelRequestPreparation(network)
            val tools = kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("function"))
                put(
                    "function",
                    kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive("lookup"))
                        put("description", kotlinx.serialization.json.JsonPrimitive("Tool instructions ".repeat(100)))
                    }
                )
            }
            val root = Json.parseToJsonElement("""{"model":"gpt-oss-20b","messages":[{"role":"user","content":"test"}],"max_tokens":8192}""").jsonObject
            val plain = Json.parseToJsonElement(preparation.prepare(root.toString(), config, provider.model, null)).jsonObject
            val withTools = kotlinx.serialization.json.JsonObject(root + ("tools" to kotlinx.serialization.json.JsonArray(listOf(tools))))
            val wire = preparation.prepare(withTools.toString(), config, provider.model, null)
            val admitted = Json.parseToJsonElement(wire).jsonObject
            assertEquals(withTools["tools"], admitted["tools"])
            assertTrue(admitted.getValue("max_tokens").jsonPrimitive.int < plain.getValue("max_tokens").jsonPrimitive.int)
            val measurement = RequestAdmission.estimate(wire)
            assertTrue(measurement.promptTokens + measurement.uncertaintyTokens + admitted.getValue("max_tokens").jsonPrimitive.int <= 8000)
        } finally {
            client.close()
        }
    }

    @Test fun unknownAccessDenialsKeepEvidenceAndBlockedAttemptsCannotClearPause() = runBlocking {
        val provider = FreeAiProvider.VIREONIX
        FreeCatalogState.recordResponse(provider, null)
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                respond("<html>edge denial with private page content</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html"))
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val request = ChatCompletionRequest(provider.model, listOf(ChatMessage(Role.USER, listOf(TextContent("test")))))
            val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)
            repeat(4) {
                val error = api.streamChatCompletion(request, 30, config).toList().last().error!!
                if (it < 2) assertTrue(error.message.contains("specific cause is unavailable"))
                assertTrue(!error.message.contains("private page content"))
            }
            assertEquals(2, calls)
            assertEquals(true, FreeCatalogState.status.value[provider]?.reachable)
            assertEquals(FreeGenerationReadiness.ACCESS_DENIED, FreeCatalogState.status.value[provider]?.readiness)
        } finally {
            FreeCatalogState.recordResponse(provider, null)
            client.close()
        }
    }

    @Test fun rateLimitBodyAndResetHeadersSurviveBeforeCooldownException() = runBlocking {
        val provider = FreeAiProvider.CEHPOINT
        val directory = java.nio.file.Files.createTempDirectory("free-http-failure").toFile()
        dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.initialize(java.io.File(directory, "failures.json"))
        dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.clear()
        val client = HttpClient(
            MockEngine {
                respond("""{"error":{"code":"tokens_limit_exceeded","quota_scope":"daily_tokens","message":"Daily token allowance reached"}}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("1"), "X-Request-ID" to listOf("quota-trace"), "X-RateLimit-Reset-Tokens" to listOf("1s")))
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val request = ChatCompletionRequest(provider.model, listOf(ChatMessage(Role.USER, listOf(TextContent("test")))))
            val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, queueScope = "48:turn", queuePersistent = true)
            val error = OpenAIAPIImpl(network).streamChatCompletion(request, 30, config).toList().last().error!!
            assertTrue(error.message.contains("Daily token allowance reached"))
            val record = dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot(48).single()
            assertEquals("tokens_limit_exceeded", record.code)
            assertEquals("daily_tokens", record.quotaScope)
            assertEquals("quota-trace", record.requestId)
            assertEquals("1s", record.resetHeaders["X-RateLimit-Reset-Tokens"])
            assertNotNull(record.retryAt)
            assertNotNull(record.outputTokens)
            assertEquals(true, FreeCatalogState.status.value[provider]?.reachable)
            assertEquals(FreeGenerationReadiness.RATE_LIMITED, FreeCatalogState.status.value[provider]?.readiness)
        } finally {
            client.close()
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.clear()
            directory.deleteRecursively()
            // The shared production limiter must finish this short test cooldown before other tests use it.
            kotlinx.coroutines.delay(1100)
            FreeCatalogState.recordResponse(provider, null)
        }
    }

    @Test fun outputOnlyRejectionCannotPoisonTotalContext() {
        assertNull(rejectedFreeContextLimit(400, """{"error":{"code":"max_tokens_exceeded","message":"maximum output tokens 8000"}}"""))
        assertNull(rejectedFreeContextLimit(403, """{"code":"context_window_exceeded","context_window":8000}"""))
        assertEquals(8000, rejectedFreeContextLimit(400, """{"code":"context_window_exceeded","context_window":8000}"""))
    }
}
