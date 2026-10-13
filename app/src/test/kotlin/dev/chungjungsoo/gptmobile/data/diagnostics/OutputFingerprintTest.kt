package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFingerprintTest {
    @Test fun `arbitrary provider chunk boundaries preserve the exact reconstructed UTF8 fingerprint`() {
        val text = "Before 😀 e\u0301 العربية\n```kotlin\nval n = 42\n```\nAfter"
        for (split in 0..text.length) {
            val accumulator = OutputFingerprintAccumulator()
            accumulator.append(text.substring(0, split))
            assertEquals(outputFingerprint(text.substring(0, split)), accumulator.snapshot())
            accumulator.append(text.substring(split))
            assertEquals(outputFingerprint(text), accumulator.snapshot())
        }
    }

    @Test fun `fingerprints remain stable across repeated snapshots empty deltas and malformed surrogates`() {
        val accumulator = OutputFingerprintAccumulator()
        val chunks = listOf("", "A\uD83D", "", "x", "\uDE00", "\uD83D", "\uDE00", "\uD83D")
        var combined = ""
        chunks.forEach { chunk ->
            combined += chunk
            accumulator.append(chunk)
            repeat(3) { assertEquals(outputFingerprint(combined), accumulator.snapshot()) }
        }
    }
}
