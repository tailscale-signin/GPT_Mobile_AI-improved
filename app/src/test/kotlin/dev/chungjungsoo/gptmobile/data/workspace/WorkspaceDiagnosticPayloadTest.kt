package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDiagnosticPayloadTest {
    @Test fun boundedChunksRestoreEvidenceAndRedactNestedArchiveSecrets() = runBlocking {
        val archive = ArchivedTextCodec.encode("Bearer abcdef0123456789 " + "evidence ".repeat(100))
        val text = JsonObject(mapOf("result" to JsonPrimitive(archive), "token" to JsonPrimitive("must-be-redacted"))).toString()
        val bytes = text.toByteArray()
        val chunks = mapOf("workspace-memory-7-one" to bytes.copyOfRange(0, bytes.size / 2), "workspace-memory-7-two" to bytes.copyOfRange(bytes.size / 2, bytes.size))
        val issued = mutableListOf<ByteArray>()
        val result = readWorkspaceDiagnosticPayload(
            7,
            metadata(chunks.keys.toList(), text),
            4096,
            {},
            { true },
            { chunks[it]!!.size + 38L },
            { chunks[it]?.copyOf()?.also(issued::add) },
            {}
        )
        assertNull(result.omissionReason)
        assertTrue(result.protected)
        val safe = requireNotNull(result.payload).toString()
        assertTrue(safe.contains("evidence"))
        assertFalse(safe.contains("must-be-redacted"))
        assertFalse(safe.contains("abcdef0123456789"))
        assertFalse(safe.contains(ArchivedTextCodec.PREFIX))
        assertTrue(issued.all { chunk -> chunk.all { it == 0.toByte() } })
    }

    @Test fun oversizedProtectedPayloadIsRejectedBeforeReadingAnyChunk() = runBlocking {
        var reads = 0
        val result = readWorkspaceDiagnosticPayload(
            7,
            metadata(listOf("workspace-memory-7-one"), "{}"),
            1024,
            {},
            { true },
            { 60_038L },
            {
                reads++
                ByteArray(60_000)
            },
            {}
        )
        assertEquals("PROTECTED_PAYLOAD_BYTE_LIMIT", result.omissionReason)
        assertEquals(0, reads)
    }

    @Test fun crossChatReferencesAreRejectedWithoutVaultReads() = runBlocking {
        var reads = 0
        val result = readWorkspaceDiagnosticPayload(
            7,
            metadata(listOf("workspace-memory-8-other"), "{}"),
            1024,
            {},
            { true },
            { 40L },
            {
                reads++
                "{}".toByteArray()
            },
            {}
        )
        assertEquals("PROTECTED_REFERENCE_SCOPE_MISMATCH", result.omissionReason)
        assertEquals(0, reads)
    }

    @Test fun deletingOrChangingOwnershipDuringDecryptionDiscardsTheResult() = runBlocking {
        var authorizations = 0
        val issued = "{}".toByteArray()
        val result = readWorkspaceDiagnosticPayload(
            7,
            metadata(listOf("workspace-memory-7-one"), "{}"),
            1024,
            {},
            { ++authorizations == 1 },
            { 40L },
            { issued },
            {}
        )
        assertEquals("PROTECTED_SOURCE_CHANGED", result.omissionReason)
        assertNull(result.payload)
        assertTrue(issued.all { it == 0.toByte() })
    }

    @Test fun digestMismatchAndUnmeasurableVaultEntriesRemainExplicitlyUnavailable() = runBlocking {
        val mismatch = readWorkspaceDiagnosticPayload(
            7,
            metadata(listOf("workspace-memory-7-one"), "{\"expected\":true}"),
            1024,
            {},
            { true },
            { 40L },
            { "{}".toByteArray() },
            {}
        )
        assertEquals("PROTECTED_DIGEST_MISMATCH", mismatch.omissionReason)
        var reads = 0
        val unknown = readWorkspaceDiagnosticPayload(
            7,
            metadata(listOf("workspace-memory-7-one"), "{}"),
            1024,
            {},
            { true },
            { null },
            {
                reads++
                "{}".toByteArray()
            },
            {}
        )
        assertEquals("PROTECTED_SIZE_UNAVAILABLE", unknown.omissionReason)
        assertEquals(0, reads)
    }

    @Test fun captureBudgetFailuresPropagateInsteadOfBecomingSourceFailures() {
        val expected = IOException("stage budget exhausted")
        val observed = assertThrows(IOException::class.java) {
            runBlocking {
                readWorkspaceDiagnosticPayload(
                    7,
                    metadata(listOf("workspace-memory-7-one"), "{}"),
                    1024,
                    { throw expected },
                    { true },
                    { 40L },
                    { "{}".toByteArray() },
                    {}
                )
            }
        }
        assertTrue(observed === expected)
    }

    private fun metadata(references: List<String>, text: String) = JsonObject(
        mapOf(
            "protectedChunks" to JsonArray(references.map(::JsonPrimitive)),
            "digest" to JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })
        )
    )
}
