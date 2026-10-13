package dev.chungjungsoo.gptmobile.data.localruntime

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

    @Test fun packagingIsSeparateFromDeviceQualification() {
        assertEquals(RuntimeAvailability.PACKAGED, RuntimeCapabilityRegistry.gguf(listOf("arm64-v8a"), true).state)
        assertEquals(setOf("cpu"), RuntimeCapabilityRegistry.gguf(listOf("x86_64"), true).backends)
        assertEquals(RuntimeAvailability.UNAVAILABLE_FOR_ABI, RuntimeCapabilityRegistry.gguf(listOf("armeabi-v7a"), true).state)
        assertEquals(RuntimeAvailability.DISABLED, RuntimeCapabilityRegistry.gguf(listOf("arm64-v8a"), false).state)
        assertFalse(compatibleSpeculativeDecoding(true, LocalModelCapabilities(4096, true, setOf("cpu"), false, false, false, false))!!)
    }
}
