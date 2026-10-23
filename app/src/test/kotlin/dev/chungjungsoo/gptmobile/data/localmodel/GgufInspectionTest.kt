package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GgufInspectionTest {
    private fun fixture(truncated: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun u32(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun u64(value: Long) = out.writeLong(java.lang.Long.reverseBytes(value))
            fun text(value: String) {
                val data = value.toByteArray()
                u64(data.size.toLong())
                out.write(data)
            }
            out.writeBytes("GGUF")
            u32(3)
            u64(1)
            u64(4)
            text("tokenizer.ggml.tokens")
            u32(9)
            u32(8)
            u64(20000)
            repeat(20000) { text("unused vocabulary $it") }
            text("tokenizer.ggml.scores")
            u32(9)
            u32(6)
            u64(if (truncated) 1000000 else 20000)
            repeat(20000) { u32(it) }
            text("general.architecture")
            u32(8)
            text("gemma")
            text("gemma.context_length")
            u32(4)
            u32(8192)
        }
        return bytes.toByteArray()
    }

    @Test fun discardedVocabularyAndFixedWidthArraysPreserveCompactMetadata() {
        val file = File.createTempFile("gguf-inspection", ".gguf")
        try {
            file.writeBytes(fixture())
            val first = GgufImportAdapter.inspect(file)
            assertEquals(mapOf("general.architecture" to "gemma", "gemma.context_length" to "8192"), first)
            assertEquals(first, GgufImportAdapter.inspect(file))
            val time = file.lastModified()
            file.writeBytes(fixture(true))
            file.setLastModified(time)
            assertThrows(IllegalArgumentException::class.java) { GgufImportAdapter.inspect(file) }
        } finally {
            file.delete()
        }
    }

    @Test fun cancellationInterruptsMetadataWalk() {
        val file = File.createTempFile("gguf-cancel", ".gguf")
        try {
            file.writeBytes(fixture())
            var calls = 0
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                GgufImportAdapter.inspect(file) { if (++calls > 10) throw kotlinx.coroutines.CancellationException() }
            }
        } finally {
            file.delete()
        }
    }
}
