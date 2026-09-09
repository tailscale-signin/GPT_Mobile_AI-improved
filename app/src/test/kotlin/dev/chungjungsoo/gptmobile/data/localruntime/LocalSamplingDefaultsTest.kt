package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.catalog.CatalogDefaultConfig
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.catalog.SocVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSamplingDefaultsTest {
    @Test
    fun `prefers GPU when the catalog lists it`() {
        val defaults = localSamplingDefaults(
            CatalogEntry(
                id = "gemma3-1b-it",
                supportedAccelerators = listOf("cpu", "gpu"),
                defaultConfig = CatalogDefaultConfig(topK = 64, topP = 0.95f, temperature = 1.0f, maxTokens = 1024)
            )
        )

        assertEquals(64, defaults.topK)
        assertEquals(0.95f, defaults.topP)
        assertEquals(1.0f, defaults.temperature)
        assertEquals(1024, defaults.maxTokens)
        assertEquals(LocalAccelerators.GPU, defaults.accelerator)
    }

    @Test
    fun `falls back to the first eligible accelerator`() {
        val defaults = localSamplingDefaults(
            entry = CatalogEntry(
                supportedAccelerators = listOf("npu", "cpu"),
                socToModelFiles = mapOf("SM8650" to SocVariant(modelFile = "npu.litertlm"))
            ),
            deviceSocModel = "SM8650"
        )

        assertEquals(LocalAccelerators.NPU, defaults.accelerator)
    }

    @Test
    fun `does not default to NPU when the device has no SOC variant`() {
        val defaults = localSamplingDefaults(
            CatalogEntry(supportedAccelerators = listOf("npu", "cpu"))
        )

        assertEquals(LocalAccelerators.CPU, defaults.accelerator)
    }

    @Test
    fun `scales default maxTokens to 4096 on high RAM device with GPU accelerator`() {
        val defaults = localSamplingDefaults(
            entry = CatalogEntry(
                id = "qwen2.5-3b-it",
                supportedAccelerators = listOf("gpu", "cpu"),
                defaultConfig = CatalogDefaultConfig(topK = 40, topP = 0.9f, temperature = 0.7f, maxTokens = 2048)
            ),
            deviceRamGb = 16L
        )

        assertEquals(4096, defaults.maxTokens)
        assertEquals(LocalAccelerators.GPU, defaults.accelerator)
    }

    @Test
    fun `clamps default maxTokens to 1024 on low-memory device`() {
        val defaults = localSamplingDefaults(
            entry = CatalogEntry(
                id = "qwen2.5-3b-it",
                supportedAccelerators = listOf("gpu", "cpu"),
                defaultConfig = CatalogDefaultConfig(topK = 40, topP = 0.9f, temperature = 0.7f, maxTokens = 2048)
            ),
            deviceRamGb = 4L
        )

        assertEquals(1024, defaults.maxTokens)
    }

    @Test
    fun `clamps default maxTokens to SoC variant context when NPU accelerator is selected`() {
        val defaults = localSamplingDefaults(
            entry = CatalogEntry(
                id = "gemma3-1b-it",
                supportedAccelerators = listOf("npu", "cpu"),
                defaultConfig = CatalogDefaultConfig(topK = 64, topP = 0.95f, temperature = 1.0f, maxTokens = 2048),
                socToModelFiles = mapOf(
                    "SM8750" to SocVariant(modelFile = "npu-sm8750.litertlm", contextSize = 1280)
                )
            ),
            deviceSocModel = "SM8750",
            deviceRamGb = 16L
        )

        assertEquals(1280, defaults.maxTokens)
        assertEquals(LocalAccelerators.NPU, defaults.accelerator)
    }
}
