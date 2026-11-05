package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaGenerationActivityTest {
    private val config = ProviderRequestConfig("http://127.0.0.1:8080/v1", "secret", providerType = "LLAMA")

    @Test fun activeGenerationIsScopedAndReleasedOnceOnEveryTermination() {
        val first = requireNotNull(LlamaGenerationActivity.started(config, "selected", "request-1"))
        val second = requireNotNull(LlamaGenerationActivity.started(config, "selected", "request-2"))
        try {
            assertEquals(2, LlamaGenerationActivity.snapshot(config, "selected").count)
            assertEquals(0, LlamaGenerationActivity.snapshot(config, "another").count)
            assertEquals(0, LlamaGenerationActivity.snapshot(config.copy(token = "changed"), "selected").count)
            first.close()
            first.close()
            val active = LlamaGenerationActivity.snapshot(config, "selected")
            assertEquals(1, active.count)
            assertEquals("request-2", active.requestId)
            assertEquals(LlamaConnectionPhase.GENERATING, LlamaHealthReport(activeGenerations = active.count).phase)
            LocalServiceHealth.networkChanged()
            assertEquals(0, LlamaGenerationActivity.snapshot(config, "selected").count)
        } finally {
            first.close()
            second.close()
        }
        assertEquals(0, LlamaGenerationActivity.snapshot(config, "selected").count)
    }

    @Test fun unrelatedProvidersDoNotClaimLocalGeneration() {
        assertNull(LlamaGenerationActivity.started(config.copy(providerType = "OPENAI"), "selected", null))
    }
}
