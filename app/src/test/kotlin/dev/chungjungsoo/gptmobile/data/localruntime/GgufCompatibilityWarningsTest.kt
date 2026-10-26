package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertTrue
import org.junit.Test

class GgufCompatibilityWarningsTest {
    @Test fun outOfRangeEogRejectsBeforeNativeLoadingAndSlidingWindowsRemainExplicit() {
        assertTrue(runCatching { ggufCompatibilityWarnings(mapOf("tokenizer.ggml.vocab_size" to "100", "tokenizer.ggml.eos_token_id" to "100")) }.isFailure)
        val warnings = ggufCompatibilityWarnings(mapOf("general.architecture" to "llama", "llama.attention.sliding_window" to "4096", "tokenizer.ggml.vocab_size" to "100", "tokenizer.ggml.eos_token_id" to "99"))
        assertTrue(warnings.single().contains("full context cache"))
        assertTrue(ggufCompatibilityWarnings(emptyMap()).single().contains("unverified"))
    }
}
