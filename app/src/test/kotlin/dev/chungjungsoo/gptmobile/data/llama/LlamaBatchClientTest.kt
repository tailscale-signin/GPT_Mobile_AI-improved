package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class LlamaBatchClientTest {
    @Test
    fun `invalid local endpoint preserves private recovery errors in callbacks and JSONL`() = kotlinx.coroutines.runBlocking {
        val client = LlamaBatchClient(serverUrl = "invalid-url?private-configuration=fixture")
        var received = emptyList<BatchResult>()
        client.processBatch(listOf(dev.chungjungsoo.gptmobile.domain.model.BatchRequest("fixture"))) { received = it }
        val expected = "Local batch failed. Retry with the selected local connection."
        assertEquals(expected, (received.single() as BatchResult.Failure).error)
        val jsonl = client.batchProcess("""{"prompt":"fixture"}""")
        assertEquals(expected, org.json.JSONObject(jsonl).getString("error"))
        assertTrue(!jsonl.contains("private-configuration"))
    }

    @Test
    fun `parseLlamaResponse parses valid JSONL responses correctly`() {
        val client = LlamaBatchClient(serverUrl = "http://localhost:8080")
        val jsonlResponse = """
            {"content": "Hello", "tokens_predicted": 1}
            {"content": "World"}
        """.trimIndent()

        val results = client.parseLlamaResponse(jsonlResponse, totalRequests = 2)

        assertEquals(2, results.size)
        assertTrue(results[0] is BatchResult.Success)
        assertEquals("Hello", (results[0] as BatchResult.Success).response)
        assertTrue(results[1] is BatchResult.Success)
        assertEquals("World", (results[1] as BatchResult.Success).response)
    }

    @Test
    fun `parseLlamaResponse fills missing responses with Failure`() {
        val client = LlamaBatchClient(serverUrl = "http://localhost:8080")
        val jsonlResponse = """
            {"content": "First response"}
        """.trimIndent()

        val results = client.parseLlamaResponse(jsonlResponse, totalRequests = 3)

        assertEquals(3, results.size)
        assertTrue(results[0] is BatchResult.Success)
        assertEquals("First response", (results[0] as BatchResult.Success).response)
        assertTrue(results[1] is BatchResult.Failure)
        assertTrue(results[2] is BatchResult.Failure)
    }

    @Test
    fun `model token IDs are not Unicode code points and excess rows cannot change result identity`() {
        val client = LlamaBatchClient()
        val results = client.parseLlamaResponse(
            """{"token_ids":[72,101,108,108,111]}
            |{"content":"unexpected extra row"}
            """.trimMargin(),
            1
        )
        assertEquals(1, results.size)
        assertTrue(results.single() is BatchResult.Failure)
    }

    @Test
    fun `completion payload escapes control characters and retains generation settings`() {
        val prompt = "quoted \"text\"\n\t\u0001"
        val payload = org.json.JSONObject(LlamaBatchClient().completionPayload(dev.chungjungsoo.gptmobile.domain.model.BatchRequest(prompt, 256, 0.5f)))
        assertEquals(prompt, payload.getString("prompt"))
        assertEquals(256, payload.getInt("n_predict"))
        assertEquals(false, payload.getBoolean("stream"))
    }

    @Test
    fun `BatchConfig initializes with proper defaults`() {
        val config = BatchConfig(
            platform = PlatformType.LLAMA_CPP,
            apiKey = "test-key",
            batchKey = "test-batch-key"
        )

        assertEquals(PlatformType.LLAMA_CPP, config.platform)
        assertEquals("test-key", config.apiKey)
        assertEquals("test-batch-key", config.batchKey)
        assertEquals("http://localhost:8080", config.llama.serverUrl)
        assertEquals(50, config.llama.batchSize)
        assertEquals(10, config.openRouter.maxConcurrentRequests)
    }
}
