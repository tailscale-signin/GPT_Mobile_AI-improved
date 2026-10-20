package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContextPreferenceTest {
    private val mib = 1024L * 1024

    @Test fun autoReplansBeforeNativeLoadAndManualRemainsExact() {
        assertEquals(4096, memoryFeasibleContext(8192, 3000 * mib, 5200 * mib, 768 * mib))
        assertTrue(runCatching { memoryFeasibleContext(8192, 3000 * mib, 5200 * mib, 768 * mib, manual = 8192) }.exceptionOrNull() is LocalMemoryAdmissionException)
        assertEquals(2048, memoryFeasibleContext(8192, 3000 * mib, 5200 * mib, 768 * mib, manual = 2048))
    }

    @Test fun insufficientWeightsMemoryCannotBeFixedByDroppingRequiredPrompt() {
        assertTrue(runCatching { memoryFeasibleContext(8192, 5000 * mib, 5200 * mib, 768 * mib) }.isFailure)
        assertTrue(runCatching { LocalContextPreference("MANUAL", 0).validated() }.isFailure)
    }

    @Test fun metadataGeometryIsArchitectureAwareAndBounded() {
        val metadata = mapOf("general.architecture" to "llama", "llama.block_count" to "32", "llama.attention.head_count" to "32", "llama.attention.head_count_kv" to "8", "llama.embedding_length" to "4096")
        assertEquals(131072L, uniformGgufKvBytesPerToken(metadata))
        assertEquals(null, uniformGgufKvBytesPerToken(metadata + ("general.architecture" to "mamba")))
        assertEquals(null, uniformGgufKvBytesPerToken(metadata + ("llama.block_count" to "999999999")))
    }
}
