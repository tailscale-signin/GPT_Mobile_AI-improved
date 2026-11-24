package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.workspace.readWorkspaceDiagnosticPayload
import java.io.StringReader
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCaptureAllocationTest {
    @Test fun sharedPayloadReuseKeepsFreshRowIdentityAndRechecksAuthority() {
        val redactor = DiagnosticContentRedactor()
        val reuse = DiagnosticRedactionReuse(redactor::redact)
        val payload = buildJsonObject {
            put("status", "COMPLETED")
            put("text", "private source canary")
            put("durationMs", 123)
        }
        val first = reuse.workspaceRow(row(1, payload)) {}
        var checks = 0
        val second = reuse.workspaceRow(row(2, payload)) { checks++ }
        assertFalse(first.reused)
        assertTrue(second.reused)
        assertTrue(checks >= 2)
        assertEquals(JsonPrimitive(2), second.value.jsonObject["rowId"])
        assertEquals(JsonPrimitive("record-2"), second.value.jsonObject.getValue("fields").jsonObject["id"])
        assertEquals(first.value.jsonObject.getValue("fields").jsonObject["payload"], second.value.jsonObject.getValue("fields").jsonObject["payload"])
        assertFalse(second.value.toString().contains("private source canary"))
        assertFalse(reuse.workspaceRow(row(3, JsonObject(payload + ("durationMs" to JsonPrimitive(124))))) {}.reused)
        assertTrue(runCatching { reuse.workspaceRow(row(4, payload)) { error("authority revoked") } }.isFailure)
        reuse.clear()
        assertFalse(reuse.workspaceRow(row(5, payload)) {}.reused)
    }

    @Test fun reusedPayloadRetainsTheFieldRedactionContext() {
        val redactor = DiagnosticContentRedactor()
        for (payload in listOf(JsonPrimitive("private payload canary"), JsonArray(listOf(JsonPrimitive("private payload canary"))))) {
            val reuse = DiagnosticRedactionReuse(redactor::redact)
            for (id in 1..2) {
                val observed = reuse.workspaceRow(row(id, payload)) {}
                assertEquals(redactor.redact(row(id, payload)), observed.value)
                assertFalse(observed.value.toString().contains("private payload canary"))
            }
        }
    }

    @Test fun safeImmutableBranchesAreReusedWithoutRetainingPrivateSiblings() {
        val safe = buildJsonObject {
            put("status", "COMPLETED")
            put("durationMs", 123)
            put("counts", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
        }
        val redactor = DiagnosticContentRedactor()
        assertSame(safe, redactor.redact(safe))
        val original = JsonObject(mapOf("receipt" to safe, "text" to JsonPrimitive("private canary"), "token" to JsonPrimitive("secret canary")))
        val result = redactor.redact(original).jsonObject
        assertSame(safe, result["receipt"])
        assertFalse(result.toString().contains("private canary"))
        assertFalse(result.toString().contains("secret canary"))
    }

    @Test fun incrementalShapeScannerPreservesQuotedBoundaryStateAndBudgets() {
        val values = listOf(
            "{\"text\":\"${"x".repeat(4093)}\\\"[{},]\\\\\",\"items\":[1,2,3]}",
            "[".repeat(25) + "0" + "]".repeat(25),
            "[" + List(300) { "0" }.joinToString(",") + "]"
        )
        for (value in values) {
            assertEquals(diagnosticJsonShapeFailure(value, 128), diagnosticJsonShapeFailure(StringReader(value), 128))
        }
    }

    @Test fun protectedStreamRejectsMalformedUtf8AndPreservesMultibyteEvidence() = runBlocking {
        val good = buildJsonObject {
            put("status", "COMPLETED")
            put("value", "x".repeat(4094) + "é😀")
        }.toString().encodeToByteArray()
        val bad = "{\"value\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
        for (bytes in listOf(good, bad)) {
            val issued = bytes.copyOf()
            val metadata = buildJsonObject {
                put("protectedChunks", JsonArray(listOf(JsonPrimitive("workspace-memory-7-one"))))
                put("digest", DiagnosticRecordChunks.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            }
            val result = readWorkspaceDiagnosticPayload(7, metadata, 16 * 1024, {}, { true }, { bytes.size + 38L }, { issued }, {})
            if (bytes === good) {
                assertNull(result.omissionReason)
                assertTrue(requireNotNull(result.payload).toString().contains("é😀"))
            } else {
                assertEquals("PROTECTED_PAYLOAD_INVALID_UTF8", result.omissionReason)
                assertNull(result.payload)
            }
            assertTrue(issued.all { it == 0.toByte() })
        }
    }

    private fun row(id: Int, payload: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        put("table", "workspace_records")
        put("rowId", id)
        put("tableHighWatermark", 859)
        put(
            "fields",
            buildJsonObject {
                put("id", "record-$id")
                put("payload", payload)
            }
        )
    }
}
