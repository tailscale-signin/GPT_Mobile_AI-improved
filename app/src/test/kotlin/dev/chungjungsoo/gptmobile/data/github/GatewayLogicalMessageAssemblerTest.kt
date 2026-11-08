package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayLogicalMessageAssemblerTest {
    private fun parts(text: String, size: Int = 128 * 1024): List<GatewayMessageRecord> {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val chunks = text.chunked(size)
        return chunks.mapIndexed { index, chunk -> GatewayMessageRecord("conversation", index + 1L, "part-$index", "job", "assistant", "final", chunk, 1000, "fingerprint", null, "logical", index, chunks.size, hash) }
    }

    @Test fun moreThanTwoMiBIsCompleteAfterAllBoundedPages() = runTest {
        val content = "Complete Unicode evidence αβ\n".repeat(100_000)
        val parts = parts(content)
        val requested = mutableListOf<Int>()
        val result = GatewayLogicalMessageAssembler.assemble(parts.first()) { offset ->
            requested += offset
            parts.drop(offset).take(16)
        }
        assertNull(result.content)
        assertEquals(content.toByteArray().size.toLong(), result.bytes)
        assertTrue(requested.size >= 2)
    }

    @Test fun boundedLogicalMessageKeepsItsCompleteUnicodeText() = runTest {
        val content = "Complete Unicode evidence αβ\n".repeat(8000)
        val values = parts(content)
        val result = GatewayLogicalMessageAssembler.assemble(values.first()) { offset -> values.drop(offset).take(16) }
        assertEquals(content, result.content)
        assertEquals(content.toByteArray().size.toLong(), result.bytes)
    }

    @Test fun incompleteOrCorruptPartsCannotProduceFinalText() = runTest {
        val values = parts("x".repeat(300_000))
        assertTrue(runCatching { GatewayLogicalMessageAssembler.assemble(values.first()) { offset -> values.dropLast(1).drop(offset).take(16) } }.isFailure)
        val corrupt = values.mapIndexed { index, part -> if (index == 1) part.copy(content = "changed") else part }
        assertTrue(runCatching { GatewayLogicalMessageAssembler.assemble(corrupt.first()) { offset -> corrupt.drop(offset).take(16) } }.isFailure)
    }

    @Test fun oversizedLogicalTextIsVerifiedWithoutBuildingOneHugeString() = runTest {
        val text = "z".repeat(GatewayLogicalMessageAssembler.LOCAL_PROJECTION_BYTES + 1)
        val values = parts(text)
        val result = GatewayLogicalMessageAssembler.assemble(values.first()) { offset -> values.drop(offset).take(16) }
        assertNull(result.content)
        assertEquals(text.length.toLong(), result.bytes)
    }

    @Test fun mixedJobAndDuplicatePartIndexCannotCompleteAnotherMessage() = runTest {
        val values = parts("x".repeat(300_000))
        for (changed in listOf(values[1].copy(jobId = "another-job"), values[1].copy(partIndex = 0))) {
            val corrupted = values.toMutableList().also { it[1] = changed }
            assertTrue(runCatching { GatewayLogicalMessageAssembler.assemble(corrupted.first()) { offset -> corrupted.drop(offset).take(16) } }.isFailure)
        }
    }
}
