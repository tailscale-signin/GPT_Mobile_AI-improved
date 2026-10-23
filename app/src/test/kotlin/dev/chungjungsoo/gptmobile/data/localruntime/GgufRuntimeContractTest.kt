package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufRuntimeContractTest {
    @Test fun splitUnicodeNeverProducesReplacementCharacters() {
        val decoder = Utf8GenerationDecoder()
        val bytes = "Evidence 🧪 café 中文".toByteArray()
        val output = bytes.joinToString("") { decoder.append(byteArrayOf(it)) }
        assertEquals("Evidence 🧪 café 中文", output)
        assertTrue(decoder.isComplete())
    }

    @Test fun interruptedAndMalformedUnicodeAreExplicit() {
        val decoder = Utf8GenerationDecoder()
        assertEquals("", decoder.append(byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
        assertFalse(decoder.isComplete())
        assertThrows(CharacterCodingException::class.java) { decoder.append(byteArrayOf(0x20)) }
    }

    @Test fun standardBuildCannotClaimGenieXIsInstalledViaGenericGguf() {
        if (!dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED) {
            val error = assertThrows(IllegalStateException::class.java) {
                RuntimeCapabilityRegistry.requireRuntime(ArtifactRuntime.GENIEX_LLAMA_CPP)
            }
            assertTrue(error.message.orEmpty().contains("GenieX preview"))
        }
    }

    @Test fun packagingIsSeparateFromDeviceQualification() {
        assertEquals(RuntimeAvailability.PACKAGED, RuntimeCapabilityRegistry.gguf(listOf("arm64-v8a"), true).state)
        assertEquals(setOf("cpu"), RuntimeCapabilityRegistry.gguf(listOf("x86_64"), true, false, false).backends)
        assertEquals(setOf("cpu", "gpu"), RuntimeCapabilityRegistry.gguf(listOf("arm64-v8a"), true, true, false).backends)
        assertEquals(setOf("cpu", "opencl"), RuntimeCapabilityRegistry.gguf(listOf("arm64-v8a"), true, false, true).backends)
        assertEquals(RuntimeAvailability.UNAVAILABLE_FOR_ABI, RuntimeCapabilityRegistry.gguf(listOf("armeabi-v7a"), true).state)
        assertEquals(RuntimeAvailability.DISABLED, RuntimeCapabilityRegistry.gguf(listOf("arm64-v8a"), false).state)
        assertFalse(compatibleSpeculativeDecoding(true, LocalModelCapabilities(4096, true, setOf("cpu"), false, false, false, false))!!)
    }
}
