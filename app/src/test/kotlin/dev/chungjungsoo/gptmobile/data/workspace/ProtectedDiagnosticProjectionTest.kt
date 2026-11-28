package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks
import dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedDiagnosticProjectionTest {
    @Test(timeout = 10000)
    fun oversizedCheckpointKeepsBoundedAuthenticatedSummaryWithoutMaterializingItsHistory() = runBlocking {
        val source = fixture()
        val issued = mutableListOf<ByteArray>()
        val omissions = mutableListOf<String>()
        var authorizations = 0
        val result = capture(source, issued = issued, omitted = omissions::add, authorized = {
            authorizations++
            true
        })
        assertNull(result.omissionReason)
        val payload = requireNotNull(result.payload).jsonObject
        assertEquals(JsonPrimitive("BLOCKED"), payload["task_state"])
        assertEquals(JsonPrimitive("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE"), payload["stop_code"])
        assertEquals(JsonPrimitive(0), payload["verified_criteria"])
        assertEquals(JsonPrimitive("[redacted]"), payload.getValue("acceptance_explanation").jsonObject["token"])
        val projection = payload.getValue("diagnostic_partial_payload").jsonObject
        assertEquals(JsonPrimitive(false), projection["complete"])
        assertEquals(JsonPrimitive(source.encodeToByteArray().size), projection["source_bytes"])
        assertEquals(JsonPrimitive(hash(source)), projection["source_sha256"])
        assertEquals(listOf("PROTECTED_PAYLOAD_BYTE_LIMIT"), omissions)
        assertTrue(payload.toString().length < 4096)
        assertFalse(payload.toString().contains("private-history-canary"))
        assertTrue(issued.isNotEmpty())
        assertTrue(issued.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(3, authorizations)
    }

    @Test(timeout = 10000)
    fun changedDigestCannotAuthenticateAnOtherwiseValidCompactSummary() = runBlocking {
        val source = fixture()
        val result = capture(source.replace("BLOCKED", "UNKNOWN"), expectedDigest = hash(source))
        assertEquals("PROTECTED_DIGEST_MISMATCH", result.omissionReason)
        assertNull(result.payload)
    }

    @Test(timeout = 10000)
    fun revokedOwnershipAfterReadingDiscardsTheSummary() = runBlocking {
        var checks = 0
        val result = capture(fixture(), authorized = { ++checks == 1 })
        assertEquals("PROTECTED_SOURCE_CHANGED", result.omissionReason)
        assertNull(result.payload)
    }

    @Test(timeout = 10000)
    fun malformedSkippedHistoryCannotProduceTrustedDiagnosticEvidence() = runBlocking {
        val source = fixture().dropLast(1) + ",\"invalid_history\":[1,]}"
        val result = capture(source)
        assertEquals("PROTECTED_PAYLOAD_INVALID_JSON", result.omissionReason)
        assertNull(result.payload)
    }

    @Test(timeout = 10000)
    fun capacityDeferralInsideStreamingReadRemainsResumableInsteadOfBecomingMissingSource() {
        val issued = mutableListOf<ByteArray>()
        val expected = JavaHeapWorkDeferred()
        val observed = assertThrows(JavaHeapWorkDeferred::class.java) {
            runBlocking {
                capture(fixture(), issued = issued, beforeRead = { if (issued.isNotEmpty()) throw expected })
            }
        }
        assertTrue(observed === expected)
        assertTrue(issued.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test(timeout = 10000)
    fun oversizedSingleTokenFailsClosedWithinTheStreamingProjectionBound() = runBlocking {
        val source = fixture().dropLast(1) + ",\"failure_code\":\"" + "x".repeat(70 * 1024) + "\"}"
        val result = capture(source)
        assertEquals("PROTECTED_PAYLOAD_INVALID_JSON", result.omissionReason)
        assertNull(result.payload)
    }

    private fun fixture() = buildJsonObject {
        put("schema", "github.verification.v1")
        put("task_state", "BLOCKED")
        put("stop_code", "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE")
        put("required_criteria", 1)
        put("verified_criteria", 0)
        put(
            "acceptance_explanation",
            buildJsonObject {
                put("state", "BLOCKED")
                put("token", "sensitive-value")
            }
        )
        put("history", JsonArray(List(5000) { JsonObject(mapOf("raw" to JsonPrimitive("private-history-canary-" + "x".repeat(60)))) }))
    }.toString()

    private suspend fun capture(
        source: String,
        expectedDigest: String = hash(source),
        issued: MutableList<ByteArray> = mutableListOf(),
        omitted: (String) -> Unit = {},
        authorized: suspend () -> Boolean = { true },
        beforeRead: suspend () -> Unit = {}
    ): WorkspaceDiagnosticPayload {
        val bytes = source.encodeToByteArray()
        val chunks = bytes.indices.step(60_000).mapIndexed { index, offset ->
            "workspace-memory-7-$index" to bytes.copyOfRange(offset, minOf(bytes.size, offset + 60_000))
        }.toMap()
        return try {
            readWorkspaceDiagnosticPayload(
                7,
                buildJsonObject {
                    put("protectedChunks", JsonArray(chunks.keys.map(::JsonPrimitive)))
                    put("digest", expectedDigest)
                },
                256 * 1024,
                beforeRead,
                authorized,
                { chunks.getValue(it).size + 38L },
                { chunks.getValue(it).copyOf().also(issued::add) },
                omitted,
                compactOnOversize = true
            )
        } finally {
            bytes.fill(0)
            chunks.values.forEach { it.fill(0) }
        }
    }

    private fun hash(value: String) = DiagnosticRecordChunks.hex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
}
