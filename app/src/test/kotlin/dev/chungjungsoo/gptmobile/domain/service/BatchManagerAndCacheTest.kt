package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchRequestItem
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterMessageItem
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchManagerAndCacheTest {

    @Test
    fun `batching accumulates requests up to batch size`() = runTest {
        val settings = OpenRouterSettings(
            apiKey = "sk-or-test-key-123456789012345678901234567890",
            batchSize = 3,
            flushTimeoutMs = 10000L
        )

        var flushedBatches = 0
        var totalFlushedRequests = 0

        val queueManager = OpenRouterBatchQueueManager(
            settings = settings,
            scope = this
        ) { batch ->
            flushedBatches++
            totalFlushedRequests += batch.size
        }

        queueManager.enqueue(OpenRouterBatchRequestItem("1", "meta-llama/llama-3", emptyList()))
        queueManager.enqueue(OpenRouterBatchRequestItem("2", "meta-llama/llama-3", emptyList()))
        assertEquals(0, flushedBatches)
        assertEquals(2, queueManager.pendingCount())

        queueManager.enqueue(OpenRouterBatchRequestItem("3", "meta-llama/llama-3", emptyList()))
        assertEquals(1, flushedBatches)
        assertEquals(3, totalFlushedRequests)
        assertEquals(0, queueManager.pendingCount())
    }

    @Test
    fun `response cache stores and retrieves unexpired responses`() {
        val cache = ResponseCache(cacheTtlSeconds = 300)
        val promptHash = CacheKeyGenerator.generate("test-model", "Test prompt")

        assertNull(cache.get(promptHash))
        cache.put(promptHash, "Cached response content")

        val result = cache.get(promptHash)
        assertNotNull(result)
        assertEquals("Cached response content", result)
    }

    @Test
    fun `cache key generator produces distinct hash for different messages`() {
        val messages1 = listOf(OpenRouterMessageItem("user", "Hello"))
        val messages2 = listOf(OpenRouterMessageItem("user", "World"))

        val key1 = CacheKeyGenerator.generate("model-a", messages1)
        val key2 = CacheKeyGenerator.generate("model-a", messages2)

        assert(key1 != key2)
    }
}
