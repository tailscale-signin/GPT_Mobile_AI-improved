package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.chat.ArchivedTextCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStoredValueTest {
    @Test fun nestedArchivesAreDecodedBeforeCredentialRedaction() {
        val secret = "configured-secret-that-needs-redaction"
        val archive = ArchivedTextCodec.encode("Safe evidence " + "x".repeat(600) + " $secret")
        assertTrue(archive.startsWith(ArchivedTextCodec.PREFIX))
        val source = JsonObject(mapOf("timeline" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(archive)))))))
        val omissions = mutableListOf<String>()
        val projected = diagnosticStoredValue(source.toString(), 4096, omissions::add)
        val safe = redactChatDebugData(projected, listOf(secret)).toString()
        assertFalse(safe.contains(ArchivedTextCodec.PREFIX))
        assertFalse(safe.contains(secret))
        assertTrue(safe.contains("Safe evidence"))
        assertTrue(omissions.isEmpty())
    }

    @Test fun expansionBudgetIsSharedAcrossAllNestedValues() {
        val archive = ArchivedTextCodec.encode("x".repeat(700))
        val source = JsonArray(listOf(JsonPrimitive(archive), JsonPrimitive(archive)))
        val omissions = mutableListOf<String>()
        val projected = diagnosticStoredValue(source.toString(), 1024, omissions::add).toString()
        assertFalse(projected.contains(ArchivedTextCodec.PREFIX))
        assertTrue(omissions.contains("ARCHIVE_EXPANSION_LIMIT"))
        assertTrue(projected.length < 1200)
    }

    @Test fun expansionBudgetIsSharedAcrossDatabaseCells() {
        val archive = ArchivedTextCodec.encode("x".repeat(700))
        val omissions = mutableListOf<String>()
        val projector = DiagnosticValueProjector(1024, omissions::add)
        assertEquals("x".repeat(700), (projector.text(archive) as JsonPrimitive).content)
        assertTrue(projector.text(archive).toString().contains("omitted"))
        assertTrue(omissions.contains("ARCHIVE_EXPANSION_LIMIT"))
    }

    @Test fun malformedOrOversizedArchivesNeverEscapeAsEncodedData() {
        val cases = listOf(
            ArchivedTextCodec.PREFIX + "999999999:not-base64",
            ArchivedTextCodec.PREFIX + "500:not-base64",
            "wrapper text " + ArchivedTextCodec.PREFIX + "500:hidden"
        )
        cases.forEach { source ->
            val omissions = mutableListOf<String>()
            val result = diagnosticStoredValue(source, 1024, omissions::add).toString()
            assertFalse(result.contains(ArchivedTextCodec.PREFIX))
            assertTrue(omissions.isNotEmpty())
        }
    }

    @Test fun encodedFieldNamesAreNotExported() {
        val archive = ArchivedTextCodec.encode("secret ".repeat(100))
        val omissions = mutableListOf<String>()
        val result = diagnosticStoredValue(JsonObject(mapOf(archive to JsonPrimitive("data"))).toString(), 4096, omissions::add)
        assertFalse(result.toString().contains(ArchivedTextCodec.PREFIX))
        assertTrue(omissions.contains("ARCHIVED_FIELD_NAME_NOT_EXPORTED"))
    }

    @Test fun pathologicalDepthIsRejectedBeforeTreeParsing() {
        val omissions = mutableListOf<String>()
        val value = "[".repeat(5000) + "0" + "]".repeat(5000)
        val result = diagnosticStoredValue(value, 16 * 1024, omissions::add)
        assertTrue(result.toString().contains("omitted"))
        assertEquals(listOf("NESTED_DIAGNOSTIC_DEPTH_LIMIT"), omissions)
    }
}
