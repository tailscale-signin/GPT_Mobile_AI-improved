package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRecordChunksTest {
    @Test fun digestEncodingRetainsStandardSha256AndUnsignedByteValues() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DiagnosticRecordChunks.sha256(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DiagnosticRecordChunks.sha256("abc"))
        val bytes = ByteArray(256) { it.toByte() }
        val hex = DiagnosticRecordChunks.hex(bytes)
        assertEquals(512, hex.length)
        assertEquals(bytes.toList(), hex.chunked(2).map { it.toInt(16).toByte() })
    }

    @Test fun oversizedRedactedMessageReassemblesWithIndependentHashesAndUnicodeBoundaries() {
        val secret = "sk-" + "secret".repeat(300)
        val source = buildJsonObject {
            put("content", "𝕏漢字\n".repeat(32_000) + secret)
            put("run_id", "run-1")
        }
        val redacted = redactChatDebugData(source, listOf(secret)).toString()
        val chunks = DiagnosticRecordChunks.encode(redacted, 2048).toList()
        assertTrue(chunks.size > 1)
        assertTrue(redacted.toByteArray().size > 147_734)
        val reconstructed = chunks.joinToString("") { it.getValue("content").jsonPrimitive.content }
        assertEquals(redacted, reconstructed)
        assertEquals(redactChatDebugData(source, listOf(secret)), Json.parseToJsonElement(reconstructed))
        assertFalse(reconstructed.contains(secret))
        chunks.forEachIndexed { index, chunk ->
            assertEquals(JsonPrimitive(index), chunk["chunkIndex"])
            assertEquals(JsonPrimitive(chunks.size), chunk["chunkCount"])
            val value = chunk.getValue("content").jsonPrimitive.content
            assertTrue(value.length <= 2048)
            assertFalse(value.last().isHighSurrogate())
            assertEquals(DiagnosticRecordChunks.sha256(value), chunk.getValue("chunkSha256").jsonPrimitive.content)
            assertEquals(DiagnosticRecordChunks.sha256(reconstructed), chunk.getValue("recordSha256").jsonPrimitive.content)
        }
    }

    @Test fun changedChunkFailsHashVerification() {
        val chunk = DiagnosticRecordChunks.encode("secret-free text".repeat(100), 256).first()
        val changed = chunk.getValue("content").jsonPrimitive.content + "tampered"
        assertFalse(DiagnosticRecordChunks.sha256(changed) == chunk.getValue("chunkSha256").jsonPrimitive.content)
    }
}
