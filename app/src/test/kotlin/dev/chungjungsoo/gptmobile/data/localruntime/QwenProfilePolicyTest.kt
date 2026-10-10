package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenProfilePolicyTest {
    @Test fun quickAndThinkingHaveIndependentDefaults() {
        assertEquals(LocalSamplerConfig(20, .8f, .7f), QwenProfilePolicy.sampler(false))
        assertEquals(LocalSamplerConfig(20, .95f, .6f), QwenProfilePolicy.sampler(true))
        assertTrue(QwenProfilePolicy.isQwen3("local-model", "/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"))
        assertFalse(QwenProfilePolicy.isQwen3("qwen2.5", "model.litertlm"))
    }

    @Test fun packageCeilingAndBackendAreEnforcedIndependently() {
        val metadata = LocalModelCapabilities(contextTokens = 2048, backends = setOf("cpu", "gpu"), vision = false)
        metadata.validate(LocalEngineSpec("mixed.litertlm", "gpu", 2048))
        assertTrue(runCatching { metadata.validate(LocalEngineSpec("mixed.litertlm", "gpu", 4096)) }.isFailure)
        assertTrue(runCatching { metadata.validate(LocalEngineSpec("mixed.litertlm", "npu", 2048)) }.isFailure)
        assertTrue(runCatching { metadata.validate(LocalEngineSpec("mixed.litertlm", "gpu", 2048, isVisionEnabled = true)) }.isFailure)
    }

    @Test fun runtimeVersionComparisonUsesNumericComponents() {
        assertTrue(runtimeVersionIsNewer("0.18.0", "0.17.1"))
        assertFalse(runtimeVersionIsNewer("0.9.0", "0.18.0"))
        assertFalse(runtimeVersionIsNewer("0.18", "0.18.0"))
    }
}
