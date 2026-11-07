package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun largeProtectedEvidenceRestoresAllRowsWithinTheMeasuredLimit() = runBlocking {
        val text = JsonObject(
            mapOf(
                "receipts" to JsonArray(
                    List(4000) { index ->
                        JsonObject(mapOf("id" to JsonPrimitive(index), "result" to JsonPrimitive("evidence"), "token" to JsonPrimitive("private-token")))
                    }
                )
            )
        ).toString()
        val bytes = text.toByteArray()
        val chunks = bytes.indices.step(60 * 1024).mapIndexed { index, offset ->
            "workspace-memory-7-$index" to bytes.copyOfRange(offset, minOf(bytes.size, offset + 60 * 1024))
        }.toMap()
        val issued = mutableListOf<ByteArray>()
        val omissions = mutableListOf<String>()
        val result = readWorkspaceDiagnosticPayload(
            7,
            metadata(chunks.keys.toList(), text),
            1_048_576,
            {},
            { true },
            { chunks[it]!!.size + 38L },
            { chunks[it]?.copyOf()?.also(issued::add) },
            omissions::add
        )
        assertNull(result.omissionReason)
        assertTrue(omissions.isEmpty())
        val payload = requireNotNull(result.payload)
        val receipts = payload.jsonObject.getValue("receipts").jsonArray
        assertEquals(4000, receipts.size)
        assertEquals("3999", receipts.last().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(receipts.all { it.jsonObject.getValue("token").jsonPrimitive.content == "[redacted]" })
        assertFalse(payload.toString().contains("private-token"))
        val exported = DiagnosticRecordChunks.encode(payload.toString(), 12 * 1024).toList()
        assertTrue(exported.size > 1)
        val reconstructed = exported.joinToString("") { it.getValue("content").jsonPrimitive.content }
        assertEquals(payload, Json.parseToJsonElement(reconstructed))
        assertTrue(exported.all { it.getValue("recordSha256").jsonPrimitive.content == DiagnosticRecordChunks.sha256(reconstructed) })
        assertTrue(issued.all { chunk -> chunk.all { it == 0.toByte() } })
    }

    private fun metadata(references: List<String>, text: String) = JsonObject(
        mapOf(
            "protectedChunks" to JsonArray(references.map(::JsonPrimitive)),
            "digest" to JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) })
        )
    )
}
