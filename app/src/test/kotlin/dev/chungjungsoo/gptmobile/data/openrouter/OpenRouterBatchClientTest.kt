package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchSettings
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import dev.chungjungsoo.gptmobile.domain.service.BatchManager
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
