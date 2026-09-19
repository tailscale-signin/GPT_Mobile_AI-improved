package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.model.LlamaBatchSettings
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchSettings
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import dev.chungjungsoo.gptmobile.domain.service.BatchManager
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaBatchClientTest {

    @Test
    fun `parseLlamaResponse parses valid JSONL responses correctly`() {
        val client = LlamaBatchClient(serverUrl = "http://localhost:8080")
        val jsonlResponse = """
            {"token_ids": [72, 101, 108, 108, 111], "logprobs": null}
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
