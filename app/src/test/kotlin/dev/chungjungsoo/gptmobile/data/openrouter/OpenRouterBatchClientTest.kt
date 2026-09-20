package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchSettings
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import dev.chungjungsoo.gptmobile.domain.service.BatchManager
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterBatchClientTest {

    @Test
    fun `OpenRouterBatchSettings has valid defaults`() {
        val settings = OpenRouterBatchSettings()
        assertEquals(10, settings.maxConcurrentRequests)
        assertEquals(1000L, settings.retryDelayMs)
        assertEquals(30000L, settings.timeoutMs)
    }

    @Test
    fun `OpenRouter BatchConfig can be created with batchKey`() {
        val config = BatchConfig(
            platform = PlatformType.OPENROUTER,
            apiKey = "sk-or-test",
            batchKey = "sk-batch-test",
            openRouter = OpenRouterBatchSettings(
                maxConcurrentRequests = 5,
                retryDelayMs = 500L,
                timeoutMs = 15000L
            )
        )

        assertEquals(PlatformType.OPENROUTER, config.platform)
        assertEquals("sk-or-test", config.apiKey)
        assertEquals("sk-batch-test", config.batchKey)
        assertEquals(5, config.openRouter.maxConcurrentRequests)
        assertEquals(500L, config.openRouter.retryDelayMs)
        assertEquals(15000L, config.openRouter.timeoutMs)
    }

    @Test
    fun `BatchRequest correctly defaults maxTokens and temperature`() {
        val req = BatchRequest(prompt = "Hello world")
        assertEquals("Hello world", req.prompt)
        assertEquals(1024, req.maxTokens)
        assertEquals(0.7f, req.temperature, 0.001f)
    }

    @Test
    fun `batchesUrl constructs correct beta batches endpoint`() {
        val client = OpenRouterBatchClient(apiKey = "sk-test", baseUrl = "https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api/beta/batches", client.batchesUrl())
    }

    @Test
    fun `buildBatchPayload preserves required field order endpoint then model then requests`() {
        val client = OpenRouterBatchClient(apiKey = "sk-test")
        val requests = listOf(
            NativeBatchRequest(
                customId = "req-0001",
                body = JSONObject().put("messages", org.json.JSONArray(listOf(JSONObject().put("role", "user").put("content", "Hello"))))
            ),
            NativeBatchRequest(
                customId = "req-0002",
                body = JSONObject().put("messages", org.json.JSONArray(listOf(JSONObject().put("role", "user").put("content", "World"))))
            )
        )

        val payload = client.buildBatchPayload("/v1/chat/completions", "openai/gpt-4o", requests)

        // Verify strict key order in serialized string: endpoint -> model -> requests
        val endpointIdx = payload.indexOf("\"endpoint\"")
        val modelIdx = payload.indexOf("\"model\"")
        val requestsIdx = payload.indexOf("\"requests\"")

        assertTrue("endpoint must be first", endpointIdx >= 0)
        assertTrue("model must follow endpoint", modelIdx > endpointIdx)
        assertTrue("requests must follow model", requestsIdx > modelIdx)

        assertTrue(payload.contains("req-0001"))
        assertTrue(payload.contains("req-0002"))
    }

    @Test
    fun `parseBatchMeta parses batch creation and metadata JSON`() {
        val client = OpenRouterBatchClient(apiKey = "sk-test")
        val json = """
            {
              "id": "batch_123",
              "object": "batch",
              "endpoint": "/v1/chat/completions",
              "model": "openai/gpt-4o",
              "status": "validating",
              "created_at": 1782097200,
              "request_counts": {
                "total": 2,
                "completed": 0,
                "failed": 0
              }
            }
        """.trimIndent()

        val meta = client.parseBatchMeta(json)
        assertEquals("batch_123", meta.id)
        assertEquals("validating", meta.status)
        assertEquals("/v1/chat/completions", meta.endpoint)
        assertEquals("openai/gpt-4o", meta.model)
        assertEquals(1782097200L, meta.createdAt)
        assertEquals(2, meta.totalRequests)
        assertEquals(0, meta.completedRequests)
    }

    @Test
    fun `parseBatchStatus parses terminal batch status with inlined results`() {
        val client = OpenRouterBatchClient(apiKey = "sk-test")
        val json = """
            {
              "id": "batch_456",
              "status": "completed",
              "endpoint": "/v1/chat/completions",
              "model": "openai/gpt-4o",
              "created_at": 1782097200,
              "request_counts": {
                "total": 2,
                "completed": 2,
                "failed": 0
              },
              "results": [
                {
                  "custom_id": "req-0001",
                  "response": {"id": "gen-1", "choices": [{"message": {"role": "assistant", "content": "Hi there"}}]}
                },
                {
                  "custom_id": "req-0002",
                  "response": {"id": "gen-2", "choices": [{"message": {"role": "assistant", "content": "Hello universe"}}]}
                }
              ]
            }
        """.trimIndent()

        val status = client.parseBatchStatus(json)
        assertEquals("batch_456", status.meta.id)
        assertEquals("completed", status.meta.status)
        assertEquals(2, status.meta.totalRequests)
        assertEquals(2, status.meta.completedRequests)

        assertNotNull(status.results)
        assertEquals(2, status.results!!.size)
        assertEquals("req-0001", status.results!![0].customId)
        assertTrue(status.results!![0].response!!.contains("Hi there"))
        assertNull(status.results!![0].error)
    }
}
